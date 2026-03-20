use anyhow::Result;
use mdns_sd::{ServiceDaemon, ServiceEvent, ServiceInfo, TxtProperty};
/// mDNS announcement and discovery — shared between all platforms.
use std::net::IpAddr;
use tokio::sync::mpsc;

use crate::daemon::DaemonEvent;
use fenix_hub_core::protocol::{Announcement, MDNS_SERVICE_TYPE};

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

/// Starts the mDNS discovery loop in a background thread.
/// Emits DaemonEvents including peer IP so callers can do HTTP pull.
pub fn start_discovery(
    mdns: ServiceDaemon,
    group_id: String,
    event_tx: mpsc::Sender<DaemonEvent>,
) -> Result<()> {
    let receiver = mdns.browse(MDNS_SERVICE_TYPE)?;

    std::thread::spawn(move || {
        while let Ok(event) = receiver.recv() {
            match event {
                ServiceEvent::ServiceResolved(info) => {
                    let peer_ip = info
                        .get_addresses()
                        .iter()
                        .find(|a| matches!(a, IpAddr::V4(v4) if !v4.is_loopback()))
                        .or_else(|| info.get_addresses().iter().next())
                        .copied();

                    let json_data = reassemble_txt(&info);
                    if let (Some(peer_ip), Some(data)) = (peer_ip, json_data.as_deref()) {
                        if let Ok(announcement) = serde_json::from_str::<Announcement>(data) {
                            if announcement.group_id != group_id {
                                continue;
                            }
                            let _ = event_tx.blocking_send(DaemonEvent::PeerContentAvailable {
                                announcement,
                                peer_ip,
                            });
                        }
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
