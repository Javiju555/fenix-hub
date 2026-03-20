mod commands;
mod discovery;
mod persist;
mod state;
mod temp_store;
mod windowing;

use std::sync::Arc;
use tauri::Manager;
use tracing_subscriber::EnvFilter;

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tracing_subscriber::fmt()
        .with_env_filter(
            EnvFilter::from_default_env().add_directive("fenix_hub=debug".parse().unwrap()),
        )
        .init();

    let mdns = mdns_sd::ServiceDaemon::new().expect("Failed to start mDNS daemon");
    let hub_state = state::HubState::new(mdns.clone());

    tauri::Builder::default()
        .plugin(tauri_plugin_shell::init())
        .plugin(tauri_plugin_single_instance::init(|app, _argv, _cwd| {
            let _ = windowing::show_or_create_hub_window(app);
        }))
        .manage(hub_state)
        .setup(move |app| {
            let app_handle = app.handle().clone();
            let state = app.state::<state::HubState>();
            temp_store::prepare().ok();
            windowing::ensure_keepalive_window(&app_handle).ok();

            if let Some(window) = app_handle.get_webview_window("hub") {
                windowing::attach_hub_window_handlers(&window, &app_handle);
            }

            // Load persisted identity from disk (if exists)
            if let Ok(Some(identity)) = persist::load() {
                let identity = Arc::new(identity);

                // Start discovery immediately with loaded identity
                discovery::start(
                    app_handle.clone(),
                    mdns.clone(),
                    identity.clone(),
                    state.peer_content.clone(),
                );

                *tauri::async_runtime::block_on(state.identity.write()) = Some(identity);
                tracing::info!("Identity loaded from disk, discovery started");
            } else {
                tracing::info!("No saved identity — waiting for first-run setup");
            }

            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            commands::get_identity,
            commands::setup_identity,
            commands::get_local_content,
            commands::add_text_content,
            commands::add_binary_content,
            commands::remove_content,
            commands::publish_content,
            commands::stop_server,
            commands::pull_peer_content,
            commands::get_peers,
            commands::write_local_to_clipboard,
            commands::prepare_local_drag,
        ])
        .build(tauri::generate_context!())
        .expect("error while building FenixHub")
        .run(|_app, event| {
            if let tauri::RunEvent::ExitRequested { api, .. } = event {
                api.prevent_exit();
            }
        });
}
