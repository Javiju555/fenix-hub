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
    ENCRYPTED_HEADER, FNX2_CHUNK_SIZE, FNX2_HEADER_SIZE,
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

pub struct PulledContent {
    pub bytes: Vec<u8>,
    pub file_path: Option<std::path::PathBuf>, // set when streamed to disk
    pub mime_type: Option<String>,
    pub file_name: Option<String>,
}

const FNX2_GCM_TAG_BYTES: usize = 16;

// Backward compatibility: older Android builds used larger FNX2 chunk sizes.
const FNX2_LEGACY_CHUNK_SIZES: &[usize] = &[
    FNX2_CHUNK_SIZE,
    256 * 1024,
    512 * 1024,
    1024 * 1024,
    2 * 1024 * 1024,
    4 * 1024 * 1024,
];

fn full_chunk_ciphertext_len(chunk_plain_size: usize) -> usize {
    chunk_plain_size + FNX2_GCM_TAG_BYTES
}

fn chunk_count_for_size(original_size: u64, chunk_plain_size: usize) -> u32 {
    if original_size == 0 {
        return 0;
    }
    original_size
        .div_ceil(chunk_plain_size as u64)
        .min(u32::MAX as u64) as u32
}

fn preferred_fnx2_chunk_size(total_chunks: u32, original_size: u64) -> usize {
    if total_chunks <= 1 {
        return FNX2_CHUNK_SIZE;
    }

    FNX2_LEGACY_CHUNK_SIZES
        .iter()
        .copied()
        .find(|size| chunk_count_for_size(original_size, *size) == total_chunks)
        .unwrap_or(FNX2_CHUNK_SIZE)
}

fn candidate_fnx2_chunk_sizes(total_chunks: u32, original_size: u64) -> Vec<usize> {
    let mut candidates = vec![FNX2_CHUNK_SIZE];

    for size in FNX2_LEGACY_CHUNK_SIZES.iter().copied() {
        if !candidates.contains(&size) && chunk_count_for_size(original_size, size) == total_chunks {
            candidates.push(size);
        }
    }

    if total_chunks > 1 && original_size > 0 {
        let avg = original_size.div_ceil(total_chunks as u64) as usize;
        let upper = avg.next_power_of_two();
        for size in [
            avg,
            upper / 2,
            upper,
            upper.saturating_mul(2),
        ] {
            if size < FNX2_CHUNK_SIZE || size > 8 * 1024 * 1024 {
                continue;
            }
            if !candidates.contains(&size) {
                candidates.push(size);
            }
        }
    }

    for size in FNX2_LEGACY_CHUNK_SIZES.iter().copied() {
        if !candidates.contains(&size) {
            candidates.push(size);
        }
    }

    candidates
}

