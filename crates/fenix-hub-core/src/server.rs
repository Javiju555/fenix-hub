/// Ephemeral HTTP content server.
///
/// Spun up when the user publishes content. Lives only as long as the hub is open.
/// Serves content to authenticated peers that pull via HTTP GET.
///
/// ## Security
///
/// Every request must carry a valid HMAC-SHA256 signature in `X-FenixHub-Auth`.
/// The HMAC is computed over the `content_id` bytes using the group's MAC sub-key.
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
use tokio::fs::File;
use tokio::io::AsyncReadExt;
use tokio::sync::{oneshot, RwLock};

use crate::content::{ContentData, ContentItem};
use crate::crypto::ChunkEncoder;
use crate::identity::GroupIdentity;
use crate::protocol::{FNX2_CHUNK_SIZE, FNX2_COMPRESSION_NONE, ENCRYPTED_HEADER, HMAC_HEADER};

pub type ContentStore = Arc<RwLock<HashMap<String, ContentItem>>>;

#[derive(Clone)]
struct ServerState {
    content: ContentStore,
    identity: Arc<GroupIdentity>,
}

/// Starts the ephemeral content server on a random available port.
/// Returns `(port, shutdown_sender)`. Send on `shutdown_sender` to stop the server.
pub async fn start_content_server(
    identity: Arc<GroupIdentity>,
    content: ContentStore,
) -> Result<(u16, oneshot::Sender<()>)> {
    let listener = tokio::net::TcpListener::bind("0.0.0.0:0").await?;
    let port = listener.local_addr()?.port();

    let state = ServerState { content, identity };
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
    let sig_header = headers
        .get(HMAC_HEADER)
        .and_then(|v| v.to_str().ok())
        .ok_or(StatusCode::UNAUTHORIZED)?;

    let sig_bytes = hex::decode(sig_header).map_err(|_| StatusCode::UNAUTHORIZED)?;

    if !state.identity.verify(id.as_bytes(), &sig_bytes) {
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

    // ── Determine content size and prepare streaming ────────────────────────
    let (file_path, size) = match &item.data {
        ContentData::Text(text) => (None, text.len() as u64),
        ContentData::Bytes(bytes) => (None, bytes.len() as u64),
        ContentData::FilePath(path) => {
            let metadata = tokio::fs::metadata(path).await.map_err(|_| StatusCode::NOT_FOUND)?;
            (Some(path.clone()), metadata.len())
        }
        ContentData::Empty => return Err(StatusCode::NO_CONTENT),
    };

    let total_chunks = (size / FNX2_CHUNK_SIZE as u64) as u32 + if size % FNX2_CHUNK_SIZE as u64 != 0 { 1 } else { 0 };
    let enc_key = state.identity.enc_key();
    let mut encoder = ChunkEncoder::new(enc_key, total_chunks, size, FNX2_COMPRESSION_NONE);

    tracing::debug!("Serving {} — {} B, {} chunks", id, size, total_chunks);

    // ── Build streaming response ─────────────────────────────────────────────
    let mut all_data = vec![];
    all_data.push(encoder.header());

    if let Some(path) = file_path {
        let mut file = File::open(&path).await.map_err(|e| {
            tracing::error!("Failed to open {}: {}", path.display(), e);
            StatusCode::NOT_FOUND
        })?;
        let mut buf = vec![0u8; FNX2_CHUNK_SIZE];
        loop {
            let read = file.read(&mut buf).await.map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;
            if read == 0 { break; }
            let encrypted = encoder.encrypt_chunk(&buf[..read]).map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;
            all_data.push(encrypted);
        }
    } else {
        let data = match &item.data {
            ContentData::Text(text) => text.as_bytes(),
            ContentData::Bytes(bytes) => bytes.as_slice(),
            _ => unreachable!(),
        };
        for chunk in data.chunks(FNX2_CHUNK_SIZE) {
            let encrypted = encoder.encrypt_chunk(chunk).map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;
            all_data.push(encrypted);
        }
    }

    let body = Body::from(all_data.into_iter().flatten().collect::<Vec<u8>>());

    Ok((resp_headers, body))
}
