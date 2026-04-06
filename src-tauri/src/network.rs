use std::net::IpAddr;

/// Returns the best local IPv4 address for LAN communication.
/// Prefers the interface used to reach the default gateway (8.8.8.8 trick).
/// Respects FENIXHUB_LOCAL_IP env var override (useful when a VPN is active).
pub fn local_ipv4() -> Option<std::net::Ipv4Addr> {
    if let Ok(override_ip) = std::env::var("FENIXHUB_LOCAL_IP") {
        if let Ok(ip) = override_ip.trim().parse() {
            return Some(ip);
        }
    }
    let socket = std::net::UdpSocket::bind("0.0.0.0:0").ok()?;
    socket.connect("8.8.8.8:80").ok()?;
    match socket.local_addr().ok()?.ip() {
        IpAddr::V4(v4) => Some(v4),
        _ => None,
    }
}
