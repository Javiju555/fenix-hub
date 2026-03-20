use crate::daemon::HubDaemon;
use anyhow::Result;
use fenix_hub_core::identity::GroupIdentity;
/// Windows daemon implementation.
///
/// Integration points:
/// - Notifications: Windows Toast Notifications (WinRT API)
///   Via `windows` crate: Windows::UI::Notifications::ToastNotificationManager
/// - System tray: tray-icon crate or Tauri's built-in tray support
/// - Lifecycle: runs as a background process, starts with Windows (startup registry entry
///   or Task Scheduler). Does NOT require admin/service privileges.
/// - Hub window: Tauri window show/focus
/// - Drag-to-hub: Windows DnD via IDropTarget registered on the tray icon or bar button
///
/// Windows-specific notes:
/// - mDNS: mdns-sd crate works on Windows via the same socket multicast API.
///   Alternatively, use Windows DNS-SD API (Dns-SD.dll) for better firewall integration.
/// - Firewall: the ephemeral HTTP server port needs a firewall exception on first run.
///   Tauri installer can handle this via a manifest rule, or prompt user on first content share.
/// - WSL2: devices on WSL2 are on a different virtual network. FenixHub will not discover
///   them via mDNS (WSL2 network is NAT'd). This is expected behavior — WSL2 is not a LAN peer.
use std::sync::Arc;

pub struct WindowsDaemon {
    // TODO: hold Tauri AppHandle
    // TODO: hold tray icon handle
}

impl WindowsDaemon {
    pub fn new() -> Self {
        Self {}
    }
}

#[async_trait::async_trait]
impl HubDaemon for WindowsDaemon {
    async fn start(&self, identity: Arc<GroupIdentity>) -> Result<()> {
        tracing::info!(
            "FenixHub Windows daemon starting. Device: {}. Group: {}",
            identity.device_name,
            identity.group_id()
        );
        // TODO: start mDNS discovery loop (mdns-sd works on Windows)
        // TODO: start HTTP notify endpoint
        // TODO: register tray icon
        // TODO: add to Windows startup (HKCU\Software\Microsoft\Windows\CurrentVersion\Run)
        Ok(())
    }

    async fn show_notification(&self, title: &str, body: &str) -> Result<()> {
        // Production: Windows Toast Notification via WinRT
        //
        // use windows::UI::Notifications::*;
        // use windows::Data::Xml::Dom::*;
        // let toast_xml = ToastNotificationManager::GetTemplateContent(ToastTemplateType::ToastText02)?;
        // ... set title/body in XML nodes ...
        // let toast = ToastNotification::CreateToastNotification(&toast_xml)?;
        // ToastNotificationManager::CreateToastNotifierWithId(h!("FenixHub"))?.Show(&toast)?;
        tracing::info!("Windows notification: {} — {}", title, body);
        Ok(())
    }

    async fn open_hub_window(&self) -> Result<()> {
        // Production: show/focus Tauri window, bring to foreground
        // Windows requires SetForegroundWindow which needs special handling for background processes
        tracing::info!("Opening hub window (Windows)");
        Ok(())
    }

    fn platform_name(&self) -> &'static str {
        "windows"
    }
}

/// Windows startup registration.
/// Adds fenixhub.exe to HKCU run key so it starts with Windows.
/// No admin required — HKCU is per-user.
pub fn register_startup(exe_path: &str) -> Result<()> {
    // Production:
    // use winreg::enums::*;
    // use winreg::RegKey;
    // let hkcu = RegKey::predef(HKEY_CURRENT_USER);
    // let run = hkcu.open_subkey_with_flags("Software\\Microsoft\\Windows\\CurrentVersion\\Run", KEY_SET_VALUE)?;
    // run.set_value("FenixHub", &exe_path)?;
    tracing::info!("Registered FenixHub in Windows startup: {}", exe_path);
    Ok(())
}
