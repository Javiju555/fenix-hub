use anyhow::{anyhow, Result};
use std::path::{Path, PathBuf};

pub fn prepare() -> Result<()> {
    let dir = base_dir()?;
    if dir.exists() {
        std::fs::remove_dir_all(&dir)?;
    }
    std::fs::create_dir_all(&dir)?;
    Ok(())
}

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

pub fn remove_item_path(path: &Path) -> Result<()> {
    let path = path.canonicalize()?;
    let base = base_dir()?.canonicalize()?;
    if !path.starts_with(&base) {
        return Err(anyhow!("Path is outside FenixHub temp storage"));
    }
    if let Some(parent) = path.parent() {
        std::fs::remove_dir_all(parent)?;
    } else {
        std::fs::remove_file(path)?;
    }
    Ok(())
}

pub fn clear_all() -> Result<()> {
    let dir = base_dir()?;
    if dir.exists() {
        std::fs::remove_dir_all(&dir)?;
    }
    std::fs::create_dir_all(&dir)?;
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
