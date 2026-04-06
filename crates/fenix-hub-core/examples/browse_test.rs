/// Quick mDNS browse — finds announced FenixHub content on the LAN.
use fenix_hub_core::protocol::{Announcement, MDNS_SERVICE_TYPE};
use mdns_sd::{ServiceDaemon, ServiceEvent};

fn main() {
    let mdns = ServiceDaemon::new().expect("mDNS daemon failed");

    // Restrict to LAN interface (same logic as the app)
    if let Some(lan_ip) = local_ipv4() {
        mdns.disable_interface(mdns_sd::IfKind::IPv4).ok();
        mdns.disable_interface(mdns_sd::IfKind::IPv6).ok();
        mdns.enable_interface(std::net::IpAddr::V4(lan_ip)).ok();
        eprintln!("Browsing on interface {}", lan_ip);
    }

    let receiver = mdns.browse(MDNS_SERVICE_TYPE).expect("browse failed");
    eprintln!("Browsing for {} — waiting 5s…\n", MDNS_SERVICE_TYPE);

    let deadline = std::time::Instant::now() + std::time::Duration::from_secs(5);
    while std::time::Instant::now() < deadline {
        if let Ok(event) = receiver.recv_timeout(std::time::Duration::from_millis(200)) {
            if let ServiceEvent::ServiceResolved(info) = event {
                let json: String = {
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
                    chunks.sort_by_key(|(i, _)| *i);
                    String::from_utf8(chunks.into_iter().flat_map(|(_, c)| c).collect())
                        .unwrap_or_default()
                };
                if let Ok(ann) = serde_json::from_str::<Announcement>(&json) {
                    let ip = info
                        .get_addresses()
                        .iter()
                        .find(|a| matches!(a, std::net::IpAddr::V4(_)))
                        .copied()
                        .unwrap_or_else(|| *info.get_addresses().iter().next().unwrap());
                    println!("FOUND");
                    println!("  device:     {}", ann.device_name);
                    println!("  content_id: {}", ann.content_id);
                    println!(
                        "  preview:    {}",
                        &ann.preview[..ann.preview.len().min(80)]
                    );
                    println!("  ip:port:    {}:{}", ip, ann.port);
                }
            }
        }
    }
    eprintln!("Done.");
}

fn local_ipv4() -> Option<std::net::Ipv4Addr> {
    let s = std::net::UdpSocket::bind("0.0.0.0:0").ok()?;
    s.connect("8.8.8.8:80").ok()?;
    match s.local_addr().ok()?.ip() {
        std::net::IpAddr::V4(v4) => Some(v4),
        _ => None,
    }
}
