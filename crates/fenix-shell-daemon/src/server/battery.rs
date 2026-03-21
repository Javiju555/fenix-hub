use crate::state::BatteryState;
use anyhow::Result;
use zbus::{interface, Connection, SignalContext};

pub struct BatteryInterface {
    pub state: BatteryState,
}

impl BatteryInterface {
    pub fn new() -> Self {
        Self { state: BatteryState::default() }
    }
}

#[interface(name = "org.fenix.Shell.Battery")]
impl BatteryInterface {
    // ── Properties ────────────────────────────────────────────────────────────
    #[zbus(property)]
    fn present(&self) -> bool { self.state.present }

    #[zbus(property)]
    fn capacity(&self) -> u8 { self.state.capacity }

    #[zbus(property)]
    fn status(&self) -> &str { &self.state.status }

    #[zbus(property)]
    fn health_percent(&self) -> f64 { self.state.health_percent }

    // ── Signals ───────────────────────────────────────────────────────────────
    #[zbus(signal)]
    async fn battery_changed(
        ctx: &SignalContext<'_>,
        state: BatteryState,
    ) -> zbus::Result<()>;
}

pub async fn update(conn: &Connection, state: BatteryState) -> Result<()> {
    let iface_ref = conn
        .object_server()
        .interface::<_, BatteryInterface>("/org/fenix/Shell/Battery")
        .await?;

    let snapshot = state.clone();
    iface_ref.get_mut().await.state = snapshot;

    BatteryInterface::battery_changed(iface_ref.signal_context(), state).await?;
    Ok(())
}
