use crate::persist::{self, DeviceType};
use crate::temp_store;
use fenix_hub_core::content::ContentItem;
use fenix_hub_core::identity::GroupIdentity;
use fenix_hub_core::protocol::Announcement;
use mdns_sd::ServiceDaemon;
/// Tauri managed state — shared between all command handlers.
use std::collections::HashMap;
use std::net::IpAddr;
use std::sync::atomic::AtomicBool;
use std::sync::Arc;
use tokio::sync::RwLock;

pub struct HubState {
    pub identity: Arc<RwLock<Option<Arc<GroupIdentity>>>>,
    /// Device type (Desktop / Laptop / Phone / Tablet / Server) — cosmetic only.
    pub device_type: Arc<RwLock<DeviceType>>,
    pub local_content: Arc<RwLock<HashMap<String, ContentItem>>>,
    /// Peer content: content_id → (Announcement, peer_ip)
    pub peer_content: Arc<RwLock<HashMap<String, (Announcement, IpAddr)>>>,
    /// Active mDNS announcements: content_id → instance_name
    pub active_announcements: Arc<RwLock<HashMap<String, String>>>,
    pub mdns: ServiceDaemon,
    pub server_shutdown: Arc<RwLock<Option<tokio::sync::oneshot::Sender<()>>>>,
    pub server_port: Arc<RwLock<Option<u16>>>,
    pub server_guard_shutdown: Arc<RwLock<Option<tokio::sync::oneshot::Sender<()>>>>,
    pub server_guard_task: Arc<RwLock<Option<tokio::task::JoinHandle<()>>>>,
    pub ui_closing: Arc<AtomicBool>,
}

impl HubState {
    pub fn new(mdns: ServiceDaemon) -> Self {
        // Load identity from disk synchronously during construction so it is
        // available before any window (and its WebView) is created. This avoids
        // a race where get_identity() is called before setup() finishes.
        let (identity, device_type) = persist::load()
            .ok()
            .flatten()
            .map(|(id, dt)| (Some(Arc::new(id)), dt))
            .unwrap_or((None, DeviceType::default()));

        // Restore clipboard history from disk (FIFO, up to MAX_HISTORY_ITEMS)
        let history: HashMap<String, ContentItem> = temp_store::load_all_items()
            .into_iter()
            .map(|item| (item.id.clone(), item))
            .collect();
        tracing::info!("Loaded {} item(s) from clipboard history", history.len());

        Self {
            identity: Arc::new(RwLock::new(identity)),
            device_type: Arc::new(RwLock::new(device_type)),
            local_content: Arc::new(RwLock::new(history)),
            peer_content: Arc::new(RwLock::new(HashMap::new())),
            active_announcements: Arc::new(RwLock::new(HashMap::new())),
            mdns,
            server_shutdown: Arc::new(RwLock::new(None)),
            server_port: Arc::new(RwLock::new(None)),
            server_guard_shutdown: Arc::new(RwLock::new(None)),
            server_guard_task: Arc::new(RwLock::new(None)),
            ui_closing: Arc::new(AtomicBool::new(false)),
        }
    }
}
