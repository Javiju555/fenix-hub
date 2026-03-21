use crate::state::MediaState;
use anyhow::Result;
use zbus::{interface, Connection, SignalContext};

pub struct MediaInterface {
    pub state: MediaState,
}

impl MediaInterface {
    pub fn new() -> Self {
        Self { state: MediaState::default() }
    }
}

#[interface(name = "org.fenix.Shell.Media")]
impl MediaInterface {
    // ── Properties ────────────────────────────────────────────────────────────
    #[zbus(property)]
    fn title(&self) -> &str { &self.state.title }

    #[zbus(property)]
    fn artist(&self) -> &str { &self.state.artist }

    #[zbus(property)]
    fn album_art_url(&self) -> &str { &self.state.album_art_url }

    #[zbus(property)]
    fn playback_status(&self) -> &str { &self.state.playback_status }

    #[zbus(property)]
    fn is_playing(&self) -> bool { self.state.is_playing }

    // ── Methods ───────────────────────────────────────────────────────────────
    async fn play_pause(&self) -> zbus::fdo::Result<()> {
        tokio::task::spawn_blocking(|| {
            if let Some(player) = select_player() {
                player.play_pause().map_err(|e| zbus::fdo::Error::Failed(e.to_string()))?;
            }
            Ok(())
        })
        .await
        .map_err(|e| zbus::fdo::Error::Failed(e.to_string()))?
    }

    async fn next(&self) -> zbus::fdo::Result<()> {
        tokio::task::spawn_blocking(|| {
            if let Some(player) = select_player() {
                player.next().map_err(|e| zbus::fdo::Error::Failed(e.to_string()))?;
            }
            Ok(())
        })
        .await
        .map_err(|e| zbus::fdo::Error::Failed(e.to_string()))?
    }

    async fn previous(&self) -> zbus::fdo::Result<()> {
        tokio::task::spawn_blocking(|| {
            if let Some(player) = select_player() {
                player.previous().map_err(|e| zbus::fdo::Error::Failed(e.to_string()))?;
            }
            Ok(())
        })
        .await
        .map_err(|e| zbus::fdo::Error::Failed(e.to_string()))?
    }

    // ── Signals ───────────────────────────────────────────────────────────────
    #[zbus(signal)]
    async fn media_changed(
        ctx: &SignalContext<'_>,
        state: MediaState,
    ) -> zbus::Result<()>;
}

pub async fn update(conn: &Connection, state: MediaState) -> Result<()> {
    let iface_ref = conn
        .object_server()
        .interface::<_, MediaInterface>("/org/fenix/Shell/Media")
        .await?;

    let snapshot = state.clone();
    iface_ref.get_mut().await.state = snapshot;

    MediaInterface::media_changed(iface_ref.signal_context(), state).await?;
    Ok(())
}

fn select_player() -> Option<mpris::Player> {
    let finder = mpris::PlayerFinder::new().ok()?;
    finder
        .find_all()
        .ok()?
        .into_iter()
        .filter(|p| {
            let status = p
                .get_playback_status()
                .unwrap_or(mpris::PlaybackStatus::Stopped);
            let has_meta = p
                .get_metadata()
                .ok()
                .and_then(|m| m.title().map(|t| !t.trim().is_empty()))
                .unwrap_or(false);
            status == mpris::PlaybackStatus::Playing || has_meta
        })
        .min_by_key(|p| {
            match p
                .get_playback_status()
                .unwrap_or(mpris::PlaybackStatus::Stopped)
            {
                mpris::PlaybackStatus::Playing => 0,
                mpris::PlaybackStatus::Paused => 1,
                mpris::PlaybackStatus::Stopped => 2,
            }
        })
}
