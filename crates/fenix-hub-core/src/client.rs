/// HTTP client for pulling content from peers.

use anyhow::Result;
use std::net::IpAddr;
use crate::identity::GroupIdentity;
use crate::protocol::HMAC_HEADER;

/// Pulls the raw content of a content item from a peer's ephemeral server.
pub async fn pull_content(
    peer_ip: IpAddr,
    peer_port: u16,
    content_id: &str,
    identity: &GroupIdentity,
) -> Result<Vec<u8>> {
    let url = format!("http://{}:{}/content/{}", peer_ip, peer_port, content_id);
    let sig = identity.sign(format!("/content/{}", content_id).as_bytes());
    let sig_hex = hex::encode(&sig);

    let client = reqwest::Client::new();
    let response = client
        .get(&url)
        .header(HMAC_HEADER, sig_hex)
        .send()
        .await?
        .error_for_status()?;

    let bytes = response.bytes().await?;
    Ok(bytes.to_vec())
}

/// Sends a direct notification to a peer's daemon (for direct-send mode).
/// The peer's daemon will show a system notification and open its hub UI.
pub async fn send_direct_notify(
    peer_ip: IpAddr,
    peer_port: u16,
    message: &crate::protocol::HubMessage,
) -> Result<bool> {
    let url = format!("http://{}:{}/notify", peer_ip, peer_port);
    let client = reqwest::Client::new();
    let resp = client
        .post(&url)
        .json(message)
        .send()
        .await?
        .error_for_status()?;

    let ack: crate::protocol::HubMessage = resp.json().await?;
    if let crate::protocol::HubMessage::DirectAck { accepted } = ack {
        Ok(accepted)
    } else {
        Ok(false)
    }
}
