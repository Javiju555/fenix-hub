use anyhow::Result;
#[cfg(not(target_os = "linux"))]
use mdns_sd::ServiceEvent;
use mdns_sd::{ServiceDaemon, ServiceInfo, TxtProperty};
/// mDNS announcement and discovery — shared between all platforms.
use std::net::IpAddr;
use tokio::sync::mpsc;

use crate::daemon::DaemonEvent;
use fenix_hub_core::protocol::{Announcement, MDNS_PRESENCE_TYPE, MDNS_SERVICE_TYPE};

/// Announces a content item via mDNS. Returns instance_name for later unannounce.
const TXT_CHUNK_SIZE: usize = 240;

pub fn announce_content(
    mdns: &ServiceDaemon,
    announcement: &Announcement,
    local_ip: std::net::Ipv4Addr,
) -> Result<String> {
    let instance_name = format!("fenixhub-{}", announcement.content_id);
    let json = serde_json::to_string(announcement)?;
    let properties = json
        .as_bytes()
        .chunks(TXT_CHUNK_SIZE)
        .enumerate()
        .map(|(index, chunk)| TxtProperty::from((format!("data{}", index), chunk.to_vec())))
        .collect::<Vec<_>>();

    let service = ServiceInfo::new(
        MDNS_SERVICE_TYPE,
        &instance_name,
        &format!("{}.local.", announcement.device_name.replace(' ', "-")),
        IpAddr::V4(local_ip),
        announcement.port,
        properties,
    )?;

    mdns.register(service)?;
    tracing::info!(
        "mDNS: announced content {} ({})",
        announcement.content_id,
        announcement.preview
    );
    Ok(instance_name)
}

/// Removes a content announcement from mDNS.
pub fn unannounce_content(mdns: &ServiceDaemon, instance_name: &str) -> Result<()> {
    mdns.unregister(instance_name)?;
    tracing::info!("mDNS: removed announcement {}", instance_name);
    Ok(())
}

/// Announces device presence via a lightweight `_fenixhub-presence._tcp` beacon.
/// Returns the instance name for later unregistration.
pub fn announce_device_presence(
    mdns: &ServiceDaemon,
    device_name: &str,
    group_id: &str,
    local_ip: std::net::Ipv4Addr,
) -> Result<String> {
    let slug = device_name.replace(' ', "-").to_lowercase();
    let instance_name = format!("fenixhub-presence-{slug}");
    let host = format!("{slug}.local.");
    let properties = vec![
        TxtProperty::from(("device_name", device_name)),
        TxtProperty::from(("group_id", group_id)),
    ];
    let service = ServiceInfo::new(
        MDNS_PRESENCE_TYPE,
        &instance_name,
        &host,
        IpAddr::V4(local_ip),
        0u16,
        properties,
    )?;
    mdns.register(service)?;
    tracing::info!("mDNS: presence beacon registered for {device_name}");
    Ok(instance_name)
}

/// Starts presence discovery — emits PeerOnline / PeerOffline events.
pub fn start_presence_discovery(
    mdns: ServiceDaemon,
    group_id: String,
    event_tx: mpsc::Sender<DaemonEvent>,
) -> Result<()> {
    #[cfg(target_os = "linux")]
    {
        let _ = mdns;
        start_presence_discovery_avahi(group_id, event_tx)
    }
    #[cfg(not(target_os = "linux"))]
    {
        start_presence_discovery_mdns(mdns, group_id, event_tx)
    }
}

