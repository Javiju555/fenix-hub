mod commands;
mod discovery;
#[cfg(target_os = "windows")]
mod drop_target;
mod network;
mod persist;
mod state;
mod temp_store;
mod windowing;

use std::sync::atomic::{AtomicBool, Ordering};
use tauri::{
    menu::{Menu, MenuItem},
    tray::{MouseButton, MouseButtonState, TrayIconEvent},
    Manager,
};
use tracing_subscriber::EnvFilter;

/// Set to true by the tray "Salir" action so ExitRequested doesn't block it.
static QUIT_REQUESTED: AtomicBool = AtomicBool::new(false);

fn should_start_hidden() -> bool {
    std::env::args().skip(1).any(|arg| {
        matches!(
            arg.as_str(),
            "--background" | "--tray" | "--hidden" | "--startup"
        )
    })
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tracing_subscriber::fmt()
        .with_env_filter(
            EnvFilter::from_default_env().add_directive("fenix_hub=debug".parse().unwrap()),
        )
        .init();

    let mdns = mdns_sd::ServiceDaemon::new().expect("Failed to start mDNS daemon");

    // Restrict mDNS to the LAN interface only — prevents VMware/VPN adapters
    // from intercepting or misdirecting multicast traffic.
    if let Some(lan_ip) = network::local_ipv4() {
        use mdns_sd::IfKind;
        mdns.disable_interface(IfKind::IPv4).ok();
        mdns.disable_interface(IfKind::IPv6).ok();
        mdns.enable_interface(std::net::IpAddr::V4(lan_ip)).ok();
        tracing::info!("mDNS restricted to LAN interface {}", lan_ip);
    }
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
            let start_hidden = should_start_hidden();
            temp_store::prepare().ok();

            if let Some(window) = app_handle.get_webview_window("hub") {
                windowing::attach_hub_window_handlers(&window, &app_handle);
                if start_hidden {
                    let _ = window.hide();
                }
            }

            if !start_hidden {
                let _ = windowing::show_or_create_hub_window(&app_handle);
            } else {
                tracing::info!("Starting in background mode: hub window hidden");
            }

            // Tray icon menu: left-click opens hub, right-click shows menu.
            let open_item = MenuItem::with_id(app, "open", "Abrir FenixHub", true, None::<&str>)?;
            let quit_item = MenuItem::with_id(app, "quit", "Salir", true, None::<&str>)?;
            let menu = Menu::with_items(app, &[&open_item, &quit_item])?;

            if let Some(tray) = app.tray_by_id("main") {
                tray.set_menu(Some(menu))?;

                tray.on_menu_event(|app, event| match event.id().as_ref() {
                    "open" => {
                        let _ = windowing::show_or_create_hub_window(app);
                    }
                    "quit" => {
                        QUIT_REQUESTED.store(true, Ordering::Release);
                        app.exit(0);
                    }
                    _ => {}
                });

                tray.on_tray_icon_event(|tray, event| {
                    if let TrayIconEvent::Click {
                        button: MouseButton::Left,
                        button_state: MouseButtonState::Up,
                        ..
                    } = event
                    {
                        let _ = windowing::show_or_create_hub_window(tray.app_handle());
                    }
                });
            }

            // Identity is loaded synchronously in HubState::new() before any
            // window is created, so get_identity() always sees a consistent state.
            // Here we just need to start discovery if an identity was found.
            if let Ok(guard) = state.identity.try_read() {
                if let Some(ref identity) = *guard {
                    // Register presence beacon so peers see this device immediately.
                    if let Some(lan_ip) = network::local_ipv4() {
                        if let Err(e) = fenix_hub_daemon::mdns::announce_device_presence(
                            &mdns,
                            &identity.device_name,
                            &identity.group_id(),
                            lan_ip,
                        ) {
                            tracing::warn!("Failed to register presence beacon: {e}");
                        }
                    }
                    discovery::start(
                        app_handle.clone(),
                        mdns.clone(),
                        identity.clone(),
                        state.peer_content.clone(),
                    );
                    tracing::info!("Identity loaded from disk, discovery started");

                    // Re-announce all shared content every 15 s so late-joining peers
                    // discover it. mDNS-SD does not respond to PTR queries for services
                    // that have been registered for a long time.
                    let reannounce_map = state.active_announcements.clone();
                    let reannounce_mdns = mdns.clone();
                    tauri::async_runtime::spawn(async move {
                        let mut interval =
                            tokio::time::interval(std::time::Duration::from_secs(15));
                        interval.tick().await; // skip the immediate first tick
                        loop {
                            interval.tick().await;
                            let records: Vec<crate::state::AnnouncementRecord> =
                                { reannounce_map.read().await.values().cloned().collect() };
                            if records.is_empty() {
                                continue;
                            }
                            tracing::debug!(
                                "mDNS re-announce: refreshing {} service(s)",
                                records.len()
                            );
                            for rec in &records {
                                fenix_hub_daemon::mdns::unannounce_content(
                                    &reannounce_mdns,
                                    &rec.instance_name,
                                )
                                .ok();
                            }
                            for rec in &records {
                                // Only re-announce if still active (remove_content may have
                                // taken it out between our snapshot and here).
                                if !reannounce_map
                                    .read()
                                    .await
                                    .contains_key(&rec.announcement.content_id)
                                {
                                    continue;
                                }
                                if let std::net::IpAddr::V4(ipv4) = rec.local_ip {
                                    if let Err(e) = fenix_hub_daemon::mdns::announce_content(
                                        &reannounce_mdns,
                                        &rec.announcement,
                                        ipv4,
                                    ) {
                                        tracing::warn!(
                                            "mDNS re-announce failed for {}: {e}",
                                            rec.announcement.content_id
                                        );
                                    }
                                }
                            }
                        }
                    });
                } else {
                    tracing::info!("No saved identity — waiting for first-run setup");
                }
            }

            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            commands::get_identity,
            commands::setup_identity,
            commands::update_identity,
            commands::delete_identity_only,
            commands::list_identity_profiles,
            commands::save_current_identity_profile,
            commands::activate_identity_profile,
            commands::delete_identity_profile,
            commands::get_transport_capabilities,
            commands::get_transport_hardware,
            commands::get_local_content,
            commands::add_text_content,
            commands::add_binary_content,
            commands::paste_clipboard_content,
            commands::remove_content,
            commands::publish_content,
            commands::publish_all,
            commands::unpublish_content,
            commands::unpublish_all,
            commands::stop_server,
            commands::pull_peer_content,
            commands::copy_peer_content,
            commands::save_peer_content_as,
            commands::save_local_content_as,
            commands::get_peers,
            commands::write_local_to_clipboard,
            commands::prepare_local_drag,
            commands::start_native_file_drag,
            commands::resize_hub,
            commands::close_hub_window,
            commands::open_settings,
            commands::close_settings,
            commands::confirm_reset,
            commands::add_file_by_path,
            commands::share_folder,
            commands::extract_folder,
            commands::clear_received_cache,
            commands::reset_all_data,
            #[cfg(any(target_os = "linux", target_os = "windows"))]
            commands::check_firewall_status,
            #[cfg(any(target_os = "linux", target_os = "windows"))]
            commands::request_firewall_allow,
        ])
        .build(tauri::generate_context!())
        .expect("error while building FenixHub")
        .run(|_app, event| {
            if let tauri::RunEvent::ExitRequested { api, .. } = event {
                // Only keep alive when NOT explicitly quitting via tray menu
                if !QUIT_REQUESTED.load(Ordering::Acquire) {
                    api.prevent_exit();
                }
            }
        });
}
