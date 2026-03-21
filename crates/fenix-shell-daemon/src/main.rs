mod server;
mod state;
mod watchers;

use anyhow::Result;
use tokio::sync::broadcast;
use watchers::StateUpdate;
use zbus::Connection;

#[tokio::main]
async fn main() -> Result<()> {
    tracing_subscriber::fmt()
        .with_env_filter(
            tracing_subscriber::EnvFilter::from_default_env()
                .add_directive("fenix_shelld=info".parse()?)
                .add_directive("warn".parse()?),
        )
        .init();

    let conn = Connection::session().await?;

    // Register the four D-Bus interfaces before claiming the bus name
    server::register_all(&conn).await?;

    conn.request_name("org.fenix.Shell").await?;
    tracing::info!("fenix-shelld started on org.fenix.Shell (session bus)");

    // State-update broadcast channel — channel capacity covers bursts at startup
    let (tx, mut rx) = broadcast::channel::<StateUpdate>(128);

    // ── Spawn watchers ────────────────────────────────────────────────────────
    spawn_watcher("mpris",   tx.clone(), watchers::mpris::run);
    spawn_watcher("audio",   tx.clone(), watchers::audio::run);
    spawn_watcher("battery", tx.clone(), watchers::battery::run);
    spawn_watcher("network", tx.clone(), watchers::network::run);

    // ── Dispatcher loop ───────────────────────────────────────────────────────
    let sigterm = async {
        #[cfg(unix)]
        {
            use tokio::signal::unix::{signal, SignalKind};
            signal(SignalKind::terminate())
                .expect("SIGTERM handler")
                .recv()
                .await;
        }
        #[cfg(not(unix))]
        tokio::signal::ctrl_c().await.ok();
    };

    tokio::pin!(sigterm);

    loop {
        tokio::select! {
            result = rx.recv() => {
                match result {
                    Ok(update) => {
                        if let Err(e) = server::dispatch_update(&conn, update).await {
                            tracing::warn!("dispatch error: {e}");
                        }
                    }
                    Err(broadcast::error::RecvError::Lagged(n)) => {
                        tracing::warn!("dispatcher lagged {n} messages");
                    }
                    Err(broadcast::error::RecvError::Closed) => {
                        tracing::info!("broadcast channel closed, shutting down");
                        break;
                    }
                }
            }
            _ = &mut sigterm => {
                tracing::info!("SIGTERM received, shutting down");
                break;
            }
        }
    }

    Ok(())
}

/// Spawn a watcher task; restart with a 2 s back-off on failure.
fn spawn_watcher<F, Fut>(
    name: &'static str,
    tx: broadcast::Sender<StateUpdate>,
    run: F,
) where
    F: Fn(broadcast::Sender<StateUpdate>) -> Fut + Send + 'static,
    Fut: std::future::Future<Output = anyhow::Result<()>> + Send + 'static,
{
    tokio::spawn(async move {
        loop {
            if let Err(e) = run(tx.clone()).await {
                tracing::error!("{name} watcher error: {e}");
            }
            tracing::info!("{name} watcher restarting in 2 s");
            tokio::time::sleep(tokio::time::Duration::from_secs(2)).await;
        }
    });
}
