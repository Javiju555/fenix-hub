use serde::{Deserialize, Serialize};
/// Tauri IPC commands — callable from the frontend via invoke().
#[cfg(not(target_os = "windows"))]
use std::path::Path;
use std::time::{Duration, Instant};
use std::sync::Arc;
use tauri::{AppHandle, Manager, State};

use base64::Engine;
use fenix_hub_core::content::{ContentData, ContentItem};
use fenix_hub_core::identity::GroupIdentity;
use fenix_hub_core::protocol::Announcement;
use fenix_hub_core::server::{start_content_server, ContentStore};
use fenix_hub_daemon::mdns::{announce_content, unannounce_content};

use crate::discovery;
use crate::persist::{self, DeviceType};
use crate::state::HubState;
use crate::temp_store;
use crate::windowing;

const MAX_ANNOUNCEMENT_BYTES: usize = 1000;
const MAX_ANNOUNCEMENT_FILE_NAME_CHARS: usize = 80;
const MIN_ANNOUNCEMENT_PREVIEW_CHARS: usize = 24;
const MAX_PUBLISH_LIFETIME: Duration = Duration::from_secs(10 * 60);
const SERVER_GUARD_POLL_INTERVAL: Duration = Duration::from_secs(5);

// ── Identity ──────────────────────────────────────────────────────────────────

#[derive(Serialize)]
pub struct IdentityInfo {
    pub device_name: String,
    pub group_id: String,
    pub configured: bool,
    /// Cosmetic device type shown in the hub header (desktop/laptop/phone/tablet/server).
    pub device_type: String,
}

#[tauri::command]
pub async fn get_identity(state: State<'_, HubState>) -> Result<IdentityInfo, String> {
    let id = state.identity.read().await;
    let device_type = format!("{:?}", *state.device_type.read().await).to_lowercase();
    match id.as_ref() {
        Some(identity) => Ok(IdentityInfo {
            device_name: identity.device_name.clone(),
            group_id: identity.group_id(),
            configured: true,
            device_type,
        }),
        None => Ok(IdentityInfo {
            device_name: String::new(),
            group_id: String::new(),
            configured: false,
            device_type,
        }),
    }
}

#[derive(Deserialize)]
pub struct SetupIdentityArgs {
    /// None means "keep existing key, only update device_name"
    pub passphrase: Option<String>,
    pub device_name: String,
    /// Cosmetic device type. None = keep existing or use default.
    pub device_type: Option<String>,
}

#[tauri::command]
pub async fn setup_identity(
    args: SetupIdentityArgs,
    app: AppHandle,
    state: State<'_, HubState>,
) -> Result<IdentityInfo, String> {
    let had_identity = state.identity.read().await.is_some();
    let trimmed_name = args.device_name.trim();
    if trimmed_name.is_empty() {
        return Err("Device name is required".to_string());
    }

    let identity = if let Some(ref pass) = args.passphrase {
        GroupIdentity::from_passphrase(pass, trimmed_name).map_err(|e| e.to_string())?
    } else {
        // No new passphrase — keep existing group key, just rename device
        let existing = state
            .identity
            .read()
            .await
            .clone()
            .ok_or("No existing identity to update")?;
        GroupIdentity::from_key_hex(&existing.key_hex(), trimmed_name)
            .map_err(|e| e.to_string())?
    };
    let identity = Arc::new(identity);

    // Resolve device_type: use provided value, fall back to existing, then default.
    let fallback_type = state.device_type.read().await.clone();
    let device_type = parse_device_type(args.device_type.as_deref(), &fallback_type);

    // Persist derived key to disk (passphrase never saved)
    persist::save(&identity, &device_type).map_err(|e| e.to_string())?;

    // Start discovery only on first-time setup to avoid duplicate discovery loops.
    if !had_identity {
        discovery::start(
            app,
            state.mdns.clone(),
            identity.clone(),
            state.peer_content.clone(),
        );
    }

    *state.device_type.write().await = device_type;
    *state.identity.write().await = Some(identity);
    get_identity(state).await
}

#[derive(Serialize)]
pub struct UpdateIdentityResult {
    pub identity: IdentityInfo,
    pub group_changed: bool,
    pub requires_restart: bool,
}

#[tauri::command]
pub async fn update_identity(
    args: SetupIdentityArgs,
    state: State<'_, HubState>,
) -> Result<UpdateIdentityResult, String> {
    let existing = state
        .identity
        .read()
        .await
        .clone()
        .ok_or("Identity not configured")?;
    let previous_group_id = existing.group_id();

    let trimmed_name = args.device_name.trim();
    if trimmed_name.is_empty() {
        return Err("Device name is required".to_string());
    }

    let next_identity = if let Some(passphrase) = args.passphrase.as_deref() {
        let passphrase = passphrase.trim();
        if passphrase.is_empty() {
            return Err("Passphrase cannot be empty".to_string());
        }
        GroupIdentity::from_passphrase(passphrase, trimmed_name).map_err(|e| e.to_string())?
    } else {
        GroupIdentity::from_key_hex(&existing.key_hex(), trimmed_name).map_err(|e| e.to_string())?
    };

    let fallback_type = state.device_type.read().await.clone();
    let device_type = parse_device_type(args.device_type.as_deref(), &fallback_type);
    let group_changed = next_identity.group_id() != previous_group_id;

    persist::save(&next_identity, &device_type).map_err(|e| e.to_string())?;
    *state.identity.write().await = Some(Arc::new(next_identity));
    *state.device_type.write().await = device_type;

    // Existing live announcements carry old identity metadata. Stop active sharing
    // so the next publish starts with the new identity state.
    stop_active_shares(&state).await;
    state.peer_content.write().await.clear();

    let identity = get_identity(state).await?;
    Ok(UpdateIdentityResult {
        identity,
        group_changed,
        requires_restart: group_changed,
    })
}

#[tauri::command]
pub async fn delete_identity_only(state: State<'_, HubState>) -> Result<(), String> {
    stop_active_shares(&state).await;

    *state.identity.write().await = None;
    *state.device_type.write().await = DeviceType::Desktop;
    state.peer_content.write().await.clear();

    persist::delete_identity_file().map_err(|e| e.to_string())?;
    Ok(())
}

