//! Small stdin-controlled server used only by cross-application CI.
use aurashare_core::{start, Config};
use std::io::{self, BufRead, Write};
use tempfile::tempdir;

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    if std::env::var("AURASHARE_INTEGRATION_TEST").ok().as_deref() != Some("1") {
        anyhow::bail!("AURASHARE_INTEGRATION_TEST=1 is required")
    }
    let dir = tempdir()?;
    let server = start(Config {
        download_dir: dir.path().to_path_buf(),
        device_name: "AuraShare CI server".into(),
        token: "ci-internal-token".into(),
    })
    .await?;
    println!(
        "{{\"port\":{},\"download_dir\":{:?}}}",
        server.info.port,
        dir.path()
    );
    io::stdout().flush()?;
    for line in io::stdin().lock().lines() {
        match line?.trim() {
            "approve" => {
                if let Some(p) = server
                    .state
                    .peers()
                    .await
                    .into_iter()
                    .find(|p| p.status == "pending")
                {
                    server.state.approve_pairing(&p.id, true).await;
                }
            }
            "reject" => {
                if let Some(p) = server
                    .state
                    .peers()
                    .await
                    .into_iter()
                    .find(|p| p.status == "pending")
                {
                    server.state.approve_pairing(&p.id, false).await;
                }
            }
            "disconnect" => {
                if let Some(p) = server
                    .state
                    .peers()
                    .await
                    .into_iter()
                    .find(|p| p.status == "connected")
                {
                    server.state.disconnect_peer(&p.id).await;
                }
            }
            "pause" => server.state.set_paused(true).await,
            "resume" => server.state.set_paused(false).await,
            "quit" => break,
            _ => {}
        }
    }
    server.shutdown();
    Ok(())
}
