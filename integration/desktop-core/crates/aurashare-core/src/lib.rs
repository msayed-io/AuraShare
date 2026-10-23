//! Offline LAN receiving core for AuraShare.
use anyhow::{anyhow, Context, Result};
use axum::{
    body::Body,
    extract::{ConnectInfo, Path as AxumPath, Request, State},
    http::{header, HeaderMap, StatusCode},
    response::{IntoResponse, Response},
    routing::{get, post},
    Json, Router,
};
use futures_util::StreamExt;
use if_addrs::get_if_addrs;
use mdns_sd::{IfKind, ServiceDaemon, ServiceInfo};
use percent_encoding::percent_decode_str;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::{
    collections::{HashMap, HashSet, VecDeque},
    net::{IpAddr, Ipv4Addr, SocketAddr, UdpSocket},
    path::{Path, PathBuf},
    sync::Arc,
    time::{Duration, Instant},
};
use tokio::{
    fs::{self, OpenOptions},
    io::{AsyncWriteExt, BufWriter},
    net::TcpListener,
    sync::{broadcast, Mutex, RwLock, Semaphore},
    task::JoinHandle,
};
use tokio_util::sync::CancellationToken;
use uuid::Uuid;

pub mod protected_store;
pub mod secure;
pub use secure::start_secure;

const MAX_SIZE: u64 = 1 << 40;
const MAX_TERMINAL_EVENTS: usize = 100;
const MAX_ACTIVE_EVENTS: usize = 4;
const IDLE: Duration = Duration::from_secs(30);
const PAIRING_TTL: Duration = Duration::from_secs(120);
const SESSION_TTL: Duration = Duration::from_secs(45);
const MAX_PENDING: usize = 16;
const ANNOUNCE_TTL: Duration = Duration::from_secs(10);
const MAX_ANNOUNCE_BODY: usize = 256 * 1024;
const MAX_ANNOUNCEMENTS: usize = 64;
const MAX_METADATA_TEXT: usize = 512;
const MAX_THUMB_BYTES: usize = 100 * 1024;
const MAX_TERMINAL_PAIRINGS: usize = 16;

#[derive(Clone)]
pub struct Config {
    pub download_dir: PathBuf,
    pub device_name: String,
    pub token: String,
}
#[derive(Debug, Clone, Serialize)]
pub struct ServerInfo {
    pub device_name: String,
    pub port: u16,
    pub addresses: Vec<String>,
    pub download_dir: String,
    #[serde(skip_serializing)]
    pub token: String,
    pub mdns_active: bool,
    pub mdns_error: Option<String>,
    pub secure_port: Option<u16>,
}
#[derive(Debug, Clone, Serialize)]
pub struct ConnectedDevice {
    pub id: String,
    pub device_name: Option<String>,
    pub remote_address: String,
}
#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
pub struct Peer {
    pub id: String,
    pub device_name: Option<String>,
    pub remote_address: String,
    pub status: String,
    #[serde(default)]
    pub secure: bool,
}
#[derive(Debug, Deserialize)]
struct PairRequestBody {
    client_id: String,
    device_name: String,
    client_secret: String,
}
#[derive(Clone)]
struct Pairing {
    client_id: String,
    device_name: String,
    secret: String,
    ip: IpAddr,
    created: Instant,
    status: String,
    token: Option<String>,
    finished: Option<Instant>,
}
#[derive(Clone)]
struct Session {
    peer_id: String,
    ip: IpAddr,
    last_seen: Instant,
}
#[derive(Clone)]
struct Announcement {
    id: Uuid,
    edge: String,
    name: String,
    size: u64,
    mime: String,
    kind: String,
    thumb: Option<String>,
    ip: IpAddr,
    credential_hash: [u8; 32],
    created: Instant,
}

#[derive(Debug, Clone, Serialize)]
pub enum ReceiveVisualEvent {
    Start {
        id: String,
        edge: String,
        name: String,
        size: u64,
        kind: String,
        thumb: Option<String>,
        mime: String,
    },
    Done {
        id: String,
    },
    Cancel {
        id: String,
    },
}

#[derive(Debug, Clone, Serialize)]
pub struct TransferEvent {
    pub id: String,
    pub name: String,
    pub received: u64,
    pub total: Option<u64>,
    pub bytes_per_second: f64,
    pub status: String,
    pub error: Option<String>,
    pub sha256: Option<String>,
    pub path: Option<String>,
}
#[derive(Default)]
struct StateInner {
    events: VecDeque<TransferEvent>,
    paused: bool,
    legacy_enabled: bool,
    connected_devices: HashMap<String, ConnectedDevice>,
    pairings: HashMap<String, Pairing>,
    sessions: HashMap<String, Session>,
    announcements: HashMap<Uuid, Announcement>,
    active_visuals: HashSet<Uuid>,
}
pub struct SharedState {
    inner: RwLock<StateInner>,
    device_events: broadcast::Sender<Vec<ConnectedDevice>>,
    peer_events: broadcast::Sender<Vec<Peer>>,
    visual_events: broadcast::Sender<ReceiveVisualEvent>,
}
impl Default for SharedState {
    fn default() -> Self {
        let (device_events, _) = broadcast::channel(64);
        let (peer_events, _) = broadcast::channel(64);
        let (visual_events, _) = broadcast::channel(128);
        Self {
            inner: RwLock::new(StateInner::default()),
            device_events,
            peer_events,
            visual_events,
        }
    }
}
impl SharedState {
    pub async fn snapshot(&self) -> Vec<TransferEvent> {
        self.inner.read().await.events.iter().cloned().collect()
    }
    pub async fn connected_devices(&self) -> Vec<ConnectedDevice> {
        self.inner
            .read()
            .await
            .connected_devices
            .values()
            .cloned()
            .collect()
    }
    fn device_events(&self) -> broadcast::Sender<Vec<ConnectedDevice>> {
        self.device_events.clone()
    }
    pub fn peer_events(&self) -> broadcast::Sender<Vec<Peer>> {
        self.peer_events.clone()
    }
    pub fn visual_events(&self) -> broadcast::Sender<ReceiveVisualEvent> {
        self.visual_events.clone()
    }
    async fn peers_locked(inner: &StateInner) -> Vec<Peer> {
        inner
            .pairings
            .iter()
            .filter_map(|(id, p)| match p.status.as_str() {
                "pending" => Some(Peer {
                    id: id.clone(),
                    device_name: Some(p.device_name.clone()),
                    remote_address: p.ip.to_string(),
                    status: "pending".into(),
                    secure: false,
                }),
                "approved" if inner.sessions.values().any(|s| s.peer_id == *id) => Some(Peer {
                    id: id.clone(),
                    device_name: Some(p.device_name.clone()),
                    remote_address: p.ip.to_string(),
                    status: "connected".into(),
                    secure: false,
                }),
                "approved" => None,
                _ => None,
            })
            .collect()
    }
    pub async fn peers(&self) -> Vec<Peer> {
        let mut i = self.inner.write().await;
        expire_state(&mut i);
        Self::peers_locked(&i).await
    }
    pub async fn approve_pairing(&self, id: &str, approved: bool) -> bool {
        let mut i = self.inner.write().await;
        expire_state(&mut i);
        if approved
            && i.pairings
                .values()
                .filter(|p| p.status == "approved")
                .count()
                >= MAX_PENDING
        {
            return false;
        }
        let Some(p) = i.pairings.get_mut(id) else {
            return false;
        };
        if p.status != "pending" {
            return false;
        }
        p.status = if approved {
            "approved".into()
        } else {
            "rejected".into()
        };
        // For approval this is the initial-heartbeat deadline anchor; for a
        // terminal state it is the retention timestamp.
        p.finished = Some(Instant::now());
        if approved {
            p.token = Some(random_token());
        }
        let snap = Self::peers_locked(&i).await;
        let _ = self.peer_events.send(snap);
        true
    }
    pub async fn disconnect_peer(&self, id: &str) -> bool {
        let mut i = self.inner.write().await;
        let Some(p) = i.pairings.get_mut(id) else {
            return false;
        };
        if p.status != "approved" {
            return false;
        }
        p.status = "rejected".into();
        p.token = None;
        p.finished = Some(Instant::now());
        i.sessions.retain(|_, s| s.peer_id != id);
        let _ = self.peer_events.send(Self::peers_locked(&i).await);
        true
    }
    async fn add_device(&self, device: ConnectedDevice) {
        let mut inner = self.inner.write().await;
        inner.connected_devices.insert(device.id.clone(), device);
        let snapshot = inner.connected_devices.values().cloned().collect();
        // Publish while serialized with state changes so concurrent requests cannot
        // broadcast an older snapshot after a newer disconnect snapshot.
        let _ = self.device_events.send(snapshot);
    }
    async fn remove_device(&self, id: &str) {
        let mut inner = self.inner.write().await;
        inner.connected_devices.remove(id);
        let snapshot = inner.connected_devices.values().cloned().collect();
        let _ = self.device_events.send(snapshot);
    }
    pub async fn clear_history(&self) {
        self.inner
            .write()
            .await
            .events
            .retain(|e| e.status == "receiving");
    }
    pub async fn set_paused(&self, paused: bool) {
        self.inner.write().await.paused = paused;
    }
    pub async fn is_paused(&self) -> bool {
        self.inner.read().await.paused
    }
    pub async fn set_legacy_enabled(&self, enabled: bool) {
        self.inner.write().await.legacy_enabled = enabled;
    }
    pub async fn legacy_enabled(&self) -> bool {
        self.inner.read().await.legacy_enabled
    }
    async fn push(&self, event: TransferEvent) {
        let mut s = self.inner.write().await;
        if let Some(old) = s.events.iter_mut().find(|old| old.id == event.id) {
            *old = event;
        } else {
            s.events.push_back(event);
        }
        while s.events.iter().filter(|e| e.status != "receiving").count() > MAX_TERMINAL_EVENTS {
            if let Some(i) = s.events.iter().position(|e| e.status != "receiving") {
                s.events.remove(i);
            } else {
                break;
            }
        }
        while s.events.iter().filter(|e| e.status == "receiving").count() > MAX_ACTIVE_EVENTS {
            if let Some(i) = s.events.iter().position(|e| e.status == "receiving") {
                s.events.remove(i);
            } else {
                break;
            }
        }
    }
}
fn expire_state(i: &mut StateInner) {
    expire_state_at(i, Instant::now());
}

