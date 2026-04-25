/// Identity persistence.
///
/// Saves the derived group_key (NOT the passphrase) + device metadata.
/// Keyring is preferred, with identity.json as a local fallback when keyring
/// storage is unavailable or returns stale data in dev/hot-reload sessions.
use anyhow::Result;
use fenix_hub_core::identity::GroupIdentity;
use keyring::Entry;
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
    device_name: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    key_hex: Option<String>,
    /// Device type icon selector. Defaults to Desktop for old identity.json files.
    #[serde(default)]
    device_type: DeviceType,
}

#[derive(Serialize, Deserialize)]
struct PersistedProfile {
    device_name: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    key_hex: Option<String>,
    #[serde(default)]
    device_type: DeviceType,
}

#[derive(Serialize, Deserialize, Default)]
struct PersistedProfiles {
    #[serde(default)]
    active_profile: Option<String>,
    #[serde(default)]
    profiles: BTreeMap<String, PersistedProfile>,
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
    let key_hex = Entry::new("fenix-hub", &format!("profile-{}", active_name))
        .ok()
        .and_then(|entry| entry.get_password().ok())
        .or_else(|| profile.key_hex.clone());
    let Some(key_hex) = key_hex else {
        tracing::warn!(
            "Active identity profile '{}' has no usable key",
            active_name
        );
        return Ok(None);
    };
    let identity = GroupIdentity::from_key_hex(&key_hex, &profile.device_name)?;
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

    if let Ok(entry) = Entry::new("fenix-hub", &format!("profile-{}", active_name)) {
        if let Err(error) = entry.set_password(&identity.key_hex()) {
            tracing::warn!("Could not update active profile keyring entry: {}", error);
        }
    }

    profile.device_name = identity.device_name.clone();
    profile.key_hex = Some(identity.key_hex());
    profile.device_type = device_type.clone();
    write_profiles_store(&store)
}

pub fn save(identity: &GroupIdentity, device_type: &DeviceType) -> Result<()> {
    if let Ok(entry) = Entry::new("fenix-hub", "group_key") {
        if let Err(error) = entry.set_password(&identity.key_hex()) {
            tracing::warn!("Could not save identity to keyring: {}", error);
        }
    }

    let data = PersistedIdentity {
        device_name: identity.device_name.clone(),
        key_hex: Some(identity.key_hex()),
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
    let metadata = if path.exists() {
        let bytes = std::fs::read(&path)?;
        Some(serde_json::from_slice::<PersistedIdentity>(&bytes)?)
    } else {
        None
    };

    let keyring_key = Entry::new("fenix-hub", "group_key")
        .ok()
        .and_then(|entry| entry.get_password().ok());

    if let Some(key_hex) =
        keyring_key.or_else(|| metadata.as_ref().and_then(|data| data.key_hex.clone()))
    {
        let device_name = metadata
            .as_ref()
            .map(|data| data.device_name.as_str())
            .unwrap_or("Unknown");
        let device_type = metadata
            .as_ref()
            .map(|data| data.device_type.clone())
            .unwrap_or_default();
        let identity = GroupIdentity::from_key_hex(&key_hex, device_name)?;
        tracing::info!(
            "Identity loaded (device: {}, type: {:?}, metadata: {:?})",
            identity.device_name,
            device_type,
            path,
        );
        return Ok(Some((identity, device_type)));
    }

    if metadata.is_some() {
        tracing::warn!(
            "Identity metadata exists at {:?}, but no persisted group key was found",
            path,
        );
    }
    load_from_active_profile()
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
        let group_id = Entry::new("fenix-hub", &format!("profile-{}", name))
            .ok()
            .and_then(|entry| entry.get_password().ok())
            .or_else(|| profile.key_hex.clone())
            .map(|key_hex| group_id_from_key_hex(&key_hex))
            .unwrap_or_default();
        out.push(IdentityProfileInfo {
            name: name.clone(),
            device_name: profile.device_name.clone(),
            group_id,
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

    if let Ok(entry) = Entry::new("fenix-hub", &format!("profile-{}", name)) {
        if let Err(error) = entry.set_password(&identity.key_hex()) {
            tracing::warn!("Could not save profile keyring entry '{}': {}", name, error);
        }
    }

    let mut store = read_profiles_store()?;
    store.profiles.insert(
        name.to_string(),
        PersistedProfile {
            device_name: identity.device_name.clone(),
            key_hex: Some(identity.key_hex()),
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

    let key_hex = Entry::new("fenix-hub", &format!("profile-{}", profile_name))
        .ok()
        .and_then(|entry| entry.get_password().ok())
        .or_else(|| profile.key_hex.clone())
        .ok_or_else(|| anyhow::anyhow!("Profile key not found"))?;
    let identity = GroupIdentity::from_key_hex(&key_hex, &profile.device_name)?;
    let device_type = profile.device_type.clone();

    store.active_profile = Some(profile_name.to_string());
    write_profiles_store(&store)?;
    save(&identity, &device_type)?;

    Ok(Some((identity, device_type)))
}
