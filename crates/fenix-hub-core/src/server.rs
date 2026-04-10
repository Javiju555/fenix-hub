/// Ephemeral HTTP content server.
///
/// Spun up when the user publishes content. Lives only as long as the hub is open.
/// Serves content to authenticated peers that pull via HTTP GET.
///
/// ## Security
///
/// Every request must carry a valid HMAC-SHA256 signature in `X-FenixHub-Auth`.
/// The HMAC is computed over a canonical payload (method, path, group_id,
/// timestamp, nonce, and body hash) using the group's MAC sub-key.
///
/// Requests are accepted only within a short clock-skew window and nonce reuse
/// is rejected to mitigate replay attacks.
///
/// The response body is AES-256-GCM encrypted using the group's ENC sub-key.
/// A fresh random 96-bit nonce is generated per request and prepended to the
/// ciphertext (format: `nonce [12 B] || ciphertext+tag`).
///
/// The `X-FenixHub-Encrypted: 1` response header signals to v1 clients that the
/// body is encrypted.  Legacy clients (protocol_version == 0) that do not send
/// this header are not served by this implementation.
use anyhow::Result;
use axum::{
    body::Body,
    extract::{Path, State},
    http::{
        header::{CONTENT_DISPOSITION, CONTENT_TYPE},
        HeaderMap, HeaderValue, StatusCode,
    },
    response::IntoResponse,
    routing::get,
    Router,
};
use std::collections::HashMap;
use std::sync::Arc;
use std::time::{SystemTime, UNIX_EPOCH};
use tokio::sync::{oneshot, RwLock};
use zstd::bulk::compress;

use crate::content::{ContentData, ContentItem};
use crate::crypto::ChunkEncoder;
use crate::identity::GroupIdentity;
use crate::protocol::{
    canonical_auth_message, AUTH_BODY_SHA256_HEADER, AUTH_MAX_SKEW_MS, AUTH_NONCE_HEADER,
    AUTH_TIMESTAMP_HEADER, EMPTY_BODY_SHA256_HEX,
    ENCRYPTED_HEADER, FNX2_CHUNK_SIZE, FNX2_COMPRESSION_NONE, FNX2_COMPRESSION_ZSTD, HMAC_HEADER,
};

const COMPRESS_THRESHOLD_BYTES: u64 = 100 * 1024 * 1024;
const COMPRESS_MIN_RATIO: f64 = 0.95;
const MAX_REPLAY_CACHE_ENTRIES: usize = 8_192;

pub type ContentStore = Arc<RwLock<HashMap<String, ContentItem>>>;

#[derive(Clone, Default)]
struct ReplayGuard {
    seen: Arc<RwLock<HashMap<String, u64>>>,
}

impl ReplayGuard {
    async fn mark_seen(&self, nonce_hex: &str, now_ms: u64) -> bool {
        let mut seen = self.seen.write().await;
        let min_ts = now_ms.saturating_sub(AUTH_MAX_SKEW_MS);
        seen.retain(|_, ts| *ts >= min_ts);

        if seen.contains_key(nonce_hex) {
            return false;
        }

        if seen.len() >= MAX_REPLAY_CACHE_ENTRIES {
            // Drop stale entries first; if still full, evict one arbitrary entry.
            if let Some(first_key) = seen.keys().next().cloned() {
                seen.remove(&first_key);
            }
        }

        seen.insert(nonce_hex.to_string(), now_ms);
        true
    }
}

#[derive(Clone)]
struct ServerState {
    content: ContentStore,
    identity: Arc<GroupIdentity>,
    replay_guard: ReplayGuard,
}

