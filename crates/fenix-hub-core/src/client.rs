/// HTTP client for pulling content from peers.
///
/// ## Security
///
/// Each request includes an HMAC-SHA256 signature in `X-FenixHub-Auth`.
/// The signature is computed over a canonical payload including method, path,
/// group_id, timestamp, nonce, and body hash.
/// If the server responds with `X-FenixHub-Encrypted: 1`, the body is
/// AES-256-GCM decrypted using the group's ENC sub-key before returning.
///
/// A peer responding without the encrypted header is treated as a legacy
/// device (protocol_version 0). Decryption is skipped in that case, but
/// the caller should treat the data with lower trust.
use crate::crypto::{self, ChunkDecoder};
use crate::identity::GroupIdentity;
use crate::protocol::{
    canonical_auth_message, AUTH_BODY_SHA256_HEADER, AUTH_NONCE_HEADER, AUTH_TIMESTAMP_HEADER,
    EMPTY_BODY_SHA256_HEX,
    ENCRYPTED_HEADER, FNX2_COMPRESSION_NONE, FNX2_COMPRESSION_ZSTD, FNX2_HEADER_SIZE,
    HMAC_HEADER,
};
use crate::content::ContentType;
use anyhow::Result;
use rand::RngCore;
use std::net::IpAddr;
use std::time::{SystemTime, UNIX_EPOCH};
use tokio::fs::File;
use tokio::io::AsyncWriteExt;
use tokio_stream::StreamExt;
use zstd::bulk::decompress;

pub struct PulledContent {
    pub bytes: Vec<u8>,
    pub file_path: Option<std::path::PathBuf>, // set when streamed to disk
    pub mime_type: Option<String>,
    pub file_name: Option<String>,
}

