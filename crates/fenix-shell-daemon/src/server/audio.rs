use crate::state::AudioSink;
use anyhow::Result;
use zbus::{interface, Connection, SignalContext};

pub struct AudioInterface {
    pub sinks: Vec<AudioSink>,
}

impl AudioInterface {
    pub fn new() -> Self {
        Self { sinks: Vec::new() }
    }
}

#[interface(name = "org.fenix.Shell.Audio")]
impl AudioInterface {
    // ── Methods ───────────────────────────────────────────────────────────────
    /// Returns current sinks as a JSON string (Vec<AudioSink> can't be used as
    /// a D-Bus property without extra zvariant derives).
    fn get_sinks(&self) -> String {
        serde_json::to_string(&self.sinks).unwrap_or_else(|_| "[]".to_string())
    }

    async fn set_volume(&self, sink_name: String, volume: u8) -> zbus::fdo::Result<()> {
        tokio::process::Command::new("pactl")
            .args(["set-sink-volume", &sink_name, &format!("{volume}%")])
            .status()
            .await
            .map_err(|e| zbus::fdo::Error::Failed(e.to_string()))?;
        Ok(())
    }

    async fn set_muted(&self, sink_name: String, muted: bool) -> zbus::fdo::Result<()> {
        let arg = if muted { "1" } else { "0" };
        tokio::process::Command::new("pactl")
            .args(["set-sink-mute", &sink_name, arg])
            .status()
            .await
            .map_err(|e| zbus::fdo::Error::Failed(e.to_string()))?;
        Ok(())
    }

    // ── Signals ───────────────────────────────────────────────────────────────
    #[zbus(signal)]
    async fn audio_changed(
        ctx: &SignalContext<'_>,
        sinks: Vec<AudioSink>,
    ) -> zbus::Result<()>;
}

pub async fn update(conn: &Connection, sinks: Vec<AudioSink>) -> Result<()> {
    let iface_ref = conn
        .object_server()
        .interface::<_, AudioInterface>("/org/fenix/Shell/Audio")
        .await?;

    let snapshot = sinks.clone();
    iface_ref.get_mut().await.sinks = snapshot;

    AudioInterface::audio_changed(iface_ref.signal_context(), sinks).await?;
    Ok(())
}
