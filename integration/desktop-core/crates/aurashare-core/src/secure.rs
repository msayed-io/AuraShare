//! Isolated TLS 1.3 authority. HTTP never consults this credential/session store.
use anyhow::{anyhow, Context, Result};
use axum::{
    extract::{ConnectInfo, DefaultBodyLimit, Path, Request, State},
    http::{HeaderMap, StatusCode},
    middleware::{self, Next},
    response::{IntoResponse, Response},
    routing::{get, post},
    Json, Router,
};
use base64::{engine::general_purpose::STANDARD, Engine};
use rand::RngCore;
use rustls::{
    pki_types::{CertificateDer, PrivateKeyDer, PrivatePkcs8KeyDer},
    ServerConfig,
};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::{
    collections::HashMap,
    net::{Ipv4Addr, SocketAddr},
    path::PathBuf,
    sync::Arc,
    time::{Duration, SystemTime, UNIX_EPOCH},
};
use subtle::ConstantTimeEq;
use tokio::sync::RwLock;
use uuid::Uuid;
use x509_parser::{
    extensions::GeneralName,
    prelude::{FromDer, X509Certificate},
};

const QR_TTL: u64 = 120_000;
const SESSION_TTL: u64 = 45_000;
const MAX_PENDING: usize = 16;
const MAX_RECORDS: usize = 64;

#[derive(Clone, Serialize)]
pub struct PairingQr {
    pub svg: String,
    pub expires_at: u64,
    #[serde(skip_serializing)]
    pub payload: String,
}
#[derive(Clone, Serialize)]
pub struct SecureInfo {
    pub server_id: String,
    pub tls_port: u16,
    pub certificate: String,
}
#[derive(Clone, Serialize, Deserialize)]
struct Record {
    client_id: String,
    device_name: String,
    credential: String,
    trusted: bool,
}
#[derive(Clone, Serialize, Deserialize)]
struct Store {
    version: u8,
    server_id: String,
    certificate: Vec<u8>,
    key: Vec<u8>,
    records: Vec<Record>,
}
struct Pending {
    client_id: String,
    device_name: String,
    secret: String,
    expires: u64,
    status: String,
    credential: Option<String>,
    remote: SocketAddr,
}
struct Session {
    client_id: String,
    last_seen: u64,
    remote: SocketAddr,
}
#[derive(Default)]
struct Inner {
    pending: HashMap<String, Pending>,
    records: HashMap<String, Record>,
    sessions: HashMap<String, Session>,
    bootstrap: Option<(String, u64)>,
    rates: HashMap<std::net::IpAddr, (u64, u32)>,
}
pub struct SecureService {
    pub info: SecureInfo,
    persistence: PathBuf,
    store: Store,
    inner: RwLock<Inner>,
    device_name: String,
}
fn now() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as u64
}
fn secret() -> String {
    let mut b = [0u8; 32];
    rand::rngs::OsRng.fill_bytes(&mut b);
    b.iter().map(|v| format!("{v:02x}")).collect()
}
fn valid_hex(v: &str) -> bool {
    v.len() == 64
        && v.bytes()
            .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
}
fn eq(a: &str, b: &str) -> bool {
    bool::from(a.as_bytes().ct_eq(b.as_bytes()))
}
fn spki_id(der: &[u8]) -> String {
    Sha256::digest(der)
        .iter()
        .map(|v| format!("{v:02x}"))
        .collect()
}
fn expire(i: &mut Inner) {
    let at = now();
    i.pending.retain(|_, p| p.expires > at);
    i.sessions
        .retain(|_, s| at.saturating_sub(s.last_seen) < SESSION_TTL);
    i.rates
        .retain(|_, (start, _)| at.saturating_sub(*start) < 60_000);
    if i.bootstrap.as_ref().is_some_and(|(_, exp)| *exp <= at) {
        i.bootstrap = None;
    }
}
fn rate(i: &mut Inner, ip: std::net::IpAddr) -> bool {
    expire(i);
    if !i.rates.contains_key(&ip) && i.rates.len() >= 256 {
        return false;
    }
    let entry = i.rates.entry(ip).or_insert((now(), 0));
    entry.1 += 1;
    entry.1 <= 180
}
fn denied() -> Response {
    StatusCode::UNAUTHORIZED.into_response()
}
fn header<'a>(h: &'a HeaderMap, key: &str) -> &'a str {
    h.get(key).and_then(|v| v.to_str().ok()).unwrap_or("")
}

