/// Audio watcher via `pactl subscribe`.
///
/// Runs `pactl subscribe` as a subprocess, reads stdout line by line, and
/// triggers a re-read of sink state whenever a sink or server event fires.
/// A 50 ms debounce prevents redundant reads when several events arrive together.
use crate::state::AudioSink;
use crate::watchers::StateUpdate;
use anyhow::Result;
use serde::Deserialize;
use tokio::io::{AsyncBufReadExt, BufReader};
use tokio::process::Command;
use tokio::sync::broadcast;
use tokio::time::{sleep, Duration};
use tracing::{debug, warn};

pub async fn run(tx: broadcast::Sender<StateUpdate>) -> Result<()> {
    // Hydrate initial state
    let _ = tx.send(StateUpdate::Audio(read_audio_sinks().await));

    let mut child = Command::new("pactl")
        .arg("subscribe")
        .stdout(std::process::Stdio::piped())
        .spawn()?;

    let stdout = child.stdout.take().expect("pactl stdout");
    let mut lines = BufReader::new(stdout).lines();

    while let Some(line) = lines.next_line().await? {
        if line.contains("sink") || line.contains("server") {
            debug!("pactl event: {line}");
            // Debounce: wait for more events to settle
            sleep(Duration::from_millis(50)).await;
            let _ = tx.send(StateUpdate::Audio(read_audio_sinks().await));
        }
    }

    warn!("pactl subscribe exited");
    Ok(())
}

// ── pactl JSON shapes ──────────────────────────────────────────────────────

#[derive(Deserialize)]
struct PactlSink {
    name: String,
    description: String,
    mute: bool,
    volume: PactlVolume,
}

#[derive(Deserialize)]
struct PactlVolume {
    #[serde(rename = "front-left")]
    front_left: Option<PactlChannel>,
    mono: Option<PactlChannel>,
}

#[derive(Deserialize)]
struct PactlChannel {
    value_percent: String,
}

fn parse_volume(v: &PactlVolume) -> u8 {
    v.front_left
        .as_ref()
        .or(v.mono.as_ref())
        .map(|c| c.value_percent.trim_end_matches('%').parse::<u8>().unwrap_or(0))
        .unwrap_or(0)
}

async fn default_sink() -> Option<String> {
    let out = Command::new("pactl").arg("get-default-sink").output().await.ok()?;
    out.status.success().then(|| String::from_utf8_lossy(&out.stdout).trim().to_string())
}

async fn read_audio_sinks() -> Vec<AudioSink> {
    read_audio_sinks_inner().await.unwrap_or_default()
}

async fn read_audio_sinks_inner() -> Result<Vec<AudioSink>> {
    let out = Command::new("pactl")
        .args(["--format=json", "list", "sinks"])
        .output()
        .await?;

    if !out.status.success() {
        anyhow::bail!("pactl list sinks failed");
    }

    let raw: Vec<PactlSink> = serde_json::from_slice(&out.stdout)?;
    let def = default_sink().await;

    Ok(raw
        .into_iter()
        .map(|s| AudioSink {
            is_default: def.as_deref() == Some(&s.name),
            volume: parse_volume(&s.volume),
            muted: s.mute,
            name: s.name,
            description: s.description,
        })
        .collect())
}