#[derive(Deserialize)]
pub struct SaveProfileArgs {
    pub name: String,
    #[serde(default)]
    pub make_active: bool,
}

#[derive(Deserialize)]
pub struct ProfileNameArgs {
    pub name: String,
}

#[derive(Serialize)]
pub struct ProfilesPayload {
    pub profiles: Vec<persist::IdentityProfileInfo>,
}

#[tauri::command]
pub async fn list_identity_profiles() -> Result<ProfilesPayload, String> {
    let profiles = persist::list_profiles().map_err(|e| e.to_string())?;
    Ok(ProfilesPayload { profiles })
}

#[tauri::command]
pub async fn save_current_identity_profile(
    args: SaveProfileArgs,
    state: State<'_, HubState>,
) -> Result<ProfilesPayload, String> {
    let identity = state
        .identity
        .read()
        .await
        .clone()
        .ok_or("Identity not configured")?;
    let device_type = state.device_type.read().await.clone();

    persist::save_profile(&args.name, &identity, &device_type, args.make_active)
        .map_err(|e| e.to_string())?;
    list_identity_profiles().await
}

#[tauri::command]
pub async fn activate_identity_profile(
    args: ProfileNameArgs,
    state: State<'_, HubState>,
) -> Result<UpdateIdentityResult, String> {
    let Some((identity, device_type)) =
        persist::activate_profile(&args.name).map_err(|e| e.to_string())?
    else {
        return Err("Profile not found".to_string());
    };

    let current_group = state
        .identity
        .read()
        .await
        .as_ref()
        .map(|id| id.group_id())
        .unwrap_or_default();
    let next_group = identity.group_id();

    *state.identity.write().await = Some(Arc::new(identity));
    *state.device_type.write().await = device_type;

    stop_active_shares(&state).await;
    state.peer_content.write().await.clear();

    let identity = get_identity(state).await?;
    let group_changed = !current_group.is_empty() && current_group != next_group;

    Ok(UpdateIdentityResult {
        identity,
        group_changed,
        // Profile switching changes cryptographic context; keep this explicit.
        requires_restart: true,
    })
}

#[tauri::command]
pub async fn delete_identity_profile(args: ProfileNameArgs) -> Result<ProfilesPayload, String> {
    persist::remove_profile(&args.name).map_err(|e| e.to_string())?;
    list_identity_profiles().await
}

#[derive(Serialize)]
pub struct TransportRadioDetails {
    pub supported: bool,
    pub enabled: bool,
    pub permissions_ready: bool,
    pub adapters: Vec<String>,
    pub last_error: Option<String>,
}

#[derive(Serialize)]
pub struct TransportCapabilities {
    pub lan: bool,
    pub lan_ip: Option<String>,
    pub airdrop_ready: bool,
    pub flow: String,
    pub ble: TransportRadioDetails,
    pub wifi_direct: TransportRadioDetails,
    pub ble_peers: Vec<String>,
    pub wifi_direct_peers: Vec<String>,
    pub handoff_candidates: Vec<String>,
}

#[tauri::command]
pub fn get_transport_capabilities() -> TransportCapabilities {
    let lan_ip = crate::network::local_ipv4();
    let ble = ble_transport_details();
    let wifi_direct = wifi_direct_transport_details();
    let airdrop_ready = ble.supported && ble.enabled && wifi_direct.supported && wifi_direct.enabled;

    TransportCapabilities {
        lan: lan_ip.is_some(),
        lan_ip: lan_ip.map(|ip| ip.to_string()),
        airdrop_ready,
        flow: "ble_discovery_then_wifi_direct_transfer".to_string(),
        ble,
        wifi_direct,
        ble_peers: vec![],
        wifi_direct_peers: vec![],
        handoff_candidates: vec![],
    }
}

#[tauri::command]
pub fn get_transport_hardware() -> TransportCapabilities {
    get_transport_capabilities()
}

fn parse_device_type(raw: Option<&str>, fallback: &DeviceType) -> DeviceType {
    match raw {
        Some("laptop") => DeviceType::Laptop,
        Some("phone") => DeviceType::Phone,
        Some("tablet") => DeviceType::Tablet,
        Some("server") => DeviceType::Server,
        Some("desktop") => DeviceType::Desktop,
        Some(_) => DeviceType::Desktop,
        None => fallback.clone(),
    }
}

async fn stop_active_shares(state: &HubState) {
    stop_server_guard(state).await;

    let announcements: Vec<(String, String)> =
        state.active_announcements.write().await.drain().collect();
    for (_, instance_name) in announcements {
        if let Err(error) = unannounce_content(&state.mdns, &instance_name) {
            tracing::warn!("Failed to unannounce during identity update: {}", error);
        }
    }

    if let Some(tx) = state.server_shutdown.write().await.take() {
        let _ = tx.send(());
    }
    *state.server_port.write().await = None;
}

async fn start_server_guard(state: &HubState) {
    stop_server_guard(state).await;

    let Some(initial_ip) = local_ipv4().map(std::net::IpAddr::V4) else {
        tracing::warn!("Server guard disabled: unable to determine initial LAN IP");
        return;
    };

    let active_announcements = state.active_announcements.clone();
    let server_shutdown = state.server_shutdown.clone();
    let server_port = state.server_port.clone();
    let mdns = state.mdns.clone();

    let (guard_tx, mut guard_rx) = tokio::sync::oneshot::channel::<()>();
    *state.server_guard_shutdown.write().await = Some(guard_tx);

    let guard_task = tokio::spawn(async move {
        let started_at = Instant::now();
        loop {
            tokio::select! {
                _ = &mut guard_rx => break,
                _ = tokio::time::sleep(SERVER_GUARD_POLL_INTERVAL) => {}
            }

            if started_at.elapsed() >= MAX_PUBLISH_LIFETIME {
                tracing::warn!("Stopping active shares: publish lifetime exceeded");
                shutdown_server_runtime(
                    mdns.clone(),
                    active_announcements.clone(),
                    server_shutdown.clone(),
                    server_port.clone(),
                )
                .await;
                break;
            }

            let current_ip = local_ipv4().map(std::net::IpAddr::V4);
            if current_ip != Some(initial_ip) {
                tracing::warn!(
                    "Stopping active shares: network changed from {:?} to {:?}",
                    initial_ip,
                    current_ip
                );
                shutdown_server_runtime(
                    mdns.clone(),
                    active_announcements.clone(),
                    server_shutdown.clone(),
                    server_port.clone(),
                )
                .await;
                break;
            }
        }
    });

    *state.server_guard_task.write().await = Some(guard_task);
}

