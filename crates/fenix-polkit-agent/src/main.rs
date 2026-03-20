/// fenix-polkit-agent — Polkit authentication agent for Fenix Desktop.
mod agent;
mod dialog;
mod helper;

use gtk4::{glib, prelude::*};
use gtk4::gio::ApplicationFlags;

fn main() -> anyhow::Result<()> {
    tracing_subscriber::fmt::init();

    // Channel: D-Bus thread → GTK main thread
    let (tx, rx) = async_channel::bounded::<agent::AuthRequest>(4);

    // D-Bus agent runs in a dedicated tokio thread
    std::thread::spawn(move || {
        tokio::runtime::Runtime::new()
            .expect("tokio runtime")
            .block_on(agent::run(tx))
            .unwrap_or_else(|e| tracing::error!("D-Bus agent error: {e}"));
    });

    let app = gtk4::Application::builder()
        .application_id("dev.fenix.PolkitAgent")
        .flags(ApplicationFlags::IS_SERVICE)
        .build();

    app.connect_activate(move |app| {
        app.hold();

        let app = app.clone();
        let rx = rx.clone();
        glib::MainContext::default().spawn_local(async move {
            while let Ok(req) = rx.recv().await {
                dialog::show(&app, req);
            }
        });
    });

    app.run_with_args::<String>(&[]);
    Ok(())
}
