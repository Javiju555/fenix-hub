use crate::content::ContentType;
/// FenixHub wire protocol.
///
/// Two layers:
/// 1. mDNS (_fenixhub._tcp): discovery + announcement of available content.
///    TXT records carry JSON metadata + group_id. No authentication here — group_id
///    just lets devices filter out foreign groups before attempting HTTP.
///
/// 2. HTTP (axum server): actual content transfer + direct-send notifications.
///    Every request carries an HMAC-SHA256 signature header for authentication.
use serde::{Deserialize, Serialize};

/// Sent mode: how content is being shared
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub enum SendMode {
    /// Content is broadcast to all paired devices — anyone in the group can pull it
    Broadcast {},
    /// Content is sent directly to a specific device, which receives a notification
    Direct {
        /// Target device name (for display and routing)
        target_device: String,
    },
}

/// mDNS TXT record payload — serialized as JSON in the TXT record.
/// Keep this small: mDNS TXT records have a 255-byte limit per string.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Announcement {
    /// First 16 bytes of group_key as hex — used to filter foreign groups
    pub group_id: String,
    /// Unique content item ID
    pub content_id: String,
    /// Device name that is serving this content
    pub device_name: String,
    /// Short preview for hub UI (max ~80 chars)
    pub preview: String,
    pub content_type: ContentType,
    pub size_bytes: u64,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub file_name: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub mime_type: Option<String>,
    pub send_mode: SendMode,
    /// Unix timestamp
    pub created_at: u64,
    /// Port where the ephemeral HTTP server is listening
    pub port: u16,
}

/// Messages exchanged over HTTP between hub instances.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum HubMessage {
    /// Sent by Device A to Device B's daemon to trigger a direct-send notification.
    /// B's daemon shows a system notification and opens the hub UI automatically.
    DirectNotify {
        announcement: Announcement,
        /// HMAC-SHA256 signature of the JSON-serialized announcement, hex-encoded
        signature: String,
    },
    /// Response from B's daemon acknowledging the direct notification
    DirectAck { accepted: bool },
}

/// HTTP API routes served by the ephemeral content server on Device A:
///
///   GET  /content/{content_id}         → raw content bytes (authenticated via HMAC header)
///   POST /notify                        → HubMessage::DirectNotify (from peer daemon)
///
/// Authentication header: `X-FenixHub-Auth: <hmac_hex>` where the HMAC is computed over
/// the raw `content_id` bytes.
pub const HMAC_HEADER: &str = "X-FenixHub-Auth";
pub const MDNS_SERVICE_TYPE: &str = "_fenixhub._tcp.local.";