async fn stop_server_guard(state: &HubState) {
    if let Some(tx) = state.server_guard_shutdown.write().await.take() {
        let _ = tx.send(());
    }
    if let Some(task) = state.server_guard_task.write().await.take() {
        task.abort();
    }
}

async fn shutdown_server_runtime(
    mdns: mdns_sd::ServiceDaemon,
    active_announcements: Arc<tokio::sync::RwLock<std::collections::HashMap<String, String>>>,
    server_shutdown: Arc<tokio::sync::RwLock<Option<tokio::sync::oneshot::Sender<()>>>>,
    server_port: Arc<tokio::sync::RwLock<Option<u16>>>,
) {
    let announcements: Vec<(String, String)> = active_announcements.write().await.drain().collect();
    for (_, instance_name) in announcements {
        if let Err(error) = unannounce_content(&mdns, &instance_name) {
            tracing::warn!("Failed to unannounce during guarded shutdown: {}", error);
        }
    }

    if let Some(tx) = server_shutdown.write().await.take() {
        let _ = tx.send(());
    }
    *server_port.write().await = None;
}

#[cfg(target_os = "windows")]
fn ble_transport_details() -> TransportRadioDetails {
    let (adapters, error) = windows_script_lines(
        "Get-PnpDevice -Class Bluetooth -Status OK -ErrorAction SilentlyContinue | Select-Object -ExpandProperty FriendlyName",
    );
    let supported = !adapters.is_empty();

    TransportRadioDetails {
        supported,
        enabled: supported,
        permissions_ready: true,
        adapters,
        last_error: error,
    }
}

#[cfg(not(target_os = "windows"))]
fn ble_transport_details() -> TransportRadioDetails {
    let mut adapters = linux_bluetooth_adapters();
    let mut last_error = None;

    if adapters.is_empty() && command_exists("bluetoothctl") {
        match run_command_lines("bluetoothctl", &["list"]) {
            Some(lines) => {
                adapters = parse_bluetoothctl_controller_names(&lines);
            }
            None => {
                last_error = Some("failed_to_query_bluetoothctl_list".to_string());
            }
        }
    }

    let supported = !adapters.is_empty() || command_exists("bluetoothctl");
    let enabled = if command_exists("bluetoothctl") {
        run_command_lines("bluetoothctl", &["show"])
            .map(|lines| {
                lines
                    .iter()
                    .any(|line| line.trim().eq_ignore_ascii_case("Powered: yes"))
            })
            .unwrap_or(!adapters.is_empty())
    } else {
        !adapters.is_empty()
    };

    TransportRadioDetails {
        supported,
        enabled,
        permissions_ready: true,
        adapters,
        last_error,
    }
}

#[cfg(target_os = "windows")]
fn wifi_direct_transport_details() -> TransportRadioDetails {
    let (all_adapters, error_all) = windows_script_lines(
        "Get-NetAdapter -Physical -ErrorAction SilentlyContinue | Where-Object { $_.NdisPhysicalMedium -eq 'WirelessLan' } | Select-Object -ExpandProperty Name",
    );
    let (active_adapters, error_active) = windows_script_lines(
        "Get-NetAdapter -Physical -ErrorAction SilentlyContinue | Where-Object { $_.NdisPhysicalMedium -eq 'WirelessLan' -and $_.Status -eq 'Up' } | Select-Object -ExpandProperty Name",
    );

    let supported = !all_adapters.is_empty();
    let enabled = !active_adapters.is_empty();

    TransportRadioDetails {
        supported,
        enabled,
        permissions_ready: true,
        adapters: if enabled { active_adapters } else { all_adapters },
        last_error: error_all.or(error_active),
    }
}

#[cfg(not(target_os = "windows"))]
fn wifi_direct_transport_details() -> TransportRadioDetails {
    let adapters = linux_wireless_ifaces();
    let mut last_error = None;

    let iw_p2p_supported = if command_exists("iw") {
        match run_command_lines("iw", &["list"]) {
            Some(lines) => lines.iter().any(|line| {
                line.contains("P2P-client")
                    || line.contains("P2P-GO")
                    || line.contains("P2P-device")
            }),
            None => {
                last_error = Some("failed_to_query_iw_list".to_string());
                false
            }
        }
    } else {
        false
    };

    let supported = iw_p2p_supported || (!adapters.is_empty() && command_exists("wpa_cli"));
    let enabled = !adapters.is_empty();

    TransportRadioDetails {
        supported,
        enabled,
        permissions_ready: true,
        adapters,
        last_error,
    }
}

#[cfg(target_os = "windows")]
fn windows_script_lines(script: &str) -> (Vec<String>, Option<String>) {
    let output = match std::process::Command::new("powershell")
        .args(["-NoProfile", "-NonInteractive", "-Command", script])
        .output()
    {
        Ok(output) => output,
        Err(error) => return (Vec::new(), Some(error.to_string())),
    };

    if !output.status.success() {
        let stderr = String::from_utf8_lossy(&output.stderr).trim().to_string();
        return (
            Vec::new(),
            Some(if stderr.is_empty() {
                "windows_script_failed".to_string()
            } else {
                stderr
            }),
        );
    }

    let lines = String::from_utf8_lossy(&output.stdout)
        .lines()
        .map(str::trim)
        .filter(|line| !line.is_empty())
        .map(ToOwned::to_owned)
        .collect::<Vec<_>>();

    (lines, None)
}

#[cfg(not(target_os = "windows"))]
fn linux_bluetooth_adapters() -> Vec<String> {
    std::fs::read_dir("/sys/class/bluetooth")
        .ok()
        .into_iter()
        .flatten()
        .flatten()
        .filter_map(|entry| entry.file_name().to_str().map(ToOwned::to_owned))
        .filter(|name| name.starts_with("hci"))
        .collect()
}

