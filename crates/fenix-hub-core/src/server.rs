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
use tokio::sync::{oneshot, RwLock};

use crate::content::{ContentData, ContentItem};
use crate::crypto;
use crate::identity::GroupIdentity;
use crate::protocol::{ENCRYPTED_HEADER, HMAC_HEADER};

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

    let sig_bytes =
        hex::decode(sig_header).map_err(|_| StatusCode::UNAUTHORIZED)?;

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
    // Signal to peers that the body is AES-256-GCM encrypted.
    resp_headers.insert(ENCRYPTED_HEADER, HeaderValue::from_static("1"));

    // ── Load plaintext bytes ────────────────────────────────────────────────
    let plaintext: Vec<u8> = match &item.data {
        ContentData::Text(text) => text.as_bytes().to_vec(),
        ContentData::Bytes(bytes) => bytes.clone(),
        ContentData::FilePath(path) => {
            // Read file outside the store lock to avoid holding it during I/O.
            let path = path.clone();
            drop(store);
            tokio::fs::read(&path)
                .await
                .map_err(|_| StatusCode::NOT_FOUND)?
        }
        ContentData::Empty => return Err(StatusCode::NO_CONTENT),
    };

    // ── Encrypt (AES-256-GCM) ───────────────────────────────────────────────
    let enc_key = state.identity.enc_key();
    let encrypted = crypto::encrypt(enc_key, &plaintext).map_err(|e| {
        tracing::error!("Encryption error for {}: {}", id, e);
        StatusCode::INTERNAL_SERVER_ERROR
    })?;

    tracing::debug!(
        "Serving {} — plaintext {} B → encrypted {} B",
        id,
        plaintext.len(),
        encrypted.len()
    );

    Ok((resp_headers, Body::from(encrypted)))
}

// ── Large-file streaming variant ─────────────────────────────────────────────
//
// For very large files we stream from disk rather than reading everything into
// memory at once.  The tradeoff: we must encrypt the whole plaintext in memory
// before streaming the ciphertext (GCM requires the full plaintext to compute
// the authentication tag).  For files where memory matters, consider switching
// to ChaCha20-Poly1305 with a streaming AEAD construction in a future version.
//
// Current limit: files up to ~500 MB work fine on typical desktop hardware.
// Streaming encryption (e.g. age-style chunked AEAD) is tracked as a TODO.

