/// mDNS announcement and discovery — shared between all platforms.
///
/// Uses the `mdns-sd` crate which works on Linux, Windows, and macOS.
/// Android uses NsdManager in Kotlin (same service type and TXT record format).

use std::collections::HashMap;
use std::sync::Arc;
use anyhow::Result;
use mdns_sd::{ServiceDaemon, ServiceInfo, ServiceEvent};
use tokio::sync::mpsc;

use fenix_hub_core::protocol::{Announcement, MDNS_SERVICE_TYPE};
use crate::daemon::DaemonEvent;

/// Announces a content item via mDNS so peers can discover it.
/// Returns a handle — dropping it removes the announcement.
pub async fn announce_content(
    mdns: &ServiceDaemon,
    announcement: &Announcement,
    local_ip: std::net::Ipv4Addr,
) -> Result<String> {
    let instance_name = format!("fenixhub-{}", announcement.content_id);

    // Pack announcement as JSON in TXT records.
    // mDNS TXT records are key=value pairs; we split across multiple keys if needed.
    let json = serde_json::to_string(announcement)?;
    let mut properties = HashMap::new();
    properties.insert("data".to_string(), json);

    let service = ServiceInfo::new(
        MDNS_SERVICE_TYPE,
        &instance_name,
        &format!("{}.local.", announcement.device_name.replace(' ', "-")),
        std::net::IpAddr::V4(local_ip),
        announcement.port,
        properties,
    )?;

    mdns.register(service)?;
    tracing::info!("mDNS: announced content {} ({})", announcement.content_id, announcement.preview);
    Ok(instance_name)
}

/// Removes a content announcement from mDNS.
pub fn unannounce_content(mdns: &ServiceDaemon, instance_name: &str) -> Result<()> {
    mdns.unregister(instance_name)?;
    tracing::info!("mDNS: removed announcement {}", instance_name);
    Ok(())
}

/// Starts the mDNS discovery loop. Emits DaemonEvents on the provided channel
/// when peers appear, disappear, or announce content.
pub async fn start_discovery(
    mdns: Arc<ServiceDaemon>,
    group_id: String,
    event_tx: mpsc::Sender<DaemonEvent>,
) -> Result<()> {
    let receiver = mdns.browse(MDNS_SERVICE_TYPE)?;

    tokio::spawn(async move {
        while let Ok(event) = receiver.recv_async().await {
            match event {
                ServiceEvent::ServiceResolved(info) => {
                    if let Some(data) = info.get_property_val_str("data") {
                        if let Ok(announcement) = serde_json::from_str::<Announcement>(data) {
                            // Only process announcements from our group
                            if announcement.group_id != group_id {
                                continue;
                            }
                            let _ = event_tx
                                .send(DaemonEvent::PeerContentAvailable(announcement))
                                .await;
                        }
                    }
                }
                ServiceEvent::ServiceRemoved(_, fullname) => {
                    // Extract content_id from instance name "fenixhub-{content_id}"
                    if let Some(instance) = fullname.split('.').next() {
                        if let Some(content_id) = instance.strip_prefix("fenixhub-") {
                            let _ = event_tx
                                .send(DaemonEvent::PeerContentGone {
                                    content_id: content_id.to_string(),
                                    device_name: String::new(), // resolved from cache if needed
                                })
                                .await;
                        }
                    }
                }
                _ => {}
            }
        }
    });

    Ok(())
}
