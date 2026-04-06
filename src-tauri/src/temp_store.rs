use anyhow::{anyhow, Result};
use fenix_hub_core::content::{ContentData, ContentItem, ContentType};
use serde::{Deserialize, Serialize};
use std::path::PathBuf;

pub const MAX_HISTORY_ITEMS: usize = 25;

/// Initialize the clipboard directory. Does NOT wipe existing content —
/// history is preserved across restarts.
pub fn prepare() -> Result<()> {
    base_dir()?;
    Ok(())
}

#[derive(Serialize, Deserialize)]
struct StoredMeta {
    id: String,
    content_type: String,
    preview: String,
    size_bytes: u64,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    mime_type: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    file_name: Option<String>,
    created_at: u64,
    /// "text" | "bytes"
    data_kind: String,
}

/// Save item metadata to `{id}/meta.json`. Call after writing content.
pub fn write_item_meta(item: &ContentItem, data_kind: &str) -> Result<()> {
    let meta = StoredMeta {
        id: item.id.clone(),
        content_type: format!("{:?}", item.content_type).to_lowercase(),
        preview: item.preview.clone(),
        size_bytes: item.size_bytes,
        mime_type: item.mime_type.clone(),
        file_name: item.file_name.clone(),
        created_at: item.created_at,
        data_kind: data_kind.to_string(),
    };
    let item_dir = base_dir()?.join(&item.id);
    std::fs::create_dir_all(&item_dir)?;
    let json = serde_json::to_string(&meta).map_err(|e| anyhow!(e))?;
    std::fs::write(item_dir.join("meta.json"), json.as_bytes())?;
    Ok(())
}

/// Save text content to `{id}/content.txt`.
pub fn write_text_content(item_id: &str, text: &str) -> Result<()> {
    let item_dir = base_dir()?.join(item_id);
    std::fs::create_dir_all(&item_dir)?;
    std::fs::write(item_dir.join("content.txt"), text.as_bytes())?;
    Ok(())
}

/// Save binary content to `{id}/{file_name}`. Returns the full path.
pub fn write_item_bytes(item_id: &str, file_name: &str, bytes: &[u8]) -> Result<PathBuf> {
    let item_dir = base_dir()?.join(item_id);
    std::fs::create_dir_all(&item_dir)?;
    let safe_name = sanitize_file_name(file_name);
    let path = item_dir.join(if safe_name.is_empty() {
        format!("fenixhub-{}", item_id)
    } else {
        safe_name
    });
    std::fs::write(&path, bytes)?;
    Ok(path)
}

/// Load all persisted clipboard items from disk. Called on startup to
/// restore history. Items with corrupt/missing data are silently skipped.
pub fn load_all_items() -> Vec<ContentItem> {
    let Ok(base) = base_dir() else { return vec![] };
    let Ok(entries) = std::fs::read_dir(&base) else {
        return vec![];
    };

    let mut items = Vec::new();
    for entry in entries.flatten() {
        let dir_path = entry.path();
        if !dir_path.is_dir() {
            continue;
        }
        let meta_path = dir_path.join("meta.json");
        if !meta_path.exists() {
            continue;
        }
        let Ok(json) = std::fs::read_to_string(&meta_path) else {
            continue;
        };
        let Ok(meta) = serde_json::from_str::<StoredMeta>(&json) else {
            continue;
        };

        let content_type = match meta.content_type.as_str() {
            "image" => ContentType::Image,
            "file" => ContentType::File,
            _ => ContentType::Text,
        };

        let data = match meta.data_kind.as_str() {
            "text" => {
                let text_path = dir_path.join("content.txt");
                match std::fs::read_to_string(&text_path) {
                    Ok(text) => ContentData::Text(text),
                    Err(_) => continue,
                }
            }
            _ => {
                // Find the data file (any file that is not meta.json)
                let Ok(files) = std::fs::read_dir(&dir_path) else {
                    continue;
                };
                let data_file = files.flatten().find(|e| e.file_name() != "meta.json");
                match data_file {
                    Some(f) => ContentData::FilePath(f.path()),
                    None => continue,
                }
            }
        };

        items.push(ContentItem {
            id: meta.id,
            content_type,
            preview: meta.preview,
            size_bytes: meta.size_bytes,
            mime_type: meta.mime_type,
            file_name: meta.file_name,
            data,
            created_at: meta.created_at,
        });
    }
    items
}

/// Remove the oldest items from disk (and return their IDs) so that at most
/// `max` items remain. The caller is responsible for evicting them from the
/// in-memory HashMap as well.
pub fn enforce_fifo(max: usize) -> Result<Vec<String>> {
    let base = base_dir()?;
    let Ok(entries) = std::fs::read_dir(&base) else {
        return Ok(vec![]);
    };

    let mut items: Vec<(u64, String, PathBuf)> = Vec::new();
    for entry in entries.flatten() {
        let path = entry.path();
        if !path.is_dir() {
            continue;
        }
        let meta_path = path.join("meta.json");
        if !meta_path.exists() {
            continue;
        }
        let Ok(json) = std::fs::read_to_string(&meta_path) else {
            continue;
        };
        let Ok(meta) = serde_json::from_str::<StoredMeta>(&json) else {
            continue;
        };
        items.push((meta.created_at, meta.id, path));
    }

    if items.len() <= max {
        return Ok(vec![]);
    }

    // Sort ascending by timestamp so we remove oldest first
    items.sort_by_key(|(ts, _, _)| *ts);
    let to_remove = items.len() - max;
    let mut evicted = Vec::new();
    for (_, id, path) in items.iter().take(to_remove) {
        if std::fs::remove_dir_all(path).is_ok() {
            evicted.push(id.clone());
        }
    }
    Ok(evicted)
}

/// Remove a single item directory by its ID.
pub fn remove_item(item_id: &str) -> Result<()> {
    let item_dir = base_dir()?.join(item_id);
    if item_dir.exists() {
        std::fs::remove_dir_all(&item_dir)?;
    }
    Ok(())
}

fn base_dir() -> Result<PathBuf> {
    let dir = dirs::cache_dir()
        .ok_or_else(|| anyhow!("Cannot determine cache directory"))?
        .join("fenix-hub")
        .join("clipboard");
    std::fs::create_dir_all(&dir)?;
    Ok(dir)
}

fn sanitize_file_name(file_name: &str) -> String {
    file_name
        .chars()
        .map(|ch| match ch {
            '/' | '\\' | ':' | '*' | '?' | '"' | '<' | '>' | '|' => '_',
            _ => ch,
        })
        .collect::<String>()
        .trim()
        .to_string()
}
