use anyhow::Result;
use fenix_hub_core::identity::GroupIdentity;
use serde::{Deserialize, Serialize};
/// Identity persistence.
///
/// Saves the derived group_key (NOT the passphrase) to ~/.config/fenix-hub/identity.json.
/// On next startup, identity is restored from the key — no need to re-enter passphrase.
use std::path::PathBuf;

#[derive(Serialize, Deserialize)]
struct PersistedIdentity {
    key_hex: String,
    device_name: String,
}

fn config_path() -> Result<PathBuf> {
    let dir = dirs::config_dir()
        .ok_or_else(|| anyhow::anyhow!("Cannot determine config directory"))?
        .join("fenix-hub");
    std::fs::create_dir_all(&dir)?;
    Ok(dir.join("identity.json"))
}

pub fn save(identity: &GroupIdentity) -> Result<()> {
    let data = PersistedIdentity {
        key_hex: identity.key_hex(),
        device_name: identity.device_name.clone(),
    };
    let path = config_path()?;
    std::fs::write(&path, serde_json::to_string_pretty(&data)?)?;
    tracing::info!("Identity saved to {:?}", path);
    Ok(())
}

pub fn load() -> Result<Option<GroupIdentity>> {
    let path = config_path()?;
    if !path.exists() {
        return Ok(None);
    }
    let bytes = std::fs::read(&path)?;
    let data: PersistedIdentity = serde_json::from_slice(&bytes)?;
    let identity = GroupIdentity::from_key_hex(&data.key_hex, &data.device_name)?;
    tracing::info!(
        "Identity loaded from {:?} (device: {})",
        path,
        identity.device_name
    );
    Ok(Some(identity))
}
