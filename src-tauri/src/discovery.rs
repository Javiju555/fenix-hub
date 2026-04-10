use serde::Serialize;
/// mDNS discovery wiring for Tauri.
///
/// Starts the discovery loop and forwards DaemonEvents as Tauri events
/// so the frontend can react in real time.
use std::net::IpAddr;
use std::sync::Arc;
use tauri::{AppHandle, Emitter, Manager};
use tokio::sync::{mpsc, RwLock};

use crate::windowing;
use fenix_hub_core::identity::GroupIdentity;
use fenix_hub_core::protocol::Announcement;
use fenix_hub_daemon::daemon::DaemonEvent;
use fenix_hub_daemon::mdns::{start_discovery, start_presence_discovery};

/// Tauri event payloads (must be Serialize for emit)

#[derive(Clone, Serialize)]
pub struct PeerContentPayload {
    pub announcement: Announcement,
    pub peer_ip: String, // IpAddr as string for JSON
}

#[derive(Clone, Serialize)]
pub struct PeerGonePayload {
    pub content_id: String,
    pub device_name: String,
}

/// Start the discovery loop and bridge events to the Tauri frontend.
/// Call this once on app setup after identity is loaded.
pub fn start(
    app: AppHandle,
    mdns: mdns_sd::ServiceDaemon,
    identity: Arc<GroupIdentity>,
    peer_store: Arc<RwLock<std::collections::HashMap<String, (Announcement, IpAddr)>>>,
) {
    let (tx, mut rx) = mpsc::channel::<DaemonEvent>(64);

    if let Err(e) = start_discovery(mdns.clone(), identity.group_id(), tx.clone()) {
        tracing::error!("Failed to start mDNS discovery: {}", e);
        return;
    }

    if let Err(e) = start_presence_discovery(mdns, identity.group_id(), tx) {
        tracing::warn!("Failed to start presence discovery: {}", e);
        // non-fatal — content discovery still works
    }

    // Collect local IPs once to filter self-announcements.
    let local_ips: std::collections::HashSet<IpAddr> = {
        let mut set = std::collections::HashSet::new();
        if let Ok(ifaces) = local_ip_address::list_afinet_netifas() {
            for (_, ip) in ifaces {
                set.insert(ip);
            }
        }
        // Always include the LAN IP from network helper as extra safety net
        if let Some(lan) = crate::network::local_ipv4() {
            set.insert(IpAddr::V4(lan));
        }
        tracing::debug!("Self-filter IPs: {:?}", set);
        set
    };
    tauri::async_runtime::spawn(async move {
        while let Some(event) = rx.recv().await {
            match event {
                DaemonEvent::PeerContentAvailable {
                    announcement,
                    peer_ip,
                } => {
                    // Skip self — filter by IP only
                    if local_ips.contains(&peer_ip) {
                        tracing::debug!("Skipping own announcement for {}", announcement.content_id);
                        continue;
                    }
                    let id = announcement.content_id.clone();
                    peer_store
                        .write()
                        .await
                        .insert(id.clone(), (announcement.clone(), peer_ip));
                    let payload = PeerContentPayload {
                        announcement,
                        peer_ip: peer_ip.to_string(),
                    };
                    if let Some(win) = app.get_webview_window("hub") {
                        if let Err(e) = win.emit("peer-content-available", &payload) {
                            tracing::error!("failed to emit peer-content-available: {}", e);
                        } else {
                            tracing::info!("emitted peer-content-available for {}", id);
                        }
                    } else {
                        tracing::warn!(
                            "hub window not found, dropping peer-content-available for {}",
                            id
                        );
                    }
                }
                DaemonEvent::PeerContentGone {
                    content_id,
                    device_name,
                } => {
                    peer_store.write().await.remove(&content_id);
                    if let Some(win) = app.get_webview_window("hub") {
                        let _ = win.emit(
                            "peer-content-gone",
                            PeerGonePayload {
                                content_id,
                                device_name,
                            },
                        );
                    }
                }
                DaemonEvent::DirectNotifyReceived {
                    announcement,
                    peer_ip,
                } => {
                    if local_ips.contains(&peer_ip) {
                        continue;
                    }
                    let id = announcement.content_id.clone();
                    peer_store
                        .write()
                        .await
                        .insert(id, (announcement.clone(), peer_ip));
                    let _ = windowing::show_or_create_hub_window(&app);
                    let _ = app.emit(
                        "direct-notify-received",
                        PeerContentPayload {
                            announcement,
                            peer_ip: peer_ip.to_string(),
                        },
                    );
                }
                DaemonEvent::PeerOnline { device_name } => {
                    let _ = app.emit("peer-online", &device_name);
                }
                DaemonEvent::PeerOffline { device_name } => {
                    let _ = app.emit("peer-offline", &device_name);
                }
            }
        }
    });
}
