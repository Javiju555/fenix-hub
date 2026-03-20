/// D-Bus agent — registers as org.freedesktop.PolicyKit1.AuthenticationAgent.
use anyhow::{Context, Result};
use async_channel::Sender;
use std::collections::HashMap;
use std::ops::Deref;
use tokio::sync::oneshot;
use zbus::{interface, Connection};
use zbus::zvariant::{OwnedValue, Value};

pub struct AuthRequest {
    pub message: String,
    pub user_name: String,
    pub cookie: String,
    pub user_uid: u32,
    pub response_tx: oneshot::Sender<Option<String>>,
}

// Safety: oneshot::Sender<Option<String>> is Send.
unsafe impl Send for AuthRequest {}

struct AuthAgent {
    tx: Sender<AuthRequest>,
    connection: Connection,
}

#[interface(name = "org.freedesktop.PolicyKit1.AuthenticationAgent")]
impl AuthAgent {
    async fn begin_authentication(
        &self,
        _action_id: String,
        message: String,
        _icon_name: String,
        _details: HashMap<String, String>,
        cookie: String,
        identities: Vec<(String, HashMap<String, OwnedValue>)>,
    ) {
        let (user_name, user_uid) = extract_identity(&identities);
        let (resp_tx, resp_rx) = oneshot::channel();

        if self
            .tx
            .send_blocking(AuthRequest {
                message,
                user_name: user_name.clone(),
                cookie: cookie.clone(),
                user_uid,
                response_tx: resp_tx,
            })
            .is_err()
        {
            return;
        }

        let password = match resp_rx.await {
            Ok(Some(p)) => p,
            _ => return,
        };

        match crate::helper::authenticate(&user_name, &password).await {
            Ok(true) => {
                if let Err(e) = self.respond(&cookie, user_uid).await {
                    tracing::error!("AuthenticationAgentResponse2 failed: {e}");
                }
            }
            Ok(false) => tracing::warn!("Authentication failed for {user_name}"),
            Err(e) => tracing::error!("Helper error: {e}"),
        }
    }

    async fn cancel_authentication(&self, _cookie: String) {}
}

impl AuthAgent {
    async fn respond(&self, cookie: &str, uid: u32) -> Result<()> {
        let uid_val: OwnedValue = Value::from(uid).try_into()?;
        let identity: (String, HashMap<String, OwnedValue>) = (
            "unix-user".to_string(),
            HashMap::from([("uid".to_string(), uid_val)]),
        );

        let authority = zbus::Proxy::new(
            &self.connection,
            "org.freedesktop.PolicyKit1",
            "/org/freedesktop/PolicyKit1/Authority",
            "org.freedesktop.PolicyKit1.Authority",
        )
        .await?;

        authority
            .call_method("AuthenticationAgentResponse2", &(cookie, uid, &identity))
            .await
            .context("AuthenticationAgentResponse2")?;

        tracing::info!("Polkit auth succeeded");
        Ok(())
    }
}

pub async fn run(tx: Sender<AuthRequest>) -> Result<()> {
    let connection = Connection::session().await?;
    let session_id = session_id();

    let agent = AuthAgent { tx, connection: connection.clone() };
    let object_path = "/org/fenix/PolicyKit1/AuthenticationAgent";
    connection.object_server().at(object_path, agent).await?;

    let session_val: OwnedValue = Value::from(session_id.as_str()).try_into()?;
    let subject: (String, HashMap<String, OwnedValue>) = (
        "unix-session".to_string(),
        HashMap::from([("session-id".to_string(), session_val)]),
    );
    let locale = std::env::var("LANG").unwrap_or_else(|_| "en_US.UTF-8".to_string());

    let authority = zbus::Proxy::new(
        &connection,
        "org.freedesktop.PolicyKit1",
        "/org/freedesktop/PolicyKit1/Authority",
        "org.freedesktop.PolicyKit1.Authority",
    )
    .await?;

    authority
        .call_method(
            "RegisterAuthenticationAgent",
            &(&subject, locale.as_str(), object_path),
        )
        .await
        .context("RegisterAuthenticationAgent failed — is another polkit agent running?")?;

    tracing::info!("fenix-polkit-agent registered (session {session_id})");
    std::future::pending::<()>().await;
    Ok(())
}

fn session_id() -> String {
    if let Ok(id) = std::env::var("XDG_SESSION_ID") {
        if !id.is_empty() {
            return id;
        }
    }
    let uid = current_uid();
    if let Ok(out) = std::process::Command::new("loginctl")
        .args(["list-sessions", "--no-legend"])
        .output()
    {
        if let Ok(s) = std::str::from_utf8(&out.stdout) {
            for line in s.lines() {
                let parts: Vec<&str> = line.split_whitespace().collect();
                if parts.len() >= 2 {
                    if let Ok(u) = parts[1].parse::<u32>() {
                        if u == uid {
                            return parts[0].to_string();
                        }
                    }
                }
            }
        }
    }
    "1".to_string()
}

fn current_uid() -> u32 {
    #[cfg(unix)]
    unsafe {
        extern "C" { fn getuid() -> u32; }
        getuid()
    }
    #[cfg(not(unix))]
    0
}

fn extract_identity(identities: &[(String, HashMap<String, OwnedValue>)]) -> (String, u32) {
    for (kind, details) in identities {
        if kind == "unix-user" {
            if let Some(uid_val) = details.get("uid") {
                if let Value::U32(uid) = uid_val.deref() {
                    return (username_from_uid(*uid), *uid);
                }
            }
        }
    }
    let uid = current_uid();
    let name = std::env::var("USER").unwrap_or_else(|_| "user".to_string());
    (name, uid)
}

fn username_from_uid(uid: u32) -> String {
    std::fs::read_to_string("/etc/passwd")
        .ok()
        .and_then(|s| {
            s.lines()
                .find(|l| l.split(':').nth(2).and_then(|u| u.parse::<u32>().ok()) == Some(uid))
                .and_then(|l| l.split(':').next())
                .map(str::to_string)
        })
        .unwrap_or_else(|| std::env::var("USER").unwrap_or_else(|_| format!("uid:{uid}")))
}