#[cfg(not(target_os = "windows"))]
fn linux_wireless_ifaces() -> Vec<String> {
    std::fs::read_dir("/sys/class/net")
        .ok()
        .into_iter()
        .flatten()
        .flatten()
        .filter(|entry| entry.path().join("wireless").exists())
        .filter_map(|entry| entry.file_name().to_str().map(ToOwned::to_owned))
        .collect()
}

#[cfg(not(target_os = "windows"))]
fn run_command_lines(command: &str, args: &[&str]) -> Option<Vec<String>> {
    let output = std::process::Command::new(command)
        .args(args)
        .output()
        .ok()?;
    if !output.status.success() {
        return None;
    }

    Some(
        String::from_utf8_lossy(&output.stdout)
            .lines()
            .map(str::trim)
            .filter(|line| !line.is_empty())
            .map(ToOwned::to_owned)
            .collect(),
    )
}

#[cfg(not(target_os = "windows"))]
fn parse_bluetoothctl_controller_names(lines: &[String]) -> Vec<String> {
    lines
        .iter()
        .filter_map(|line| line.strip_prefix("Controller "))
        .filter_map(|rest| rest.split_once(' '))
        .map(|(_, name)| name.trim().to_string())
        .filter(|name| !name.is_empty())
        .collect()
}

