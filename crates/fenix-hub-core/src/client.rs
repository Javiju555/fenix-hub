use crate::identity::GroupIdentity;
use crate::protocol::HMAC_HEADER;
/// HTTP client for pulling content from peers.
use anyhow::Result;
use std::net::IpAddr;

pub struct PulledContent {
    pub bytes: Vec<u8>,
    pub mime_type: Option<String>,
    pub file_name: Option<String>,
}

/// Pulls the raw content of a content item from a peer's ephemeral server.
pub async fn pull_content(
    peer_ip: IpAddr,
    peer_port: u16,
    content_id: &str,
    identity: &GroupIdentity,
) -> Result<PulledContent> {
    let url = format!("http://{}:{}/content/{}", peer_ip, peer_port, content_id);
    let sig = identity.sign(content_id.as_bytes());
    let sig_hex = hex::encode(&sig);

    let client = reqwest::Client::new();
    let response = client
        .get(&url)
        .header(HMAC_HEADER, sig_hex)
        .send()
        .await?
        .error_for_status()?;

    let mime_type = response
        .headers()
        .get(reqwest::header::CONTENT_TYPE)
        .and_then(|value| value.to_str().ok())
        .map(ToOwned::to_owned);
    let file_name = response
        .headers()
        .get(reqwest::header::CONTENT_DISPOSITION)
        .and_then(|value| value.to_str().ok())
        .and_then(parse_file_name);
    let bytes = response.bytes().await?;
    Ok(PulledContent {
        bytes: bytes.to_vec(),
        mime_type,
        file_name,
    })
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

fn parse_file_name(content_disposition: &str) -> Option<String> {
    content_disposition
        .split(';')
        .map(str::trim)
        .find_map(|part| {
            part.strip_prefix("filename=")
                .map(|value| value.trim_matches('"').to_string())
        })
        .filter(|value| !value.is_empty())
}