fn expire_state_at(i: &mut StateInner, now: Instant) {
    for p in i.pairings.values_mut() {
        if p.status == "pending" && now.duration_since(p.created) > PAIRING_TTL {
            p.status = "expired".into();
            p.finished = Some(now);
        }
        if p.status == "approved" {
            let last_seen = p
                .token
                .as_ref()
                .and_then(|token| i.sessions.get(token))
                .map(|s| s.last_seen)
                .unwrap_or(p.finished.unwrap_or(p.created));
            if now.duration_since(last_seen) > SESSION_TTL {
                p.status = "expired".into();
                p.token = None;
                p.finished = Some(now);
            }
        }
    }
    i.sessions.retain(|_, s| {
        now.duration_since(s.last_seen) <= SESSION_TTL
            && i.pairings
                .get(&s.peer_id)
                .is_some_and(|p| p.status == "approved")
    });
    // Keep terminal records long enough for a client to observe 410, but never
    // let rejected/expired requests grow without bound.
    let terminal = i
        .pairings
        .values()
        .filter(|p| p.status != "pending" && p.status != "approved")
        .count();
    if terminal > MAX_TERMINAL_PAIRINGS {
        let mut ids: Vec<_> = i
            .pairings
            .iter()
            .filter(|(_, p)| p.status != "pending" && p.status != "approved")
            .map(|(id, p)| (id.clone(), p.finished.unwrap_or(p.created)))
            .collect();
        ids.sort_by_key(|(_, at)| *at);
        for (id, _) in ids.into_iter().take(terminal - MAX_TERMINAL_PAIRINGS) {
            i.pairings.remove(&id);
        }
    }
}

fn random_token() -> String {
    format!("{}{}", Uuid::new_v4().simple(), Uuid::new_v4().simple())
}
fn valid_hex_secret(s: &str) -> bool {
    s.len() == 64 && s.bytes().all(|b| b.is_ascii_hexdigit())
}
fn valid_client_id(s: &str) -> bool {
    !s.is_empty() && s.len() <= 80 && s.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'-')
}
fn origin_rejected(req: &Request) -> bool {
    req.headers().contains_key(header::ORIGIN)
}

#[derive(Clone)]
struct AppState {
    config: Config,
    shared: Arc<SharedState>,
    events: broadcast::Sender<TransferEvent>,
    slots: Arc<Semaphore>,
    cancel: CancellationToken,
    server_id: String,
    secure_auth: Option<Arc<secure::SecureService>>,
    secure_hint: Arc<RwLock<Option<secure::SecureInfo>>>,
    transfer_session: Option<(String, SocketAddr)>,
}
impl AppState {
    async fn authorized(&self, token: &str, remote: SocketAddr) -> bool {
        if let Some(security) = &self.secure_auth {
            return security.authorized(token, remote, false).await;
        }
        constant_time_eq(token.as_bytes(), self.config.token.as_bytes())
            || self
                .shared
                .session_for_token(token, remote.ip(), false)
                .await
    }
    async fn transfer_authorized(&self) -> bool {
        match (&self.secure_auth, &self.transfer_session) {
            (Some(security), Some((token, remote))) => {
                security.authorized(token, *remote, false).await
            }
            (Some(_), None) => false,
            (None, _) => true,
        }
    }
}
pub struct RunningServer {
    pub info: ServerInfo,
    pub state: Arc<SharedState>,
    pub events: broadcast::Sender<TransferEvent>,
    pub device_events: broadcast::Sender<Vec<ConnectedDevice>>,
    pub visual_events: broadcast::Sender<ReceiveVisualEvent>,
    cancel: CancellationToken,
    server_task: Mutex<Option<JoinHandle<()>>>,
    mdns: Mutex<Option<ServiceDaemon>>,
    mdns_fullname: Option<String>,
    /// The isolated TLS v3 authority. Legacy HTTP never consults this object.
    pub security: Option<Arc<secure::SecureService>>,
    secure_server_task: Option<JoinHandle<()>>,
    http_state: Arc<AppState>,
}
impl RunningServer {
    pub(crate) fn attach_secure(
        &mut self,
        service: Arc<secure::SecureService>,
        port: u16,
        task: JoinHandle<()>,
    ) {
        self.info.secure_port = Some(port);
        self.security = Some(service);
        self.secure_server_task = Some(task);
    }
    pub fn shutdown(&self) {
        self.cancel.cancel();
        if let Ok(mut guard) = self.mdns.try_lock() {
            if let Some(d) = guard.take() {
                if let Some(name) = &self.mdns_fullname {
                    let _ = d.unregister(name);
                }
                let _ = d.shutdown();
            }
        }
    }
}
impl Drop for RunningServer {
    fn drop(&mut self) {
        self.cancel.cancel();
        let _ = self.server_task.get_mut().take();
        if let Some(task) = self.secure_server_task.take() {
            task.abort();
        }
        if let Some(d) = self.mdns.get_mut().take() {
            if let Some(name) = &self.mdns_fullname {
                let _ = d.unregister(name);
            }
            let _ = d.shutdown();
        }
    }
}

