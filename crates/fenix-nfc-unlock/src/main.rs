/// fenix-nfc-unlock — NFC-triggered unlock daemon for Fenix Desktop
///
/// Flow:
///   1. Polls /sys/.../rf_detected (kernel NTAG I2C driver)
///   2. On trigger: finds phone peer via mDNS (same FenixHub group)
///   3. Sends HMAC challenge over HTTP to phone's auth endpoint
///   4. Verifies response → calls loginctl unlock-sessions
///
/// Config: ~/.config/fenix/nfc-unlock.toml
use anyhow::{bail, Context, Result};
use fenix_hub_core::{identity::GroupIdentity, protocol::MDNS_SERVICE_TYPE};
use mdns_sd::{ServiceDaemon, ServiceEvent};
use rand::RngCore;
use serde::{Deserialize, Serialize};
use std::{
    net::IpAddr,
    sync::{Arc, Mutex},
    time::Duration,
};
use tokio::{process::Command, time::sleep};

const SYSFS_RF_DETECTED: &str = "/sys/bus/i2c/devices/i2c-NTAG0001:00/rf_detected";
const CONFIG_PATH: &str = ".config/fenix/nfc-unlock.toml";

#[derive(Deserialize)]
struct Config {
    /// Full 32-byte group key as hex — copy from fenix-hub config
    group_key_hex: String,
    device_name: String,
    /// Port where the phone's FenixHub auth server listens (default: 47891)
    #[serde(default = "default_phone_port")]
    phone_auth_port: u16,
}

fn default_phone_port() -> u16 {
    47891
}

#[derive(Serialize)]
struct ChallengeRequest {
    nonce: String,
}

#[derive(Deserialize)]
struct ChallengeResponse {
    hmac: String,
}

/// Maintains the last known phone IP discovered via mDNS.
type PeerCache = Arc<Mutex<Option<IpAddr>>>;

fn read_rf_detected() -> Result<bool> {
    let raw = std::fs::read_to_string(SYSFS_RF_DETECTED)
        .context("Cannot read rf_detected sysfs — is ntag_i2c module loaded?")?;
    Ok(raw.trim() == "1")
}

fn start_mdns_discovery(group_id: String, cache: PeerCache) -> Result<()> {
    let mdns = ServiceDaemon::new()?;
    let receiver = mdns.browse(MDNS_SERVICE_TYPE)?;

    std::thread::spawn(move || {
        while let Ok(event) = receiver.recv() {
            if let ServiceEvent::ServiceResolved(info) = event {
                // Extract group_id from TXT to filter foreign groups
                let txt_json = reassemble_txt(&info);
                let peer_group_id = txt_json
                    .as_deref()
                    .and_then(|s| serde_json::from_str::<serde_json::Value>(s).ok())
                    .and_then(|v| v["group_id"].as_str().map(str::to_string));

                if peer_group_id.as_deref() != Some(&group_id) {
                    continue;
                }

                let peer_ip = info
                    .get_addresses()
                    .iter()
                    .find(|a| matches!(a, IpAddr::V4(v4) if !v4.is_loopback()))
                    .or_else(|| info.get_addresses().iter().next())
                    .copied();

                if let Some(ip) = peer_ip {
                    tracing::info!("mDNS: phone peer discovered at {}", ip);
                    *cache.lock().unwrap() = Some(ip);
                }
            }
        }
    });

    Ok(())
}

fn reassemble_txt(info: &mdns_sd::ServiceInfo) -> Option<String> {
    let mut chunks: Vec<(usize, Vec<u8>)> = info
        .get_properties()
        .iter()
        .filter_map(|p| {
            p.key()
                .strip_prefix("data")
                .and_then(|i| i.parse::<usize>().ok())
                .map(|i| (i, p.val().unwrap_or_default().to_vec()))
        })
        .collect();
    if chunks.is_empty() {
        return None;
    }
    chunks.sort_by_key(|(i, _)| *i);
    String::from_utf8(chunks.into_iter().flat_map(|(_, c)| c).collect()).ok()
}

