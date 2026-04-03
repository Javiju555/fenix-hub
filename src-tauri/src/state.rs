use crate::persist::DeviceType;
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
    pub ui_closing: Arc<AtomicBool>,
}

impl HubState {
    pub fn new(mdns: ServiceDaemon) -> Self {
        Self {
            identity: Arc::new(RwLock::new(None)),
            device_type: Arc::new(RwLock::new(DeviceType::default())),
            local_content: Arc::new(RwLock::new(HashMap::new())),
            peer_content: Arc::new(RwLock::new(HashMap::new())),
            active_announcements: Arc::new(RwLock::new(HashMap::new())),
            mdns,
            server_shutdown: Arc::new(RwLock::new(None)),
            server_port: Arc::new(RwLock::new(None)),
            ui_closing: Arc::new(AtomicBool::new(false)),
        }
    }
}