pub async fn start(config: Config) -> Result<RunningServer> {
    if config.token.is_empty() {
        return Err(anyhow!("token must not be empty"));
    }
    fs::create_dir_all(&config.download_dir)
        .await
        .context("create download directory")?;
    let listener = TcpListener::bind((Ipv4Addr::UNSPECIFIED, 0))
        .await
        .context("bind receiver")?;
    let port = listener.local_addr()?.port();
    let addresses = local_ipv4_addresses();
    let cancel = CancellationToken::new();
    let shared = Arc::new(SharedState::default());
    let (events, _) = broadcast::channel(256);
    let http_state = Arc::new(AppState {
        config: config.clone(),
        shared: shared.clone(),
        events: events.clone(),
        slots: Arc::new(Semaphore::new(4)),
        cancel: cancel.clone(),
        server_id: random_token(),
        secure_auth: None,
        secure_hint: Arc::new(RwLock::new(None)),
        transfer_session: None,
    });
    let app = Router::new()
        .route("/health", get(health))
        .route("/upload", post(upload))
        .route("/api/transfer/announce", post(announce))
        .route("/api/v1/upload", post(upload))
        .route("/api/upload", post(legacy_upload))
        .route("/api/handshake", get(handshake))
        .route("/api/pair/request", post(pair_request))
        .route("/api/pair/status/{request_id}", get(pair_status))
        .route("/api/session/heartbeat", post(heartbeat))
        .route("/api/session/disconnect", post(disconnect))
        .route("/ping", get(health))
        .with_state(http_state.clone());
    let (mdns, mdns_active, mdns_error, mdns_fullname) =
        advertise(&config.device_name, port, &addresses);
    let info = ServerInfo {
        device_name: config.device_name.clone(),
        port,
        addresses,
        download_dir: config.download_dir.display().to_string(),
        token: config.token.clone(),
        mdns_active,
        mdns_error,
        secure_port: None,
    };
    let task_cancel = cancel.clone();
    let expiry_cancel = cancel.clone();
    let expiry_shared = shared.clone();
    tokio::spawn(async move {
        let mut tick = tokio::time::interval(Duration::from_millis(100));
        loop {
            tokio::select! {
                _ = expiry_cancel.cancelled() => break,
                _ = tick.tick() => {
                    let mut inner = expiry_shared.inner.write().await;
                    let before = SharedState::peers_locked(&inner).await;
                    expire_state(&mut inner);
                    let after = SharedState::peers_locked(&inner).await;
                    drop(inner);
                    if before != after { let _ = expiry_shared.peer_events.send(after); }
                }
            }
        }
    });
    let task = tokio::spawn(async move {
        let _ = axum::serve(
            listener,
            app.into_make_service_with_connect_info::<SocketAddr>(),
        )
        .with_graceful_shutdown(task_cancel.cancelled_owned())
        .await;
    });
    Ok(RunningServer {
        info,
        state: shared.clone(),
        events,
        cancel,
        device_events: shared.device_events(),
        visual_events: shared.visual_events(),
        server_task: Mutex::new(Some(task)),
        mdns: Mutex::new(mdns),
        mdns_fullname,
        security: None,
        secure_server_task: None,
        http_state,
    })
}
fn secure_transfer_router(server: &RunningServer, security: Arc<secure::SecureService>) -> Router {
    let mut state = (*server.http_state).clone();
    state.secure_auth = Some(security);
    Router::new()
        .route("/upload", post(upload))
        .route("/api/transfer/announce", post(announce))
        .with_state(Arc::new(state))
}
/// Returns whether an IPv4 address is a usable unicast endpoint for a phone.
/// This deliberately accepts non-private unicast addresses on Windows; the
/// frozen Android 1.0.28 parser remains LAN-only and is not changed here.
pub fn is_valid_pairing_ipv4(ip: Ipv4Addr) -> bool {
    let octets = ip.octets();
    !ip.is_loopback()
        && !ip.is_unspecified()
        && !ip.is_multicast()
        && octets[0] != 0
        && octets[0] < 240
        && ip != Ipv4Addr::BROADCAST
}

fn pairing_class(ip: Ipv4Addr) -> u8 {
    if ip.is_private() {
        0
    } else if !ip.is_link_local() {
        1
    } else {
        2
    }
}

fn select_pairing_ipv4(
    route: Option<Ipv4Addr>,
    addresses: impl IntoIterator<Item = Ipv4Addr>,
) -> Option<Ipv4Addr> {
    if route.is_some_and(is_valid_pairing_ipv4) {
        return route;
    }
    addresses
        .into_iter()
        .filter(|ip| is_valid_pairing_ipv4(*ip))
        .min_by_key(|ip| (pairing_class(*ip), ip.octets()))
}

/// Re-enumerate interfaces for each QR request; never use ServerInfo's startup snapshot.
pub fn fresh_pairing_ipv4() -> Option<Ipv4Addr> {
    let route = UdpSocket::bind((Ipv4Addr::UNSPECIFIED, 0))
        .ok()
        .and_then(|socket| {
            socket.connect((Ipv4Addr::new(198, 51, 100, 1), 9)).ok()?;
            match socket.local_addr().ok()?.ip() {
                IpAddr::V4(ip) => Some(ip),
                _ => None,
            }
        });
    let addresses = get_if_addrs()
        .ok()
        .into_iter()
        .flatten()
        .filter_map(|item| match item.ip() {
            IpAddr::V4(ip) => Some(ip),
            _ => None,
        });
    select_pairing_ipv4(route, addresses)
}