impl SecureService {
    pub async fn create(config: &crate::Config, dir: PathBuf) -> Result<Arc<Self>> {
        let path = dir.join("secure-identity.blob");
        let store: Store = match crate::protected_store::load(&path)? {
            Some(s) => s,
            None => {
                let key = rcgen::KeyPair::generate().context("generate identity key")?;
                let cert = rcgen::CertificateParams::new(vec!["aurashare.local".to_string()])?
                    .self_signed(&key)
                    .context("generate identity certificate")?;
                let s = Store {
                    version: 3,
                    server_id: spki_id(&key.public_key_der()),
                    certificate: cert.der().to_vec(),
                    key: key.serialize_der(),
                    records: Vec::new(),
                };
                crate::protected_store::save(&path, &s).context("persist identity")?;
                s
            }
        };
        if store.version != 3 || !valid_hex(&store.server_id) || store.records.len() > MAX_RECORDS {
            return Err(anyhow!("invalid protected identity"));
        }
        let key = rcgen::KeyPair::try_from(store.key.as_slice()).context("decode identity key")?;
        if !eq(&spki_id(&key.public_key_der()), &store.server_id) {
            return Err(anyhow!("identity key changed"));
        }
        let (rest, cert) = X509Certificate::from_der(&store.certificate)
            .map_err(|_| anyhow!("invalid identity certificate"))?;
        let san = cert
            .subject_alternative_name()
            .map_err(|_| anyhow!("invalid identity SAN"))?;
        if !rest.is_empty()
            || !cert.validity().is_valid()
            || !san.is_some_and(|s| {
                s.value
                    .general_names
                    .iter()
                    .any(|n| matches!(n, GeneralName::DNSName("aurashare.local")))
            })
            || cert.signature_algorithm.algorithm.to_id_string() != "1.2.840.10045.4.3.2"
            || !eq(&spki_id(cert.public_key().raw), &store.server_id)
            || cert.public_key().subject_public_key.data.len() != 65
            || cert.verify_signature(None).is_err()
        {
            return Err(anyhow!("identity certificate policy failed"));
        }
        let mut records = HashMap::new();
        for r in &store.records {
            if !crate::valid_client_id(&r.client_id)
                || (r.trusted && !valid_hex(&r.credential))
                || records.insert(r.client_id.clone(), r.clone()).is_some()
            {
                return Err(anyhow!("invalid protected device record"));
            }
        }
        let service = Arc::new(Self {
            info: SecureInfo {
                server_id: store.server_id.clone(),
                tls_port: 0,
                certificate: STANDARD.encode(&store.certificate),
            },
            persistence: path,
            store,
            inner: RwLock::new(Inner {
                records,
                ..Inner::default()
            }),
            device_name: config.device_name.clone(),
        });
        service.tls_config()?;
        Ok(service)
    }
    fn tls_config(&self) -> Result<ServerConfig> {
        let mut provider = rustls::crypto::ring::default_provider();
        provider.cipher_suites.retain(|suite| {
            matches!(
                suite.suite(),
                rustls::CipherSuite::TLS13_AES_256_GCM_SHA384
                    | rustls::CipherSuite::TLS13_CHACHA20_POLY1305_SHA256
            )
        });
        let mut config = ServerConfig::builder_with_provider(Arc::new(provider))
            .with_protocol_versions(&[&rustls::version::TLS13])?
            .with_no_client_auth()
            .with_single_cert(
                vec![CertificateDer::from(self.store.certificate.clone())],
                PrivateKeyDer::Pkcs8(PrivatePkcs8KeyDer::from(self.store.key.clone())),
            )?;
        config.alpn_protocols = vec![b"http/1.1".to_vec()];
        Ok(config)
    }
    pub async fn create_pairing_qr(&self, host: &str, port: u16) -> Result<PairingQr> {
        let ip: Ipv4Addr = host.parse().context("invalid QR address")?;
        if !crate::is_valid_pairing_ipv4(ip) || port == 0 || port != self.info.tls_port {
            return Err(anyhow!("invalid QR endpoint"));
        }
        let expires_at = now() + QR_TTL;
        let bootstrap = secret();
        let payload = serde_json::json!({"type":"aurashare-secure-pair","version":3,"host":host,"port":port,"certificate":self.info.certificate,"server_id":self.info.server_id,"bootstrap":bootstrap,"expires_at":expires_at}).to_string();
        if payload.len() > 8192 {
            return Err(anyhow!("QR too large"));
        }
        let svg = qrcode::QrCode::new(payload.as_bytes())?
            .render::<qrcode::render::svg::Color>()
            .min_dimensions(440, 440)
            .build();
        let svg = svg[svg
            .find("<svg")
            .ok_or_else(|| anyhow!("invalid SVG output"))?..]
            .to_string();
        self.inner.write().await.bootstrap = Some((bootstrap, expires_at));
        Ok(PairingQr {
            svg,
            expires_at,
            payload,
        })
    }
    pub async fn peers(&self) -> Vec<crate::Peer> {
        let mut i = self.inner.write().await;
        expire(&mut i);
        let mut out: Vec<_> = i
            .records
            .values()
            .filter(|r| r.trusted)
            .map(|r| {
                let session = i.sessions.values().find(|s| s.client_id == r.client_id);
                crate::Peer {
                    id: r.client_id.clone(),
                    device_name: Some(r.device_name.clone()),
                    remote_address: session.map(|s| s.remote.to_string()).unwrap_or_default(),
                    status: if session.is_some() {
                        "connected"
                    } else {
                        "trusted"
                    }
                    .into(),
                    secure: true,
                }
            })
            .collect();
        out.extend(
            i.pending
                .iter()
                .filter(|(_, p)| p.status == "pending")
                .map(|(id, p)| crate::Peer {
                    id: id.clone(),
                    device_name: Some(p.device_name.clone()),
                    remote_address: p.remote.to_string(),
                    status: "pending".into(),
                    secure: true,
                }),
        );
        out.sort_by(|a, b| a.id.cmp(&b.id));
        out
    }
    fn persist_records(&self, records: &HashMap<String, Record>) -> Result<()> {
        let mut store = self.store.clone();
        store.records = records.values().cloned().collect();
        crate::protected_store::save(&self.persistence, &store)
    }
    pub async fn approve_pairing(&self, id: &str, approved: bool) -> bool {
        let mut i = self.inner.write().await;
        expire(&mut i);
        let Some(p) = i.pending.get(id) else {
            return false;
        };
        if p.status != "pending" {
            return false;
        }
        if !approved {
            i.pending.get_mut(id).unwrap().status = "rejected".into();
            return true;
        }
        if !i.records.contains_key(&p.client_id) && i.records.len() >= MAX_RECORDS {
            return false;
        }
        let credential = secret();
        let client_id = p.client_id.clone();
        let mut records = i.records.clone();
        records.insert(
            client_id.clone(),
            Record {
                client_id: client_id.clone(),
                device_name: p.device_name.clone(),
                credential: credential.clone(),
                trusted: true,
            },
        );
        if self.persist_records(&records).is_err() {
            return false;
        }
        i.records = records;
        i.sessions.retain(|_, s| s.client_id != client_id);
        let p = i.pending.get_mut(id).unwrap();
        p.status = "approved".into();
        p.credential = Some(credential);
        true
    }
    pub async fn disconnect_peer(&self, id: &str) -> bool {
        let mut i = self.inner.write().await;
        let count = i.sessions.len();
        i.sessions.retain(|_, s| s.client_id != id);
        count != i.sessions.len()
    }
    pub async fn revoke_peer(&self, id: &str) -> bool {
        let mut i = self.inner.write().await;
        let mut records = i.records.clone();
        let Some(r) = records.get_mut(id) else {
            return false;
        };
        if !r.trusted {
            return false;
        }
        r.trusted = false;
        r.credential.clear();
        if self.persist_records(&records).is_err() {
            return false;
        }
        i.records = records;
        i.sessions.retain(|_, s| s.client_id != id);
        i.pending.retain(|_, p| p.client_id != id);
        true
    }
    pub(crate) async fn authorized(&self, token: &str, remote: SocketAddr, refresh: bool) -> bool {
        if !valid_hex(token) {
            return false;
        }
        let mut i = self.inner.write().await;
        expire(&mut i);
        if let Some(session) = i.sessions.get_mut(token) {
            if session.remote.ip() == remote.ip() {
                if refresh {
                    session.last_seen = now();
                }
                return true;
            }
        }
        false
    }
    fn routes(self: &Arc<Self>) -> Router {
        Router::new()
            .route("/api/secure/handshake", get(handshake))
            .route("/api/secure/pair/request", post(pair_request))
            .route("/api/secure/pair/status/{id}", get(pair_status))
            .route("/api/secure/session/connect", post(connect))
            .route("/api/secure/session/heartbeat", post(heartbeat))
            .route("/api/secure/session/disconnect", post(disconnect))
            .route("/api/secure/device/revoke", post(revoke))
            .layer(DefaultBodyLimit::max(8192))
            .with_state(self.clone())
    }
}
#[derive(Deserialize)]
struct PairBody {
    client_id: String,
    device_name: String,
    bootstrap: String,
}
#[derive(Deserialize)]
struct ClientBody {
    client_id: String,
}
async fn no_origin(req: Request, next: Next) -> Response {
    if req.headers().contains_key("origin") {
        return StatusCode::FORBIDDEN.into_response();
    }
    next.run(req).await
}
async fn handshake(State(s): State<Arc<SecureService>>) -> impl IntoResponse {
    Json(serde_json::json!({"server":"AuraShare","protocol":3,"server_id":s.info.server_id}))
}
async fn pair_request(
    ConnectInfo(remote): ConnectInfo<SocketAddr>,
    State(s): State<Arc<SecureService>>,
    Json(b): Json<PairBody>,
) -> Response {
    let mut i = s.inner.write().await;
    if !rate(&mut i, remote.ip()) {
        return StatusCode::TOO_MANY_REQUESTS.into_response();
    }
    if !crate::valid_client_id(&b.client_id)
        || b.device_name.trim().is_empty()
        || b.device_name.len() > 512
        || b.device_name
            .chars()
            .any(|c| c.is_control() || crate::is_bidi_control(c))
        || !valid_hex(&b.bootstrap)
        || i.bootstrap
            .as_ref()
            .is_none_or(|(v, exp)| *exp <= now() || !eq(v, &b.bootstrap))
    {
        return StatusCode::BAD_REQUEST.into_response();
    }
    if i.pending.len() >= MAX_PENDING {
        return StatusCode::TOO_MANY_REQUESTS.into_response();
    }
    i.bootstrap = None;
    let id = format!("secure-{}", Uuid::new_v4().simple());
    let sec = secret();
    i.pending.insert(
        id.clone(),
        Pending {
            client_id: b.client_id,
            device_name: b.device_name,
            secret: sec.clone(),
            expires: now() + QR_TTL,
            status: "pending".into(),
            credential: None,
            remote,
        },
    );
    (
        StatusCode::ACCEPTED,
        Json(serde_json::json!({"request_id":id,"request_secret":sec})),
    )
        .into_response()
}
async fn pair_status(
    ConnectInfo(remote): ConnectInfo<SocketAddr>,
    State(s): State<Arc<SecureService>>,
    Path(id): Path<String>,
    h: HeaderMap,
) -> Response {
    let mut i = s.inner.write().await;
    if !rate(&mut i, remote.ip()) {
        return StatusCode::TOO_MANY_REQUESTS.into_response();
    }
    let Some(p) = i.pending.get(&id) else {
        return (
            StatusCode::GONE,
            Json(serde_json::json!({"status":"expired"})),
        )
            .into_response();
    };
    let sec = header(&h, "x-aurashare-pair-secret");
    if !valid_hex(sec) || !eq(sec, &p.secret) || p.remote.ip() != remote.ip() {
        return denied();
    }
    if p.status == "approved" {
        return Json(serde_json::json!({"status":"approved","server_id":s.info.server_id,"device_name":s.device_name,"credential":p.credential})).into_response();
    }
    Json(serde_json::json!({"status":p.status})).into_response()
}
async fn connect(
    ConnectInfo(remote): ConnectInfo<SocketAddr>,
    State(s): State<Arc<SecureService>>,
    h: HeaderMap,
    Json(b): Json<ClientBody>,
) -> Response {
    let mut i = s.inner.write().await;
    if !rate(&mut i, remote.ip()) {
        return StatusCode::TOO_MANY_REQUESTS.into_response();
    }
    let credential = header(&h, "x-aurashare-device-credential");
    let Some(r) = i.records.get(&b.client_id) else {
        return denied();
    };
    if !r.trusted || !valid_hex(credential) || !eq(credential, &r.credential) {
        return denied();
    }
    i.sessions
        .retain(|_, session| session.client_id != b.client_id);
    let token = secret();
    i.sessions.insert(
        token.clone(),
        Session {
            client_id: b.client_id,
            last_seen: now(),
            remote,
        },
    );
    Json(serde_json::json!({"server_id":s.info.server_id,"token":token})).into_response()
}
async fn heartbeat(
    ConnectInfo(remote): ConnectInfo<SocketAddr>,
    State(s): State<Arc<SecureService>>,
    h: HeaderMap,
) -> Response {
    if !s
        .authorized(header(&h, "x-aurashare-token"), remote, true)
        .await
    {
        return denied();
    }
    Json(serde_json::json!({"status":"connected"})).into_response()
}
async fn disconnect(
    ConnectInfo(remote): ConnectInfo<SocketAddr>,
    State(s): State<Arc<SecureService>>,
    h: HeaderMap,
) -> Response {
    let token = header(&h, "x-aurashare-token");
    if !s.authorized(token, remote, false).await {
        return denied();
    }
    s.inner.write().await.sessions.remove(token);
    Json(serde_json::json!({"status":"disconnected"})).into_response()
}
async fn revoke(
    ConnectInfo(remote): ConnectInfo<SocketAddr>,
    State(s): State<Arc<SecureService>>,
    h: HeaderMap,
) -> Response {
    let credential = header(&h, "x-aurashare-device-credential");
    let id = {
        let mut i = s.inner.write().await;
        if !rate(&mut i, remote.ip()) {
            return StatusCode::TOO_MANY_REQUESTS.into_response();
        }
        i.records
            .values()
            .find(|r| r.trusted && valid_hex(credential) && eq(credential, &r.credential))
            .map(|r| r.client_id.clone())
    };
    let Some(id) = id else {
        return denied();
    };
    if !s.revoke_peer(&id).await {
        return StatusCode::INTERNAL_SERVER_ERROR.into_response();
    }
    StatusCode::NO_CONTENT.into_response()
}

