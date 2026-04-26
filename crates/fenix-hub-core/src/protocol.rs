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
/// Version 0: legacy, unencrypted. Version 1+: mandatory AES-256-GCM.
/// Version 2: FNX2 chunked-AEAD wire format. Version 3 (current): protocol stable.
/// Devices running v1+ refuse to pull from peers advertising version 0.
use crate::content::ContentType;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};

/// Current protocol version — bumped whenever the wire format changes.
pub const PROTOCOL_VERSION: u8 = 3;

/// FNX2 chunked AEAD wire format magic.
pub const FNX2_MAGIC: &[u8; 4] = b"FNX2";

/// FNX2 header size: magic(4) + base_nonce(12) + total_chunks(4) + original_size(8) + compression(1)
pub const FNX2_HEADER_SIZE: usize = 29;

/// Chunk size for streaming AEAD (4 MB).
/// Larger chunks let AES-NI / Android's JCA hardware AES path amortize
/// per-chunk init/doFinal overhead, restoring ~30 MB/s throughput.
pub const FNX2_CHUNK_SIZE: usize = 4 * 1024 * 1024;

pub const FNX2_COMPRESSION_NONE: u8 = 0x00;
pub const FNX2_COMPRESSION_ZSTD: u8 = 0x01;

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
/// Value: hex-encoded HMAC-SHA256(mac_key, canonical_auth_message).
pub const HMAC_HEADER: &str = "X-FenixHub-Auth";

/// HTTP header carrying the request timestamp in unix milliseconds.
pub const AUTH_TIMESTAMP_HEADER: &str = "X-FenixHub-Timestamp";

/// HTTP header carrying a per-request random nonce (hex).
pub const AUTH_NONCE_HEADER: &str = "X-FenixHub-Nonce";

/// HTTP header carrying `SHA256(body)` as lowercase hex.
pub const AUTH_BODY_SHA256_HEADER: &str = "X-FenixHub-Body-Sha256";

/// Maximum accepted clock skew for authenticated requests.
pub const AUTH_MAX_SKEW_MS: u64 = 90_000;

/// SHA-256 of an empty body.
pub const EMPTY_BODY_SHA256_HEX: &str =
    "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

/// Canonical auth message prefix.
const AUTH_CANONICAL_PREFIX: &str = "fenixhub-auth-v1";

/// HTTP response header indicating AES-256-GCM encrypted body.
/// Present (value "1") when the response body is encrypted.
/// Absent on legacy peers (protocol_version == 0).
pub const ENCRYPTED_HEADER: &str = "X-FenixHub-Encrypted";

/// mDNS service type for FenixHub content announcements.
pub const MDNS_SERVICE_TYPE: &str = "_fenixhub._tcp.local.";

/// mDNS service type for device presence beacons (no content).
/// Announced from daemon start so peers appear in the UI before sharing anything.
pub const MDNS_PRESENCE_TYPE: &str = "_fenixhub-presence._tcp.local.";

/// Builds the canonical signed payload used by request authentication.
pub fn canonical_auth_message(
    method: &str,
    path: &str,
    group_id: &str,
    timestamp_ms: u64,
    nonce_hex: &str,
    body_sha256_hex: &str,
) -> Vec<u8> {
    format!(
        "{}\n{}\n{}\n{}\n{}\n{}\n{}",
        AUTH_CANONICAL_PREFIX,
        method.trim().to_ascii_uppercase(),
        path.trim(),
        group_id.trim(),
        timestamp_ms,
        nonce_hex.trim().to_ascii_lowercase(),
        body_sha256_hex.trim().to_ascii_lowercase(),
    )
    .into_bytes()
}

/// Returns SHA-256(input) as lowercase hex.
pub fn sha256_hex(input: &[u8]) -> String {
    let mut hasher = Sha256::new();
    hasher.update(input);
    hex::encode(hasher.finalize())
}

#[cfg(test)]
mod tests {
    use super::*;
    use hmac::{Hmac, Mac};

    #[test]
    fn canonical_auth_hmac_vector_matches_android() {
        type HmacSha256 = Hmac<Sha256>;

        let canonical = canonical_auth_message(
            "get",
            "/content/test-id",
            "0123456789abcdef0123456789abcdef",
            1_735_689_600_000,
            "00112233445566778899AABBCCDDEEFF",
            &EMPTY_BODY_SHA256_HEX.to_ascii_uppercase(),
        );

        let expected_canonical = b"fenixhub-auth-v1\nGET\n/content/test-id\n0123456789abcdef0123456789abcdef\n1735689600000\n00112233445566778899aabbccddeeff\ne3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
        assert_eq!(expected_canonical.as_slice(), canonical.as_slice());

        let key = hex::decode("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f")
            .expect("valid test key");
        let mut mac = HmacSha256::new_from_slice(&key).expect("hmac key accepted");
        mac.update(&canonical);
        let signature = hex::encode(mac.finalize().into_bytes());

        assert_eq!(
            "5bce910b31e95e17a0e8fd3373510f562f30d9021ebc5007849edb1487992a37",
            signature
        );
    }
}