fn local_ipv4_addresses() -> Vec<String> {
    let mut out = Vec::new();
    if let Ok(items) = get_if_addrs() {
        for i in items {
            if let IpAddr::V4(v4) = i.ip() {
                if !v4.is_loopback() && !v4.is_unspecified() {
                    out.push(v4.to_string());
                }
            }
        }
    }
    out.sort();
    out.dedup();
    if out.is_empty() {
        out.push("127.0.0.1".into());
    }
    out
}
#[cfg(test)]
mod address_tests {
    use super::*;
    #[test]
    fn pairing_predicate_filters_non_unicast_reserved_ranges() {
        for ip in [
            Ipv4Addr::new(0, 1, 2, 3),
            Ipv4Addr::LOCALHOST,
            Ipv4Addr::UNSPECIFIED,
            Ipv4Addr::BROADCAST,
            Ipv4Addr::new(224, 0, 0, 1),
            Ipv4Addr::new(240, 0, 0, 1),
        ] {
            assert!(!is_valid_pairing_ipv4(ip));
        }
        for ip in [
            Ipv4Addr::new(192, 168, 1, 2),
            Ipv4Addr::new(100, 64, 1, 2),
            Ipv4Addr::new(8, 8, 8, 8),
            Ipv4Addr::new(169, 254, 1, 2),
        ] {
            assert!(is_valid_pairing_ipv4(ip));
        }
    }
    #[test]
    fn repeated_selection_uses_only_current_addresses_including_after_disconnect() {
        let first = select_pairing_ipv4(None, [Ipv4Addr::new(192, 168, 1, 4)]);
        let changed = select_pairing_ipv4(None, [Ipv4Addr::new(10, 0, 0, 20)]);
        let public_only = select_pairing_ipv4(None, [Ipv4Addr::new(203, 0, 113, 20)]);
        assert_eq!(first, Some(Ipv4Addr::new(192, 168, 1, 4)));
        assert_eq!(changed, Some(Ipv4Addr::new(10, 0, 0, 20)));
        assert_ne!(first, changed);
        assert_eq!(public_only, Some(Ipv4Addr::new(203, 0, 113, 20)));
        assert_eq!(select_pairing_ipv4(None, []), None);
    }
    #[test]
    fn pairing_selection_prefers_private_then_public_then_linklocal_and_ignores_stale_route() {
        let selected = select_pairing_ipv4(
            Some(Ipv4Addr::LOCALHOST),
            [
                Ipv4Addr::new(169, 254, 1, 2),
                Ipv4Addr::new(8, 8, 8, 8),
                Ipv4Addr::new(192, 168, 1, 4),
            ],
        );
        assert_eq!(selected, Some(Ipv4Addr::new(192, 168, 1, 4)));
        assert_eq!(
            select_pairing_ipv4(
                Some(Ipv4Addr::new(100, 64, 1, 2)),
                [Ipv4Addr::new(192, 168, 1, 4)]
            ),
            Some(Ipv4Addr::new(100, 64, 1, 2))
        );
    }
}
fn advertise(
    name: &str,
    port: u16,
    addresses: &[String],
) -> (Option<ServiceDaemon>, bool, Option<String>, Option<String>) {
    let daemon = match ServiceDaemon::new() {
        Ok(d) => d,
        Err(e) => return (None, false, Some(e.to_string()), None),
    };
    let instance = format!(
        "{}-{}",
        sanitize_dns(name),
        &Uuid::new_v4().to_string()[..8]
    );
    let fullname = format!("{}._aurashare._tcp.local.", instance);
    let host = format!("{}.local.", instance);
    let addr = if addresses.is_empty() {
        "127.0.0.1".to_string()
    } else {
        addresses.join(",")
    };
    let properties = [("version", "2")];
    let _ = daemon.disable_interface(IfKind::IPv6);
    match ServiceInfo::new(
        "_aurashare._tcp.local.",
        &instance,
        &host,
        addr,
        port,
        &properties[..],
    ) {
        Ok(service) => match daemon.register(service.enable_addr_auto()) {
            Ok(_) => (Some(daemon), true, None, Some(fullname)),
            Err(e) => {
                let _ = daemon.shutdown();
                (None, false, Some(e.to_string()), None)
            }
        },
        Err(e) => {
            let _ = daemon.shutdown();
            (None, false, Some(e.to_string()), None)
        }
    }
}
fn sanitize_dns(s: &str) -> String {
    let mut x: String = s
        .chars()
        .map(|c| {
            if c.is_ascii_alphanumeric() || c == '-' {
                c
            } else {
                '-'
            }
        })
        .collect();
    if x.is_empty() {
        x = "AuraShare".into();
    }
    x.chars().take(50).collect()
}
async fn health() -> impl IntoResponse {
    Json(serde_json::json!({"service":"AuraShare","version":"1"}))
}
#[derive(Debug, Deserialize)]
struct AnnounceBody {
    id: Uuid,
    direction: Option<String>,
    name: String,
    size: u64,
    mime: String,
    kind: String,
    thumb: Option<String>,
}
fn credential_hash(token: &str) -> [u8; 32] {
    let mut h = Sha256::new();
    h.update(token.as_bytes());
    h.finalize().into()
}
fn valid_kind(k: &str) -> bool {
    matches!(k, "image" | "video" | "other")
}
fn valid_mime(m: &str) -> bool {
    !m.is_empty()
        && m.len() <= 128
        && m.bytes()
            .all(|b| b.is_ascii_graphic() && b != b'"' && b != b'\\')
}
fn decode_b64(s: &str) -> Option<Vec<u8>> {
    let mut out = Vec::with_capacity(s.len() * 3 / 4);
    let mut acc = 0u32;
    let mut bits = 0u8;
    for b in s.bytes() {
        if b == b'=' {
            break;
        }
        let v = match b {
            b'A'..=b'Z' => b - b'A',
            b'a'..=b'z' => b - b'a' + 26,
            b'0'..=b'9' => b - b'0' + 52,
            b'+' => 62,
            b'/' => 63,
            _ => return None,
        };
        acc = (acc << 6) | v as u32;
        bits += 6;
        if bits >= 8 {
            bits -= 8;
            out.push((acc >> bits) as u8);
        }
    }
    Some(out)
}
fn thumb_data_url(raw: Option<&str>) -> Result<Option<String>> {
    let Some(data) = raw else { return Ok(None) };
    if data.len() > MAX_THUMB_BYTES * 2 {
        return Err(anyhow!("thumb too large"));
    }
    let bytes = decode_b64(data).ok_or_else(|| anyhow!("invalid thumb base64"))?;
    if bytes.len() > MAX_THUMB_BYTES
        || bytes.len() < 4
        || bytes[0] != 0xff
        || bytes[1] != 0xd8
        || bytes[bytes.len() - 2] != 0xff
        || bytes[bytes.len() - 1] != 0xd9
    {
        return Err(anyhow!("thumb is not a JPEG"));
    }
    Ok(Some(format!("data:image/jpeg;base64,{data}")))
}

async fn announce(
    ConnectInfo(remote): ConnectInfo<SocketAddr>,
    State(app): State<Arc<AppState>>,
    req: Request,
) -> Response {
    if origin_rejected(&req) {
        return error(StatusCode::FORBIDDEN, "browser origins are not accepted");
    }
    let token = req
        .headers()
        .get("x-aurashare-token")
        .and_then(|v| v.to_str().ok())
        .unwrap_or("")
        .to_owned();
    if !app.authorized(&token, remote).await {
        return error(StatusCode::UNAUTHORIZED, "invalid token");
    }
    let body = match axum::body::to_bytes(req.into_body(), MAX_ANNOUNCE_BODY).await {
        Ok(b) => b,
        Err(_) => return error(StatusCode::PAYLOAD_TOO_LARGE, "announcement too large"),
    };
    let input: AnnounceBody = match serde_json::from_slice(&body) {
        Ok(v) => v,
        Err(_) => return error(StatusCode::BAD_REQUEST, "invalid announcement"),
    };
    if input.name.len() > MAX_METADATA_TEXT
        || input
            .name
            .chars()
            .any(|c| c.is_control() || is_bidi_control(c))
        || input.size > MAX_SIZE
        || !valid_kind(&input.kind)
        || !valid_mime(&input.mime)
    {
        return error(StatusCode::BAD_REQUEST, "invalid announcement metadata");
    };
    if app.secure_auth.is_some()
        && !matches!(input.direction.as_deref(), Some("top" | "left" | "right"))
    {
        return error(
            StatusCode::BAD_REQUEST,
            "secure announcement requires a valid direction",
        );
    }
    let edge = match input.direction.as_deref() {
        Some("top") => "top".into(),
        Some("left") => "left".into(),
        Some("right") => "right".into(),
        None => {
            eprintln!("warning: missing announcement direction; using top");
            "top".into()
        }
        Some(other) => {
            eprintln!("warning: invalid announcement direction {other:?}; using top");
            "top".into()
        }
    };
    let thumb = match thumb_data_url(input.thumb.as_deref()) {
        Ok(v) => v,
        Err(e) => {
            eprintln!("warning: ignoring malformed announcement thumbnail: {e}");
            None
        }
    };
    let a = Announcement {
        id: input.id,
        edge,
        name: input.name,
        size: input.size,
        mime: input.mime,
        kind: input.kind,
        thumb,
        ip: remote.ip(),
        credential_hash: credential_hash(&token),
        created: Instant::now(),
    };
    let mut inner = app.shared.inner.write().await;
    let duplicate = inner.announcements.contains_key(&a.id);
    if !duplicate && inner.active_visuals.contains(&a.id) {
        // A retry must not replace the visual lifecycle of an already streaming body.
        return Json(serde_json::json!({"status":"accepted"})).into_response();
    }
    if let Some(old) = inner.announcements.get(&a.id) {
        if old.ip != a.ip || !constant_time_eq(&old.credential_hash, &a.credential_hash) {
            return error(StatusCode::CONFLICT, "transfer id collision");
        }
    }
    let id = a.id;
    let evicted = if !duplicate && inner.announcements.len() >= MAX_ANNOUNCEMENTS {
        inner
            .announcements
            .iter()
            .min_by_key(|(_, a)| a.created)
            .map(|(id, _)| *id)
    } else {
        None
    };
    if let Some(old_id) = evicted {
        inner.announcements.remove(&old_id);
        inner.active_visuals.remove(&old_id);
    }
    inner.announcements.insert(id, a.clone());
    if !duplicate {
        inner.active_visuals.insert(id);
    }
    drop(inner);
    if let Some(old_id) = evicted {
        let _ = app.shared.visual_events.send(ReceiveVisualEvent::Cancel {
            id: old_id.to_string(),
        });
    }
    if !duplicate {
        let _ = app.shared.visual_events.send(ReceiveVisualEvent::Start {
            id: id.to_string(),
            edge: a.edge.clone(),
            name: a.name.clone(),
            size: a.size,
            kind: a.kind.clone(),
            thumb: a.thumb.clone(),
            mime: a.mime.clone(),
        });
        let shared = app.shared.clone();
        tokio::spawn(async move {
            tokio::time::sleep(ANNOUNCE_TTL).await;
            let removed = {
                let mut inner = shared.inner.write().await;
                let removed = inner.announcements.remove(&id).is_some();
                if removed {
                    inner.active_visuals.remove(&id);
                }
                removed
            };
            if removed {
                let _ = shared
                    .visual_events
                    .send(ReceiveVisualEvent::Cancel { id: id.to_string() });
            }
        });
    }
    Json(serde_json::json!({"status":"accepted"})).into_response()
}