#[cfg(not(target_os = "linux"))]
fn start_presence_discovery_mdns(
    mdns: ServiceDaemon,
    group_id: String,
    event_tx: mpsc::Sender<DaemonEvent>,
) -> Result<()> {
    let receiver = mdns.browse(MDNS_PRESENCE_TYPE)?;
    std::thread::spawn(move || {
        while let Ok(event) = receiver.recv() {
            match event {
                ServiceEvent::ServiceResolved(info) => {
                    let props = info.get_properties();
                    let Some(device_name) =
                        props.get("device_name").map(|v| v.val_str().to_string())
                    else {
                        continue;
                    };
                    let peer_group = props.get("group_id").map(|v| v.val_str()).unwrap_or("");
                    if peer_group != group_id {
                        continue;
                    }
                    tracing::info!("mDNS: peer online: {device_name}");
                    let _ = event_tx.blocking_send(DaemonEvent::PeerOnline { device_name });
                }
                ServiceEvent::ServiceRemoved(_, fullname) => {
                    if let Some(instance) = fullname.split('.').next() {
                        if let Some(slug) = instance.strip_prefix("fenixhub-presence-") {
                            tracing::info!("mDNS: peer offline: {slug}");
                            let _ = event_tx.blocking_send(DaemonEvent::PeerOffline {
                                device_name: slug.to_string(),
                            });
                        }
                    }
                }
                _ => {}
            }
        }
    });
    Ok(())
}

/// Linux presence discovery via a second avahi-browse on `_fenixhub-presence._tcp`.
#[cfg(target_os = "linux")]
fn start_presence_discovery_avahi(
    group_id: String,
    event_tx: mpsc::Sender<DaemonEvent>,
) -> Result<()> {
    use std::io::{BufRead, BufReader};
    use std::process::{Command, Stdio};

    which_avahi_browse()?;

    let svc_type = MDNS_PRESENCE_TYPE
        .strip_suffix(".local.")
        .unwrap_or(MDNS_PRESENCE_TYPE);

    std::thread::spawn(move || {
        let mut child = match Command::new("stdbuf")
            .args([
                "-oL",
                "avahi-browse",
                "-r",
                "-p",
                "--no-db-lookup",
                svc_type,
            ])
            .stdout(Stdio::piped())
            .stderr(Stdio::null())
            .spawn()
        {
            Ok(c) => c,
            Err(e) => {
                tracing::error!("presence avahi-browse spawn failed: {e}");
                return;
            }
        };

        let reader = BufReader::new(child.stdout.take().expect("stdout piped"));
        let mut seen: std::collections::HashSet<String> = std::collections::HashSet::new();

        for line in reader.lines() {
            let Ok(line) = line else { break };
            let fields: Vec<&str> = line.splitn(10, ';').collect();
            if fields.len() < 2 {
                continue;
            }
            match fields[0] {
                "=" if fields.len() >= 9 => {
                    let instance = fields[3];
                    if fields[2] == "IPv6" {
                        continue;
                    }
                    let txt_block = if fields.len() >= 10 { fields[9] } else { "" };
                    let Some((device_name, peer_group)) = parse_presence_txt(txt_block) else {
                        continue;
                    };
                    if peer_group != group_id || seen.contains(&device_name) {
                        continue;
                    }
                    tracing::info!("avahi: peer online: {device_name}");
                    seen.insert(device_name.clone());
                    let _ = instance; // instance name tracked via seen set
                    let _ = event_tx.blocking_send(DaemonEvent::PeerOnline { device_name });
                }
                "-" if fields.len() >= 4 => {
                    let instance = fields[3];
                    if let Some(slug) = instance.strip_prefix("fenixhub-presence-") {
                        // Reconstruct device name from slug is lossy; emit slug so frontend
                        // can match on the set of known online devices.
                        let device_key = slug.to_string();
                        if seen.remove(&device_key) {
                            tracing::info!("avahi: peer offline: {device_key}");
                            let _ = event_tx.blocking_send(DaemonEvent::PeerOffline {
                                device_name: device_key,
                            });
                        }
                    }
                }
                _ => {}
            }
        }
    });

    Ok(())
}

/// Extracts (device_name, group_id) from an avahi-browse -p TXT block for presence records.
/// Format: `"device_name=Foo Bar" "group_id=<uuid>"`
#[cfg(target_os = "linux")]
fn parse_presence_txt(txt_block: &str) -> Option<(String, String)> {
    let mut device_name = None;
    let mut group_id = None;

    // Split on '"' boundaries (avahi wraps each property in quotes)
    for token in txt_block.split('"') {
        let token = token.trim();
        if let Some(v) = token.strip_prefix("device_name=") {
            device_name = Some(v.to_string());
        } else if let Some(v) = token.strip_prefix("group_id=") {
            group_id = Some(v.to_string());
        }
    }
    match (device_name, group_id) {
        (Some(dn), Some(gid)) if !dn.is_empty() && !gid.is_empty() => Some((dn, gid)),
        _ => None,
    }
}