/// Pulls the raw content of a content item from a peer's ephemeral server.
///
/// Steps:
/// 1. Build canonical auth payload + HMAC signature and send auth headers.
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
    let canonical_path = format!("/content/{}", content_id);
    let auth = build_request_auth(identity, "GET", &canonical_path, &[])?;

    // Reuse a single client across all pulls — avoids a new TCP handshake per call.
    static CLIENT: std::sync::OnceLock<reqwest::Client> = std::sync::OnceLock::new();
    let client = CLIENT.get_or_init(reqwest::Client::new);

    let response = client
        .get(&url)
        .header(HMAC_HEADER, auth.signature_hex)
        .header(AUTH_TIMESTAMP_HEADER, auth.timestamp_ms.to_string())
        .header(AUTH_NONCE_HEADER, auth.nonce_hex)
        .header(AUTH_BODY_SHA256_HEADER, auth.body_sha256_hex)
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

    let is_v2_stream = response
        .headers()
        .get(ENCRYPTED_HEADER)
        .and_then(|v| v.to_str().ok())
        .map(|v| v == "2")
        .unwrap_or(false);

    let bytes = if is_v2_stream {
        tracing::debug!("Peer {} uses FNX2 v2 streaming", peer_ip);
        pull_v2_body_to_bytes(response, content_id, peer_ip, identity.enc_key()).await?
    } else if is_encrypted {
        let raw_bytes = response.bytes().await?;
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
        response.bytes().await?.to_vec()
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
        file_path: None,
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

struct RequestAuth {
    signature_hex: String,
    timestamp_ms: u64,
    nonce_hex: String,
    body_sha256_hex: String,
}

fn build_request_auth(
    identity: &GroupIdentity,
    method: &str,
    path: &str,
    body: &[u8],
) -> Result<RequestAuth> {
    let timestamp_ms = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_err(|e| anyhow::anyhow!("Clock error while building auth headers: {}", e))?
        .as_millis() as u64;

    let mut nonce = [0u8; 16];
    rand::thread_rng().fill_bytes(&mut nonce);
    let nonce_hex = hex::encode(nonce);

    let body_sha256_hex = if body.is_empty() {
        EMPTY_BODY_SHA256_HEX.to_string()
    } else {
        crate::protocol::sha256_hex(body)
    };

    let canonical = canonical_auth_message(
        method,
        path,
        &identity.group_id(),
        timestamp_ms,
        &nonce_hex,
        &body_sha256_hex,
    );
    let signature_hex = hex::encode(identity.sign(&canonical));

    Ok(RequestAuth {
        signature_hex,
        timestamp_ms,
        nonce_hex,
        body_sha256_hex,
    })
}

/// Pulls content with streaming decrypt to a file (FNX2 v2).
/// Writes each decrypted chunk directly to disk, never holding full file in RAM.
pub async fn pull_content_to_file(
    peer_ip: IpAddr,
    peer_port: u16,
    content_id: &str,
    identity: &GroupIdentity,
    output_path: &std::path::Path,
) -> Result<ContentType> {
    let url = format!("http://{}:{}/content/{}", peer_ip, peer_port, content_id);
    let canonical_path = format!("/content/{}", content_id);
    let auth = build_request_auth(identity, "GET", &canonical_path, &[])?;

    static CLIENT: std::sync::OnceLock<reqwest::Client> = std::sync::OnceLock::new();
    let client = CLIENT.get_or_init(reqwest::Client::new);

    let response = client
        .get(&url)
        .header(HMAC_HEADER, auth.signature_hex)
        .header(AUTH_TIMESTAMP_HEADER, auth.timestamp_ms.to_string())
        .header(AUTH_NONCE_HEADER, auth.nonce_hex)
        .header(AUTH_BODY_SHA256_HEADER, auth.body_sha256_hex)
        .send()
        .await?
        .error_for_status()?;

    let is_v2 = response
        .headers()
        .get(ENCRYPTED_HEADER)
        .and_then(|v| v.to_str().ok())
        .map(|v| v == "2")
        .unwrap_or(false);

    if !is_v2 {
        // Peer is an older server — fall back to pull_content (buffered).
        tracing::warn!(
            "Peer {} does not support FNX2 v2 streaming; falling back to buffered pull",
            peer_ip
        );
        let pulled = pull_content(peer_ip, peer_port, content_id, identity).await?;
        tokio::fs::write(output_path, &pulled.bytes).await?;
        return Ok(ContentType::File);
    }

    // Stream the response body
    let mut body = response.bytes_stream();
    let enc_key = identity.enc_key();

    // Read FNX2 header first
    let mut header_buf = vec![0u8; FNX2_HEADER_SIZE];
    let mut header_read = 0usize;
    while header_read < FNX2_HEADER_SIZE {
        if let Some(chunk) = body.next().await {
            let chunk = chunk?;
            let take = (FNX2_HEADER_SIZE - header_read).min(chunk.len());
            header_buf[header_read..header_read + take].copy_from_slice(&chunk[..take]);
            header_read += take;
        } else {
            anyhow::bail!("Incomplete FNX2 header");
        }
    }

    let mut decoder = ChunkDecoder::new(enc_key, &header_buf)?;

    tracing::debug!(
        "Pulling {} from {} — {} chunks, {} B",
        content_id,
        peer_ip,
        decoder.total_chunks,
        decoder.original_size
    );

    // Open output file and decrypt chunks. If compression is enabled, we decode
    // after collecting compressed plaintext.
    let mut file = File::create(output_path).await?;
    let mut compressed_plaintext = Vec::new();

    while let Some(chunk) = body.next().await {
        let chunk = chunk?;
        if chunk.is_empty() {
            continue;
        }
        let plaintext = decoder.decrypt_chunk(&chunk)?;
        if decoder.compression == FNX2_COMPRESSION_NONE {
            file.write_all(&plaintext).await?;
        } else {
            compressed_plaintext.extend_from_slice(&plaintext);
        }
    }

    if decoder.compression != FNX2_COMPRESSION_NONE {
        let decoded = decode_compressed_payload(
            compressed_plaintext,
            decoder.compression,
            decoder.original_size,
        )?;
        file.write_all(&decoded).await?;
    }

    file.flush().await?;

    tracing::debug!(
        "Pulled {} from {} — {} B written to {}",
        content_id,
        peer_ip,
        decoder.original_size,
        output_path.display()
    );

    Ok(ContentType::File)
}

async fn pull_v2_body_to_bytes(
    response: reqwest::Response,
    content_id: &str,
    peer_ip: IpAddr,
    enc_key: &[u8; 32],
) -> Result<Vec<u8>> {
    let mut body = response.bytes_stream();

    // Read FNX2 header first.
    let mut header_buf = vec![0u8; FNX2_HEADER_SIZE];
    let mut header_read = 0usize;
    while header_read < FNX2_HEADER_SIZE {
        if let Some(chunk) = body.next().await {
            let chunk = chunk?;
            let take = (FNX2_HEADER_SIZE - header_read).min(chunk.len());
            header_buf[header_read..header_read + take].copy_from_slice(&chunk[..take]);
            header_read += take;
        } else {
            anyhow::bail!("Incomplete FNX2 header");
        }
    }

    let mut decoder = ChunkDecoder::new(enc_key, &header_buf)?;
    let mut decrypted = Vec::new();

    while let Some(chunk) = body.next().await {
        let chunk = chunk?;
        if chunk.is_empty() {
            continue;
        }
        let plaintext = decoder.decrypt_chunk(&chunk)?;
        decrypted.extend_from_slice(&plaintext);
    }

    let bytes = decode_compressed_payload(decrypted, decoder.compression, decoder.original_size)?;
    tracing::debug!(
        "Pulled {} from {} via FNX2 — {} B",
        content_id,
        peer_ip,
        bytes.len()
    );
    Ok(bytes)
}

fn decode_compressed_payload(
    payload: Vec<u8>,
    compression: u8,
    original_size: u64,
) -> Result<Vec<u8>> {
    match compression {
        FNX2_COMPRESSION_NONE => Ok(payload),
        FNX2_COMPRESSION_ZSTD => {
            let expected = usize::try_from(original_size)
                .map_err(|_| anyhow::anyhow!("Original size too large for decompression"))?;
            decompress(&payload, expected)
                .map_err(|e| anyhow::anyhow!("zstd decompression failed: {}", e))
        }
        unknown => Err(anyhow::anyhow!(
            "Unsupported FNX2 compression mode: {}",
            unknown
        )),
    }
}