pub async fn start_secure(
    config: crate::Config,
    persistence_dir: PathBuf,
) -> Result<crate::RunningServer> {
    // Load/validate DPAPI before opening even the compatibility listener: fail closed.
    let mut security = SecureService::create(&config, persistence_dir).await?;
    let listener =
        std::net::TcpListener::bind((Ipv4Addr::UNSPECIFIED, 0)).context("bind TLS listener")?;
    listener.set_nonblocking(true)?;
    let port = listener.local_addr()?.port();
    Arc::get_mut(&mut security)
        .ok_or_else(|| anyhow!("identity already shared"))?
        .info
        .tls_port = port;
    let tls = axum_server::tls_rustls::RustlsConfig::from_config(Arc::new(security.tls_config()?));
    let mut server = crate::start(config).await?;
    let router = security
        .routes()
        .merge(crate::secure_transfer_router(&server, security.clone()))
        .layer(middleware::from_fn(no_origin));
    let handle = axum_server::Handle::new();
    let shutdown = handle.clone();
    let cancel = server.cancel.clone();
    tokio::spawn(async move {
        cancel.cancelled().await;
        shutdown.graceful_shutdown(Some(Duration::from_secs(2)));
    });
    let transport = axum_server::from_tcp_rustls(listener, tls).handle(handle);
    let task = tokio::spawn(async move {
        let _ = transport
            .serve(router.into_make_service_with_connect_info::<SocketAddr>())
            .await;
    });
    *server.http_state.secure_hint.write().await = Some(security.info.clone());
    server.attach_secure(security, port, task);
    Ok(server)
}