async fn authenticate(identity: &GroupIdentity, phone_ip: IpAddr, phone_port: u16) -> Result<bool> {
    // Generate random 32-byte nonce
    let mut nonce_bytes = [0u8; 32];
    rand::thread_rng().fill_bytes(&mut nonce_bytes);
    let nonce_hex = hex::encode(nonce_bytes);

    // Header: HMAC(group_key, "auth/challenge:" + nonce_hex)
    let header_msg = format!("auth/challenge:{}", nonce_hex);
    let our_sig = hex::encode(identity.sign(header_msg.as_bytes()));

    let url = format!("http://{}:{}/auth/challenge", phone_ip, phone_port);
    let client = reqwest::Client::builder()
        .timeout(Duration::from_secs(5))
        .build()?;

    let resp = client
        .post(&url)
        .header("X-FenixHub-Auth", our_sig)
        .json(&ChallengeRequest {
            nonce: nonce_hex.clone(),
        })
        .send()
        .await
        .context("No response from phone — is FenixHub running?")?;

    if !resp.status().is_success() {
        bail!("Phone rejected challenge: HTTP {}", resp.status());
    }

    let body: ChallengeResponse = resp.json().await?;
    let their_hmac = hex::decode(&body.hmac).context("Invalid HMAC hex from phone")?;

    // Response: HMAC(group_key, nonce_hex)
    Ok(identity.verify(nonce_hex.as_bytes(), &their_hmac))
}

async fn unlock_session() -> Result<()> {
    Command::new("loginctl")
        .arg("unlock-sessions")
        .status()
        .await
        .context("Failed to run loginctl")?;
    Ok(())
}

#[tokio::main]
async fn main() -> Result<()> {
    tracing_subscriber::fmt::init();

    let config_path = dirs::home_dir()
        .context("Cannot find home dir")?
        .join(CONFIG_PATH);

    let config: Config = toml::from_str(
        &std::fs::read_to_string(&config_path)
            .with_context(|| format!("Config not found at {}", config_path.display()))?,
    )?;

    let identity = GroupIdentity::from_key_hex(&config.group_key_hex, &config.device_name)?;
    tracing::info!(
        "fenix-nfc-unlock started. Device: {}. Group: {}",
        config.device_name,
        identity.group_id()
    );

    let peer_cache: PeerCache = Arc::new(Mutex::new(None));
    start_mdns_discovery(identity.group_id(), Arc::clone(&peer_cache))?;

    // Cooldown: avoid re-triggering immediately after an unlock
    let mut cooldown = false;

    loop {
        sleep(Duration::from_millis(200)).await;

        if cooldown {
            cooldown = false;
            sleep(Duration::from_secs(3)).await;
            continue;
        }

        let triggered = match read_rf_detected() {
            Ok(v) => v,
            Err(e) => {
                tracing::error!("{}", e);
                sleep(Duration::from_secs(5)).await;
                continue;
            }
        };

        if !triggered {
            continue;
        }

        tracing::info!("NFC trigger detected");

        let phone_ip = match *peer_cache.lock().unwrap() {
            Some(ip) => ip,
            None => {
                tracing::warn!("No phone peer discovered yet — is FenixHub open on the phone?");
                continue;
            }
        };

        match authenticate(&identity, phone_ip, config.phone_auth_port).await {
            Ok(true) => {
                tracing::info!("Auth OK — unlocking session");
                if let Err(e) = unlock_session().await {
                    tracing::error!("Unlock failed: {}", e);
                }
                cooldown = true;
            }
            Ok(false) => {
                tracing::warn!("Auth failed — HMAC mismatch (wrong device or wrong group)");
            }
            Err(e) => {
                tracing::warn!("Auth error: {}", e);
            }
        }
    }
}
