/// Content types that FenixHub can transfer between devices.
use serde::{Deserialize, Serialize};
use std::path::PathBuf;
use uuid::Uuid;

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "snake_case")]
pub enum ContentType {
    Text,
    Image,
    File,
    Folder,
}

/// A piece of content held in the local hub, ready to be shared.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ContentItem {
    /// Unique ID for this content item (random UUID per session)
    pub id: String,
    pub content_type: ContentType,
    /// Short preview shown in hub UI and in mDNS announcement.
    /// For text: first ~80 chars. For files: "filename.ext (4.2 MB)". For images: "image.png (320×240)".
    pub preview: String,
    /// Size in bytes of the full content (for progress indication)
    pub size_bytes: u64,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub mime_type: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub file_name: Option<String>,
    /// The actual content, held in memory or in the hub's temp storage.
    #[serde(skip)]
    pub data: ContentData,
    /// Unix timestamp when this item was added to the hub
    pub created_at: u64,
}

/// The actual content data, kept in memory or backed by a temp file.
#[derive(Debug, Clone, Default)]
pub enum ContentData {
    #[default]
    Empty,
    Text(String),
    Bytes(Vec<u8>), // images and files share this
    FilePath(PathBuf),
}

impl ContentItem {
    pub fn from_text(text: String) -> Self {
        let preview = text.chars().take(80).collect::<String>();
        let size = text.len() as u64;
        Self {
            id: new_id(),
            content_type: ContentType::Text,
            preview,
            size_bytes: size,
            mime_type: Some("text/plain; charset=utf-8".to_string()),
            file_name: None,
            data: ContentData::Text(text),
            created_at: unix_now(),
        }
    }

    pub fn from_file(filename: &str, data: Vec<u8>) -> Self {
        Self::from_binary(
            ContentType::File,
            Some(filename.to_string()),
            None,
            data,
            Some(filename.to_string()),
        )
    }

    pub fn from_image(filename: &str, data: Vec<u8>) -> Self {
        Self::from_binary(
            ContentType::Image,
            Some(filename.to_string()),
            Some("image/*".to_string()),
            data,
            Some(filename.to_string()),
        )
    }

    pub fn from_binary(
        content_type: ContentType,
        file_name: Option<String>,
        mime_type: Option<String>,
        data: Vec<u8>,
        preview: Option<String>,
    ) -> Self {
        let size = data.len() as u64;
        Self {
            id: new_id(),
            content_type,
            preview: preview.unwrap_or_else(|| {
                file_name
                    .clone()
                    .unwrap_or_else(|| format!("clipboard-{}", unix_now()))
            }),
            size_bytes: size,
            mime_type,
            file_name,
            data: ContentData::Bytes(data),
            created_at: unix_now(),
        }
    }

    pub fn from_temp_file(
        path: PathBuf,
        content_type: ContentType,
        file_name: Option<String>,
        mime_type: Option<String>,
        preview: Option<String>,
    ) -> anyhow::Result<Self> {
        let size = std::fs::metadata(&path)?.len();
        let resolved_name = file_name.or_else(|| {
            path.file_name()
                .and_then(|name| name.to_str())
                .map(ToOwned::to_owned)
        });

        Ok(Self {
            id: new_id(),
            content_type,
            preview: preview.unwrap_or_else(|| {
                resolved_name
                    .clone()
                    .unwrap_or_else(|| format!("clipboard-{}", unix_now()))
            }),
            size_bytes: size,
            mime_type,
            file_name: resolved_name,
            data: ContentData::FilePath(path),
            created_at: unix_now(),
        })
    }
}

fn new_id() -> String {
    Uuid::new_v4().to_string()
}

fn unix_now() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as u64
}
