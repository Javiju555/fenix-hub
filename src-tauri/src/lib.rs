mod commands;
mod state;

use tracing_subscriber::EnvFilter;

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tracing_subscriber::fmt()
        .with_env_filter(EnvFilter::from_default_env().add_directive("fenix_hub=debug".parse().unwrap()))
        .init();

    tauri::Builder::default()
        .plugin(tauri_plugin_shell::init())
        .manage(state::HubState::new())
        .invoke_handler(tauri::generate_handler![
            commands::get_identity,
            commands::setup_identity,
            commands::get_local_content,
            commands::add_text_content,
            commands::remove_content,
            commands::publish_content,
            commands::pull_peer_content,
            commands::get_peers,
        ])
        .run(tauri::generate_context!())
        .expect("error while running FenixHub");
}
