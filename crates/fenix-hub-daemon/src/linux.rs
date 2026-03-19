/// Linux daemon implementation.
///
/// Integration points:
/// - Notifications: org.freedesktop.Notifications D-Bus interface (libnotify-compatible)
///   Implemented via `notify-rust` crate or direct zbus call.
/// - Hub window: Tauri window management (show/focus the hub panel window)
/// - Lifecycle: can run embedded in fenix-shell process or as standalone fenixhubd daemon
/// - Drag-to-hub: implemented in the Tauri frontend via Wayland DnD (wl-drag-and-drop),
///   drops on the bar button trigger a Tauri command that calls daemon.add_content()

use std::sync::Arc;
use anyhow::Result;
use fenix_hub_core::identity::GroupIdentity;
use crate::daemon::HubDaemon;

pub struct LinuxDaemon {
    // TODO: hold Tauri AppHandle for window management
    // TODO: hold D-Bus connection for notifications
}

impl LinuxDaemon {
    pub fn new() -> Self {
        Self {}
    }
}

#[async_trait::async_trait]
impl HubDaemon for LinuxDaemon {
    async fn start(&self, identity: Arc<GroupIdentity>) -> Result<()> {
        tracing::info!(
            "FenixHub Linux daemon starting. Device: {}. Group: {}",
            identity.device_name,
            identity.group_id()
        );
        // TODO: start mDNS discovery loop
        // TODO: start HTTP notify endpoint (axum route for POST /notify)
        // TODO: register with fenix-shell via D-Bus or IPC
        Ok(())
    }

    async fn show_notification(&self, title: &str, body: &str) -> Result<()> {
        // Production: use org.freedesktop.Notifications D-Bus interface
        // notify-send compatible, works with dunst, mako, sway-notification-center
        //
        // Example with zbus (uncomment when zbus dep is added):
        // let conn = zbus::Connection::session().await?;
        // let proxy = NotificationsProxy::new(&conn).await?;
        // proxy.notify("FenixHub", 0, "network-workgroup", title, body, &[], HashMap::new(), 5000).await?;
        tracing::info!("Notification: {} — {}", title, body);
        Ok(())
    }

    async fn open_hub_window(&self) -> Result<()> {
        // Production: call Tauri AppHandle to show/focus the hub panel window
        // app_handle.get_webview_window("hub").unwrap().show().unwrap();
        tracing::info!("Opening hub window");
        Ok(())
    }

    fn platform_name(&self) -> &'static str {
        "linux"
    }
}
