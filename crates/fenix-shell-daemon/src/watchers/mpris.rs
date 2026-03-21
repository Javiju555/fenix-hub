/// MPRIS watcher.
///
/// Strategy:
///  - Subscribe to `NameOwnerChanged` on org.freedesktop.DBus to detect
///    player connect/disconnect → immediate re-read.
///  - While a player is active, poll every 1 s for track/position changes.
///    (Full per-player PropertiesChanged subscription is a future improvement.)
use crate::state::MediaState;
use crate::watchers::StateUpdate;
use anyhow::Result;
use base64::Engine as _;
use futures_util::StreamExt as _;
use mpris::{PlaybackStatus, PlayerFinder};
use tokio::sync::broadcast;
use tracing::{debug, warn};
use zbus::fdo::DBusProxy;
use zbus::Connection;

pub async fn run(tx: broadcast::Sender<StateUpdate>) -> Result<()> {
    let conn = Connection::session().await?;
    let dbus = DBusProxy::new(&conn).await?;
    let mut name_changes = dbus.receive_name_owner_changed().await?;

    // Hydrate initial state
    let _ = tx.send(StateUpdate::Media(read_media_state().await));

    loop {
        tokio::select! {
            maybe = name_changes.next() => {
                let Some(sig) = maybe else { break };
                match sig.args() {
                    Ok(args) if args.name.starts_with("org.mpris.MediaPlayer2.") => {
                        debug!("MPRIS player event: {}", args.name);
                        let _ = tx.send(StateUpdate::Media(read_media_state().await));
                    }
                    Err(e) => warn!("MPRIS signal args error: {e}"),
                    _ => {}
                }
            }
            // 1 s fallback to catch track changes while a player is active
            _ = tokio::time::sleep(tokio::time::Duration::from_secs(1)) => {
                let _ = tx.send(StateUpdate::Media(read_media_state().await));
            }
        }
    }

    Ok(())
}

async fn read_media_state() -> MediaState {
    tokio::task::spawn_blocking(read_media_state_sync)
        .await
        .unwrap_or_default()
}

fn read_media_state_sync() -> MediaState {
    let Some(player) = select_player() else {
        return MediaState::default();
    };

    let playback_status = player
        .get_playback_status()
        .unwrap_or(PlaybackStatus::Stopped);
    let metadata = player.get_metadata().ok();

    let title = metadata
        .as_ref()
        .and_then(|m| m.title())
        .unwrap_or("")
        .trim()
        .to_string();

    let artist = metadata
        .as_ref()
        .and_then(|m| m.artists())
        .map(|v| v.join(", "))
        .unwrap_or_default();

    let album_art_url = metadata
        .as_ref()
        .and_then(|m| m.art_url())
        .map(art_url_to_data)
        .unwrap_or_default();

    if title.is_empty() && playback_status != PlaybackStatus::Playing {
        return MediaState::default();
    }

    let is_playing = playback_status == PlaybackStatus::Playing;
    let playback_status_str = match playback_status {
        PlaybackStatus::Playing => "Playing",
        PlaybackStatus::Paused => "Paused",
        PlaybackStatus::Stopped => "Stopped",
    }
    .to_string();

    MediaState {
        title,
        artist,
        album_art_url,
        playback_status: playback_status_str,
        is_playing,
    }
}

fn select_player() -> Option<mpris::Player> {
    let finder = PlayerFinder::new().ok()?;
    finder
        .find_all()
        .ok()?
        .into_iter()
        .filter(|p| {
            let status = p.get_playback_status().unwrap_or(PlaybackStatus::Stopped);
            let has_meta = p
                .get_metadata()
                .ok()
                .and_then(|m| m.title().map(|t| !t.trim().is_empty() && t != "Unknown"))
                .unwrap_or(false);
            status == PlaybackStatus::Playing || has_meta
        })
        .min_by_key(|p| match p.get_playback_status().unwrap_or(PlaybackStatus::Stopped) {
            PlaybackStatus::Playing => 0,
            PlaybackStatus::Paused => 1,
            PlaybackStatus::Stopped => 2,
        })
}

fn art_url_to_data(url: &str) -> String {
    if url.starts_with("file://") {
        let path = url.trim_start_matches("file://");
        if let Ok(data) = std::fs::read(path) {
            let mime = if path.ends_with(".png") { "image/png" } else { "image/jpeg" };
            let b64 = base64::engine::general_purpose::STANDARD.encode(&data);
            return format!("data:{mime};base64,{b64}");
        }
    }
    url.to_string()
}