/// Starts the ephemeral content server on a random available port.
/// Returns `(port, shutdown_sender)`. Send on `shutdown_sender` to stop the server.
pub async fn start_content_server(
    identity: Arc<GroupIdentity>,
    content: ContentStore,
) -> Result<(u16, oneshot::Sender<()>)> {
    let listener = tokio::net::TcpListener::bind("0.0.0.0:0").await?;
    let port = listener.local_addr()?.port();

    let state = ServerState {
        content,
        identity,
        replay_guard: ReplayGuard::default(),
    };
    let app = Router::new()
        .route("/content/{id}", get(serve_content))
        .with_state(state);

    let (tx, rx) = oneshot::channel::<()>();
    tokio::spawn(async move {
        axum::serve(listener, app)
            .with_graceful_shutdown(async {
                rx.await.ok();
            })
            .await
            .ok();
    });

    tracing::info!("FenixHub content server started on port {}", port);
    Ok((port, tx))
}

async fn serve_content(
    Path(id): Path<String>,
    headers: HeaderMap,
    State(state): State<ServerState>,
) -> Result<impl IntoResponse, StatusCode> {
    // ── Authentication (HMAC-SHA256) ────────────────────────────────────────
    let sig_header = header_value(&headers, HMAC_HEADER)?;
    let timestamp_ms = header_value(&headers, AUTH_TIMESTAMP_HEADER)?
        .parse::<u64>()
        .map_err(|_| StatusCode::UNAUTHORIZED)?;
    let nonce_hex = header_value(&headers, AUTH_NONCE_HEADER)?.to_ascii_lowercase();
    let body_sha256_hex = header_value(&headers, AUTH_BODY_SHA256_HEADER)?.to_ascii_lowercase();

    if !is_valid_nonce_hex(&nonce_hex) {
        return Err(StatusCode::UNAUTHORIZED);
    }

    if body_sha256_hex != EMPTY_BODY_SHA256_HEX {
        return Err(StatusCode::UNAUTHORIZED);
    }

    let now_ms = unix_time_ms().ok_or(StatusCode::UNAUTHORIZED)?;
    if now_ms.abs_diff(timestamp_ms) > AUTH_MAX_SKEW_MS {
        return Err(StatusCode::UNAUTHORIZED);
    }

    let canonical = canonical_auth_message(
        "GET",
        &format!("/content/{}", id),
        &state.identity.group_id(),
        timestamp_ms,
        &nonce_hex,
        &body_sha256_hex,
    );

    let sig_bytes = hex::decode(sig_header).map_err(|_| StatusCode::UNAUTHORIZED)?;
    if !state.identity.verify(&canonical, &sig_bytes) {
        return Err(StatusCode::UNAUTHORIZED);
    }

    if !state.replay_guard.mark_seen(&nonce_hex, now_ms).await {
        return Err(StatusCode::UNAUTHORIZED);
    }

    // ── Content lookup ──────────────────────────────────────────────────────
    let store = state.content.read().await;
    let item = store.get(&id).ok_or(StatusCode::NOT_FOUND)?;

    // ── Build response headers ──────────────────────────────────────────────
    let mut resp_headers = HeaderMap::new();

    if let Some(mime_type) = item.mime_type.as_deref() {
        if let Ok(hv) = HeaderValue::from_str(mime_type) {
            resp_headers.insert(CONTENT_TYPE, hv);
        }
    }
    if let Some(file_name) = item.file_name.as_deref() {
        let cd = format!(
            "inline; filename=\"{}\"",
            file_name.replace('\\', "_").replace('"', "_")
        );
        if let Ok(hv) = HeaderValue::from_str(&cd) {
            resp_headers.insert(CONTENT_DISPOSITION, hv);
        }
    }
    // Signal FNX2 chunked streaming (v2).
    resp_headers.insert(ENCRYPTED_HEADER, HeaderValue::from_static("2"));

    // ── Prepare payload (optional compression before encryption) ─────────────
    let raw_payload = match &item.data {
        ContentData::Text(text) => text.as_bytes().to_vec(),
        ContentData::Bytes(bytes) => bytes.clone(),
        ContentData::FilePath(path) => tokio::fs::read(path)
            .await
            .map_err(|_| StatusCode::NOT_FOUND)?,
        ContentData::Empty => return Err(StatusCode::NO_CONTENT),
    };

    let original_size = raw_payload.len() as u64;
    let (payload, compression) = maybe_compress_payload(item, raw_payload);
    let transfer_size = payload.len() as u64;
    let total_chunks = (transfer_size / FNX2_CHUNK_SIZE as u64) as u32
        + if transfer_size % FNX2_CHUNK_SIZE as u64 != 0 {
            1
        } else {
            0
        };

    let enc_key = state.identity.enc_key();
    let mut encoder = ChunkEncoder::new(enc_key, total_chunks, original_size, compression);

    tracing::debug!(
        "Serving {} — original={} B transfer={} B chunks={} compression={}",
        id,
        original_size,
        transfer_size,
        total_chunks,
        compression
    );

    // ── Build streaming response ─────────────────────────────────────────────
    let mut all_data = vec![];
    all_data.push(encoder.header());

    for chunk in payload.chunks(FNX2_CHUNK_SIZE) {
        let encrypted = encoder
            .encrypt_chunk(chunk)
            .map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;
        all_data.push(encrypted);
    }

    let body = Body::from(all_data.into_iter().flatten().collect::<Vec<u8>>());

    Ok((resp_headers, body))
}

