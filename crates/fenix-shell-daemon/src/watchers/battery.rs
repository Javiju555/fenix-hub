/// Battery watcher via inotify on /sys/class/power_supply/.
///
/// Watches for CLOSE_WRITE events on the battery sysfs directory so the daemon
/// reacts immediately when the kernel updates capacity/status files.
use crate::state::BatteryState;
use crate::watchers::StateUpdate;
use anyhow::Result;
use futures_util::StreamExt as _;
use inotify::{Inotify, WatchMask};
use tokio::fs;
use tokio::sync::broadcast;
use tracing::{debug, warn};

pub async fn run(tx: broadcast::Sender<StateUpdate>) -> Result<()> {
    // Hydrate initial state
    let _ = tx.send(StateUpdate::Battery(read_battery_state().await));

    let bat = match find_battery().await {
        Some(b) => b,
        None => {
            warn!("No battery found, battery watcher exiting");
            return Ok(());
        }
    };

    let inotify = Inotify::init()?;
    let path = format!("/sys/class/power_supply/{bat}");
    inotify
        .watches()
        .add(&path, WatchMask::CLOSE_WRITE | WatchMask::MODIFY)?;

    let buffer = [0u8; 1024];
    let mut stream = inotify.into_event_stream(buffer)?;

    while let Some(event) = stream.next().await {
        match event {
            Ok(ev) => debug!("battery inotify: {:?}", ev.name),
            Err(e) => {
                warn!("battery inotify error: {e}");
                break;
            }
        }
        let _ = tx.send(StateUpdate::Battery(read_battery_state().await));
    }

    Ok(())
}

async fn find_battery() -> Option<String> {
    for bat in ["BAT0", "BAT1"] {
        if fs::metadata(format!("/sys/class/power_supply/{bat}")).await.is_ok() {
            return Some(bat.to_string());
        }
    }
    None
}

async fn bat_file(bat: &str, file: &str) -> Option<String> {
    fs::read_to_string(format!("/sys/class/power_supply/{bat}/{file}"))
        .await
        .ok()
        .map(|s| s.trim().to_string())
}

async fn read_battery_state() -> BatteryState {
    let Some(bat) = find_battery().await else {
        return BatteryState::default();
    };

    let capacity = bat_file(&bat, "capacity")
        .await
        .and_then(|s| s.parse::<u8>().ok())
        .unwrap_or(0);

    let status = bat_file(&bat, "status")
        .await
        .unwrap_or_else(|| "Unknown".to_string());

    let health_percent = {
        let full = bat_file(&bat, "energy_full").await.and_then(|s| s.parse::<u64>().ok());
        let design = bat_file(&bat, "energy_full_design").await.and_then(|s| s.parse::<u64>().ok());
        match (full, design) {
            (Some(f), Some(d)) if d > 0 => (f as f64 / d as f64) * 100.0,
            _ => 100.0,
        }
    };

    BatteryState { present: true, capacity, status, health_percent }
}
