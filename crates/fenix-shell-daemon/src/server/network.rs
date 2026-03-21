use crate::state::NetworkState;
use anyhow::Result;
use zbus::{interface, Connection, SignalContext};

pub struct NetworkInterface {
    pub state: NetworkState,
}

impl NetworkInterface {
    pub fn new() -> Self {
        Self { state: NetworkState::default() }
    }
}

#[interface(name = "org.fenix.Shell.Network")]
impl NetworkInterface {
    // ── Properties ────────────────────────────────────────────────────────────
    #[zbus(property)]
    fn connectivity(&self) -> &str { &self.state.connectivity }

    #[zbus(property)]
    fn connection_name(&self) -> &str { &self.state.connection_name }

    #[zbus(property)]
    fn connection_type(&self) -> &str { &self.state.connection_type }

    #[zbus(property)]
    fn wifi_ssid(&self) -> &str { &self.state.wifi_ssid }

    #[zbus(property)]
    fn wifi_signal(&self) -> u8 { self.state.wifi_signal }

    // ── Methods ───────────────────────────────────────────────────────────────
    async fn wifi_disconnect(&self) -> zbus::fdo::Result<()> {
        let iface = detect_wifi_interface().await.unwrap_or_else(|| "wlan0".to_string());
        tokio::process::Command::new("nmcli")
            .args(["device", "disconnect", &iface])
            .status()
            .await
            .map_err(|e| zbus::fdo::Error::Failed(e.to_string()))?;
        Ok(())
    }

    // ── Signals ───────────────────────────────────────────────────────────────
    #[zbus(signal)]
    async fn network_changed(
        ctx: &SignalContext<'_>,
        state: NetworkState,
    ) -> zbus::Result<()>;
}

pub async fn update(conn: &Connection, state: NetworkState) -> Result<()> {
    let iface_ref = conn
        .object_server()
        .interface::<_, NetworkInterface>("/org/fenix/Shell/Network")
        .await?;

    let snapshot = state.clone();
    iface_ref.get_mut().await.state = snapshot;

    NetworkInterface::network_changed(iface_ref.signal_context(), state).await?;
    Ok(())
}

async fn detect_wifi_interface() -> Option<String> {
    let out = tokio::process::Command::new("nmcli")
        .args(["-t", "-f", "DEVICE,TYPE", "device"])
        .output()
        .await
        .ok()?;
    let text = String::from_utf8_lossy(&out.stdout);
    for line in text.lines() {
        let mut parts = line.splitn(2, ':');
        let dev = parts.next()?.trim();
        let typ = parts.next()?.trim();
        if typ.contains("wifi") {
            return Some(dev.to_string());
        }
    }
    None
}
