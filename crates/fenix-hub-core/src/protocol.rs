/// FenixHub wire protocol.
///
/// Two layers:
///
/// 1. **mDNS** (`_fenixhub._tcp`): discovery + announcement of available content.
///    TXT records carry JSON metadata + group_id. The group_id lets devices filter
///    out foreign groups before attempting HTTP. Content metadata in mDNS is in
///    plaintext by design — only the raw content bytes are encrypted.
///
/// 2. **HTTP** (ephemeral axum server): actual content transfer.
///    Every request carries an HMAC-SHA256 signature header (authentication).
///    The response body is AES-256-GCM encrypted (confidentiality + integrity).
///
/// ## Protocol version
///
/// `protocol_version` in `Announcement` allows future capability negotiation.
/// Version 1 (current): mandatory AES-256-GCM content encryption.
/// Devices running v1 will refuse to pull from peers advertising version 0.
use crate::content::ContentType;
use serde::{Deserialize, Serialize};

/// Current protocol version — bumped whenever the wire format changes.
pub const PROTOCOL_VERSION: u8 = 1;

/// How content is being shared.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub enum SendMode {
    /// Content is broadcast to all paired devices — anyone in the group can pull it.
    Broadcast {},
    /// Content is sent directly to a specific device, which receives a notification.
    Direct {
        /// Target device name (for display and routing).
        target_device: String,
    },
}

/// mDNS TXT record payload — serialized as JSON in TXT records (240-byte chunks).
///
/// Keep this small: mDNS TXT records have a 255-byte limit per string, and the
/// whole payload should stay well under 1000 bytes to ensure reliable multicast.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Announcement {
    /// Protocol version — used for capability negotiation.
    /// Defaults to 0 (legacy) when absent in the parsed JSON (old peers).
    #[serde(default)]
    pub protocol_version: u8,

    /// First 16 bytes of group_key as hex — used to filter foreign groups.
    pub group_id: String,

    /// Unique content item ID (UUID).
    pub content_id: String,

    /// Device name that is serving this content.
    pub device_name: String,

    /// Short preview shown in hub UI and in mDNS announcement (max ~80 chars).
    pub preview: String,

    pub content_type: ContentType,

    pub size_bytes: u64,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub file_name: Option<String>,

    #[serde(skip_serializing_if = "Option::is_none")]
    pub mime_type: Option<String>,

    pub send_mode: SendMode,

    /// Unix timestamp (milliseconds) when this item was added to the hub.
    pub created_at: u64,

    /// Port where the ephemeral HTTP server is listening.
    pub port: u16,
}

/// Messages exchanged over HTTP between hub instances (direct-send notifications).
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum HubMessage {
    /// Sent by Device A to Device B's daemon to trigger a direct-send notification.
    /// B's daemon shows a system notification and opens the hub UI automatically.
    DirectNotify {
        announcement: Announcement,
        /// HMAC-SHA256 of the JSON-serialized announcement, hex-encoded.
        signature: String,
    },
    /// Response from B's daemon acknowledging the direct notification.
    DirectAck { accepted: bool },
}

/// HTTP header carrying the HMAC-SHA256 request signature.
/// Value: hex-encoded HMAC-SHA256(mac_key, content_id_bytes).
pub const HMAC_HEADER: &str = "X-FenixHub-Auth";

/// HTTP response header indicating AES-256-GCM encrypted body.
/// Present (value "1") when the response body is encrypted.
/// Absent on legacy peers (protocol_version == 0).
pub const ENCRYPTED_HEADER: &str = "X-FenixHub-Encrypted";

/// mDNS service type for FenixHub.
pub const MDNS_SERVICE_TYPE: &str = "_fenixhub._tcp.local.";
