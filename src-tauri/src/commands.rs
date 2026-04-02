use serde::{Deserialize, Serialize};
/// Tauri IPC commands — callable from the frontend via invoke().
use std::net::IpAddr;
use std::sync::Arc;
use tauri::{AppHandle, State};

use base64::Engine;
use fenix_hub_core::content::{ContentData, ContentItem};
use fenix_hub_core::identity::GroupIdentity;
use fenix_hub_core::protocol::Announcement;
use fenix_hub_core::server::{start_content_server, ContentStore};
use fenix_hub_daemon::mdns::{announce_content, unannounce_content};

use crate::discovery;
use crate::persist;
use crate::state::HubState;
use crate::temp_store;

const MAX_ANNOUNCEMENT_BYTES: usize = 1000;
const MAX_ANNOUNCEMENT_FILE_NAME_CHARS: usize = 80;
const MIN_ANNOUNCEMENT_PREVIEW_CHARS: usize = 24;
const ANNOUNCEMENT_PREVIEW_TRIM_STEP: usize = 8;

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
        GroupIdentity::from_passphrase(pass, &args.device_name).map_err(|e| e.to_string())?
    } else {
        // No new passphrase — keep existing group key, just rename device
        let existing = state
            .identity
            .read()
            .await
            .clone()
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
    pub file_name: Option<String>,
    pub mime_type: Option<String>,
    pub transfer_path: Option<String>,
}

