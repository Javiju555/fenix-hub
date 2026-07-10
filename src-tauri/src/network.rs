use std::net::{IpAddr, Ipv4Addr};

/// Returns the best local IPv4 address for LAN communication.
///
/// Strategy (in order of priority):
/// 1. `FENIXHUB_LOCAL_IP` env var override
/// 2. `local-ip-address` crate (interface-based, works offline)
/// 3. Legacy fallback: UDP connect to 8.8.8.8 (works when internet is reachable)
///
/// This avoids the fragile `8.8.8.8:80` trick in offline LAN/local-hotspot scenarios
/// which is especially important on macOS where VPN/tunnel interfaces are common.
pub fn local_ipv4() -> Option<Ipv4Addr> {
    // 1. Environment override wins unconditionally (for VPN workarounds).
    if let Ok(override_ip) = std::env::var("FENIXHUB_LOCAL_IP") {
        if let Ok(ip) = override_ip.trim().parse() {
            return Some(ip);
        }
    }

    // 2. Interface enumeration via `local-ip-address` crate.
    //    Works offline, on local-only networks, and on macOS without internet.
    if let Ok(ip) = local_ip_address::local_ip() {
        if let IpAddr::V4(v4) = ip {
            if !v4.is_loopback() {
                return Some(v4);
            }
        }
    }

    // 3. Legacy fallback: UDP connect trick (only works with internet reachable).
    legacy_udp_trick()
}

/// UDP connect-to-8.8.8.8 trick — works when internet is reachable.
/// When it works it returns the interface IP from the kernel routing table.
fn legacy_udp_trick() -> Option<Ipv4Addr> {
    let socket = std::net::UdpSocket::bind("0.0.0.0:0").ok()?;
    socket.connect("8.8.8.8:80").ok()?;
    match socket.local_addr().ok()?.ip() {
        IpAddr::V4(v4) if !v4.is_loopback() => Some(v4),
        _ => None,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn local_ipv4_returns_some() {
        // On any machine with a network interface, this should return an IP.
        // Only fails on machines with no network at all.
        let ip = local_ipv4();
        assert!(ip.is_some(), "Expected a local IPv4 address");
        if let Some(ip) = ip {
            assert!(!ip.is_loopback(), "Should not return loopback");
        }
    }

    #[test]
    fn env_override_wins() {
        // Temporarily set the env var and verify it's returned.
        std::env::set_var("FENIXHUB_LOCAL_IP", "10.0.0.42");
        let ip = local_ipv4();
        assert_eq!(ip, Some(Ipv4Addr::new(10, 0, 0, 42)));
        std::env::remove_var("FENIXHUB_LOCAL_IP");
    }

    #[test]
    fn env_override_invalid_falls_back() {
        std::env::set_var("FENIXHUB_LOCAL_IP", "not-an-ip");
        let ip = local_ipv4();
        assert!(ip.is_some(), "Should fall back to interface enumeration");
        std::env::remove_var("FENIXHUB_LOCAL_IP");
    }
}