struct VisualGuard {
    shared: Arc<SharedState>,
    id: Uuid,
    finished: bool,
}
impl VisualGuard {
    fn new(shared: Arc<SharedState>, id: Uuid, started: bool, event: ReceiveVisualEvent) -> Self {
        if started {
            let _ = shared.visual_events.send(event);
        }
        Self {
            shared,
            id,
            finished: false,
        }
    }
    async fn done(mut self) {
        let _ = self.shared.visual_events.send(ReceiveVisualEvent::Done {
            id: self.id.to_string(),
        });
        self.finished = true;
        self.remove().await;
    }
    async fn cancel(mut self) {
        let _ = self.shared.visual_events.send(ReceiveVisualEvent::Cancel {
            id: self.id.to_string(),
        });
        self.finished = true;
        self.remove().await;
    }
    async fn remove(&self) {
        self.shared
            .inner
            .write()
            .await
            .active_visuals
            .remove(&self.id);
    }
}
impl Drop for VisualGuard {
    fn drop(&mut self) {
        if !self.finished {
            let shared = self.shared.clone();
            let id = self.id;
            tokio::spawn(async move {
                let _ = shared
                    .visual_events
                    .send(ReceiveVisualEvent::Cancel { id: id.to_string() });
                shared.inner.write().await.active_visuals.remove(&id);
            });
        }
    }
}

async fn upload(
    ConnectInfo(remote): ConnectInfo<SocketAddr>,
    State(app): State<Arc<AppState>>,
    req: Request,
) -> Response {
    upload_request(app, req, false, remote).await
}
async fn legacy_upload(
    ConnectInfo(remote): ConnectInfo<SocketAddr>,
    State(app): State<Arc<AppState>>,
    req: Request,
) -> Response {
    upload_request(app, req, true, remote).await
}
async fn upload_request(
    app: Arc<AppState>,
    req: Request,
    legacy: bool,
    remote: SocketAddr,
) -> Response {
    if req.headers().contains_key(header::ORIGIN) {
        return error(StatusCode::FORBIDDEN, "browser origins are not accepted");
    }
    if !legacy || !app.shared.legacy_enabled().await {
        let supplied = req
            .headers()
            .get("x-aurashare-token")
            .and_then(|v| v.to_str().ok())
            .unwrap_or("");
        if !app.authorized(supplied, remote).await {
            return error(StatusCode::UNAUTHORIZED, "invalid token");
        }
    }
    if app.shared.is_paused().await {
        return error(StatusCode::LOCKED, "receiving is paused");
    }
    let permit = match app.slots.try_acquire() {
        Ok(p) => p,
        Err(_) => {
            return error(
                StatusCode::TOO_MANY_REQUESTS,
                "maximum concurrent uploads reached",
            )
        }
    };
    let headers = req.headers().clone();
    let name = match file_name_mode(&headers, legacy) {
        Ok(n) => n,
        Err(e) => return error(StatusCode::BAD_REQUEST, &e.to_string()),
    };
    let declared = match optional_u64(&headers, "x-file-size") {
        Ok(v) => v,
        Err(e) => return error(StatusCode::BAD_REQUEST, &e.to_string()),
    };
    let content_length = match optional_u64(&headers, "content-length") {
        Ok(v) => v,
        Err(e) => return error(StatusCode::BAD_REQUEST, &e.to_string()),
    };
    if let (Some(a), Some(b)) = (declared, content_length) {
        if a != b {
            return error(
                StatusCode::BAD_REQUEST,
                "X-File-Size does not match Content-Length",
            );
        }
    }
    let total = declared.or(content_length);
    if total.is_some_and(|n| n > MAX_SIZE) {
        return error(StatusCode::PAYLOAD_TOO_LARGE, "file exceeds 1 TiB");
    }
    let expected = match headers.get("x-file-sha256").and_then(|v| v.to_str().ok()) {
        Some(v) if v.len() == 64 && v.bytes().all(|b| b.is_ascii_hexdigit()) => {
            Some(v.to_ascii_lowercase())
        }
        Some(_) => return error(StatusCode::BAD_REQUEST, "invalid SHA-256 header"),
        None => None,
    };
    let token = headers
        .get("x-aurashare-token")
        .and_then(|v| v.to_str().ok())
        .unwrap_or("");
    let requested = headers
        .get("x-transfer-id")
        .and_then(|v| v.to_str().ok())
        .and_then(|v| Uuid::parse_str(v).ok());
    let (visual_id, visual, started) = {
        let mut inner = app.shared.inner.write().await;
        let matched = requested.and_then(|id| {
            let a = inner.announcements.get(&id)?;
            (a.ip == remote.ip()
                && constant_time_eq(&a.credential_hash, &credential_hash(token))
                && inner.active_visuals.contains(&id))
            .then_some(id)
        });
        if let Some(id) = matched {
            let a = inner.announcements.remove(&id).unwrap();
            (
                id,
                ReceiveVisualEvent::Start {
                    id: id.to_string(),
                    edge: a.edge,
                    name: a.name,
                    size: a.size,
                    kind: a.kind,
                    thumb: a.thumb,
                    mime: a.mime,
                },
                false,
            )
        } else {
            if requested.is_some() {
                eprintln!("warning: unknown, expired, duplicate, or mismatched X-Transfer-Id; using fresh visual id");
            }
            let edge = match headers.get("x-direction").and_then(|v| v.to_str().ok()) {
                Some("left") => "left",
                Some("right") => "right",
                Some("top") => "top",
                None => {
                    eprintln!("warning: missing X-Direction; using top");
                    "top"
                }
                Some(x) => {
                    eprintln!("warning: invalid X-Direction {x:?}; using top");
                    "top"
                }
            };
            let id = Uuid::new_v4();
            inner.active_visuals.insert(id);
            (
                id,
                ReceiveVisualEvent::Start {
                    id: id.to_string(),
                    edge: edge.into(),
                    name: name.clone(),
                    size: total.unwrap_or(0),
                    kind: "other".into(),
                    thumb: None,
                    mime: "".into(),
                },
                true,
            )
        }
    };
    let visual_guard = VisualGuard::new(app.shared.clone(), visual_id, started, visual);

    let device = ConnectedDevice {
        id: Uuid::new_v4().to_string(),
        device_name: device_name(&headers),
        remote_address: remote.to_string(),
    };
    let device_guard = DeviceGuard::new(app.shared.clone(), device).await;
    let transfer_app = if app.secure_auth.is_some() {
        let mut state = (*app).clone();
        state.transfer_session = Some((token.to_owned(), remote));
        Arc::new(state)
    } else {
        app.clone()
    };
    let result = receive(
        transfer_app,
        device_guard.id().to_string(),
        name,
        total,
        expected,
        req.into_body(),
    )
    .await;
    device_guard.finish().await;
    drop(permit);
    match result {
        Ok(event) => {
            visual_guard.done().await;
            Json(event).into_response()
        }
        Err((status, msg)) => {
            visual_guard.cancel().await;
            error(status, &msg)
        }
    }
}
fn device_name(headers: &HeaderMap) -> Option<String> {
    let raw = headers.get("x-device-name")?.to_str().ok()?;
    let decoded = percent_decode_str(raw)
        .decode_utf8()
        .ok()?
        .trim()
        .to_owned();
    if decoded.is_empty()
        || decoded.chars().count() > 80
        || decoded
            .chars()
            .any(|c| c.is_control() || is_bidi_control(c))
    {
        return None;
    }
    Some(decoded)
}
fn is_bidi_control(c: char) -> bool {
    matches!(c, '\u{061c}' | '\u{200e}'..='\u{200f}' | '\u{202a}'..='\u{202e}' | '\u{2066}'..='\u{2069}' | '\u{206b}'..='\u{206f}')
}
struct DeviceGuard {
    shared: Arc<SharedState>,
    id: String,
    active: bool,
}
impl DeviceGuard {
    async fn new(shared: Arc<SharedState>, device: ConnectedDevice) -> Self {
        let id = device.id.clone();
        shared.add_device(device).await;
        Self {
            shared,
            id,
            active: true,
        }
    }
    fn id(&self) -> &str {
        &self.id
    }
    async fn finish(mut self) {
        if self.active {
            self.shared.remove_device(&self.id).await;
            self.active = false;
        }
    }
}
impl Drop for DeviceGuard {
    fn drop(&mut self) {
        if self.active {
            let shared = self.shared.clone();
            let id = self.id.clone();
            tokio::spawn(async move {
                shared.remove_device(&id).await;
            });
        }
    }
}

