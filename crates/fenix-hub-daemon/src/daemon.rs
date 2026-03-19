/// HubDaemon trait — platform-agnostic interface for the background daemon.
///
/// Each platform implements this trait:
/// - Linux:   LinuxDaemon   — integrates with fenix-shell, D-Bus notifications
/// - Windows: WindowsDaemon — system tray process, Windows toast notifications
/// - Android: implemented in Kotlin (separate repo), communicates via same HTTP protocol

use anyhow::Result;
use std::sync::Arc;
use fenix_hub_core::identity::GroupIdentity;
use fenix_hub_core::protocol::Announcement;

/// Events emitted by the daemon to the Tauri frontend (via Tauri event system).
#[derive(Debug, Clone)]
pub enum DaemonEvent {
    /// A peer announced new content available for broadcast pull
    PeerContentAvailable(Announcement),
    /// A peer sent a direct notification targeting this device
    DirectNotifyReceived(Announcement),
    /// A peer's content disappeared (they closed their hub)
    PeerContentGone { content_id: String, device_name: String },
    /// A new device in our group came online
    PeerOnline { device_name: String },
    /// A device in our group went offline
    PeerOffline { device_name: String },
}

/// Platform-agnostic daemon interface.
#[async_trait::async_trait]
pub trait HubDaemon: Send + Sync {
    /// Start the daemon background tasks (mDNS listener, HTTP notify endpoint, etc.)
    async fn start(&self, identity: Arc<GroupIdentity>) -> Result<()>;

    /// Show a system notification to the user (platform-specific implementation).
    /// Used when a direct-send arrives while the hub UI is not open.
    async fn show_notification(&self, title: &str, body: &str) -> Result<()>;

    /// Open/focus the hub UI window (platform-specific).
    /// Called automatically on direct-send receive.
    async fn open_hub_window(&self) -> Result<()>;

    /// Platform name for display/diagnostics
    fn platform_name(&self) -> &'static str;
}