impl From<&ContentItem> for ContentItemDto {
    fn from(item: &ContentItem) -> Self {
        Self {
            id: item.id.clone(),
            content_type: format!("{:?}", item.content_type).to_lowercase(),
            preview: item.preview.clone(),
            size_bytes: item.size_bytes,
            created_at: item.created_at,
            file_name: item.file_name.clone(),
            mime_type: item.mime_type.clone(),
            transfer_path: transfer_path(item),
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
    state
        .local_content
        .write()
        .await
        .insert(item.id.clone(), item);
    Ok(dto)
}

#[derive(Deserialize)]
pub struct AddBinaryContentArgs {
    pub file_name: String,
    pub mime_type: Option<String>,
    pub bytes_base64: String,
    pub preview: Option<String>,
}

#[tauri::command]
pub async fn add_binary_content(
    args: AddBinaryContentArgs,
    state: State<'_, HubState>,
) -> Result<ContentItemDto, String> {
    let bytes = base64::engine::general_purpose::STANDARD
        .decode(args.bytes_base64)
        .map_err(|e| e.to_string())?;
    let content_type = match args.mime_type.as_deref() {
        Some(mime) if mime.starts_with("image/") => fenix_hub_core::content::ContentType::Image,
        _ => fenix_hub_core::content::ContentType::File,
    };
    let item = create_temp_binary_item(
        bytes,
        content_type,
        Some(args.file_name),
        args.mime_type,
        args.preview,
    )
    .map_err(|e| e.to_string())?;
    let dto = ContentItemDto::from(&item);
    state
        .local_content
        .write()
        .await
        .insert(item.id.clone(), item);
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
    let removed = state.local_content.write().await.remove(&id);
    if let Some(item) = removed {
        cleanup_item_storage(&item);
    }
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
pub async fn publish_content(args: PublishArgs, state: State<'_, HubState>) -> Result<(), String> {
    let identity = state
        .identity
        .read()
        .await
        .clone()
        .ok_or("Identity not configured")?;
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
        None => fenix_hub_core::protocol::SendMode::Broadcast {},
        Some(ref target) => fenix_hub_core::protocol::SendMode::Direct {
            target_device: target.clone(),
        },
    };

    let announcement = compact_announcement_for_mdns(Announcement {
        protocol_version: fenix_hub_core::protocol::PROTOCOL_VERSION,
        group_id: identity.group_id(),
        content_id: item.id.clone(),
        device_name: identity.device_name.clone(),
        preview: item.preview.clone(),
        content_type: item.content_type.clone(),
        size_bytes: item.size_bytes,
        file_name: item.file_name.clone(),
        mime_type: item.mime_type.clone(),
        send_mode,
        created_at: item.created_at,
        port,
    });

    // Get local IP for mDNS announcement
    let local_ip = local_ipv4().ok_or("Cannot determine local IP")?;

    let instance_name =
        announce_content(&state.mdns, &announcement, local_ip).map_err(|e| e.to_string())?;

    state
        .active_announcements
        .write()
        .await
        .insert(args.content_id.clone(), instance_name);

    tracing::info!("Published {} on port {} via mDNS", args.content_id, port);
    Ok(())
}

/// Stop the content server and remove all mDNS announcements (user closed hub).
#[tauri::command]
pub async fn stop_server(state: State<'_, HubState>) -> Result<(), String> {
    // Unannounce all active announcements
    let announcements: Vec<(String, String)> =
        state.active_announcements.write().await.drain().collect();
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
pub async fn write_local_to_clipboard(
    id: String,
    state: State<'_, HubState>,
) -> Result<(), String> {
    let content = state.local_content.read().await;
    let item = content.get(&id).ok_or("Content not found")?;
    write_item_to_clipboard(item).map_err(|e| e.to_string())?;
    tracing::info!("Copied local item {} to clipboard", id);
    Ok(())
}

#[derive(Serialize)]
pub struct DragPayloadDto {
    pub text: Option<String>,
    pub uri_list: Option<String>,
}

#[tauri::command]
pub async fn prepare_local_drag(
    id: String,
    state: State<'_, HubState>,
) -> Result<DragPayloadDto, String> {
    let content = state.local_content.read().await;
    let item = content.get(&id).ok_or("Content not found")?;
    write_item_to_clipboard(item).map_err(|e| e.to_string())?;

    let payload = match &item.data {
        ContentData::Text(text) => DragPayloadDto {
            text: Some(text.clone()),
            uri_list: None,
        },
        ContentData::FilePath(path) => {
            let value = format!("file://{}", path.to_string_lossy());
            DragPayloadDto {
                text: Some(path.to_string_lossy().to_string()),
                uri_list: Some(value),
            }
        }
        ContentData::Bytes(bytes) => {
            let path = temp_store::write_item_bytes(
                &item.id,
                item.file_name.as_deref().unwrap_or("fenixhub-item.bin"),
                bytes,
            )
            .map_err(|e| e.to_string())?;
            let value = format!("file://{}", path.to_string_lossy());
            DragPayloadDto {
                text: Some(path.to_string_lossy().to_string()),
                uri_list: Some(value),
            }
        }
        ContentData::Empty => DragPayloadDto {
            text: None,
            uri_list: None,
        },
    };

    Ok(payload)
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

    let identity = state
        .identity
        .read()
        .await
        .clone()
        .ok_or("Identity not configured")?;

    let pulled =
        fenix_hub_core::client::pull_content(peer_ip, announcement.port, &content_id, &identity)
            .await
            .map_err(|e| e.to_string())?;

    let item = match announcement.content_type {
        fenix_hub_core::content::ContentType::Text => {
            let text = String::from_utf8(pulled.bytes).map_err(|e| e.to_string())?;
            ContentItem::from_text(text)
        }
        fenix_hub_core::content::ContentType::Image => create_temp_binary_item(
            pulled.bytes,
            fenix_hub_core::content::ContentType::Image,
            pulled.file_name.or_else(|| announcement.file_name.clone()),
            pulled.mime_type.or_else(|| announcement.mime_type.clone()),
            Some(announcement.preview.clone()),
        )
        .map_err(|e| e.to_string())?,
        fenix_hub_core::content::ContentType::File => create_temp_binary_item(
            pulled.bytes,
            fenix_hub_core::content::ContentType::File,
            pulled.file_name.or_else(|| announcement.file_name.clone()),
            pulled.mime_type.or_else(|| announcement.mime_type.clone()),
            Some(announcement.preview.clone()),
        )
        .map_err(|e| e.to_string())?,
    };

    write_item_to_clipboard(&item).map_err(|e| e.to_string())?;
    let dto = ContentItemDto::from(&item);
    state
        .local_content
        .write()
        .await
        .insert(item.id.clone(), item);

    tracing::info!(
        "Pulled {} from {} ({})",
        content_id,
        announcement.device_name,
        peer_ip
    );

    Ok(dto)
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

fn write_item_to_clipboard(item: &ContentItem) -> anyhow::Result<()> {
    let mut clipboard = arboard::Clipboard::new()?;
    match &item.data {
        ContentData::Text(text) => {
            clipboard.set_text(text)?;
        }
        ContentData::FilePath(path) => {
            clipboard.set_text(path.to_string_lossy().to_string())?;
        }
        ContentData::Bytes(bytes) => {
            let path = temp_store::write_item_bytes(
                &item.id,
                item.file_name.as_deref().unwrap_or("fenixhub-item.bin"),
                bytes,
            )?;
            clipboard.set_text(path.to_string_lossy().to_string())?;
        }
        ContentData::Empty => {}
    }
    Ok(())
}

fn create_temp_binary_item(
    bytes: Vec<u8>,
    content_type: fenix_hub_core::content::ContentType,
    file_name: Option<String>,
    mime_type: Option<String>,
    preview: Option<String>,
) -> anyhow::Result<ContentItem> {
    let temp_id = uuid::Uuid::new_v4().to_string();
    let default_name =
        default_file_name(content_type.clone(), mime_type.as_deref(), temp_id.as_str());
    let final_name = file_name.unwrap_or(default_name);
    let path = temp_store::write_item_bytes(&temp_id, &final_name, &bytes)?;
    ContentItem::from_temp_file(path, content_type, Some(final_name), mime_type, preview)
}

fn default_file_name(
    content_type: fenix_hub_core::content::ContentType,
    mime_type: Option<&str>,
    seed: &str,
) -> String {
    let extension = mime_type
        .and_then(extension_from_mime)
        .unwrap_or_else(|| match content_type {
            fenix_hub_core::content::ContentType::Image => "png",
            fenix_hub_core::content::ContentType::File => "bin",
            fenix_hub_core::content::ContentType::Text => "txt",
        });
    format!("fenixhub-{}.{}", seed, extension)
}

fn extension_from_mime(mime_type: &str) -> Option<&'static str> {
    match mime_type {
        "image/png" => Some("png"),
        "image/jpeg" => Some("jpg"),
        "image/webp" => Some("webp"),
        "image/gif" => Some("gif"),
        "application/pdf" => Some("pdf"),
        "text/plain" | "text/plain; charset=utf-8" => Some("txt"),
        "application/zip" => Some("zip"),
        _ => None,
    }
}

fn cleanup_item_storage(item: &ContentItem) {
    if let ContentData::FilePath(path) = &item.data {
        temp_store::remove_item_path(path).ok();
    }
}

fn transfer_path(item: &ContentItem) -> Option<String> {
    match &item.data {
        ContentData::FilePath(path) => Some(path.to_string_lossy().to_string()),
        _ => None,
    }
}

fn compact_announcement_for_mdns(mut announcement: Announcement) -> Announcement {
    if announcement_size(&announcement) <= MAX_ANNOUNCEMENT_BYTES {
        return announcement;
    }

    let fallback_preview = announcement_preview_fallback(&announcement);
    if announcement.preview != fallback_preview {
        announcement.preview = fallback_preview;
    }
    if announcement_size(&announcement) <= MAX_ANNOUNCEMENT_BYTES {
        return announcement;
    }

    if let Some(file_name) = announcement
        .file_name
        .clone()
        .filter(|name| name.chars().count() > MAX_ANNOUNCEMENT_FILE_NAME_CHARS)
    {
        announcement.file_name = Some(truncate_chars(
            &file_name,
            MAX_ANNOUNCEMENT_FILE_NAME_CHARS,
        ));
    }
    if announcement_size(&announcement) <= MAX_ANNOUNCEMENT_BYTES {
        return announcement;
    }

    announcement.mime_type = None;
    if announcement_size(&announcement) <= MAX_ANNOUNCEMENT_BYTES {
        return announcement;
    }

    let mut preview_len = announcement.preview.chars().count();
    while announcement_size(&announcement) > MAX_ANNOUNCEMENT_BYTES
        && preview_len > MIN_ANNOUNCEMENT_PREVIEW_CHARS
    {
        preview_len = preview_len
            .saturating_sub(ANNOUNCEMENT_PREVIEW_TRIM_STEP)
            .max(MIN_ANNOUNCEMENT_PREVIEW_CHARS);
        announcement.preview = truncate_chars(&announcement.preview, preview_len);
    }
    if announcement_size(&announcement) <= MAX_ANNOUNCEMENT_BYTES {
        return announcement;
    }

    announcement.file_name = None;
    if announcement_size(&announcement) <= MAX_ANNOUNCEMENT_BYTES {
        return announcement;
    }

    announcement.preview = announcement_kind_label(&announcement.content_type).to_string();
    announcement
}

fn announcement_size(announcement: &Announcement) -> usize {
    serde_json::to_vec(announcement)
        .map(|payload| payload.len())
        .unwrap_or(usize::MAX)
}

fn announcement_preview_fallback(announcement: &Announcement) -> String {
    match announcement.content_type {
        fenix_hub_core::content::ContentType::Text => {
            truncate_chars(&announcement.preview, MAX_ANNOUNCEMENT_FILE_NAME_CHARS)
        }
        fenix_hub_core::content::ContentType::Image => announcement
            .file_name
            .as_deref()
            .map(|name| format!("Imagen: {}", truncate_chars(name, 48)))
            .unwrap_or_else(|| "Imagen lista para descargar".to_string()),
        fenix_hub_core::content::ContentType::File => announcement
            .file_name
            .as_deref()
            .map(|name| format!("Archivo: {}", truncate_chars(name, 48)))
            .unwrap_or_else(|| "Archivo listo para descargar".to_string()),
    }
}

fn announcement_kind_label(
    content_type: &fenix_hub_core::content::ContentType,
) -> &'static str {
    match content_type {
        fenix_hub_core::content::ContentType::Text => "Texto",
        fenix_hub_core::content::ContentType::Image => "Imagen",
        fenix_hub_core::content::ContentType::File => "Archivo",
    }
}

fn truncate_chars(value: &str, max_chars: usize) -> String {
    value.chars().take(max_chars).collect()
}

#[cfg(test)]
mod tests {
    use super::*;
    use fenix_hub_core::content::ContentType;
    use fenix_hub_core::protocol::SendMode;

    #[test]
    fn compacts_large_image_announcement_for_mdns() {
        let announcement = Announcement {
            group_id: "g".repeat(32),
            content_id: "content-1".to_string(),
            device_name: "Pixel".to_string(),
            preview: format!("data:image/jpeg;base64,{}", "A".repeat(4096)),
            content_type: ContentType::Image,
            size_bytes: 42,
            file_name: Some("camera-roll-photo.jpg".to_string()),
            mime_type: Some("image/jpeg".to_string()),
            send_mode: SendMode::Broadcast {},
            created_at: 1,
            port: 8765,
        };

        let compacted = compact_announcement_for_mdns(announcement);
        assert!(announcement_size(&compacted) <= MAX_ANNOUNCEMENT_BYTES);
        assert!(!compacted.preview.starts_with("data:image"));
    }
}