/// Starts the mDNS discovery loop in a background thread.
/// Emits DaemonEvents including peer IP so callers can do HTTP pull.
///
/// On Linux, avahi-browse is used instead of mdns-sd because the system avahi
/// daemon holds the multicast socket exclusively, preventing mdns-sd from
/// receiving incoming announcements (though sending still works).
pub fn start_discovery(
    mdns: ServiceDaemon,
    group_id: String,
    event_tx: mpsc::Sender<DaemonEvent>,
) -> Result<()> {
    #[cfg(target_os = "linux")]
    {
        let _ = mdns; // announcing via mdns-sd still works; only browsing uses avahi
        start_discovery_avahi(group_id, event_tx)
    }
    #[cfg(not(target_os = "linux"))]
    {
        start_discovery_mdns(mdns, group_id, event_tx)
    }
}

#[cfg(not(target_os = "linux"))]
fn start_discovery_mdns(
    mdns: ServiceDaemon,
    group_id: String,
    event_tx: mpsc::Sender<DaemonEvent>,
) -> Result<()> {
    let receiver = mdns.browse(MDNS_SERVICE_TYPE)?;

    std::thread::spawn(move || {
        while let Ok(event) = receiver.recv() {
            match event {
                ServiceEvent::ServiceResolved(info) => {
                    tracing::debug!(
                        "mDNS resolved: {} addrs={:?}",
                        info.get_fullname(),
                        info.get_addresses()
                    );
                    let peer_ip = info
                        .get_addresses()
                        .iter()
                        .find(|a| matches!(a, IpAddr::V4(v4) if !v4.is_loopback()))
                        .or_else(|| info.get_addresses().iter().next())
                        .copied();

                    let json_data = reassemble_txt(&info);
                    if let (Some(peer_ip), Some(data)) = (peer_ip, json_data.as_deref()) {
                        match serde_json::from_str::<Announcement>(data) {
                            Ok(announcement) => {
                                if announcement.group_id != group_id {
                                    tracing::debug!(
                                        "mDNS: ignoring {} — group_id mismatch (got {}, want {})",
                                        announcement.device_name,
                                        &announcement.group_id[..8],
                                        &group_id[..8],
                                    );
                                    continue;
                                }
                                tracing::info!(
                                    "mDNS: accepted peer {} at {}",
                                    announcement.device_name,
                                    peer_ip
                                );
                                let _ = event_tx.blocking_send(DaemonEvent::PeerContentAvailable {
                                    announcement,
                                    peer_ip,
                                });
                            }
                            Err(e) => {
                                tracing::warn!("mDNS: failed to parse announcement JSON: {}", e);
                            }
                        }
                    } else {
                        tracing::debug!(
                            "mDNS resolved but no IP or TXT data for {}",
                            info.get_fullname()
                        );
                    }
                }
                ServiceEvent::ServiceRemoved(_, fullname) => {
                    if let Some(instance) = fullname.split('.').next() {
                        if let Some(content_id) = instance.strip_prefix("fenixhub-") {
                            let _ = event_tx.blocking_send(DaemonEvent::PeerContentGone {
                                content_id: content_id.to_string(),
                                device_name: String::new(),
                            });
                        }
                    }
                }
                _ => {}
            }
        }
    });

    Ok(())
}

