/// Tauri managed state — shared between all command handlers.

use std::collections::HashMap;
use std::sync::Arc;
use tokio::sync::RwLock;
use fenix_hub_core::content::ContentItem;
use fenix_hub_core::identity::GroupIdentity;
use fenix_hub_core::protocol::Announcement;

pub struct HubState {
    /// The local device's group identity (set after first-run setup)
    pub identity: Arc<RwLock<Option<Arc<GroupIdentity>>>>,
    /// Content items in the local hub (added locally, not yet published)
    pub local_content: Arc<RwLock<HashMap<String, ContentItem>>>,
    /// Peer announcements discovered via mDNS (keyed by content_id)
    pub peer_content: Arc<RwLock<HashMap<String, Announcement>>>,
    /// Shutdown sender for the active content server (if any)
    pub server_shutdown: Arc<RwLock<Option<tokio::sync::oneshot::Sender<()>>>>,
    /// Active content server port (if any)
    pub server_port: Arc<RwLock<Option<u16>>>,
}

impl HubState {
    pub fn new() -> Self {
        Self {
            identity: Arc::new(RwLock::new(None)),
            local_content: Arc::new(RwLock::new(HashMap::new())),
            peer_content: Arc::new(RwLock::new(HashMap::new())),
            server_shutdown: Arc::new(RwLock::new(None)),
            server_port: Arc::new(RwLock::new(None)),
        }
    }
}