fn header_value<'a>(headers: &'a HeaderMap, key: &'static str) -> Result<&'a str, StatusCode> {
    headers
        .get(key)
        .and_then(|v| v.to_str().ok())
        .map(str::trim)
        .filter(|v| !v.is_empty())
        .ok_or(StatusCode::UNAUTHORIZED)
}

fn is_valid_nonce_hex(nonce: &str) -> bool {
    (16..=128).contains(&nonce.len())
    && nonce.len() % 2 == 0
        && nonce.as_bytes().iter().all(u8::is_ascii_hexdigit)
}

fn unix_time_ms() -> Option<u64> {
    Some(
        SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .ok()?
            .as_millis() as u64,
    )
}

fn maybe_compress_payload(item: &ContentItem, payload: Vec<u8>) -> (Vec<u8>, u8) {
    if (payload.len() as u64) < COMPRESS_THRESHOLD_BYTES {
        return (payload, FNX2_COMPRESSION_NONE);
    }
    if !is_compressible(item) {
        return (payload, FNX2_COMPRESSION_NONE);
    }

    let Ok(compressed) = compress(&payload, 3) else {
        return (payload, FNX2_COMPRESSION_NONE);
    };

    let ratio = compressed.len() as f64 / payload.len() as f64;
    if ratio >= COMPRESS_MIN_RATIO {
        return (payload, FNX2_COMPRESSION_NONE);
    }

    (compressed, FNX2_COMPRESSION_ZSTD)
}

fn is_compressible(item: &ContentItem) -> bool {
    if let Some(mime) = item.mime_type.as_deref() {
        if mime.starts_with("video/")
            || mime.starts_with("audio/")
            || mime == "application/zip"
            || mime == "application/x-7z-compressed"
            || mime == "application/x-rar-compressed"
            || mime == "application/gzip"
            || mime == "application/x-xz"
            || mime == "application/zstd"
            || mime == "application/vnd.rar"
            || mime == "application/pdf"
            || mime == "image/jpeg"
            || mime == "image/jpg"
            || mime == "image/webp"
            || mime == "image/png"
            || mime == "image/gif"
            || mime == "image/avif"
        {
            return false;
        }
    }

    if let Some(name) = item.file_name.as_deref() {
        let lower = name.to_ascii_lowercase();
        if lower.ends_with(".zip")
            || lower.ends_with(".7z")
            || lower.ends_with(".rar")
            || lower.ends_with(".gz")
            || lower.ends_with(".xz")
            || lower.ends_with(".zst")
            || lower.ends_with(".jpg")
            || lower.ends_with(".jpeg")
            || lower.ends_with(".png")
            || lower.ends_with(".webp")
            || lower.ends_with(".gif")
            || lower.ends_with(".avif")
            || lower.ends_with(".mp4")
            || lower.ends_with(".mkv")
            || lower.ends_with(".mov")
            || lower.ends_with(".mp3")
            || lower.ends_with(".aac")
            || lower.ends_with(".flac")
            || lower.ends_with(".ogg")
            || lower.ends_with(".pdf")
        {
            return false;
        }
    }

    true
}
