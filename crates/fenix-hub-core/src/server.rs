/// Ephemeral HTTP content server.
///
/// Spun up when the user publishes content. Lives only as long as the hub is open.
/// Serves content to authenticated peers that pull via HTTP GET.

use std::collections::HashMap;
use std::net::SocketAddr;
use std::sync::Arc;
use tokio::sync::{RwLock, oneshot};
use axum::{Router, routing::get, extract::{Path, State}, http::{HeaderMap, StatusCode}, body::Bytes};
use anyhow::Result;

use crate::content::{ContentItem, ContentData};
use crate::identity::GroupIdentity;
use crate::protocol::HMAC_HEADER;

pub type ContentStore = Arc<RwLock<HashMap<String, ContentItem>>>;

#[derive(Clone)]
struct ServerState {
    content: ContentStore,
    identity: Arc<GroupIdentity>,
}

/// Starts the ephemeral content server on a random available port.
/// Returns (port, shutdown_sender). Drop or send on shutdown_sender to stop.
pub async fn start_content_server(
    identity: Arc<GroupIdentity>,
    content: ContentStore,
) -> Result<(u16, oneshot::Sender<()>)> {
    let listener = tokio::net::TcpListener::bind("0.0.0.0:0").await?;
    let port = listener.local_addr()?.port();

    let state = ServerState { content, identity };
    let app = Router::new()
        .route("/content/{id}", get(serve_content))
        .route("/content/{id}/preview", get(serve_preview))
        .with_state(state);

    let (tx, rx) = oneshot::channel::<()>();
    tokio::spawn(async move {
        axum::serve(listener, app)
            .with_graceful_shutdown(async { rx.await.ok(); })
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
) -> Result<Bytes, StatusCode> {
    // Verify HMAC signature
    let sig_header = headers
        .get(HMAC_HEADER)
        .and_then(|v| v.to_str().ok())
        .ok_or(StatusCode::UNAUTHORIZED)?;

    let expected_sig = state.identity.sign(format!("/content/{}", id).as_bytes());
    let expected_hex = hex::encode(&expected_sig);
    if sig_header != expected_hex {
        return Err(StatusCode::UNAUTHORIZED);
    }

    let store = state.content.read().await;
    let item = store.get(&id).ok_or(StatusCode::NOT_FOUND)?;

    match &item.data {
        ContentData::Text(text) => Ok(Bytes::from(text.clone().into_bytes())),
        ContentData::Bytes(bytes) => Ok(Bytes::from(bytes.clone())),
        ContentData::Empty => Err(StatusCode::NO_CONTENT),
    }
}

async fn serve_preview(
    Path(id): Path<String>,
    State(state): State<ServerState>,
) -> Result<String, StatusCode> {
    let store = state.content.read().await;
    let item = store.get(&id).ok_or(StatusCode::NOT_FOUND)?;
    Ok(item.preview.clone())
}
