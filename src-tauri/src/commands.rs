/// Tauri IPC commands — callable from the frontend via invoke().

use std::sync::Arc;
use tauri::State;
use serde::{Deserialize, Serialize};

use fenix_hub_core::content::ContentItem;
use fenix_hub_core::identity::GroupIdentity;
use fenix_hub_core::protocol::Announcement;
use fenix_hub_core::server::{start_content_server, ContentStore};

use crate::state::HubState;

// ── Identity ─────────────────────────────────────────────────────────────────

#[derive(Serialize)]
pub struct IdentityInfo {
    pub device_name: String,
    pub group_id: String,
    pub configured: bool,
}

#[tauri::command]
pub async fn get_identity(state: State<'_, HubState>) -> Result<IdentityInfo, String> {
    let id = state.identity.read().await;
    match id.as_ref() {
        Some(identity) => Ok(IdentityInfo {
            device_name: identity.device_name.clone(),
            group_id: identity.group_id(),
            configured: true,
        }),
        None => Ok(IdentityInfo {
            device_name: String::new(),
            group_id: String::new(),
            configured: false,
        }),
    }
}

#[derive(Deserialize)]
pub struct SetupIdentityArgs {
    pub passphrase: String,
    pub device_name: String,
}

/// First-time setup: derive group identity from passphrase.
/// The passphrase is used as a seed — it never leaves this device.
/// Two devices with the same passphrase will derive the same group_key.
#[tauri::command]
pub async fn setup_identity(
    args: SetupIdentityArgs,
    state: State<'_, HubState>,
) -> Result<IdentityInfo, String> {
    let identity = GroupIdentity::from_passphrase(&args.passphrase, &args.device_name)
        .map_err(|e| e.to_string())?;
    let identity = Arc::new(identity);
    let info = IdentityInfo {
        device_name: identity.device_name.clone(),
        group_id: identity.group_id(),
        configured: true,
    };
    *state.identity.write().await = Some(identity);
    Ok(info)
}

// ── Local content management ──────────────────────────────────────────────────

#[derive(Serialize)]
pub struct ContentItemDto {
    pub id: String,
    pub content_type: String,
    pub preview: String,
    pub size_bytes: u64,
    pub created_at: u64,
}

impl From<&ContentItem> for ContentItemDto {
    fn from(item: &ContentItem) -> Self {
        Self {
            id: item.id.clone(),
            content_type: format!("{:?}", item.content_type).to_lowercase(),
            preview: item.preview.clone(),
            size_bytes: item.size_bytes,
            created_at: item.created_at,
        }
    }
}

#[tauri::command]
pub async fn get_local_content(state: State<'_, HubState>) -> Result<Vec<ContentItemDto>, String> {
    let content = state.local_content.read().await;
    let mut items: Vec<ContentItemDto> = content.values().map(|i| i.into()).collect();
    items.sort_by(|a, b| b.created_at.cmp(&a.created_at));
    Ok(items)
}

#[tauri::command]
pub async fn add_text_content(
    text: String,
    state: State<'_, HubState>,
) -> Result<ContentItemDto, String> {
    let item = ContentItem::from_text(text);
    let dto = ContentItemDto::from(&item);
    state.local_content.write().await.insert(item.id.clone(), item);
    Ok(dto)
}

#[tauri::command]
pub async fn remove_content(id: String, state: State<'_, HubState>) -> Result<(), String> {
    state.local_content.write().await.remove(&id);
    Ok(())
}

// ── Publishing (broadcast or direct) ─────────────────────────────────────────

#[derive(Deserialize)]
pub struct PublishArgs {
    pub content_id: String,
    /// null = broadcast; Some(device_name) = direct send
    pub target_device: Option<String>,
}

/// Publish a local content item: start ephemeral HTTP server + announce via mDNS.
/// For direct sends, also notifies the target peer's daemon.
#[tauri::command]
pub async fn publish_content(
    args: PublishArgs,
    state: State<'_, HubState>,
) -> Result<(), String> {
    let identity = state.identity.read().await.clone().ok_or("Identity not configured")?;

    let content_store: ContentStore = state.local_content.clone();

    // Start ephemeral HTTP server if not already running
    let port = {
        let existing = state.server_port.read().await;
        if let Some(p) = *existing {
            p
        } else {
            drop(existing);
            let (port, shutdown_tx) = start_content_server(identity.clone(), content_store)
                .await
                .map_err(|e| e.to_string())?;
            *state.server_shutdown.write().await = Some(shutdown_tx);
            *state.server_port.write().await = Some(port);
            port
        }
    };

    let content = state.local_content.read().await;
    let item = content.get(&args.content_id).ok_or("Content not found")?;

    let send_mode = match args.target_device {
        None => fenix_hub_core::protocol::SendMode::Broadcast,
        Some(ref target) => fenix_hub_core::protocol::SendMode::Direct {
            target_device: target.clone(),
        },
    };

    let announcement = Announcement {
        group_id: identity.group_id(),
        content_id: item.id.clone(),
        device_name: identity.device_name.clone(),
        preview: item.preview.clone(),
        content_type: item.content_type.clone(),
        size_bytes: item.size_bytes,
        send_mode,
        created_at: item.created_at,
        port,
    };

    // TODO: announce via mDNS (mdns-sd ServiceDaemon needs to be stored in state)
    // TODO: for direct send, also POST to target peer's /notify endpoint

    tracing::info!(
        "Published content {} on port {} (mode: {:?})",
        args.content_id, port, announcement.send_mode
    );
    Ok(())
}

// ── Peer content ─────────────────────────────────────────────────────────────

#[tauri::command]
pub async fn get_peers(state: State<'_, HubState>) -> Result<Vec<Announcement>, String> {
    let peers = state.peer_content.read().await;
    Ok(peers.values().cloned().collect())
}

/// Pull content from a peer and put it in the local clipboard / content store.
#[tauri::command]
pub async fn pull_peer_content(
    content_id: String,
    state: State<'_, HubState>,
) -> Result<ContentItemDto, String> {
    let peers = state.peer_content.read().await;
    let announcement = peers.get(&content_id).ok_or("Peer content not found")?;

    let identity = state.identity.read().await.clone().ok_or("Identity not configured")?;

    // TODO: resolve peer IP from mDNS record (stored alongside announcement)
    // let data = fenix_hub_core::client::pull_content(peer_ip, announcement.port, &content_id, &identity).await?;

    // TODO: write to system clipboard (via tauri-plugin-clipboard or arboard)

    tracing::info!("Pulled content {} from peer {}", content_id, announcement.device_name);

    Ok(ContentItemDto {
        id: content_id,
        content_type: format!("{:?}", announcement.content_type).to_lowercase(),
        preview: announcement.preview.clone(),
        size_bytes: announcement.size_bytes,
        created_at: announcement.created_at,
    })
}
