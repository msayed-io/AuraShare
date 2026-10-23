//! Gated stdin-controlled server used only by the Android secure v3 harness.
use aurashare_core::{start_secure, Config};
use serde_json::json;
use std::{
    io::{self, BufRead, Write},
    sync::Arc,
    time::Duration,
};
use tempfile::tempdir;
use tokio::sync::watch;

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    if std::env::var("AURASHARE_INTEGRATION_TEST").ok().as_deref() != Some("1") {
        anyhow::bail!("AURASHARE_INTEGRATION_TEST=1 is required")
    }
    let dir = tempdir()?;
    let identity = dir.path().join("secure-identity");
    let server = Arc::new(
        start_secure(
            Config {
                download_dir: dir.path().join("downloads"),
                device_name: "AuraShare CI server".into(),
                token: "ci-internal-token".into(),
            },
            identity,
        )
        .await?,
    );
    let security = server.security.as_ref().unwrap().clone();
    let qr = security
        .create_pairing_qr("192.168.1.20", security.info.tls_port)
        .await?;
    // The harness intentionally patches only the QR host to loopback. The
    // certificate and server identity remain exactly those produced by core.
    let startup = json!({
        "qr": qr.payload,
        "port": server.info.port,
        "secure_port": security.info.tls_port,
        "download_dir": dir.path().join("downloads"),
    });
    println!("{}", serde_json::to_string(&startup)?);
    io::stdout().flush()?;

    let (stop_tx, stop_rx) = watch::channel(false);
    let observer_security = security.clone();
    let observer = tokio::spawn(async move {
        let mut announced = std::collections::HashSet::new();
        loop {
            if *stop_rx.borrow() {
                break;
            }
            for peer in observer_security.peers().await {
                if peer.status == "pending" && announced.insert(peer.id.clone()) {
                    println!("{}", json!({"event":"pending"}));
                    let _ = io::stdout().flush();
                }
            }
            tokio::time::sleep(Duration::from_millis(50)).await;
        }
    });

    let stdin = io::stdin();
    for line in stdin.lock().lines() {
        let command = line?;
        match command.trim() {
            "peers" => {
                println!("{}", serde_json::to_string(&security.peers().await)?);
                io::stdout().flush()?;
            }
            "approve" | "reject" => {
                let approved = command.trim() == "approve";
                let result = security
                    .peers()
                    .await
                    .into_iter()
                    .find(|p| p.status == "pending")
                    .map(|p| (p.id, approved));
                let approved_result = match result {
                    Some((id, value)) => security.approve_pairing(&id, value).await,
                    None => false,
                };
                println!("{}", json!({"approved": approved_result}));
                io::stdout().flush()?;
            }
            "disconnect" => {
                let result = security
                    .peers()
                    .await
                    .into_iter()
                    .find(|p| p.status == "connected")
                    .map(|p| p.id);
                let disconnected = match result {
                    Some(id) => security.disconnect_peer(&id).await,
                    None => false,
                };
                println!("{}", json!({"disconnected": disconnected}));
                io::stdout().flush()?;
            }
            "quit" => break,
            _ => {}
        }
    }
    let _ = stop_tx.send(true);
    observer.abort();
    server.shutdown();
    Ok(())
}
