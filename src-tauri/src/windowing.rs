use std::sync::atomic::Ordering;

use anyhow::{anyhow, Result};
use tauri::{
    AppHandle, Emitter, Manager, WebviewWindow, WebviewWindowBuilder, WindowEvent,
};

use fenix_hub_daemon::mdns::unannounce_content;

use crate::state::HubState;

const HUB_WINDOW_LABEL: &str = "hub";

pub fn attach_hub_window_handlers(window: &WebviewWindow, app: &AppHandle) {
    app.state::<HubState>()
        .ui_closing
        .store(false, Ordering::Release);

    let window_ref = window.clone();
    let app_handle = app.clone();

    window.on_window_event(move |event| {
        if let WindowEvent::CloseRequested { api, .. } = event {
            api.prevent_close();

            let state = app_handle.state::<HubState>();
            if state.ui_closing.swap(true, Ordering::AcqRel) {
                return;
            }

            let window = window_ref.clone();
            let app = app_handle.clone();
            tauri::async_runtime::spawn(async move {
                let state = app.state::<HubState>();
                if let Err(error) = close_hub_ui_state(&state).await {
                    tracing::warn!("Failed to close hub UI cleanly: {}", error);
                }
                if let Err(error) = window.destroy() {
                    tracing::warn!("Failed to destroy hub window: {}", error);
                }
            });
        }
    });
}

pub fn show_or_create_hub_window(app: &AppHandle) -> Result<()> {
    if let Some(window) = app.get_webview_window(HUB_WINDOW_LABEL) {
        reveal_hub_window(&window)?;
        let _ = window.emit("hub-activate", ());
        return Ok(());
    }

    let window = create_hub_window(app)?;
    reveal_hub_window(&window)?;
    Ok(())
}

fn create_hub_window(app: &AppHandle) -> Result<WebviewWindow> {
    let window_config = app
        .config()
        .app
        .windows
        .iter()
        .find(|item| item.label == HUB_WINDOW_LABEL)
        .cloned()
        .ok_or_else(|| anyhow!("Hub window configuration not found"))?;

    let window = tauri::WebviewWindowBuilder::from_config(app, &window_config)?.build()?;

    // Mark as a utility window so compositors (GNOME/Mutter, KWin, Hyprland)
    // float it automatically, respect set_position, and hide it from the taskbar.
    #[cfg(target_os = "linux")]
    set_utility_type_hint(&window);

    attach_hub_window_handlers(&window, app);
    Ok(window)
}

#[cfg(target_os = "linux")]
fn set_utility_type_hint(window: &WebviewWindow) {
    use gtk::prelude::GtkWindowExt;
    if let Ok(gtk_win) = window.gtk_window() {
        gtk_win.set_type_hint(gtk::gdk::WindowTypeHint::Utility);
    }
}

fn reveal_hub_window(window: &WebviewWindow) -> Result<()> {
    let _ = window.unminimize();
    // Set AOT + position BEFORE show() so the WM receives the position hint
    // before it runs its placement algorithm.  On GNOME/Mutter this is the only
    // reliable way — calling set_position after show() races with smart-placement.
    window.set_always_on_top(true)?;
    position_hub_window_top(window, 820.0);
    window.show()?;
    window.set_focus()?;

    // On Linux, re-apply after the WM finishes its async placement pass.
    #[cfg(target_os = "linux")]
    {
        let w = window.clone();
        tauri::async_runtime::spawn(async move {
            tokio::time::sleep(std::time::Duration::from_millis(200)).await;
            let size = w.outer_size().unwrap_or_default();
            if size.width > 0 && size.width < 800 {
                return;
            }
            let _ = w.set_always_on_top(true);
            position_hub_window_top(&w, 820.0);
        });
    }

    Ok(())
}

/// Position the hub window at the top-center of the primary monitor.
/// `logical_width` is the new window width in logical pixels (e.g. 820 expanded, 280 pill).
pub(crate) fn position_hub_window_top(window: &WebviewWindow, logical_width: f64) {
    let monitor = match window.primary_monitor() {
        Ok(Some(m)) => m,
        Ok(None) => match window.available_monitors() {
            Ok(monitors) if !monitors.is_empty() => monitors.into_iter().next().unwrap(),
            _ => return,
        },
        Err(_) => return,
    };

    let screen_w = monitor.size().width as i32;
    let scale = monitor.scale_factor();
    let win_w = (logical_width * scale) as i32;
    let x = (screen_w - win_w) / 2;
    let y = (8.0 * scale) as i32;
    tracing::debug!(
        "position_hub_window_top: screen_w={} win_w={} scale={} → x={} y={}",
        screen_w, win_w, scale, x, y
    );
    let _ = window.set_position(tauri::PhysicalPosition::new(x, y));
}

async fn close_hub_ui_state(state: &HubState) -> Result<()> {
    let announcements: Vec<(String, String)> =
        state.active_announcements.write().await.drain().collect();
    for (_, instance_name) in announcements {
        unannounce_content(&state.mdns, &instance_name).ok();
    }

    if let Some(tx) = state.server_shutdown.write().await.take() {
        let _ = tx.send(());
    }
    *state.server_port.write().await = None;

    tracing::info!("Hub UI closed: server stopped, announcements removed");
    Ok(())
}
