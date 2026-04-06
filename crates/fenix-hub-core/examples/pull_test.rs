/// Quick pull test — reads identity from AppData and pulls a specific content item.
/// Usage: cargo run -p fenix-hub-core --example pull_test <ip> <port> <content_id>
use fenix_hub_core::client::pull_content;
use fenix_hub_core::identity::GroupIdentity;
use std::net::IpAddr;

#[tokio::main]
async fn main() {
    let args: Vec<String> = std::env::args().collect();
    let peer_ip: IpAddr = args
        .get(1)
        .expect("usage: pull_test <ip> <port> <content_id>")
        .parse()
        .expect("invalid IP");
    let peer_port: u16 = args
        .get(2)
        .expect("missing port")
        .parse()
        .expect("invalid port");
    let content_id = args.get(3).expect("missing content_id").as_str();

    // Read identity from AppData
    let identity_path = std::env::var("APPDATA")
        .map(|d| {
            std::path::PathBuf::from(d)
                .join("fenix-hub")
                .join("identity.json")
        })
        .unwrap_or_else(|_| std::path::PathBuf::from("identity.json"));

    let data: serde_json::Value =
        serde_json::from_slice(&std::fs::read(&identity_path).expect("identity.json not found"))
            .expect("invalid JSON");

    let key_hex = data["key_hex"].as_str().expect("key_hex missing");
    let identity = GroupIdentity::from_key_hex(key_hex, "pull-test").expect("bad key");

    println!("Pulling {} from {}:{} …", content_id, peer_ip, peer_port);

    match pull_content(peer_ip, peer_port, content_id, &identity).await {
        Ok(item) => {
            let text = String::from_utf8_lossy(&item.bytes);
            println!("✓ OK ({} bytes)", item.bytes.len());
            println!("  mime:      {:?}", item.mime_type);
            println!("  file_name: {:?}", item.file_name);
            println!("  content:   {}", &text[..text.len().min(200)]);
        }
        Err(e) => {
            eprintln!("✗ FAIL: {}", e);
        }
    }
}