fn constant_time_eq(a: &[u8], b: &[u8]) -> bool {
    if a.len() != b.len() {
        return false;
    }
    let mut diff = 0usize;
    for (x, y) in a.iter().zip(b) {
        diff |= (*x ^ *y) as usize;
    }
    diff == 0
}
fn error(status: StatusCode, message: &str) -> Response {
    (status, Json(serde_json::json!({"error": message}))).into_response()
}
fn optional_u64(headers: &HeaderMap, key: &str) -> Result<Option<u64>> {
    headers
        .get(key)
        .map(|v| {
            v.to_str()
                .context("invalid numeric header")
                .and_then(|s| s.parse::<u64>().context("invalid numeric header"))
        })
        .transpose()
}
fn file_name_mode(headers: &HeaderMap, legacy: bool) -> Result<String> {
    let raw = headers
        .get("x-file-name")
        .ok_or_else(|| anyhow!("missing X-File-Name"))?
        .to_str()
        .context("invalid filename header")?;
    let raw = if legacy {
        raw.replace('+', " ")
    } else {
        raw.to_owned()
    };
    let decoded = percent_decode_str(&raw)
        .decode_utf8()
        .context("filename is not UTF-8")?
        .into_owned();
    validate_name(&decoded)?;
    Ok(decoded)
}
async fn handshake(State(app): State<Arc<AppState>>, req: Request) -> Response {
    if origin_rejected(&req) {
        return error(StatusCode::FORBIDDEN, "browser origins are not accepted");
    }
    let mut body = serde_json::json!({"server":"AuraShare","hostname":app.config.device_name.clone(),"protocol":2,"pairing_method":"approval","requires_pairing":true,"server_id":app.server_id});
    if let Some(hint) = &*app.secure_hint.read().await {
        // Routing hints are untrusted on HTTP. Android still requires the QR-pinned TLS key.
        body["secure_server_id"] = hint.server_id.clone().into();
        body["secure_port"] = hint.tls_port.into();
    }
    Json(body).into_response()
}
impl SharedState {
    async fn session_for_token(&self, token: &str, ip: IpAddr, establish: bool) -> bool {
        let mut i = self.inner.write().await;
        expire_state(&mut i);
        if let Some(s) = i.sessions.get_mut(token) {
            if s.ip == ip {
                s.last_seen = Instant::now();
                return true;
            }
            return false;
        }
        if establish {
            let Some((peer_id, _p)) = i
                .pairings
                .iter()
                .find(|(_, p)| {
                    p.status == "approved" && p.ip == ip && p.token.as_deref() == Some(token)
                })
                .map(|(id, p)| (id.clone(), p.clone()))
            else {
                return false;
            };
            i.sessions.insert(
                token.to_owned(),
                Session {
                    peer_id,
                    ip,
                    last_seen: Instant::now(),
                },
            );
            let _ = self.peer_events.send(Self::peers_locked(&i).await);
            return true;
        }
        false
    }
    async fn pairing_status(&self, id: &str, secret: &str, ip: IpAddr) -> Option<String> {
        let mut i = self.inner.write().await;
        expire_state(&mut i);
        let p = i.pairings.get(id)?;
        if p.ip != ip || !constant_time_eq(p.secret.as_bytes(), secret.as_bytes()) {
            return None;
        }
        Some(p.status.clone())
    }
}

