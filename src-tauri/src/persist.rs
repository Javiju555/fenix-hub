/// Identity persistence.
///
/// Saves the derived group_key (NOT the passphrase) + device metadata to
/// ~/.config/fenix-hub/identity.json.
/// On next startup, identity is restored from the key — no need to re-enter passphrase.
use anyhow::Result;
use fenix_hub_core::identity::GroupIdentity;
use serde::{Deserialize, Serialize};
use std::path::PathBuf;

/// Device type — purely cosmetic, stored locally and shown in the hub UI.
/// Does NOT affect key derivation or group membership.
#[derive(Debug, Clone, Serialize, Deserialize, Default, PartialEq)]
#[serde(rename_all = "snake_case")]
pub enum DeviceType {
    #[default]
    Desktop,
    Laptop,
    Phone,
    Tablet,
    Server,
}

#[derive(Serialize, Deserialize)]
struct PersistedIdentity {
    key_hex: String,
    device_name: String,
    /// Device type icon selector. Defaults to Desktop for old identity.json files.
    #[serde(default)]
    device_type: DeviceType,
}

fn config_path() -> Result<PathBuf> {
    let dir = dirs::config_dir()
        .ok_or_else(|| anyhow::anyhow!("Cannot determine config directory"))?
        .join("fenix-hub");
    std::fs::create_dir_all(&dir)?;
    Ok(dir.join("identity.json"))
}

pub fn save(identity: &GroupIdentity, device_type: &DeviceType) -> Result<()> {
    let data = PersistedIdentity {
        key_hex: identity.key_hex(),
        device_name: identity.device_name.clone(),
        device_type: device_type.clone(),
    };
    let path = config_path()?;
    std::fs::write(&path, serde_json::to_string_pretty(&data)?)?;
    tracing::info!("Identity saved to {:?}", path);
    Ok(())
}

/// Returns `(identity, device_type)`.
pub fn load() -> Result<Option<(GroupIdentity, DeviceType)>> {
    let path = config_path()?;
    if !path.exists() {
        return Ok(None);
    }
    let bytes = std::fs::read(&path)?;
    let data: PersistedIdentity = serde_json::from_slice(&bytes)?;
    let identity = GroupIdentity::from_key_hex(&data.key_hex, &data.device_name)?;
    tracing::info!(
        "Identity loaded from {:?} (device: {}, type: {:?})",
        path,
        identity.device_name,
        data.device_type,
    );
    Ok(Some((identity, data.device_type)))
}