/// Linux-specific discovery via avahi-browse.
///
/// Parses the `-p` (parseable) output format:
///   `=;iface;proto;name;type;domain;hostname;ip;port;"data0=..." "data1=..."`
///   `-;iface;proto;name;type;domain`
#[cfg(target_os = "linux")]
fn start_discovery_avahi(group_id: String, event_tx: mpsc::Sender<DaemonEvent>) -> Result<()> {
    use std::io::{BufRead, BufReader};
    use std::process::{Command, Stdio};

    // Verify avahi-browse is available before spawning
    which_avahi_browse()?;

    std::thread::spawn(move || {
        // stdbuf -oL forces line-buffering on avahi-browse stdout so new
        // services appear immediately instead of waiting for the 4 KB pipe buffer.
        let mut child = match Command::new("stdbuf")
            .args([
                "-oL",
                "avahi-browse",
                "-r",
                "-p",
                "--no-db-lookup",
                avahi_service_type(),
            ])
            .stdout(Stdio::piped())
            .stderr(Stdio::null())
            .spawn()
        {
            Ok(c) => c,
            Err(e) => {
                tracing::error!("avahi-browse spawn failed: {}", e);
                return;
            }
        };

        let stdout = child.stdout.take().expect("stdout piped");
        let reader = BufReader::new(stdout);
        // Track seen content_ids to deduplicate IPv4/IPv6 duplicates
        let mut seen: std::collections::HashSet<String> = std::collections::HashSet::new();

        for line in reader.lines() {
            let line = match line {
                Ok(l) => l,
                Err(_) => break,
            };

            // Split into at most 10 fields on ';' — field[9] is the TXT block
            // which may contain ';' inside JSON values (e.g. "text/plain; charset=utf-8")
            let fields: Vec<&str> = line.splitn(10, ';').collect();
            if fields.len() < 2 {
                continue;
            }

            match fields[0] {
                "=" if fields.len() >= 9 => {
                    let instance_name = fields[3];
                    let content_id = match instance_name.strip_prefix("fenixhub-") {
                        Some(id) => id.to_string(),
                        None => continue,
                    };

                    // Prefer IPv4; skip IPv6 duplicates
                    if fields[2] == "IPv6" {
                        continue;
                    }
                    if seen.contains(&content_id) {
                        continue;
                    }

                    let ip_str = fields[7];
                    let port_str = fields[8];
                    let txt_block = if fields.len() >= 10 { fields[9] } else { "" };

                    let peer_ip: IpAddr = match ip_str.parse() {
                        Ok(ip) => ip,
                        Err(_) => continue,
                    };
                    let _port: u16 = match port_str.parse() {
                        Ok(p) => p,
                        Err(_) => continue,
                    };

                    if let Some(json) = parse_avahi_txt(txt_block) {
                        let announcement = match serde_json::from_str::<Announcement>(&json) {
                            Ok(a) => a,
                            Err(e) => {
                                tracing::error!(
                                    "avahi: JSON parse failed: {} | json snippet: {}",
                                    e,
                                    &json[..json.len().min(200)]
                                );
                                continue;
                            }
                        };
                        if announcement.group_id != group_id {
                            continue;
                        }
                        tracing::info!(
                            "avahi: peer content from {} at {}",
                            announcement.device_name,
                            peer_ip
                        );
                        seen.insert(content_id);
                        let _ = event_tx.blocking_send(DaemonEvent::PeerContentAvailable {
                            announcement,
                            peer_ip,
                        });
                    }
                }
                "-" if fields.len() >= 4 => {
                    let instance_name = fields[3];
                    if let Some(content_id) = instance_name.strip_prefix("fenixhub-") {
                        tracing::info!("avahi: peer content gone {}", content_id);
                        seen.remove(content_id);
                        let _ = event_tx.blocking_send(DaemonEvent::PeerContentGone {
                            content_id: content_id.to_string(),
                            device_name: String::new(),
                        });
                    }
                }
                _ => {}
            }
        }
    });

    Ok(())
}

#[cfg(target_os = "linux")]
fn avahi_service_type() -> &'static str {
    MDNS_SERVICE_TYPE
        .strip_suffix(".local.")
        .unwrap_or(MDNS_SERVICE_TYPE)
}