async fn pair_request(
    ConnectInfo(remote): ConnectInfo<SocketAddr>,
    State(app): State<Arc<AppState>>,
    req: Request,
) -> Response {
    if origin_rejected(&req) {
        return error(StatusCode::FORBIDDEN, "browser origins are not accepted");
    }
    let body = match axum::body::to_bytes(req.into_body(), 8192).await {
        Ok(b) => b,
        Err(_) => return error(StatusCode::BAD_REQUEST, "invalid request"),
    };
    let input: PairRequestBody = match serde_json::from_slice(&body) {
        Ok(v) => v,
        Err(_) => return error(StatusCode::BAD_REQUEST, "invalid request"),
    };
    if !valid_client_id(&input.client_id)
        || !valid_hex_secret(&input.client_secret)
        || input.device_name.trim().is_empty()
        || input.device_name.chars().count() > 80
        || input
            .device_name
            .chars()
            .any(|c| c.is_control() || is_bidi_control(c))
    {
        return error(StatusCode::BAD_REQUEST, "invalid pairing request");
    }
    let mut i = app.shared.inner.write().await;
    expire_state(&mut i);
    if let Some((id, _)) = i.pairings.iter().find(|(_, p)| {
        p.client_id == input.client_id
            && p.secret == input.client_secret
            && p.ip == remote.ip()
            && p.status == "pending"
    }) {
        return (
            StatusCode::ACCEPTED,
            Json(serde_json::json!({"request_id":id,"status":"pending"})),
        )
            .into_response();
    }
    if i.pairings
        .values()
        .filter(|p| p.status == "pending")
        .count()
        >= MAX_PENDING
    {
        return error(StatusCode::TOO_MANY_REQUESTS, "too many pending pairings");
    }
    let id = Uuid::new_v4().to_string();
    i.pairings.insert(
        id.clone(),
        Pairing {
            client_id: input.client_id,
            device_name: input.device_name.trim().to_owned(),
            secret: input.client_secret,
            ip: remote.ip(),
            created: Instant::now(),
            status: "pending".into(),
            token: None,
            finished: None,
        },
    );
    let _ = app
        .shared
        .peer_events
        .send(SharedState::peers_locked(&i).await);
    (
        StatusCode::ACCEPTED,
        Json(serde_json::json!({"request_id":id,"status":"pending"})),
    )
        .into_response()
}
async fn pair_status(
    ConnectInfo(remote): ConnectInfo<SocketAddr>,
    State(app): State<Arc<AppState>>,
    AxumPath(id): AxumPath<String>,
    req: Request,
) -> Response {
    if origin_rejected(&req) {
        return error(StatusCode::FORBIDDEN, "browser origins are not accepted");
    }
    let secret = req
        .headers()
        .get("x-aurashare-pair-secret")
        .and_then(|v| v.to_str().ok())
        .unwrap_or("");
    let Some(status) = app.shared.pairing_status(&id, secret, remote.ip()).await else {
        return error(StatusCode::UNAUTHORIZED, "invalid pairing credentials");
    };
    let code = if status == "rejected" || status == "expired" {
        StatusCode::GONE
    } else {
        StatusCode::OK
    };
    let mut v = serde_json::json!({"status":status,"device_name":app.config.device_name,"server_id":app.server_id});
    if status == "approved" {
        let i = app.shared.inner.read().await;
        if let Some(token) = i.pairings.get(&id).and_then(|p| p.token.clone()) {
            v["token"] = serde_json::json!(token);
        }
    }
    (code, Json(v)).into_response()
}
async fn heartbeat(
    ConnectInfo(remote): ConnectInfo<SocketAddr>,
    State(app): State<Arc<AppState>>,
    req: Request,
) -> Response {
    if origin_rejected(&req) {
        return error(StatusCode::FORBIDDEN, "browser origins are not accepted");
    }
    let t = req
        .headers()
        .get("x-aurashare-token")
        .and_then(|v| v.to_str().ok())
        .unwrap_or("");
    if app.shared.session_for_token(t, remote.ip(), true).await {
        Json(serde_json::json!({"status":"connected"})).into_response()
    } else {
        error(StatusCode::UNAUTHORIZED, "invalid session")
    }
}
async fn disconnect(
    ConnectInfo(remote): ConnectInfo<SocketAddr>,
    State(app): State<Arc<AppState>>,
    req: Request,
) -> Response {
    if origin_rejected(&req) {
        return error(StatusCode::FORBIDDEN, "browser origins are not accepted");
    }
    let t = req
        .headers()
        .get("x-aurashare-token")
        .and_then(|v| v.to_str().ok())
        .unwrap_or("");
    let mut i = app.shared.inner.write().await;
    expire_state(&mut i);
    let Some(s) = i.sessions.get(t).cloned() else {
        return error(StatusCode::UNAUTHORIZED, "invalid session");
    };
    if s.ip != remote.ip() {
        return error(StatusCode::UNAUTHORIZED, "invalid session");
    };
    i.sessions.remove(t);
    if let Some(p) = i.pairings.get_mut(&s.peer_id) {
        p.status = "rejected".into();
        p.token = None;
        p.finished = Some(Instant::now());
    }
    let _ = app
        .shared
        .peer_events
        .send(SharedState::peers_locked(&i).await);
    Json(serde_json::json!({"status":"disconnected"})).into_response()
}

fn validate_name(name: &str) -> Result<()> {
    if name.is_empty()
        || name == "."
        || name == ".."
        || name.contains('/')
        || name.contains('\\')
        || name.contains('\0')
        || name.chars().any(|c| c.is_control())
        || name
            .chars()
            .any(|c| matches!(c, '<' | '>' | ':' | '"' | '|' | '?' | '*'))
        || matches!(name.chars().last(), Some('.' | ' '))
        || name.encode_utf16().count() > 180
    {
        return Err(anyhow!("invalid filename"));
    }
    let stem = name.trim_end_matches([' ', '.']);
    let upper = stem.split('.').next().unwrap_or(stem).to_ascii_uppercase();
    if [
        "CON", "PRN", "AUX", "NUL", "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8",
        "COM9", "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9", "COM¹",
        "COM²", "COM³", "LPT¹", "LPT²", "LPT³",
    ]
    .contains(&upper.as_str())
    {
        return Err(anyhow!("reserved filename"));
    }
    Ok(())
}
struct TempGuard {
    path: PathBuf,
    armed: bool,
}
impl Drop for TempGuard {
    fn drop(&mut self) {
        if self.armed {
            let _ = std::fs::remove_file(&self.path);
        }
    }
}
async fn receive(
    app: Arc<AppState>,
    id: String,
    name: String,
    total: Option<u64>,
    expected: Option<String>,
    body: Body,
) -> std::result::Result<TransferEvent, (StatusCode, String)> {
    let started = Instant::now();
    let mut received = 0u64;
    let mut hasher = Sha256::new();
    let temp = app
        .config
        .download_dir
        .join(format!(".aurashare-{}.part", id));
    let mut guard = TempGuard {
        path: temp.clone(),
        armed: true,
    };
    let raw = match OpenOptions::new()
        .write(true)
        .create_new(true)
        .open(&temp)
        .await
    {
        Ok(f) => f,
        Err(e) => {
            return fail_event(
                &app,
                &id,
                &name,
                total,
                0,
                StatusCode::INTERNAL_SERVER_ERROR,
                e.to_string(),
            )
            .await
        }
    };
    let mut file = BufWriter::with_capacity(64 * 1024, raw);
    let mut stream = body.into_data_stream();
    let mut last_received = 0u64;
    emit(
        &app,
        TransferEvent {
            id: id.clone(),
            name: name.clone(),
            received: 0,
            total,
            bytes_per_second: 0.0,
            status: "receiving".into(),
            error: None,
            sha256: None,
            path: None,
        },
    )
    .await;
    let mut last = Instant::now();
    loop {
        let item = tokio::select! { _ = app.cancel.cancelled() => return cleanup_fail(&app, file, &id, &name, total, received, StatusCode::SERVICE_UNAVAILABLE, "server shutdown".into()).await, next = tokio::time::timeout(IDLE, stream.next()) => match next { Ok(v) => v, Err(_) => return cleanup_fail(&app, file, &id, &name, total, received, StatusCode::REQUEST_TIMEOUT, "idle timeout".into()).await } };
        let chunk = match item {
            Some(Ok(c)) => c,
            Some(Err(e)) => {
                return cleanup_fail(
                    &app,
                    file,
                    &id,
                    &name,
                    total,
                    received,
                    StatusCode::BAD_REQUEST,
                    e.to_string(),
                )
                .await
            }
            None => break,
        };
        if !app.transfer_authorized().await {
            return cleanup_fail(
                &app,
                file,
                &id,
                &name,
                total,
                received,
                StatusCode::UNAUTHORIZED,
                "secure session was revoked or expired".into(),
            )
            .await;
        }
        received = match received.checked_add(chunk.len() as u64) {
            Some(n) => n,
            None => {
                return cleanup_fail(
                    &app,
                    file,
                    &id,
                    &name,
                    total,
                    received,
                    StatusCode::PAYLOAD_TOO_LARGE,
                    "size overflow".into(),
                )
                .await
            }
        };
        if received > MAX_SIZE || total.is_some_and(|n| received > n) {
            return cleanup_fail(
                &app,
                file,
                &id,
                &name,
                total,
                received,
                StatusCode::PAYLOAD_TOO_LARGE,
                "declared size exceeded".into(),
            )
            .await;
        }
        hasher.update(&chunk);
        if let Err(e) = file.write_all(&chunk).await {
            return cleanup_fail(
                &app,
                file,
                &id,
                &name,
                total,
                received,
                StatusCode::INTERNAL_SERVER_ERROR,
                e.to_string(),
            )
            .await;
        }
        if last.elapsed() >= Duration::from_millis(100) {
            let elapsed = last.elapsed().as_secs_f64().max(0.001);
            let bytes_per_second = (received - last_received) as f64 / elapsed;
            last_received = received;
            last = Instant::now();
            emit(
                &app,
                TransferEvent {
                    id: id.clone(),
                    name: name.clone(),
                    received,
                    total,
                    bytes_per_second,
                    status: "receiving".into(),
                    error: None,
                    sha256: None,
                    path: None,
                },
            )
            .await;
        }
    }
    if total.is_some_and(|n| n != received) {
        return cleanup_fail(
            &app,
            file,
            &id,
            &name,
            total,
            received,
            StatusCode::BAD_REQUEST,
            "body length does not match X-File-Size".into(),
        )
        .await;
    }
    if let Err(e) = file.flush().await {
        return cleanup_fail(
            &app,
            file,
            &id,
            &name,
            total,
            received,
            StatusCode::INTERNAL_SERVER_ERROR,
            e.to_string(),
        )
        .await;
    }
    if let Err(e) = file.get_ref().sync_all().await {
        return cleanup_fail(
            &app,
            file,
            &id,
            &name,
            total,
            received,
            StatusCode::INTERNAL_SERVER_ERROR,
            e.to_string(),
        )
        .await;
    }
    drop(file);
    let digest = format!("{:x}", hasher.finalize());
    if expected
        .as_deref()
        .is_some_and(|e| !constant_time_eq(e.as_bytes(), digest.as_bytes()))
    {
        let _ = fs::remove_file(&temp).await;
        return fail_event(
            &app,
            &id,
            &name,
            total,
            received,
            StatusCode::BAD_REQUEST,
            "SHA-256 mismatch".into(),
        )
        .await;
    }
    if !app.transfer_authorized().await {
        let _ = fs::remove_file(&temp).await;
        return fail_event(
            &app,
            &id,
            &name,
            total,
            received,
            StatusCode::UNAUTHORIZED,
            "secure session was revoked or expired".into(),
        )
        .await;
    }
    let final_path = match finalize(&app.config.download_dir, &name, &temp).await {
        Ok(p) => p,
        Err(e) => {
            return fail_event(
                &app,
                &id,
                &name,
                total,
                received,
                StatusCode::INTERNAL_SERVER_ERROR,
                e.to_string(),
            )
            .await
        }
    };
    guard.armed = false;
    let event = TransferEvent {
        id,
        name,
        received,
        total,
        bytes_per_second: received as f64 / started.elapsed().as_secs_f64().max(0.001),
        status: "completed".into(),
        error: None,
        sha256: Some(digest),
        path: Some(final_path.display().to_string()),
    };
    emit(&app, event.clone()).await;
    Ok(event)
}
#[allow(clippy::too_many_arguments)] // Carries a single failed transfer into the cleanup boundary.
async fn cleanup_fail(
    app: &Arc<AppState>,
    file: BufWriter<tokio::fs::File>,
    id: &str,
    name: &str,
    total: Option<u64>,
    received: u64,
    status: StatusCode,
    msg: String,
) -> std::result::Result<TransferEvent, (StatusCode, String)> {
    let mut file = file;
    let _ = file.flush().await;
    drop(file);
    fail_event(app, id, name, total, received, status, msg).await
}
#[allow(clippy::too_many_arguments)] // Creates the complete event without retaining a request body.
async fn fail_event(
    app: &Arc<AppState>,
    id: &str,
    name: &str,
    total: Option<u64>,
    received: u64,
    status: StatusCode,
    msg: String,
) -> std::result::Result<TransferEvent, (StatusCode, String)> {
    let e = TransferEvent {
        id: id.into(),
        name: name.into(),
        received,
        total,
        bytes_per_second: 0.0,
        status: "failed".into(),
        error: Some(msg.clone()),
        sha256: None,
        path: None,
    };
    emit(app, e).await;
    Err((status, msg))
}

