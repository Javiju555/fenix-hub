/// Network watcher via NetworkManager D-Bus (system bus).
///
/// Subscribes to `org.freedesktop.NetworkManager.PropertiesChanged` to detect
/// connectivity changes, then re-reads state via nmcli.
/// A 30 s fallback poll covers WiFi signal strength updates.
use crate::state::NetworkState;
use crate::watchers::StateUpdate;
use anyhow::Result;
use futures_util::StreamExt as _;
use tokio::process::Command;
use tokio::sync::broadcast;
use tracing::debug;
use zbus::Connection;

pub async fn run(tx: broadcast::Sender<StateUpdate>) -> Result<()> {
    let conn = Connection::system().await?;

    // Hydrate initial state
    let _ = tx.send(StateUpdate::Network(read_network_state().await));

    // Watch NetworkManager PropertiesChanged (covers connectivity, active connections, etc.)
    let nm_proxy = zbus::Proxy::new(
        &conn,
        "org.freedesktop.NetworkManager",
        "/org/freedesktop/NetworkManager",
        "org.freedesktop.DBus.Properties",
    )
    .await?;

    let mut prop_stream = nm_proxy.receive_signal("PropertiesChanged").await?;

    loop {
        tokio::select! {
            maybe = prop_stream.next() => {
                match maybe {
                    Some(_msg) => {
                        debug!("NM PropertiesChanged");
                        let _ = tx.send(StateUpdate::Network(read_network_state().await));
                    }
                    None => break,
                }
            }
            // 30 s fallback for signal strength / minor changes
            _ = tokio::time::sleep(tokio::time::Duration::from_secs(30)) => {
                let _ = tx.send(StateUpdate::Network(read_network_state().await));
            }
        }
    }

    Ok(())
}

async fn read_network_state() -> NetworkState {
    read_network_state_inner().await.unwrap_or_default()
}

async fn read_network_state_inner() -> Result<NetworkState> {
    // Active connection (name, type)
    let ac_out = Command::new("nmcli")
        .args(["-t", "-f", "NAME,TYPE,STATE,DEVICE", "connection", "show", "--active"])
        .output()
        .await?;
    let ac_text = String::from_utf8_lossy(&ac_out.stdout);

    let mut connection_name = String::new();
    let mut connection_type = String::new();
    let mut wifi_ssid = String::new();
    let mut wifi_signal: u8 = 0;

    // Pick the wifi connection if present, else the first active one
    let mut first_name = String::new();
    let mut first_type = String::new();

    for line in ac_text.lines() {
        let parts: Vec<&str> = line.splitn(4, ':').collect();
        if parts.len() < 4 { continue; }
        let name = parts[0].trim();
        let typ  = parts[1].trim();
        if first_name.is_empty() {
            first_name = name.to_string();
            first_type = typ.to_string();
        }
        if typ.contains("802-11-wireless") || typ.contains("wifi") {
            connection_name = name.to_string();
            connection_type = typ.to_string();
            break;
        }
    }

    if connection_name.is_empty() {
        connection_name = first_name;
        connection_type = first_type;
    }

    // Wifi SSID + signal for active network
    if connection_type.contains("802-11-wireless") || connection_type.contains("wifi") {
        let wifi_out = Command::new("nmcli")
            .args(["-t", "-f", "SSID,SIGNAL,IN-USE", "device", "wifi", "list"])
            .output()
            .await?;
        let wifi_text = String::from_utf8_lossy(&wifi_out.stdout);
        for line in wifi_text.lines() {
            let parts: Vec<&str> = line.splitn(3, ':').collect();
            if parts.len() < 3 { continue; }
            let in_use = parts[2].trim() == "*";
            if in_use {
                wifi_ssid = parts[0].trim().to_string();
                wifi_signal = parts[1].trim().parse::<u8>().unwrap_or(0);
                break;
            }
        }
    }

    // Connectivity from nmcli general
    let conn_out = Command::new("nmcli")
        .args(["-t", "-f", "CONNECTIVITY", "general"])
        .output()
        .await?;
    let raw_conn = String::from_utf8_lossy(&conn_out.stdout);
    let connectivity = raw_conn
        .lines()
        .next()
        .unwrap_or("unknown")
        .trim()
        .to_string();
    let connectivity = match connectivity.to_lowercase().as_str() {
        "full"    => "Full",
        "limited" => "Limited",
        "portal"  => "Portal",
        "none"    => "None",
        _         => "Unknown",
    }
    .to_string();

    Ok(NetworkState { connectivity, connection_name, connection_type, wifi_ssid, wifi_signal })
}
