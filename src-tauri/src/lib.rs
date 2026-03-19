mod commands;
mod discovery;
mod persist;
mod state;

use std::sync::Arc;
use tauri::Manager;
use tracing_subscriber::EnvFilter;

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tracing_subscriber::fmt()
        .with_env_filter(
            EnvFilter::from_default_env()
                .add_directive("fenix_hub=debug".parse().unwrap()),
        )
        .init();

    let mdns = mdns_sd::ServiceDaemon::new().expect("Failed to start mDNS daemon");
    let hub_state = state::HubState::new(mdns.clone());

    tauri::Builder::default()
        .plugin(tauri_plugin_shell::init())
        .manage(hub_state)
        .setup(move |app| {
            let app_handle = app.handle().clone();
            let state = app.state::<state::HubState>();

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
            commands::remove_content,
            commands::publish_content,
            commands::stop_server,
            commands::pull_peer_content,
            commands::get_peers,
        ])
        .run(tauri::generate_context!())
        .expect("error while running FenixHub");
}
