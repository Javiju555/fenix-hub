/// Identity persistence.
///
/// Saves the derived group_key (NOT the passphrase) + device metadata to
/// ~/.config/fenix-hub/identity.json.
/// On next startup, identity is restored from the key — no need to re-enter passphrase.
use anyhow::Result;
use fenix_hub_core::identity::GroupIdentity;
use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;
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

#[derive(Serialize, Deserialize, Default)]
struct PersistedProfiles {
    #[serde(default)]
    active_profile: Option<String>,
    #[serde(default)]
    profiles: BTreeMap<String, PersistedIdentity>,
}

#[derive(Debug, Clone, Serialize)]
pub struct IdentityProfileInfo {
    pub name: String,
    pub device_name: String,
    pub group_id: String,
    pub device_type: DeviceType,
    pub active: bool,
}

fn config_path() -> Result<PathBuf> {
    let dir = dirs::config_dir()
        .ok_or_else(|| anyhow::anyhow!("Cannot determine config directory"))?
        .join("fenix-hub");
    std::fs::create_dir_all(&dir)?;
    Ok(dir.join("identity.json"))
}

fn profiles_path() -> Result<PathBuf> {
    let dir = dirs::config_dir()
        .ok_or_else(|| anyhow::anyhow!("Cannot determine config directory"))?
        .join("fenix-hub");
    std::fs::create_dir_all(&dir)?;
    Ok(dir.join("profiles.json"))
}

fn read_profiles_store() -> Result<PersistedProfiles> {
    let path = profiles_path()?;
    if !path.exists() {
        return Ok(PersistedProfiles::default());
    }
    let bytes = std::fs::read(&path)?;
    let store: PersistedProfiles = serde_json::from_slice(&bytes)?;
    Ok(store)
}

fn write_profiles_store(store: &PersistedProfiles) -> Result<()> {
    let path = profiles_path()?;
    std::fs::write(&path, serde_json::to_string_pretty(store)?)?;
    Ok(())
}

fn group_id_from_key_hex(key_hex: &str) -> String {
    let Ok(bytes) = hex::decode(key_hex) else {
        return String::new();
    };
    if bytes.len() < 16 {
        return String::new();
    }
    hex::encode(&bytes[..16])
}

fn load_from_active_profile() -> Result<Option<(GroupIdentity, DeviceType)>> {
    let store = read_profiles_store()?;
    let Some(active_name) = store.active_profile.as_deref() else {
        return Ok(None);
    };
    let Some(profile) = store.profiles.get(active_name) else {
        return Ok(None);
    };
    let identity = GroupIdentity::from_key_hex(&profile.key_hex, &profile.device_name)?;
    Ok(Some((identity, profile.device_type.clone())))
}

fn sync_active_profile(identity: &GroupIdentity, device_type: &DeviceType) -> Result<()> {
    let path = profiles_path()?;
    if !path.exists() {
        return Ok(());
    }

    let mut store = read_profiles_store()?;
    let Some(active_name) = store.active_profile.clone() else {
        return Ok(());
    };
    let Some(profile) = store.profiles.get_mut(&active_name) else {
        return Ok(());
    };

    profile.key_hex = identity.key_hex();
    profile.device_name = identity.device_name.clone();
    profile.device_type = device_type.clone();
    write_profiles_store(&store)
}

pub fn save(identity: &GroupIdentity, device_type: &DeviceType) -> Result<()> {
    let data = PersistedIdentity {
        key_hex: identity.key_hex(),
        device_name: identity.device_name.clone(),
        device_type: device_type.clone(),
    };
    let path = config_path()?;
    std::fs::write(&path, serde_json::to_string_pretty(&data)?)?;
    sync_active_profile(identity, device_type)?;
    tracing::info!("Identity saved to {:?}", path);
    Ok(())
}

/// Returns `(identity, device_type)`.
pub fn load() -> Result<Option<(GroupIdentity, DeviceType)>> {
    let path = config_path()?;
    if !path.exists() {
        return load_from_active_profile();
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

pub fn delete_identity_file() -> Result<()> {
    let path = config_path()?;
    if path.exists() {
        std::fs::remove_file(&path)?;
    }
    Ok(())
}

pub fn list_profiles() -> Result<Vec<IdentityProfileInfo>> {
    let store = read_profiles_store()?;
    let mut out = Vec::with_capacity(store.profiles.len());

    for (name, profile) in &store.profiles {
        out.push(IdentityProfileInfo {
            name: name.clone(),
            device_name: profile.device_name.clone(),
            group_id: group_id_from_key_hex(&profile.key_hex),
            device_type: profile.device_type.clone(),
            active: store.active_profile.as_deref() == Some(name.as_str()),
        });
    }
    Ok(out)
}

pub fn save_profile(
    profile_name: &str,
    identity: &GroupIdentity,
    device_type: &DeviceType,
    make_active: bool,
) -> Result<()> {
    let name = profile_name.trim();
    if name.is_empty() {
        anyhow::bail!("Profile name is required");
    }

    let mut store = read_profiles_store()?;
    store.profiles.insert(
        name.to_string(),
        PersistedIdentity {
            key_hex: identity.key_hex(),
            device_name: identity.device_name.clone(),
            device_type: device_type.clone(),
        },
    );

    if make_active || store.active_profile.is_none() {
        store.active_profile = Some(name.to_string());
    }

    write_profiles_store(&store)
}

pub fn remove_profile(profile_name: &str) -> Result<()> {
    let mut store = read_profiles_store()?;
    store.profiles.remove(profile_name);

    if store.active_profile.as_deref() == Some(profile_name) {
        store.active_profile = store.profiles.keys().next().cloned();
    }

    write_profiles_store(&store)
}

pub fn activate_profile(profile_name: &str) -> Result<Option<(GroupIdentity, DeviceType)>> {
    let mut store = read_profiles_store()?;
    let Some(profile) = store.profiles.get(profile_name) else {
        return Ok(None);
    };

    let identity = GroupIdentity::from_key_hex(&profile.key_hex, &profile.device_name)?;
    let device_type = profile.device_type.clone();

    store.active_profile = Some(profile_name.to_string());
    write_profiles_store(&store)?;
    save(&identity, &device_type)?;

    Ok(Some((identity, device_type)))
}
