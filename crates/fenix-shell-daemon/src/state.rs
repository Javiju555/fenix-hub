use serde::{Deserialize, Serialize};
use zbus::zvariant::Type;

/// Current media playback state — doubles as D-Bus transfer type.
#[derive(Clone, Debug, Default, Serialize, Deserialize, Type)]
pub struct MediaState {
    pub title: String,
    pub artist: String,
    /// data: URI or empty string when unavailable
    pub album_art_url: String,
    /// "Playing" | "Paused" | "Stopped"
    pub playback_status: String,
    pub is_playing: bool,
}

/// A single PulseAudio/PipeWire sink.
#[derive(Clone, Debug, Default, Serialize, Deserialize, Type)]
pub struct AudioSink {
    pub name: String,
    pub description: String,
    pub volume: u8,
    pub muted: bool,
    pub is_default: bool,
}

/// Battery state.
#[derive(Clone, Debug, Serialize, Deserialize, Type)]
pub struct BatteryState {
    pub present: bool,
    pub capacity: u8,
    /// "Charging" | "Discharging" | "Full" | "Unknown"
    pub status: String,
    pub health_percent: f64,
}

impl Default for BatteryState {
    fn default() -> Self {
        Self {
            present: false,
            capacity: 0,
            status: "Unknown".to_string(),
            health_percent: 100.0,
        }
    }
}

/// Network connectivity state.
#[derive(Clone, Debug, Default, Serialize, Deserialize, Type)]
pub struct NetworkState {
    /// "Full" | "Limited" | "Portal" | "None" | "Unknown"
    pub connectivity: String,
    pub connection_name: String,
    /// e.g. "802-11-wireless" | "802-3-ethernet"
    pub connection_type: String,
    pub wifi_ssid: String,
    pub wifi_signal: u8,
}
