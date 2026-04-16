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

use crate::content::{ContentData, ContentItem};
use crate::crypto::ChunkEncoder;
use crate::identity::GroupIdentity;
use crate::protocol::{
    canonical_auth_message, AUTH_BODY_SHA256_HEADER, AUTH_MAX_SKEW_MS, AUTH_NONCE_HEADER,
    AUTH_TIMESTAMP_HEADER, EMPTY_BODY_SHA256_HEX,
    ENCRYPTED_HEADER, FNX2_CHUNK_SIZE, HMAC_HEADER,
};
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

/// Fixed well-known port for the content server.
/// Using a fixed port allows users to whitelist it in their firewall once.
/// If this port is already in use we fall back to an ephemeral port.
pub const DEFAULT_SERVER_PORT: u16 = 7473;

/// Starts the ephemeral content server, preferring port 7473.
/// Returns `(port, shutdown_sender)`. Send on `shutdown_sender` to stop the server.
pub async fn start_content_server(
    identity: Arc<GroupIdentity>,
    content: ContentStore,
) -> Result<(u16, oneshot::Sender<()>)> {
    let listener = match tokio::net::TcpListener::bind(format!("0.0.0.0:{DEFAULT_SERVER_PORT}")).await {
        Ok(l) => l,
        Err(_) => tokio::net::TcpListener::bind("0.0.0.0:0").await?,
    };
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

    // ── Build response body ──────────────────────────────────────────────────
    let body = match &item.data {
        ContentData::FilePath(path) => {
            // Large file: stream encrypt chunk by chunk — never holds full file in RAM.
            let path = path.clone();
            let enc_key = *state.identity.enc_key();
            let item_size = tokio::fs::metadata(&path).await
                .map(|m| m.len())
                .unwrap_or(0);
            let total_chunks = (item_size / FNX2_CHUNK_SIZE as u64)
                + if item_size % FNX2_CHUNK_SIZE as u64 != 0 { 1 } else { 0 };
            let mut encoder = ChunkEncoder::new(
                &enc_key,
                total_chunks as u32,
                item_size,
            );
            let header = encoder.header();

            tracing::debug!(
                "Serving {} — size={} B chunks={} compression=none (streaming)",
                id,
                item_size,
                total_chunks,
            );

            type BoxError = Box<dyn std::error::Error + Send + Sync + 'static>;
            let stream: futures_util::stream::BoxStream<
                'static,
                Result<bytes::Bytes, BoxError>,
            > = Box::pin(async_stream::try_stream! {
                yield bytes::Bytes::from(header);

                let mut file = tokio::fs::File::open(&path).await
                    .map_err(|e| -> BoxError { Box::new(e) })?;
                let mut buf = vec![0u8; FNX2_CHUNK_SIZE];
                loop {
                    use tokio::io::AsyncReadExt;
                    // Read exactly one logical FNX2 chunk unless EOF is reached.
                    let mut read_total = 0usize;
                    while read_total < FNX2_CHUNK_SIZE {
                        let n = file.read(&mut buf[read_total..]).await
                            .map_err(|e| -> BoxError { Box::new(e) })?;
                        if n == 0 {
                            break;
                        }
                        read_total += n;
                    }

                    if read_total == 0 {
                        break;
                    }

                    let encrypted = encoder.encrypt_chunk(&buf[..read_total])
                        .map_err(|e| -> BoxError { e.to_string().into() })?;
                    yield bytes::Bytes::from(encrypted);

                    // EOF reached mid-chunk: this was the last chunk.
                    if read_total < FNX2_CHUNK_SIZE {
                        break;
                    }
                }
            });

            Body::from_stream(stream)
        }
        _ => {
            // Text / Bytes / Empty: always small, buffer is fine.
            let raw_payload = match &item.data {
                ContentData::Text(text) => text.as_bytes().to_vec(),
                ContentData::Bytes(b) => b.clone(),
                ContentData::Empty => return Err(StatusCode::NO_CONTENT),
                ContentData::FilePath(_) => unreachable!(),
            };

            let original_size = raw_payload.len() as u64;
            let payload = raw_payload;
            let transfer_size = payload.len() as u64;
            let total_chunks = (transfer_size / FNX2_CHUNK_SIZE as u64) as u32
                + if transfer_size % FNX2_CHUNK_SIZE as u64 != 0 { 1 } else { 0 };

            let enc_key = state.identity.enc_key();
            let mut encoder = ChunkEncoder::new(enc_key, total_chunks, original_size);

            tracing::debug!(
                "Serving {} — original={} B transfer={} B chunks={}",
                id,
                original_size,
                transfer_size,
                total_chunks
            );

            let mut all_data: Vec<u8> = encoder.header();
            for chunk in payload.chunks(FNX2_CHUNK_SIZE) {
                let encrypted = encoder
                    .encrypt_chunk(chunk)
                    .map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;
                all_data.extend_from_slice(&encrypted);
            }

            Body::from(all_data)
        }
    };

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