#[cfg(not(target_os = "windows"))]
fn command_exists(command: &str) -> bool {
    std::process::Command::new("which")
        .arg(command)
        .output()
        .map(|output| output.status.success())
        .unwrap_or(false)
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
    /// Full text content — only set for text items (used by drag & clipboard)
    pub data_text: Option<String>,
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
            data_text: match &item.data {
                ContentData::Text(t) => Some(t.clone()),
                _ => None,
            },
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
    // Deduplicate: return existing item if identical text is already in the hub
    {
        let content = state.local_content.read().await;
        for existing in content.values() {
            if let ContentData::Text(ref t) = existing.data {
                if t == &text {
                    return Ok(ContentItemDto::from(existing));
                }
            }
        }
    }
    let item = ContentItem::from_text(text);
    if let ContentData::Text(ref t) = item.data {
        temp_store::write_text_content(&item.id, t).ok();
    }
    temp_store::write_item_meta(&item, "text").ok();
    let dto = ContentItemDto::from(&item);
    state
        .local_content
        .write()
        .await
        .insert(item.id.clone(), item);
    evict_fifo(&state).await;
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
    temp_store::write_item_meta(&item, "bytes").ok();
    let dto = ContentItemDto::from(&item);
    state
        .local_content
        .write()
        .await
        .insert(item.id.clone(), item);
    evict_fifo(&state).await;
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

    start_server_guard(&state).await;

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
        preview: preview_for_announcement(item),
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
    stop_active_shares(state.inner()).await;
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

/// Pull content from a peer via HTTP and store it in the local hub history.
#[tauri::command]
pub async fn pull_peer_content(
    content_id: String,
    state: State<'_, HubState>,
) -> Result<ContentItemDto, String> {
    let (announcement, peer_ip, pulled) = ensure_peer_cached(&content_id, &state).await?;
    let item = build_peer_item(&announcement, pulled)?;

    // Persist pulled item to disk history
    match &item.data {
        ContentData::Text(ref t) => {
            temp_store::write_text_content(&item.id, t).ok();
            temp_store::write_item_meta(&item, "text").ok();
        }
        _ => {
            temp_store::write_item_meta(&item, "bytes").ok();
        }
    }

    let dto = ContentItemDto::from(&item);
    state
        .local_content
        .write()
        .await
        .insert(item.id.clone(), item);
    evict_fifo(&state).await;

    tracing::info!(
        "Pulled {} from {} ({})",
        content_id,
        announcement.device_name,
        peer_ip
    );

    Ok(dto)
}

#[derive(Serialize)]
pub struct CopyPeerResult {
    /// Absolute path to the cached file, if the content is an image.
    /// The frontend uses this to show a thumbnail preview without re-downloading.
    pub cached_path: Option<String>,
}

#[tauri::command]
pub async fn copy_peer_content(
    content_id: String,
    state: State<'_, HubState>,
) -> Result<CopyPeerResult, String> {
    let (announcement, peer_ip, pulled) = ensure_peer_cached(&content_id, &state).await?;
    // Capture the cached path before consuming the announcement.
    let is_image = announcement.content_type == fenix_hub_core::content::ContentType::Image;
    let cached_path = if is_image {
        let p = peer_received_path(&content_id, &announcement);
        if p.exists() { p.to_str().map(ToOwned::to_owned) } else { None }
    } else {
        None
    };
    let item = build_peer_item(&announcement, pulled)?;
    // Image decode (JPEG → RGBA8 ~48 MB for a 3 MP photo) is CPU-bound and
    // must not block the async executor.  Move it to a blocking thread.
    tokio::task::spawn_blocking(move || write_item_to_clipboard(&item))
        .await
        .map_err(|e| e.to_string())?
        .map_err(|e| e.to_string())?;
    tracing::info!(
        "Copied {} from {} ({}) to clipboard",
        content_id,
        announcement.device_name,
        peer_ip
    );
    Ok(CopyPeerResult { cached_path })
}

#[derive(Serialize)]
pub struct SaveContentResult {
    pub saved: bool,
    pub path: Option<String>,
}

#[tauri::command]
pub async fn save_peer_content_as(
    content_id: String,
    state: State<'_, HubState>,
) -> Result<SaveContentResult, String> {
    // Read announcement metadata without downloading yet.
    let (announcement, peer_ip) = {
        let peers = state.peer_content.read().await;
        let (ann, ip) = peers.get(&content_id).ok_or("Peer content not found")?;
        (ann.clone(), *ip)
    };

    // Suggest a file name from announcement metadata (no download needed).
    let suggested_name = announcement
        .file_name
        .clone()
        .unwrap_or_else(|| {
            default_file_name(
                announcement.content_type.clone(),
                announcement.mime_type.as_deref(),
                &announcement.content_id,
            )
        });

    // Show save dialog first — if cancelled, skip the download entirely.
    let Some(target_path) = rfd::FileDialog::new()
        .set_file_name(&suggested_name)
        .save_file()
    else {
        return Ok(SaveContentResult {
            saved: false,
            path: None,
        });
    };

    // Serve from cache when available; otherwise pull directly to the target
    // without writing to the cache (avoid duplicating large files on disk).
    let temp_path = peer_received_path(&content_id, &announcement);
    if temp_path.exists() {
        std::fs::copy(&temp_path, &target_path).map_err(|e| e.to_string())?;
    } else {
        let identity = state
            .identity
            .read()
            .await
            .clone()
            .ok_or("Identity not configured")?;

        if announcement.content_type == fenix_hub_core::content::ContentType::Text {
            let pulled = fenix_hub_core::client::pull_content(
                peer_ip,
                announcement.port,
                &content_id,
                &identity,
            )
            .await
            .map_err(|e| e.to_string())?;
            std::fs::write(&target_path, &pulled.bytes).map_err(|e| e.to_string())?;
        } else {
            fenix_hub_core::client::pull_content_to_file(
                peer_ip,
                announcement.port,
                &content_id,
                &identity,
                &target_path,
            )
            .await
            .map_err(|e| e.to_string())?;
        }
    }

    tracing::info!(
        "Saved {} from {} ({}) to {}",
        content_id,
        announcement.device_name,
        peer_ip,
        target_path.display()
    );

    Ok(SaveContentResult {
        saved: true,
        path: Some(target_path.to_string_lossy().to_string()),
    })
}

#[tauri::command]
pub async fn save_local_content_as(
    id: String,
    state: State<'_, HubState>,
) -> Result<SaveContentResult, String> {
    let content = state.local_content.read().await;
    let item = content.get(&id).ok_or("Content not found")?;
    let suggested_name = item
        .file_name
        .clone()
        .unwrap_or_else(|| default_file_name(item.content_type.clone(), item.mime_type.as_deref(), &item.id));

    let Some(target_path) = rfd::FileDialog::new()
        .set_file_name(&suggested_name)
        .save_file()
    else {
        return Ok(SaveContentResult {
            saved: false,
            path: None,
        });
    };

    save_item_to_path(item, &target_path).map_err(|e| e.to_string())?;
    tracing::info!("Saved local item {} to {}", id, target_path.display());

    Ok(SaveContentResult {
        saved: true,
        path: Some(target_path.to_string_lossy().to_string()),
    })
}

// ── Helpers ───────────────────────────────────────────────────────────────────

/// Returns the temp path where a received peer file is cached.
/// `~/.cache/fenix-hub/received/<content_id>[.<ext>]`
fn peer_received_path(content_id: &str, announcement: &Announcement) -> std::path::PathBuf {
    let dir = dirs::cache_dir()
        .unwrap_or_else(|| std::path::PathBuf::from("/tmp"))
        .join("fenix-hub")
        .join("received");
    let _ = std::fs::create_dir_all(&dir);

    // Derive extension from the file name or MIME type.
    // Text content always gets .txt regardless of MIME to avoid mime_guess
    // returning unusual extensions like .asm for text/plain.
    let ext: Option<String> = if announcement.content_type == fenix_hub_core::content::ContentType::Text {
        Some("txt".to_string())
    } else {
        announcement
            .file_name
            .as_deref()
            .and_then(|n| std::path::Path::new(n).extension())
            .and_then(|e| e.to_str())
            .map(|s| s.to_string())
            .or_else(|| {
                announcement
                    .mime_type
                    .as_deref()
                    .and_then(|m| mime_guess::get_mime_extensions_str(m))
                    .and_then(|exts| exts.first())
                    .map(|s| s.to_string())
            })
    };

    match ext {
        Some(e) => dir.join(format!("{content_id}.{e}")),
        None => dir.join(content_id),
    }
}

/// Pull peer content, using the disk cache when available.
/// On cache miss the content is fetched, decrypted, and written to disk
/// before being returned — subsequent calls skip the network entirely.
async fn ensure_peer_cached(
    content_id: &str,
    state: &State<'_, HubState>,
) -> Result<(Announcement, std::net::IpAddr, fenix_hub_core::client::PulledContent), String> {
    let peers = state.peer_content.read().await;
    let (announcement, peer_ip) = peers.get(content_id).ok_or("Peer content not found")?;
    let announcement = announcement.clone();
    let peer_ip = *peer_ip;
    drop(peers);

    let temp_path = peer_received_path(content_id, &announcement);

    if temp_path.exists() {
        tracing::debug!("Cache hit for {content_id}: {:?}", temp_path);
        // For File type: avoid loading large files into RAM — point at the cached path.
        let pulled = if announcement.content_type == fenix_hub_core::content::ContentType::File {
            fenix_hub_core::client::PulledContent {
                bytes: vec![],
                file_path: Some(temp_path.clone()),
                file_name: announcement.file_name.clone(),
                mime_type: announcement.mime_type.clone(),
            }
        } else {
            let bytes = std::fs::read(&temp_path).map_err(|e| e.to_string())?;
            fenix_hub_core::client::PulledContent {
                bytes,
                file_path: None,
                file_name: announcement.file_name.clone(),
                mime_type: announcement.mime_type.clone(),
            }
        };
        return Ok((announcement, peer_ip, pulled));
    }

    tracing::debug!("Cache miss for {content_id}, pulling from {peer_ip}");
    let identity = state
        .identity
        .read()
        .await
        .clone()
        .ok_or("Identity not configured")?;

    // For File content type: stream directly to disk — don't buffer in RAM.
    let pulled = if announcement.content_type == fenix_hub_core::content::ContentType::File {
        fenix_hub_core::client::pull_content_to_file(
            peer_ip,
            announcement.port,
            content_id,
            &identity,
            &temp_path,
        )
        .await
        .map_err(|e| e.to_string())?;
        fenix_hub_core::client::PulledContent {
            bytes: vec![],
            file_path: Some(temp_path.clone()),
            file_name: announcement.file_name.clone(),
            mime_type: announcement.mime_type.clone(),
        }
    } else {
        // Text and Image: load in memory (needed for clipboard / preview generation).
        let p = fenix_hub_core::client::pull_content(peer_ip, announcement.port, content_id, &identity)
            .await
            .map_err(|e| e.to_string())?;
        if let Err(e) = std::fs::write(&temp_path, &p.bytes) {
            tracing::warn!("Failed to cache {content_id} to {:?}: {e}", temp_path);
        } else {
            tracing::debug!("Cached {content_id} → {:?}", temp_path);
        }
        p
    };

    Ok((announcement, peer_ip, pulled))
}

fn build_peer_item(
    announcement: &Announcement,
    pulled: fenix_hub_core::client::PulledContent,
) -> Result<ContentItem, String> {
    match announcement.content_type {
        fenix_hub_core::content::ContentType::Text => {
            let text = String::from_utf8(pulled.bytes).map_err(|e| e.to_string())?;
            Ok(ContentItem::from_text(text))
        }
        fenix_hub_core::content::ContentType::Image => {
            // Use the announcement preview (already a base64 thumbnail) to avoid
            // a full JPEG decode just to build the local preview.  Only fall back
            // to generating one from bytes if the announcement has no preview.
            let preview = announcement_preview_data_url(&announcement.preview)
                .or_else(|| image_preview_data_url(&pulled.bytes, 96, 72));
            create_temp_binary_item(
                pulled.bytes,
                fenix_hub_core::content::ContentType::Image,
                pulled.file_name.or_else(|| announcement.file_name.clone()),
                pulled.mime_type.or_else(|| announcement.mime_type.clone()),
                preview.or_else(|| Some(announcement.preview.clone())),
            )
            .map_err(|e| e.to_string())
        }
        fenix_hub_core::content::ContentType::File => {
            if let Some(path) = pulled.file_path {
                // Already on disk from streaming — point ContentItem at existing file.
                let final_name = pulled
                    .file_name
                    .or_else(|| announcement.file_name.clone())
                    .unwrap_or_else(|| "archivo".to_string());
                ContentItem::from_temp_file(
                    path,
                    fenix_hub_core::content::ContentType::File,
                    Some(final_name),
                    pulled.mime_type.or_else(|| announcement.mime_type.clone()),
                    Some(announcement.preview.clone()),
                )
                .map_err(|e| e.to_string())
            } else {
                create_temp_binary_item(
                    pulled.bytes,
                    fenix_hub_core::content::ContentType::File,
                    pulled.file_name.or_else(|| announcement.file_name.clone()),
                    pulled.mime_type.or_else(|| announcement.mime_type.clone()),
                    Some(announcement.preview.clone()),
                )
                .map_err(|e| e.to_string())
            }
        }
    }
}


fn preview_for_announcement(item: &ContentItem) -> String {
    if item.content_type != fenix_hub_core::content::ContentType::Image {
        return item.preview.clone();
    }

    image_bytes_from_item(item)
        .and_then(|bytes| image_preview_data_url(&bytes, 36, 28))
        .unwrap_or_else(|| item.preview.clone())
}

fn announcement_preview_data_url(preview: &str) -> Option<String> {
    preview
        .starts_with("data:image")
        .then(|| preview.to_string())
}

fn image_bytes_from_item(item: &ContentItem) -> Option<Vec<u8>> {
    match &item.data {
        ContentData::FilePath(path) => std::fs::read(path).ok(),
        ContentData::Bytes(bytes) => Some(bytes.clone()),
        _ => None,
    }
}

fn image_preview_data_url(bytes: &[u8], max_edge: u32, quality: u8) -> Option<String> {
    let image = image::load_from_memory(bytes).ok()?;
    let thumb = image.thumbnail(max_edge, max_edge).to_rgb8();
    let mut encoded = Vec::new();
    let mut encoder = image::codecs::jpeg::JpegEncoder::new_with_quality(&mut encoded, quality);
    encoder.encode_image(&thumb).ok()?;
    Some(format!(
        "data:image/jpeg;base64,{}",
        base64::engine::general_purpose::STANDARD.encode(encoded)
    ))
}

fn local_ipv4() -> Option<std::net::Ipv4Addr> {
    crate::network::local_ipv4()
}

fn save_item_to_path(item: &ContentItem, path: &std::path::Path) -> anyhow::Result<()> {
    match &item.data {
        ContentData::Text(text) => {
            std::fs::write(path, text.as_bytes())?;
        }
        ContentData::FilePath(source) => {
            std::fs::copy(source, path)?;
        }
        ContentData::Bytes(bytes) => {
            std::fs::write(path, bytes)?;
        }
        ContentData::Empty => {
            std::fs::write(path, [])?;
        }
    }
    Ok(())
}

fn write_item_to_clipboard(item: &ContentItem) -> anyhow::Result<()> {
    use fenix_hub_core::content::ContentType;
    let mut clipboard = arboard::Clipboard::new()?;
    match &item.data {
        ContentData::Text(text) => {
            clipboard.set_text(text)?;
        }
        ContentData::FilePath(path) => {
            if item.content_type == ContentType::Image {
                clipboard_set_image_file(&mut clipboard, path)?;
            } else {
                clipboard.set_text(path.to_string_lossy().to_string())?;
            }
        }
        ContentData::Bytes(bytes) => {
            if item.content_type == ContentType::Image {
                let path = temp_store::write_item_bytes(
                    &item.id,
                    item.file_name.as_deref().unwrap_or("fenixhub-item.png"),
                    bytes,
                )?;
                clipboard_set_image_file(&mut clipboard, &path)?;
            } else {
                let path = temp_store::write_item_bytes(
                    &item.id,
                    item.file_name.as_deref().unwrap_or("fenixhub-item.bin"),
                    bytes,
                )?;
                clipboard.set_text(path.to_string_lossy().to_string())?;
            }
        }
        ContentData::Empty => {}
    }
    Ok(())
}

fn clipboard_set_image_file(
    clipboard: &mut arboard::Clipboard,
    path: &std::path::Path,
) -> anyhow::Result<()> {
    // On Linux X11: pipe the raw PNG bytes to xclip — avoids 4-second RGBA decode.
    // xclip stays alive in the background serving clipboard requests until
    // another app claims ownership.
    #[cfg(target_os = "linux")]
    {
        if let Ok(file) = std::fs::File::open(path) {
            let result = std::process::Command::new("xclip")
                .args(["-selection", "clipboard", "-t", "image/png", "-i"])
                .stdin(file)
                .stdout(std::process::Stdio::null())
                .stderr(std::process::Stdio::null())
                .spawn();
            if result.is_ok() {
                // xclip forks internally — the spawned child exits quickly,
                // leaving a grandchild to serve clipboard requests.
                return Ok(());
            }
            tracing::warn!("xclip not found, falling back to arboard RGBA decode");
        }
    }

    let img = image::open(path)?.to_rgba8();
    let width = img.width() as usize;
    let height = img.height() as usize;
    clipboard.set_image(arboard::ImageData {
        width,
        height,
        bytes: img.into_raw().into(),
    })?;
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
    temp_store::remove_item(&item.id).ok();
}

async fn evict_fifo(state: &State<'_, HubState>) {
    match temp_store::enforce_fifo(temp_store::MAX_HISTORY_ITEMS) {
        Ok(evicted) if !evicted.is_empty() => {
            let mut content = state.local_content.write().await;
            for id in &evicted {
                content.remove(id);
            }
            tracing::debug!("FIFO evicted {} old item(s)", evicted.len());
        }
        Err(e) => tracing::warn!("FIFO enforcement failed: {}", e),
        _ => {}
    }
}

/// Add a file by filesystem path — avoids base64 round-trip for drag & drop from Explorer.
/// Rust reads the bytes directly; the WebView never sees the raw data.
#[tauri::command]
pub async fn add_file_by_path(
    path: String,
    state: State<'_, HubState>,
) -> Result<ContentItemDto, String> {
    let path = std::path::PathBuf::from(&path);
    // Deduplicate: same source path already in hub → return existing
    {
        let content = state.local_content.read().await;
        for existing in content.values() {
            if existing.file_name.as_deref() == path.file_name().and_then(|n| n.to_str()) {
                if let ContentData::FilePath(ref p) = existing.data {
                    if p == &path { return Ok(ContentItemDto::from(existing)); }
                }
            }
        }
    }
    let file_name = path
        .file_name()
        .and_then(|n| n.to_str())
        .unwrap_or("fenixhub-item")
        .to_string();
    let mime_type = mime_guess::from_path(&path)
        .first()
        .map(|m| m.to_string());

    let is_image = mime_type.as_deref().map_or(false, |m| m.starts_with("image/"));
    let is_text = is_text_mime(mime_type.as_deref(), &file_name);

    let content_type = if is_image {
        fenix_hub_core::content::ContentType::Image
    } else {
        fenix_hub_core::content::ContentType::File
    };

    // For text files: read as UTF-8 and use text content path (no temp binary copy needed)
    if is_text {
        let text = std::fs::read_to_string(&path).map_err(|e| e.to_string())?;
        let item = fenix_hub_core::content::ContentItem::from_text(text);
        // Override preview to show filename too: "script.py · def main()…"
        let mut item = item;
        item.file_name = Some(file_name.clone());
        // "script.py · first line of content…" — keep total under 80 chars
        let content_preview = item.preview.chars().take(60).collect::<String>();
        item.preview = format!("{} · {}", file_name, content_preview);
        temp_store::write_text_content(&item.id, match &item.data {
            ContentData::Text(t) => t,
            _ => "",
        }).ok();
        temp_store::write_item_meta(&item, "text").ok();
        let dto = ContentItemDto::from(&item);
        state.local_content.write().await.insert(item.id.clone(), item);
        evict_fifo(&state).await;
        return Ok(dto);
    }

    let bytes = std::fs::read(&path).map_err(|e| e.to_string())?;
    let item = create_temp_binary_item(bytes, content_type, Some(file_name), mime_type, None)
        .map_err(|e| e.to_string())?;
    temp_store::write_item_meta(&item, "bytes").ok();
    let dto = ContentItemDto::from(&item);
    state.local_content.write().await.insert(item.id.clone(), item);
    evict_fifo(&state).await;
    Ok(dto)
}

fn is_text_mime(mime: Option<&str>, file_name: &str) -> bool {
    if let Some(m) = mime {
        if m.starts_with("text/") {
            return true;
        }
        // application/json, application/xml, application/javascript, etc.
        if matches!(m, "application/json" | "application/xml" | "application/javascript"
            | "application/typescript" | "application/toml" | "application/yaml") {
            return true;
        }
    }
    // Fallback: check extension for common text formats mime_guess might miss
    let ext = std::path::Path::new(file_name)
        .extension()
        .and_then(|e| e.to_str())
        .unwrap_or("")
        .to_lowercase();
    matches!(ext.as_str(),
        "md" | "txt" | "rs" | "py" | "js" | "ts" | "jsx" | "tsx" | "json" | "toml"
        | "yaml" | "yml" | "xml" | "html" | "css" | "sh" | "bash" | "zsh" | "fish"
        | "go" | "java" | "c" | "h" | "cpp" | "hpp" | "cs" | "rb" | "php" | "swift"
        | "kt" | "kts" | "lua" | "vim" | "conf" | "ini" | "env" | "gitignore" | "log"
        | "csv" | "sql"
    )
}

/// Resize the hub window from Rust (bypasses `resizable: false` JS restriction).
#[tauri::command]
pub fn resize_hub(app: AppHandle, width: f64, height: f64) -> Result<(), String> {
    if let Some(win) = app.get_webview_window("hub") {
        win.set_size(tauri::LogicalSize::new(width, height))
            .map_err(|e| e.to_string())?;
        // Recenter after every resize: collapsing changes width 820→280 and expanding
        // 280→820, so the x position must be recalculated to stay top-center.
        windowing::position_hub_window_top(&win, width);
    }
    Ok(())
}

/// Close the hub window cleanly from JS: unannounce, stop server, destroy window.
/// History is intentionally kept (FIFO persists across sessions).
#[tauri::command]
pub fn open_settings(app: AppHandle) -> Result<(), String> {
    if let Some(win) = app.get_webview_window("settings") {
        // Restore from minimized state before showing
        win.unminimize().ok();
        win.set_resizable(false).ok();
        win.set_maximizable(false).ok();
        win.set_minimizable(false).ok();
        win.set_size(tauri::LogicalSize::new(560.0, 560.0)).ok();
        win.show().ok();
        win.set_focus().ok();
        return Ok(());
    }
    // Recreate if the window was previously hidden/destroyed.
    let config = app
        .config()
        .app
        .windows
        .iter()
        .find(|w| w.label == "settings")
        .cloned()
        .ok_or("Settings window config not found")?;
    let win = tauri::WebviewWindowBuilder::from_config(&app, &config)
        .map_err(|e| e.to_string())?
        .build()
        .map_err(|e| e.to_string())?;
    // Hide instead of destroy on close so re-open is instant and doesn't recreate.
    let win_ref = win.clone();
    win.on_window_event(move |event| {
        if let tauri::WindowEvent::CloseRequested { api, .. } = event {
            api.prevent_close();
            win_ref.hide().ok();
        }
    });
    win.set_resizable(false).ok();
    win.set_maximizable(false).ok();
    win.set_minimizable(false).ok();
    win.set_size(tauri::LogicalSize::new(560.0, 560.0)).ok();
    win.show().ok();
    win.set_focus().ok();
    Ok(())
}

/// Hides the settings window (user clicked the custom close button).
#[tauri::command]
pub fn close_settings(app: AppHandle) -> Result<(), String> {
    if let Some(win) = app.get_webview_window("settings") {
        win.hide().map_err(|e| e.to_string())?;
    }
    Ok(())
}

/// Shows a native confirmation dialog before a destructive reset.
/// Returns true if the user confirmed.
#[tauri::command]
pub async fn confirm_reset() -> Result<bool, String> {
    let result = tokio::task::spawn_blocking(|| {
        rfd::MessageDialog::new()
            .set_title("¿Eliminar todos los datos?")
            .set_description("Se eliminarán la identidad, el historial y la caché de este dispositivo. Esta acción no se puede deshacer.")
            .set_level(rfd::MessageLevel::Warning)
            .set_buttons(rfd::MessageButtons::OkCancel)
            .show()
    })
    .await
    .map_err(|e| e.to_string())?;
    Ok(matches!(result, rfd::MessageDialogResult::Ok))
}

#[tauri::command]
pub async fn close_hub_window(app: AppHandle, state: State<'_, HubState>) -> Result<(), String> {
    stop_active_shares(state.inner()).await;
    if let Some(win) = app.get_webview_window("hub") {
        win.destroy().map_err(|e| e.to_string())?;
    }
    tracing::info!("Hub closed via command");
    Ok(())
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
        announcement.file_name = Some(truncate_utf8_bytes(&file_name, MAX_ANNOUNCEMENT_FILE_NAME_CHARS));
    }
    if announcement_size(&announcement) <= MAX_ANNOUNCEMENT_BYTES {
        return announcement;
    }

    announcement.mime_type = None;
    if announcement_size(&announcement) <= MAX_ANNOUNCEMENT_BYTES {
        return announcement;
    }

    // Calculate the byte budget for the preview in one pass.
    let current_size = announcement_size(&announcement);
    if current_size > MAX_ANNOUNCEMENT_BYTES {
        let over_by = current_size - MAX_ANNOUNCEMENT_BYTES;
        let preview_bytes = announcement.preview.len();
        let target_bytes = preview_bytes
            .saturating_sub(over_by + 16) // 16 bytes safety margin
            .max(MIN_ANNOUNCEMENT_PREVIEW_CHARS * 2);
        announcement.preview = truncate_utf8_bytes(&announcement.preview, target_bytes);
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
            truncate_utf8_bytes(&announcement.preview, MAX_ANNOUNCEMENT_FILE_NAME_CHARS)
        }
        fenix_hub_core::content::ContentType::Image => announcement
            .file_name
            .as_deref()
            .map(|name| format!("Imagen: {}", truncate_utf8_bytes(name, 48)))
            .unwrap_or_else(|| "Imagen lista para descargar".to_string()),
        fenix_hub_core::content::ContentType::File => announcement
            .file_name
            .as_deref()
            .map(|name| format!("Archivo: {}", truncate_utf8_bytes(name, 48)))
            .unwrap_or_else(|| "Archivo listo para descargar".to_string()),
    }
}