/// Parses avahi-browse -p TXT block: `"data0=chunk0" "data1=chunk1" ...`
///
/// avahi escaping rules (output of avahi-browse -p):
///   `\"` → literal `"`
///   `\\` → literal `\`
///   `\DDD` (3 decimal digits) → raw byte with that value (non-ASCII UTF-8)
///
/// Returns the reassembled JSON string, or None if unparseable.
#[cfg(target_os = "linux")]
fn parse_avahi_txt(txt_block: &str) -> Option<String> {
    let mut chunks: Vec<(usize, String)> = Vec::new();
    let mut remaining = txt_block.trim();

    while !remaining.is_empty() {
        if !remaining.starts_with('"') {
            break;
        }
        remaining = &remaining[1..];

        // Decode avahi-escaped quoted value into raw bytes, then UTF-8 decode.
        // avahi emits non-ASCII chars as `\DDD` (3-digit decimal byte value).
        let mut value_bytes: Vec<u8> = Vec::new();
        let mut chars = remaining.char_indices();
        let end = loop {
            match chars.next() {
                None => break remaining.len(),
                Some((_, '\\')) => match chars.next() {
                    Some((_, '"')) => value_bytes.push(b'"'),
                    Some((_, '\\')) => value_bytes.push(b'\\'),
                    // \DDD decimal byte escape (avahi non-ASCII encoding)
                    Some((_, c)) if c.is_ascii_digit() => {
                        let d1 = c.to_digit(10).unwrap() as u32;
                        let d2 = chars.next().and_then(|(_, d)| d.to_digit(10)).unwrap_or(0);
                        let d3 = chars.next().and_then(|(_, d)| d.to_digit(10)).unwrap_or(0);
                        value_bytes.push((d1 * 100 + d2 * 10 + d3) as u8);
                    }
                    Some((_, c)) => {
                        value_bytes.push(b'\\');
                        let mut buf = [0u8; 4];
                        value_bytes.extend_from_slice(c.encode_utf8(&mut buf).as_bytes());
                    }
                    None => break remaining.len(),
                },
                Some((i, '"')) => break i + 1,
                Some((_, c)) => {
                    let mut buf = [0u8; 4];
                    value_bytes.extend_from_slice(c.encode_utf8(&mut buf).as_bytes());
                }
            }
        };
        remaining = remaining[end..].trim_start();
        let value = String::from_utf8_lossy(&value_bytes).into_owned();

        // value = "data0=json_chunk" or "data1=json_chunk"
        if let Some(rest) = value.strip_prefix("data") {
            if let Some(eq) = rest.find('=') {
                if let Ok(idx) = rest[..eq].parse::<usize>() {
                    chunks.push((idx, rest[eq + 1..].to_string()));
                }
            }
        }
    }

    if chunks.is_empty() {
        return None;
    }
    chunks.sort_by_key(|(i, _)| *i);
    Some(chunks.into_iter().map(|(_, c)| c).collect())
}

#[cfg(target_os = "linux")]
fn which_avahi_browse() -> Result<()> {
    for bin in &["avahi-browse", "stdbuf"] {
        let found = std::process::Command::new("which")
            .arg(bin)
            .output()
            .ok()
            .filter(|o| o.status.success())
            .is_some();
        if !found {
            anyhow::bail!(
                "{} not found — install {} package",
                bin,
                if *bin == "avahi-browse" {
                    "avahi"
                } else {
                    "coreutils"
                }
            );
        }
    }
    Ok(())
}

#[cfg(not(target_os = "linux"))]
fn reassemble_txt(info: &mdns_sd::ServiceInfo) -> Option<String> {
    let mut chunks = info
        .get_properties()
        .iter()
        .filter_map(|property| {
            property
                .key()
                .strip_prefix("data")
                .and_then(|index| index.parse::<usize>().ok())
                .map(|index| (index, property.val().unwrap_or_default().to_vec()))
        })
        .collect::<Vec<_>>();

    if chunks.is_empty() {
        return None;
    }

    chunks.sort_by_key(|(index, _)| *index);
    let payload = chunks
        .into_iter()
        .flat_map(|(_, chunk)| chunk)
        .collect::<Vec<u8>>();

    String::from_utf8(payload).ok()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[cfg(target_os = "linux")]
    #[test]
    fn avahi_service_type_strips_local_suffix() {
        assert_eq!(avahi_service_type(), "_fenixhub._tcp");
    }

    #[cfg(target_os = "linux")]
    #[test]
    fn parse_avahi_txt_reassembles_chunks() {
        let txt = "\"data0={\\\"preview\\\":\\\"hello \" \"data1=world\\\"}\"";
        assert_eq!(
            parse_avahi_txt(txt).as_deref(),
            Some("{\"preview\":\"hello world\"}")
        );
    }
}