async fn emit(app: &Arc<AppState>, event: TransferEvent) {
    app.shared.push(event.clone()).await;
    // Receiving files does not depend on the desktop window keeping up with events.
    let _ = app.events.send(event);
}

async fn finalize(dir: &Path, name: &str, temp: &Path) -> Result<PathBuf> {
    for i in 0..100_000u32 {
        let candidate = candidate_path(dir, name, i);
        match fs::hard_link(temp, &candidate).await {
            Ok(()) => {
                fs::remove_file(temp)
                    .await
                    .context("remove temporary file")?;
                return Ok(candidate);
            }
            Err(e) if e.kind() == std::io::ErrorKind::AlreadyExists => continue,
            Err(e) => return Err(e.into()),
        }
    }
    Err(anyhow!("unable to allocate destination name"))
}
fn candidate_path(dir: &Path, name: &str, index: u32) -> PathBuf {
    if index == 0 {
        return dir.join(name);
    }
    let p = Path::new(name);
    let stem = p.file_stem().and_then(|s| s.to_str()).unwrap_or(name);
    let ext = p.extension().and_then(|s| s.to_str());
    let n = match ext {
        Some(e) => format!("{} ({}).{}", stem, index, e),
        None => format!("{} ({})", stem, index),
    };
    dir.join(n)
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn validation() {
        assert!(validate_name("../x").is_err());
        assert!(validate_name("CON.txt").is_err());
        assert!(validate_name("COM¹.txt").is_err());
        assert!(validate_name("a:b").is_err());
        assert!(validate_name("ok.txt").is_ok());
    }
    #[test]
    fn expiry_clock_revokes_sessions_and_bounds_terminal_pairings() {
        let now = Instant::now();
        let mut state = StateInner::default();
        for n in 0..(MAX_TERMINAL_PAIRINGS + 4) {
            state.pairings.insert(
                format!("r{n}"),
                Pairing {
                    client_id: format!("c{n}"),
                    device_name: "Phone".into(),
                    secret: "s".into(),
                    ip: IpAddr::V4(Ipv4Addr::LOCALHOST),
                    created: now,
                    status: "rejected".into(),
                    token: None,
                    finished: Some(now),
                },
            );
        }
        let id: String = "pending".into();
        state.pairings.insert(
            id.clone(),
            Pairing {
                client_id: "pending-client".into(),
                device_name: "Phone".into(),
                secret: "s".into(),
                ip: IpAddr::V4(Ipv4Addr::LOCALHOST),
                created: now,
                status: "pending".into(),
                token: Some("t".into()),
                finished: None,
            },
        );
        state.sessions.insert(
            "t".into(),
            Session {
                peer_id: id,
                ip: IpAddr::V4(Ipv4Addr::LOCALHOST),
                last_seen: now,
            },
        );
        expire_state_at(&mut state, now + PAIRING_TTL + Duration::from_secs(1));
        assert_eq!(state.pairings.get("pending").unwrap().status, "expired");
        assert!(state.sessions.is_empty());
        assert!(state.pairings.len() <= MAX_TERMINAL_PAIRINGS);
    }

    #[test]
    fn extension_collision() {
        assert_eq!(
            candidate_path(Path::new("/tmp"), "a.txt", 1),
            PathBuf::from("/tmp/a (1).txt")
        );
    }

    #[tokio::test]
    async fn expired_approval_cannot_reestablish_a_session() {
        let now = Instant::now();
        let shared = SharedState::default();
        let ip = IpAddr::V4(Ipv4Addr::LOCALHOST);
        let mut inner = shared.inner.write().await;
        inner.pairings.insert(
            "approved-peer".into(),
            Pairing {
                client_id: "phone".into(),
                device_name: "Phone".into(),
                secret: "a".repeat(64),
                ip,
                created: now,
                status: "approved".into(),
                token: Some("old-token".into()),
                finished: Some(now),
            },
        );
        inner.sessions.insert(
            "old-token".into(),
            Session {
                peer_id: "approved-peer".into(),
                ip,
                last_seen: now,
            },
        );
        expire_state_at(&mut inner, now + SESSION_TTL + Duration::from_secs(1));
        assert_eq!(inner.pairings["approved-peer"].status, "expired");
        assert!(inner.pairings["approved-peer"].token.is_none());
        assert!(inner.sessions.is_empty());
        drop(inner);
        assert!(!shared.session_for_token("old-token", ip, true).await);
        assert!(!shared.session_for_token("old-token", ip, false).await);
    }
}
