/// HTTP client for pulling content from peers.
///
/// ## Security
///
/// Each request includes an HMAC-SHA256 signature in `X-FenixHub-Auth`.
/// If the server responds with `X-FenixHub-Encrypted: 1`, the body is
/// AES-256-GCM decrypted using the group's ENC sub-key before returning.
///
/// A peer responding without the encrypted header is treated as a legacy
/// device (protocol_version 0). Decryption is skipped in that case, but
/// the caller should treat the data with lower trust.
use crate::crypto;
use crate::identity::GroupIdentity;
use crate::protocol::{ENCRYPTED_HEADER, HMAC_HEADER};
use anyhow::Result;
use std::net::IpAddr;

pub struct PulledContent {
    pub bytes: Vec<u8>,
    pub mime_type: Option<String>,
    pub file_name: Option<String>,
}

/// Pulls the raw content of a content item from a peer's ephemeral server.
///
/// Steps:
/// 1. Compute HMAC-SHA256(mac_key, content_id) → send in `X-FenixHub-Auth`.
/// 2. Receive response.
/// 3. If `X-FenixHub-Encrypted: 1` is present, decrypt with AES-256-GCM(enc_key).
/// 4. Return plaintext bytes + metadata headers.
pub async fn pull_content(
    peer_ip: IpAddr,
    peer_port: u16,
    content_id: &str,
    identity: &GroupIdentity,
) -> Result<PulledContent> {
    let url = format!("http://{}:{}/content/{}", peer_ip, peer_port, content_id);

    // Sign the request with HMAC-SHA256(mac_key, content_id).
    let sig = identity.sign(content_id.as_bytes());
    let sig_hex = hex::encode(&sig);

    // Reuse a single client across all pulls — avoids a new TCP handshake per call.
    static CLIENT: std::sync::OnceLock<reqwest::Client> = std::sync::OnceLock::new();
    let client = CLIENT.get_or_init(reqwest::Client::new);

    let response = client
        .get(&url)
        .header(HMAC_HEADER, sig_hex)
        .send()
        .await?
        .error_for_status()?;

    // Extract metadata headers before consuming the body.
    let mime_type = response
        .headers()
        .get(reqwest::header::CONTENT_TYPE)
        .and_then(|v| v.to_str().ok())
        .map(ToOwned::to_owned);

    let file_name = response
        .headers()
        .get(reqwest::header::CONTENT_DISPOSITION)
        .and_then(|v| v.to_str().ok())
        .and_then(parse_file_name);

    let is_encrypted = response
        .headers()
        .get(ENCRYPTED_HEADER)
        .and_then(|v| v.to_str().ok())
        .map(|v| v == "1")
        .unwrap_or(false);

    let raw_bytes = response.bytes().await?;

    // Decrypt if the server indicated AES-256-GCM encryption.
    let bytes = if is_encrypted {
        let enc_key = identity.enc_key();
        crypto::decrypt(enc_key, &raw_bytes).map_err(|e| {
            anyhow::anyhow!(
                "Failed to decrypt content {} from {}: {}",
                content_id,
                peer_ip,
                e
            )
        })?
    } else {
        // Legacy peer (protocol_version == 0) — no encryption.
        tracing::warn!(
            "Peer at {} served unencrypted content {} — consider upgrading",
            peer_ip,
            content_id
        );
        raw_bytes.to_vec()
    };

    tracing::debug!(
        "Pulled {} from {} — {} B{}",
        content_id,
        peer_ip,
        bytes.len(),
        if is_encrypted {
            " (encrypted)"
        } else {
            " (plaintext, legacy)"
        }
    );

    Ok(PulledContent {
        bytes,
        mime_type,
        file_name,
    })
}

/// Sends a direct notification to a peer's daemon (for direct-send mode).
/// The peer's daemon will show a system notification and open its hub UI.
pub async fn send_direct_notify(
    peer_ip: IpAddr,
    peer_port: u16,
    message: &crate::protocol::HubMessage,
) -> Result<bool> {
    let url = format!("http://{}:{}/notify", peer_ip, peer_port);
    let client = reqwest::Client::new();
    let resp = client
        .post(&url)
        .json(message)
        .send()
        .await?
        .error_for_status()?;

    let ack: crate::protocol::HubMessage = resp.json().await?;
    if let crate::protocol::HubMessage::DirectAck { accepted } = ack {
        Ok(accepted)
    } else {
        Ok(false)
    }
}

fn parse_file_name(content_disposition: &str) -> Option<String> {
    content_disposition
        .split(';')
        .map(str::trim)
        .find_map(|part| {
            part.strip_prefix("filename=")
                .map(|value| value.trim_matches('"').to_string())
        })
        .filter(|value| !value.is_empty())
}
