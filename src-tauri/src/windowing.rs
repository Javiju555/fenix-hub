use std::sync::atomic::Ordering;

use anyhow::{anyhow, Result};
use tauri::{
    AppHandle, Emitter, Manager, WebviewUrl, WebviewWindow, WebviewWindowBuilder, WindowEvent,
};

use fenix_hub_daemon::mdns::unannounce_content;

use crate::state::HubState;
use crate::temp_store;

const HUB_WINDOW_LABEL: &str = "hub";
const KEEPALIVE_WINDOW_LABEL: &str = "daemon-keepalive";

pub fn ensure_keepalive_window(app: &AppHandle) -> Result<()> {
    if app.get_webview_window(KEEPALIVE_WINDOW_LABEL).is_some() {
        return Ok(());
    }

    WebviewWindowBuilder::new(
        app,
        KEEPALIVE_WINDOW_LABEL,
        WebviewUrl::External("about:blank".parse()?),
    )
        .title("")
        .inner_size(1.0, 1.0)
        .min_inner_size(1.0, 1.0)
        .resizable(false)
        .decorations(false)
        .transparent(true)
        .visible(false)
        .skip_taskbar(true)
        .focusable(false)
        .build()?;

    Ok(())
}

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
        window.show()?;
        window.unminimize()?;
        window.set_focus()?;
        let _ = window.emit("hub-activate", ());
        return Ok(());
    }

    let window = create_hub_window(app)?;
    window.show()?;
    window.unminimize()?;
    window.set_focus()?;
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
    attach_hub_window_handlers(&window, app);
    Ok(window)
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

    state.local_content.write().await.clear();
    temp_store::clear_all()?;

    tracing::info!("Hub UI closed: local clipboard cleared, server stopped");
    Ok(())
}
