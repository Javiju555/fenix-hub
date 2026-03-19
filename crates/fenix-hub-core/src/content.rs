/// Content types that FenixHub can transfer between devices.

use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "snake_case")]
pub enum ContentType {
    Text,
    Image,
    File,
    // Future: Url (with OG preview)
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
    /// The actual content, held in RAM only — never persisted to disk.
    #[serde(skip)]
    pub data: ContentData,
    /// Unix timestamp when this item was added to the hub
    pub created_at: u64,
}

/// The actual content data, kept in RAM.
#[derive(Debug, Clone, Default)]
pub enum ContentData {
    #[default]
    Empty,
    Text(String),
    Bytes(Vec<u8>), // images and files share this
}

impl ContentItem {
    pub fn from_text(text: String) -> Self {
        let preview = if text.len() > 80 {
            format!("{}…", &text[..80])
        } else {
            text.clone()
        };
        let size = text.len() as u64;
        Self {
            id: new_id(),
            content_type: ContentType::Text,
            preview,
            size_bytes: size,
            data: ContentData::Text(text),
            created_at: unix_now(),
        }
    }

    pub fn from_file(filename: &str, data: Vec<u8>) -> Self {
        let size = data.len() as u64;
        Self {
            id: new_id(),
            content_type: ContentType::File,
            preview: format!("{} ({})", filename, human_size(size)),
            size_bytes: size,
            data: ContentData::Bytes(data),
            created_at: unix_now(),
        }
    }

    pub fn from_image(filename: &str, data: Vec<u8>) -> Self {
        let size = data.len() as u64;
        Self {
            id: new_id(),
            content_type: ContentType::Image,
            preview: format!("{} ({})", filename, human_size(size)),
            size_bytes: size,
            data: ContentData::Bytes(data),
            created_at: unix_now(),
        }
    }
}

fn new_id() -> String {
    use rand::Rng;
    let bytes: [u8; 8] = rand::thread_rng().gen();
    hex::encode(bytes)
}

fn unix_now() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs()
}

fn human_size(bytes: u64) -> String {
    if bytes < 1024 {
        format!("{} B", bytes)
    } else if bytes < 1024 * 1024 {
        format!("{:.1} KB", bytes as f64 / 1024.0)
    } else {
        format!("{:.1} MB", bytes as f64 / (1024.0 * 1024.0))
    }
}
