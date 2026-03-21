pub mod audio;
pub mod battery;
pub mod mpris;
pub mod network;

use crate::state::{AudioSink, BatteryState, MediaState, NetworkState};

#[derive(Debug, Clone)]
pub enum StateUpdate {
    Media(MediaState),
    Audio(Vec<AudioSink>),
    Battery(BatteryState),
    Network(NetworkState),
}
