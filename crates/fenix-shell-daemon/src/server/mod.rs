pub mod audio;
pub mod battery;
pub mod media;
pub mod network;

use crate::watchers::StateUpdate;
use anyhow::Result;
use zbus::Connection;

pub async fn register_all(conn: &Connection) -> Result<()> {
    conn.object_server()
        .at("/org/fenix/Shell/Media", media::MediaInterface::new())
        .await?;
    conn.object_server()
        .at("/org/fenix/Shell/Audio", audio::AudioInterface::new())
        .await?;
    conn.object_server()
        .at("/org/fenix/Shell/Battery", battery::BatteryInterface::new())
        .await?;
    conn.object_server()
        .at("/org/fenix/Shell/Network", network::NetworkInterface::new())
        .await?;
    Ok(())
}

pub async fn dispatch_update(conn: &Connection, update: StateUpdate) -> Result<()> {
    match update {
        StateUpdate::Media(s)   => media::update(conn, s).await,
        StateUpdate::Audio(s)   => audio::update(conn, s).await,
        StateUpdate::Battery(s) => battery::update(conn, s).await,
        StateUpdate::Network(s) => network::update(conn, s).await,
    }
}
