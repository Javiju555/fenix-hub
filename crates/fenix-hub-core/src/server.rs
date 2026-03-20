use anyhow::Result;
use axum::{
    body::Bytes,
    extract::{Path, State},
    http::{
        header::{CONTENT_DISPOSITION, CONTENT_TYPE},
        HeaderMap, HeaderValue, StatusCode,
    },
    response::IntoResponse,
    routing::get,
    Router,
};
/// Ephemeral HTTP content server.
///
/// Spun up when the user publishes content. Lives only as long as the hub is open.
/// Serves content to authenticated peers that pull via HTTP GET.
use std::collections::HashMap;
use std::sync::Arc;
use tokio::sync::{oneshot, RwLock};

use crate::content::{ContentData, ContentItem};
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
    // Verify HMAC signature
    let sig_header = headers
        .get(HMAC_HEADER)
        .and_then(|v| v.to_str().ok())
        .ok_or(StatusCode::UNAUTHORIZED)?;

    let expected_sig = state.identity.sign(id.as_bytes());
    let expected_hex = hex::encode(&expected_sig);
    if sig_header != expected_hex {
        return Err(StatusCode::UNAUTHORIZED);
    }

    let store = state.content.read().await;
    let item = store.get(&id).ok_or(StatusCode::NOT_FOUND)?;

    let payload = match &item.data {
        ContentData::Text(text) => text.clone().into_bytes(),
        ContentData::Bytes(bytes) => bytes.clone(),
        ContentData::FilePath(path) => tokio::fs::read(path)
            .await
            .map_err(|_| StatusCode::NOT_FOUND)?,
        ContentData::Empty => return Err(StatusCode::NO_CONTENT),
    };

    let mut response_headers = HeaderMap::new();
    if let Some(mime_type) = item.mime_type.as_deref() {
        if let Ok(header) = HeaderValue::from_str(mime_type) {
            response_headers.insert(CONTENT_TYPE, header);
        }
    }
    if let Some(file_name) = item.file_name.as_deref() {
        let value = format!(
            "inline; filename=\"{}\"",
            file_name.replace('\\', "_").replace('"', "_")
        );
        if let Ok(header) = HeaderValue::from_str(&value) {
            response_headers.insert(CONTENT_DISPOSITION, header);
        }
    }

    Ok((response_headers, Bytes::from(payload)))
}
