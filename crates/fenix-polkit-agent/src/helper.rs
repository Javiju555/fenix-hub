/// Interact with polkit-agent-helper-1 (setuid) for PAM authentication.
///
/// Protocol over stdin/stdout:
///   helper writes: "PAM_PROMPT_ECHO_OFF <prompt>\n" → agent writes password + "\n"
///   helper writes: "SUCCESS\n" | "FAILURE\n"
use anyhow::Result;
use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};
use tokio::process::Command;

const HELPER: &str = "/usr/lib/polkit-1/polkit-agent-helper-1";

pub async fn authenticate(username: &str, password: &str) -> Result<bool> {
    let mut child = Command::new(HELPER)
        .arg(username)
        .stdin(std::process::Stdio::piped())
        .stdout(std::process::Stdio::piped())
        .stderr(std::process::Stdio::null())
        .spawn()?;

    let mut stdin = child.stdin.take().expect("helper stdin");
    let stdout = child.stdout.take().expect("helper stdout");
    let mut lines = BufReader::new(stdout).lines();

    while let Some(line) = lines.next_line().await? {
        tracing::debug!("helper → {}", line);

        if line.starts_with("PAM_PROMPT_ECHO_OFF") || line.starts_with("PAM_PROMPT_ECHO_ON") {
            stdin.write_all(password.as_bytes()).await?;
            stdin.write_all(b"\n").await?;
            stdin.flush().await?;
        } else if line == "SUCCESS" {
            let _ = child.wait().await;
            return Ok(true);
        } else if line == "FAILURE" {
            let _ = child.wait().await;
            return Ok(false);
        }
        // PAM_TEXT_INFO / PAM_ERROR_MSG — just log
    }

    let status = child.wait().await?;
    Ok(status.success())
}