fn decrypt_v2_from_raw_with_chunk_size(
    raw: &[u8],
    enc_key: &[u8; 32],
    chunk_plain_size: usize,
) -> Result<Vec<u8>> {
    let mut decoder = ChunkDecoder::new(enc_key, &raw[..FNX2_HEADER_SIZE])?;
    let mut decrypted = Vec::new();
    let mut pos = FNX2_HEADER_SIZE;

    let full_chunk = full_chunk_ciphertext_len(chunk_plain_size);
    for chunk_index in 0..decoder.total_chunks {
        let is_last = chunk_index == decoder.total_chunks - 1;
        let end = if is_last { raw.len() } else { pos + full_chunk };
        if end > raw.len() {
            anyhow::bail!("FNX2 body truncated at chunk {chunk_index}");
        }
        let plaintext = decoder.decrypt_chunk(&raw[pos..end])?;
        decrypted.extend_from_slice(&plaintext);
        pos = end;
    }

    if pos != raw.len() {
        anyhow::bail!(
            "FNX2 body had {} trailing bytes after decrypt",
            raw.len().saturating_sub(pos)
        );
    }

    if decrypted.len() as u64 != decoder.original_size {
        anyhow::bail!(
            "FNX2 payload size mismatch: decrypted {} B, expected {} B",
            decrypted.len(),
            decoder.original_size
        );
    }

    Ok(decrypted)
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

    let encrypted_mode = response
        .headers()
        .get(ENCRYPTED_HEADER)
        .and_then(|v| v.to_str().ok())
        .map(ToOwned::to_owned);

    let is_v2_stream = encrypted_mode.as_deref() == Some("2");
    let is_encrypted = matches!(encrypted_mode.as_deref(), Some("1") | Some("2"));

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
        if is_v2_stream {
            " (encrypted, FNX2)"
        } else if is_encrypted {
            " (encrypted, v1)"
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

    if let Err(error) = pull_v2_body_to_file(response, content_id, peer_ip, identity.enc_key(), output_path).await {
        tracing::warn!(
            "FNX2 stream pull failed for {} from {}: {}. Retrying with buffered pull.",
            content_id,
            peer_ip,
            error
        );
        let _ = tokio::fs::remove_file(output_path).await;
        let pulled = pull_content(peer_ip, peer_port, content_id, identity).await?;
        tokio::fs::write(output_path, &pulled.bytes).await?;
    }

    Ok(ContentType::File)
}

async fn pull_v2_body_to_file(
    response: reqwest::Response,
    content_id: &str,
    peer_ip: IpAddr,
    enc_key: &[u8; 32],
    output_path: &std::path::Path,
) -> Result<()> {
    // Stream the response body
    let mut body = response.bytes_stream();

    // Read FNX2 header first, keeping any leftover bytes from the same TCP chunk.
    let mut header_buf = vec![0u8; FNX2_HEADER_SIZE];
    let mut header_read = 0usize;
    let mut leftover: Vec<u8> = Vec::new();
    while header_read < FNX2_HEADER_SIZE {
        if let Some(chunk) = body.next().await {
            let chunk = chunk?;
            let take = (FNX2_HEADER_SIZE - header_read).min(chunk.len());
            header_buf[header_read..header_read + take].copy_from_slice(&chunk[..take]);
            header_read += take;
            if chunk.len() > take {
                leftover.extend_from_slice(&chunk[take..]);
            }
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

    let chunk_plain_size = preferred_fnx2_chunk_size(decoder.total_chunks, decoder.original_size);
    if chunk_plain_size != FNX2_CHUNK_SIZE {
        tracing::warn!(
            "Peer {} appears to use legacy FNX2 chunk size {} KB (declared chunks={})",
            peer_ip,
            chunk_plain_size / 1024,
            decoder.total_chunks
        );
    }

    // Open output file and decrypt chunks.
    let mut file = File::create(output_path).await?;
    // pending accumulates ciphertext bytes until we have a full encrypted chunk.
    let full_chunk = full_chunk_ciphertext_len(chunk_plain_size);
    let mut pending: Vec<u8> = leftover;
    let mut decrypted_chunks = 0u32;
    let mut written_bytes = 0u64;

    let flush_pending = |pending: &mut Vec<u8>,
                         decoder: &mut ChunkDecoder,
                         decrypted_chunks: &mut u32,
                         is_final: bool|
     -> Result<Vec<u8>> {
        let mut out = Vec::new();
        while pending.len() >= full_chunk || (is_final && !pending.is_empty()) {
            if *decrypted_chunks >= decoder.total_chunks {
                anyhow::bail!(
                    "FNX2 stream has more chunks than declared (declared {})",
                    decoder.total_chunks
                );
            }

            let end = if pending.len() >= full_chunk {
                full_chunk
            } else {
                pending.len()
            };
            let plaintext = decoder.decrypt_chunk(&pending[..end])?;
            *decrypted_chunks += 1;
            out.extend_from_slice(&plaintext);
            pending.drain(..end);
        }
        Ok(out)
    };

    while let Some(chunk) = body.next().await {
        let chunk = chunk?;
        if chunk.is_empty() {
            continue;
        }
        pending.extend_from_slice(&chunk);
        let plaintext = flush_pending(&mut pending, &mut decoder, &mut decrypted_chunks, false)?;
        written_bytes += plaintext.len() as u64;
        file.write_all(&plaintext).await?;
    }
    // Flush final (potentially short) chunk
    let plaintext = flush_pending(&mut pending, &mut decoder, &mut decrypted_chunks, true)?;
    written_bytes += plaintext.len() as u64;
    file.write_all(&plaintext).await?;

    if decrypted_chunks != decoder.total_chunks {
        anyhow::bail!(
            "FNX2 stream chunk count mismatch: decoded {}, expected {}",
            decrypted_chunks,
            decoder.total_chunks
        );
    }

    if written_bytes != decoder.original_size {
        anyhow::bail!(
            "FNX2 stream size mismatch: wrote {} B, expected {} B",
            written_bytes,
            decoder.original_size
        );
    }

    file.flush().await?;

    tracing::debug!(
        "Pulled {} from {} — {} B written to {}",
        content_id,
        peer_ip,
        decoder.original_size,
        output_path.display()
    );

    Ok(())
}

async fn pull_v2_body_to_bytes(
    response: reqwest::Response,
    content_id: &str,
    peer_ip: IpAddr,
    enc_key: &[u8; 32],
) -> Result<Vec<u8>> {
    let mut body = response.bytes_stream();

    // Accumulate the full response into a buffer first, then parse.
    // The bytes_stream yields raw TCP chunks — if we try to read the 29-byte
    // FNX2 header and the first chunk is larger, leftover bytes would be lost.
    // Buffering avoids that without holding two copies (collect then slice).
    let mut raw: Vec<u8> = Vec::new();
    while let Some(chunk) = body.next().await {
        raw.extend_from_slice(&chunk?);
    }

    if raw.len() < FNX2_HEADER_SIZE {
        anyhow::bail!("Incomplete FNX2 header: {} bytes", raw.len());
    }

    let header_decoder = ChunkDecoder::new(enc_key, &raw[..FNX2_HEADER_SIZE])?;
    let candidate_sizes = candidate_fnx2_chunk_sizes(
        header_decoder.total_chunks,
        header_decoder.original_size,
    );

    let mut last_error: Option<anyhow::Error> = None;
    let mut attempted_sizes: Vec<usize> = Vec::new();
    for chunk_plain_size in candidate_sizes {
        attempted_sizes.push(chunk_plain_size);
        match decrypt_v2_from_raw_with_chunk_size(&raw, enc_key, chunk_plain_size) {
            Ok(decrypted) => {
                if chunk_plain_size != FNX2_CHUNK_SIZE {
                    tracing::warn!(
                        "Peer {} uses legacy FNX2 chunk size {} KB for {}",
                        peer_ip,
                        chunk_plain_size / 1024,
                        content_id
                    );
                }

                tracing::debug!(
                    "Pulled {} from {} via FNX2 — {} B",
                    content_id,
                    peer_ip,
                    decrypted.len()
                );
                return Ok(decrypted);
            }
            Err(error) => {
                last_error = Some(error);
            }
        }
    }

    if let Some(error) = last_error {
        anyhow::bail!(
            "Unable to decode FNX2 payload for {} from {} (chunks={}, original={} B, attempted_chunk_sizes_kb={:?}): {}",
            content_id,
            peer_ip,
            header_decoder.total_chunks,
            header_decoder.original_size,
            attempted_sizes.iter().map(|size| size / 1024).collect::<Vec<_>>(),
            error
        );
    }

    anyhow::bail!("Unable to decode FNX2 payload with any supported chunk size");
}