/// Delete all files in the peer received cache (~/.cache/fenix-hub/received/).
#[tauri::command]
pub fn clear_received_cache() -> Result<(), String> {
    let dir = dirs::cache_dir()
        .unwrap_or_else(|| std::path::PathBuf::from("/tmp"))
        .join("fenix-hub")
        .join("received");
    if dir.exists() {
        std::fs::remove_dir_all(&dir).map_err(|e| e.to_string())?;
    }
    Ok(())
}

/// Delete all FenixHub data: clipboard history, identity, and received cache.
/// The app should be restarted or the window closed after this.
#[tauri::command]
pub async fn reset_all_data(state: State<'_, HubState>) -> Result<(), String> {
    // Clear in-memory clipboard
    {
        let mut store = state.local_content.write().await;
        store.clear();
    }
    // Remove clipboard dir on disk
    if let Ok(clipboard_dir) = dirs::cache_dir()
        .ok_or("no cache dir")
        .map(|d| d.join("fenix-hub").join("clipboard"))
    {
        if clipboard_dir.exists() {
            std::fs::remove_dir_all(&clipboard_dir).ok();
        }
    }
    // Remove received cache
    if let Ok(received_dir) = dirs::cache_dir()
        .ok_or("no cache dir")
        .map(|d| d.join("fenix-hub").join("received"))
    {
        if received_dir.exists() {
            std::fs::remove_dir_all(&received_dir).ok();
        }
    }
    // Remove identity
    if let Ok(config_dir) = dirs::config_dir()
        .ok_or("no config dir")
        .map(|d| d.join("fenix-hub"))
    {
        if config_dir.exists() {
            std::fs::remove_dir_all(&config_dir).ok();
        }
    }
    Ok(())
}

fn announcement_kind_label(content_type: &fenix_hub_core::content::ContentType) -> &'static str {
    match content_type {
        fenix_hub_core::content::ContentType::Text => "Texto",
        fenix_hub_core::content::ContentType::Image => "Imagen",
        fenix_hub_core::content::ContentType::File => "Archivo",
    }
}

fn truncate_utf8_bytes(value: &str, max_bytes: usize) -> String {
    if value.len() <= max_bytes {
        return value.to_string();
    }
    let mut end = max_bytes;
    while !value.is_char_boundary(end) {
        end -= 1;
    }
    value[..end].to_string()
}

#[cfg(test)]
mod tests {
    use super::*;
    use fenix_hub_core::content::ContentType;
    use fenix_hub_core::protocol::SendMode;

    #[test]
    fn compacts_large_image_announcement_for_mdns() {
        let announcement = Announcement {
            protocol_version: 1,
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
