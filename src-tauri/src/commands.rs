/// Tauri IPC commands — callable from the frontend via invoke().

use std::net::IpAddr;
use std::sync::Arc;
use tauri::{AppHandle, State};
use serde::{Deserialize, Serialize};

use fenix_hub_core::content::ContentItem;
use fenix_hub_core::identity::GroupIdentity;
use fenix_hub_core::protocol::Announcement;
use fenix_hub_core::server::{start_content_server, ContentStore};
use fenix_hub_daemon::mdns::{announce_content, unannounce_content};

use crate::state::HubState;
use crate::persist;
use crate::discovery;

// ── Identity ──────────────────────────────────────────────────────────────────

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
    /// None means "keep existing key, only update device_name"
    pub passphrase: Option<String>,
    pub device_name: String,
}

#[tauri::command]
pub async fn setup_identity(
    args: SetupIdentityArgs,
    app: AppHandle,
    state: State<'_, HubState>,
) -> Result<IdentityInfo, String> {
    let identity = if let Some(ref pass) = args.passphrase {
        GroupIdentity::from_passphrase(pass, &args.device_name)
            .map_err(|e| e.to_string())?
    } else {
        // No new passphrase — keep existing group key, just rename device
        let existing = state.identity.read().await.clone()
            .ok_or("No existing identity to update")?;
        GroupIdentity::from_key_hex(&existing.key_hex(), &args.device_name)
            .map_err(|e| e.to_string())?
    };
    let identity = Arc::new(identity);

    // Persist derived key to disk (passphrase never saved)
    persist::save(&identity).map_err(|e| e.to_string())?;

    // Start discovery with the new identity
    discovery::start(
        app,
        state.mdns.clone(),
        identity.clone(),
        state.peer_content.clone(),
    );

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
    // Unannounce from mDNS if it was published
    let instance = state.active_announcements.write().await.remove(&id);
    if let Some(instance_name) = instance {
        unannounce_content(&state.mdns, &instance_name)
            .map_err(|e| tracing::warn!("Failed to unannounce {}: {}", id, e))
            .ok();
    }
    state.local_content.write().await.remove(&id);
    Ok(())
}

// ── Publishing ────────────────────────────────────────────────────────────────

#[derive(Deserialize)]
pub struct PublishArgs {
    pub content_id: String,
    pub target_device: Option<String>,
}

/// Start ephemeral HTTP server (if needed) + announce via mDNS.
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

    // Get local IP for mDNS announcement
    let local_ip = local_ipv4().ok_or("Cannot determine local IP")?;

    let instance_name = announce_content(&state.mdns, &announcement, local_ip)
        .map_err(|e| e.to_string())?;

    state.active_announcements.write().await
        .insert(args.content_id.clone(), instance_name);

    tracing::info!("Published {} on port {} via mDNS", args.content_id, port);
    Ok(())
}

/// Stop the content server and remove all mDNS announcements (user closed hub).
#[tauri::command]
pub async fn stop_server(state: State<'_, HubState>) -> Result<(), String> {
    // Unannounce all active announcements
    let announcements: Vec<(String, String)> = state.active_announcements.write().await
        .drain()
        .collect();
    for (_, instance_name) in announcements {
        unannounce_content(&state.mdns, &instance_name).ok();
    }

    // Shut down HTTP server
    if let Some(tx) = state.server_shutdown.write().await.take() {
        let _ = tx.send(());
    }
    *state.server_port.write().await = None;

    tracing::info!("Hub server stopped, all announcements removed");
    Ok(())
}

/// Copy a local item directly to the system clipboard (click-to-copy).
#[tauri::command]
pub async fn write_local_to_clipboard(id: String, state: State<'_, HubState>) -> Result<(), String> {
    let content = state.local_content.read().await;
    let item = content.get(&id).ok_or("Content not found")?;
    let mut clipboard = arboard::Clipboard::new().map_err(|e| e.to_string())?;
    clipboard.set_text(&item.preview).map_err(|e| e.to_string())?;
    tracing::info!("Copied local item {} to clipboard", id);
    Ok(())
}

// ── Peer content ──────────────────────────────────────────────────────────────

#[tauri::command]
pub async fn get_peers(state: State<'_, HubState>) -> Result<Vec<Announcement>, String> {
    let peers = state.peer_content.read().await;
    Ok(peers.values().map(|(a, _)| a.clone()).collect())
}

/// Pull content from a peer via HTTP and write it to the system clipboard.
#[tauri::command]
pub async fn pull_peer_content(
    content_id: String,
    state: State<'_, HubState>,
) -> Result<ContentItemDto, String> {
    let peers = state.peer_content.read().await;
    let (announcement, peer_ip) = peers.get(&content_id).ok_or("Peer content not found")?;
    let announcement = announcement.clone();
    let peer_ip = *peer_ip;
    drop(peers);

    let identity = state.identity.read().await.clone().ok_or("Identity not configured")?;

    let data = fenix_hub_core::client::pull_content(peer_ip, announcement.port, &content_id, &identity)
        .await
        .map_err(|e| e.to_string())?;

    // Write to system clipboard
    write_to_clipboard(&announcement, data).map_err(|e| e.to_string())?;

    tracing::info!("Pulled {} from {} ({})", content_id, announcement.device_name, peer_ip);

    Ok(ContentItemDto {
        id: content_id,
        content_type: format!("{:?}", announcement.content_type).to_lowercase(),
        preview: announcement.preview.clone(),
        size_bytes: announcement.size_bytes,
        created_at: announcement.created_at,
    })
}

// ── Helpers ───────────────────────────────────────────────────────────────────

fn local_ipv4() -> Option<std::net::Ipv4Addr> {
    // Connect a UDP socket to a public IP (no data sent) to find local outbound IP
    let socket = std::net::UdpSocket::bind("0.0.0.0:0").ok()?;
    socket.connect("8.8.8.8:80").ok()?;
    match socket.local_addr().ok()?.ip() {
        IpAddr::V4(v4) => Some(v4),
        _ => None,
    }
}

fn write_to_clipboard(announcement: &Announcement, data: Vec<u8>) -> anyhow::Result<()> {
    use fenix_hub_core::content::ContentType;
    let mut clipboard = arboard::Clipboard::new()?;
    match announcement.content_type {
        ContentType::Text => {
            let text = String::from_utf8(data)?;
            clipboard.set_text(text)?;
        }
        ContentType::Image => {
            // arboard supports RGBA images; for now store as text path or skip
            // Full image clipboard support requires decoding PNG → RGBA pixels
            // TODO: decode image bytes → arboard::ImageData
            tracing::info!("Image clipboard: {} bytes (full decode pending)", data.len());
        }
        ContentType::File => {
            // Files: write to ~/Downloads and put path in clipboard
            let filename = announcement.preview.split(' ').next().unwrap_or("fenixhub_file");
            let dest = dirs::download_dir()
                .unwrap_or_else(|| std::path::PathBuf::from("."))
                .join(filename);
            std::fs::write(&dest, &data)?;
            clipboard.set_text(dest.to_string_lossy().to_string())?;
            tracing::info!("File saved to {:?}", dest);
        }
    }
    Ok(())
}
