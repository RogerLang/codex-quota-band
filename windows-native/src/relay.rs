use crate::network::SyncPayload;
use crate::{
    QuotaSnapshotV3, SyncedTask, protect_current_user_bytes, unprotect_current_user_bytes,
};
use aes_gcm::aead::{Aead, KeyInit, Payload};
use aes_gcm::{Aes256Gcm, Nonce};
use base64::Engine;
use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use serde::{Deserialize, Serialize};
use serde_json::Value;
use std::fs;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex, RwLock};
use std::time::Duration;
use zeroize::{Zeroize, ZeroizeOnDrop};

const RELAY_PROTOCOL_VERSION: u8 = 1;
const RELAY_AAD_MAGIC: &[u8] = b"CQ-RELAY-V1\0";
const CREDENTIAL_MAGIC: &[u8] = b"CQRC\x01";
const SEQUENCE_MAGIC: &[u8] = b"CQRS\x01";

#[derive(Debug)]
pub enum RelayError {
    InvalidConfiguration,
    Randomness,
    Crypto,
    Serialization,
    Storage,
    Transport,
    Rejected(u16),
    SequenceExhausted,
    Superseded,
}

impl std::fmt::Display for RelayError {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::InvalidConfiguration => formatter.write_str("invalid relay configuration"),
            Self::Randomness => formatter.write_str("secure randomness is unavailable"),
            Self::Crypto => formatter.write_str("relay cryptography failed"),
            Self::Serialization => formatter.write_str("relay serialization failed"),
            Self::Storage => formatter.write_str("relay state storage failed"),
            Self::Transport => formatter.write_str("relay transport failed"),
            Self::Rejected(status) => {
                write!(formatter, "relay rejected publish with HTTP {status}")
            }
            Self::SequenceExhausted => formatter.write_str("relay sequence exhausted"),
            Self::Superseded => formatter
                .write_str("relay publish superseded by newer state or credential rotation"),
        }
    }
}

impl std::error::Error for RelayError {}

#[derive(Clone, Zeroize, ZeroizeOnDrop)]
pub struct RelayCredentials {
    base_url: String,
    topic: [u8; 32],
    key: [u8; 32],
    device_id: [u8; 16],
}

impl RelayCredentials {
    pub fn generate(base_url: &str) -> Result<Self, RelayError> {
        let base_url = normalize_base_url(base_url)?;
        let mut topic = [0_u8; 32];
        let mut key = [0_u8; 32];
        let mut device_id = [0_u8; 16];
        getrandom::fill(&mut topic).map_err(|_| RelayError::Randomness)?;
        getrandom::fill(&mut key).map_err(|_| RelayError::Randomness)?;
        getrandom::fill(&mut device_id).map_err(|_| RelayError::Randomness)?;
        Ok(Self {
            base_url,
            topic,
            key,
            device_id,
        })
    }

    pub fn base_url(&self) -> &str {
        &self.base_url
    }

    pub fn topic_name(&self) -> String {
        URL_SAFE_NO_PAD.encode(self.topic)
    }

    pub fn topic_hint(&self) -> String {
        hex::encode(&self.topic[..4]).to_uppercase()
    }

    pub fn pairing_deep_link(&self) -> Result<String, RelayError> {
        let payload = RelayPairingPayload {
            protocol_version: RELAY_PROTOCOL_VERSION,
            payload_type: RelayPairingType::RelayPairing,
            relay_base_url: &self.base_url,
            topic: self.topic_name(),
            key: URL_SAFE_NO_PAD.encode(self.key),
            device_id: URL_SAFE_NO_PAD.encode(self.device_id),
        };
        let encoded = serde_json::to_vec(&payload).map_err(|_| RelayError::Serialization)?;
        Ok(format!(
            "codexquota://pair?relay={}",
            URL_SAFE_NO_PAD.encode(encoded)
        ))
    }

    fn aad(&self) -> Vec<u8> {
        let mut aad = Vec::with_capacity(RELAY_AAD_MAGIC.len() + self.topic.len());
        aad.extend_from_slice(RELAY_AAD_MAGIC);
        aad.extend_from_slice(&self.topic);
        aad
    }
}

fn normalize_base_url(value: &str) -> Result<String, RelayError> {
    let url = reqwest::Url::parse(value).map_err(|_| RelayError::InvalidConfiguration)?;
    if url.scheme() != "https"
        || !url.username().is_empty()
        || url.password().is_some()
        || url.query().is_some()
        || url.fragment().is_some()
        || !(url.path().is_empty() || url.path() == "/")
    {
        return Err(RelayError::InvalidConfiguration);
    }
    Ok(value.trim_end_matches('/').to_string())
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct RelayPairingPayload<'a> {
    protocol_version: u8,
    #[serde(rename = "type")]
    payload_type: RelayPairingType,
    relay_base_url: &'a str,
    topic: String,
    key: String,
    device_id: String,
}

#[derive(Serialize)]
#[serde(rename_all = "snake_case")]
enum RelayPairingType {
    RelayPairing,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct RelayEnvelope {
    pub version: u8,
    pub nonce: String,
    pub ciphertext: String,
}

impl RelayEnvelope {
    pub fn encrypt(
        credentials: &RelayCredentials,
        sequence: u64,
        snapshot: &SyncPayload,
    ) -> Result<Self, RelayError> {
        let plaintext = RelayPlaintextV1 {
            protocol_version: RELAY_PROTOCOL_VERSION,
            sequence,
            generated_at_ms: snapshot.tasks.generated_at_ms,
            quota: QuotaSnapshotV3::from(&snapshot.quota),
            tasks: &snapshot.tasks.tasks,
            chat_gpt_state: snapshot.tasks.chat_gpt_state,
            chat_gpt_focused: snapshot.tasks.chat_gpt_focused,
        };
        let mut encoded = serde_json::to_vec(&plaintext).map_err(|_| RelayError::Serialization)?;
        let mut nonce = [0_u8; 12];
        getrandom::fill(&mut nonce).map_err(|_| RelayError::Randomness)?;
        let cipher = Aes256Gcm::new_from_slice(&credentials.key).map_err(|_| RelayError::Crypto)?;
        let ciphertext = cipher
            .encrypt(
                Nonce::from_slice(&nonce),
                Payload {
                    msg: &encoded,
                    aad: &credentials.aad(),
                },
            )
            .map_err(|_| RelayError::Crypto)?;
        encoded.zeroize();
        Ok(Self {
            version: RELAY_PROTOCOL_VERSION,
            nonce: URL_SAFE_NO_PAD.encode(nonce),
            ciphertext: URL_SAFE_NO_PAD.encode(ciphertext),
        })
    }

    pub fn nonce_bytes(&self) -> Result<Vec<u8>, RelayError> {
        let nonce = URL_SAFE_NO_PAD
            .decode(&self.nonce)
            .map_err(|_| RelayError::Crypto)?;
        if nonce.len() != 12 {
            return Err(RelayError::Crypto);
        }
        Ok(nonce)
    }

    fn decrypt(&self, credentials: &RelayCredentials) -> Result<Vec<u8>, RelayError> {
        if self.version != RELAY_PROTOCOL_VERSION {
            return Err(RelayError::Crypto);
        }
        let nonce = self.nonce_bytes()?;
        let ciphertext = URL_SAFE_NO_PAD
            .decode(&self.ciphertext)
            .map_err(|_| RelayError::Crypto)?;
        let cipher = Aes256Gcm::new_from_slice(&credentials.key).map_err(|_| RelayError::Crypto)?;
        cipher
            .decrypt(
                Nonce::from_slice(&nonce),
                Payload {
                    msg: &ciphertext,
                    aad: &credentials.aad(),
                },
            )
            .map_err(|_| RelayError::Crypto)
    }
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct RelayPlaintextV1<'a> {
    protocol_version: u8,
    sequence: u64,
    generated_at_ms: i64,
    quota: QuotaSnapshotV3,
    tasks: &'a [SyncedTask],
    chat_gpt_state: crate::ChatGptState,
    chat_gpt_focused: bool,
}

pub fn decrypt_payload_for_test(
    credentials: &RelayCredentials,
    envelope: &RelayEnvelope,
) -> Result<Value, RelayError> {
    let mut plaintext = envelope.decrypt(credentials)?;
    let value = serde_json::from_slice(&plaintext).map_err(|_| RelayError::Serialization);
    plaintext.zeroize();
    value
}

pub struct RelaySequenceStore {
    path: PathBuf,
    current: u64,
}

impl RelaySequenceStore {
    pub fn load(path: impl Into<PathBuf>) -> Result<Self, RelayError> {
        let path = path.into();
        let current = match fs::read(&path) {
            Ok(bytes)
                if bytes.len() == SEQUENCE_MAGIC.len() + 8 && bytes.starts_with(SEQUENCE_MAGIC) =>
            {
                u64::from_le_bytes(bytes[SEQUENCE_MAGIC.len()..].try_into().unwrap())
            }
            Ok(_) => return Err(RelayError::Storage),
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => 0,
            Err(_) => return Err(RelayError::Storage),
        };
        Ok(Self { path, current })
    }

    pub fn next(&mut self) -> Result<u64, RelayError> {
        let next = self
            .current
            .checked_add(1)
            .ok_or(RelayError::SequenceExhausted)?;
        if let Some(parent) = self
            .path
            .parent()
            .filter(|parent| !parent.as_os_str().is_empty())
        {
            fs::create_dir_all(parent).map_err(|_| RelayError::Storage)?;
        }
        let mut payload = Vec::with_capacity(SEQUENCE_MAGIC.len() + 8);
        payload.extend_from_slice(SEQUENCE_MAGIC);
        payload.extend_from_slice(&next.to_le_bytes());
        fs::write(&self.path, payload).map_err(|_| RelayError::Storage)?;
        self.current = next;
        Ok(next)
    }
}

pub struct RelayCredentialStore {
    path: PathBuf,
}

impl RelayCredentialStore {
    pub fn new(path: impl Into<PathBuf>) -> Self {
        Self { path: path.into() }
    }

    pub fn load_or_create(&self, base_url: &str) -> Result<RelayCredentials, RelayError> {
        if self.path.exists() {
            return self.load();
        }
        let credentials = RelayCredentials::generate(base_url)?;
        self.save(&credentials)?;
        Ok(credentials)
    }

    pub fn rotate(&self, base_url: &str) -> Result<RelayCredentials, RelayError> {
        let credentials = RelayCredentials::generate(base_url)?;
        self.save(&credentials)?;
        Ok(credentials)
    }

    pub fn load(&self) -> Result<RelayCredentials, RelayError> {
        let bytes = fs::read(&self.path).map_err(|_| RelayError::Storage)?;
        if !bytes.starts_with(CREDENTIAL_MAGIC) {
            return Err(RelayError::Storage);
        }
        let mut plaintext = unprotect_current_user_bytes(&bytes[CREDENTIAL_MAGIC.len()..])
            .map_err(|_| RelayError::Storage)?;
        let disk: RelayCredentialDisk =
            serde_json::from_slice(&plaintext).map_err(|_| RelayError::Storage)?;
        plaintext.zeroize();
        disk.try_into()
    }

    pub fn save(&self, credentials: &RelayCredentials) -> Result<(), RelayError> {
        if let Some(parent) = self
            .path
            .parent()
            .filter(|parent| !parent.as_os_str().is_empty())
        {
            fs::create_dir_all(parent).map_err(|_| RelayError::Storage)?;
        }
        let disk = RelayCredentialDisk::from(credentials);
        let mut plaintext = serde_json::to_vec(&disk).map_err(|_| RelayError::Serialization)?;
        let mut protected =
            protect_current_user_bytes(&plaintext).map_err(|_| RelayError::Storage)?;
        plaintext.zeroize();
        let mut payload = Vec::with_capacity(CREDENTIAL_MAGIC.len() + protected.len());
        payload.extend_from_slice(CREDENTIAL_MAGIC);
        payload.append(&mut protected);
        fs::write(&self.path, payload).map_err(|_| RelayError::Storage)
    }
}

#[derive(Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct RelayCredentialDisk {
    protocol_version: u8,
    base_url: String,
    topic: String,
    key: String,
    device_id: String,
}

impl From<&RelayCredentials> for RelayCredentialDisk {
    fn from(credentials: &RelayCredentials) -> Self {
        Self {
            protocol_version: RELAY_PROTOCOL_VERSION,
            base_url: credentials.base_url.clone(),
            topic: URL_SAFE_NO_PAD.encode(credentials.topic),
            key: URL_SAFE_NO_PAD.encode(credentials.key),
            device_id: URL_SAFE_NO_PAD.encode(credentials.device_id),
        }
    }
}

impl TryFrom<RelayCredentialDisk> for RelayCredentials {
    type Error = RelayError;

    fn try_from(value: RelayCredentialDisk) -> Result<Self, Self::Error> {
        if value.protocol_version != RELAY_PROTOCOL_VERSION {
            return Err(RelayError::InvalidConfiguration);
        }
        let topic = decode_array::<32>(&value.topic)?;
        let key = decode_array::<32>(&value.key)?;
        let device_id = decode_array::<16>(&value.device_id)?;
        Ok(Self {
            base_url: normalize_base_url(&value.base_url)?,
            topic,
            key,
            device_id,
        })
    }
}

fn decode_array<const N: usize>(value: &str) -> Result<[u8; N], RelayError> {
    let decoded = URL_SAFE_NO_PAD
        .decode(value)
        .map_err(|_| RelayError::InvalidConfiguration)?;
    decoded
        .try_into()
        .map_err(|_| RelayError::InvalidConfiguration)
}

pub trait RelayHttpTransport: Send + Sync {
    fn post(&self, url: &str, body: &str) -> Result<u16, RelayError>;
}

struct ReqwestRelayTransport {
    client: reqwest::blocking::Client,
}

impl ReqwestRelayTransport {
    fn new() -> Result<Self, RelayError> {
        let _ = rustls::crypto::ring::default_provider().install_default();
        let client = reqwest::blocking::Client::builder()
            .timeout(Duration::from_secs(15))
            .build()
            .map_err(|_| RelayError::Transport)?;
        Ok(Self { client })
    }
}

impl RelayHttpTransport for ReqwestRelayTransport {
    fn post(&self, url: &str, body: &str) -> Result<u16, RelayError> {
        self.client
            .post(url)
            .header(reqwest::header::CONTENT_TYPE, "text/plain; charset=utf-8")
            .body(body.to_owned())
            .send()
            .map(|response| response.status().as_u16())
            .map_err(|_| RelayError::Transport)
    }
}

#[derive(Clone)]
pub struct RelayPublisher {
    credentials: Arc<RwLock<RelayCredentials>>,
    sequence: Arc<Mutex<RelaySequenceStore>>,
    transport: Arc<dyn RelayHttpTransport>,
    retry_delays: Arc<Vec<Duration>>,
    publish_serial: Arc<tokio::sync::Mutex<()>>,
    generation: Arc<AtomicU64>,
    rotation_pending: Arc<AtomicBool>,
}

impl RelayPublisher {
    pub fn new(
        credentials: RelayCredentials,
        sequence_path: impl Into<PathBuf>,
    ) -> Result<Self, RelayError> {
        Self::with_transport(
            credentials,
            sequence_path,
            Arc::new(ReqwestRelayTransport::new()?),
            vec![
                Duration::from_secs(1),
                Duration::from_secs(2),
                Duration::from_secs(4),
            ],
        )
    }

    pub fn with_transport(
        credentials: RelayCredentials,
        sequence_path: impl Into<PathBuf>,
        transport: Arc<dyn RelayHttpTransport>,
        retry_delays: Vec<Duration>,
    ) -> Result<Self, RelayError> {
        Ok(Self {
            credentials: Arc::new(RwLock::new(credentials)),
            sequence: Arc::new(Mutex::new(RelaySequenceStore::load(sequence_path)?)),
            transport,
            retry_delays: Arc::new(retry_delays),
            publish_serial: Arc::new(tokio::sync::Mutex::new(())),
            generation: Arc::new(AtomicU64::new(0)),
            rotation_pending: Arc::new(AtomicBool::new(false)),
        })
    }

    pub async fn set_credentials(&self, credentials: RelayCredentials) -> Result<(), RelayError> {
        // Stop retries immediately, then wait for any already-started HTTP request to finish.
        // When this returns, no request using the old topic/key can still be in flight.
        self.rotation_pending.store(true, Ordering::SeqCst);
        let _serial = self.publish_serial.lock().await;
        let result = self
            .credentials
            .write()
            .map_err(|_| RelayError::Storage)
            .map(|mut stored| {
                *stored = credentials;
                self.generation.fetch_add(1, Ordering::SeqCst);
            });
        self.rotation_pending.store(false, Ordering::SeqCst);
        result
    }

    pub async fn publish(&self, snapshot: SyncPayload) -> Result<(), RelayError> {
        let generation = self.generation.load(Ordering::SeqCst);
        self.publish_for_generation(snapshot, generation, None)
            .await
    }

    async fn publish_for_generation(
        &self,
        snapshot: SyncPayload,
        generation: u64,
        latest_revision: Option<(&AtomicU64, u64)>,
    ) -> Result<(), RelayError> {
        let _serial = self.publish_serial.lock().await;
        self.ensure_active(generation, latest_revision)?;
        let sequence = self
            .sequence
            .lock()
            .map_err(|_| RelayError::Storage)?
            .next()?;
        let credentials = self
            .credentials
            .read()
            .map_err(|_| RelayError::Storage)?
            .clone();
        let mut snapshot = snapshot;
        // This authenticated relay timestamp represents the actual Windows publish attempt,
        // not the last task transition. Android uses it to reject old ntfy cached messages
        // as evidence of a currently running Windows publisher.
        snapshot.tasks.generated_at_ms = chrono::Utc::now().timestamp_millis();
        let envelope = RelayEnvelope::encrypt(&credentials, sequence, &snapshot)?;
        let body = serde_json::to_string(&envelope).map_err(|_| RelayError::Serialization)?;
        let url = format!("{}/{}", credentials.base_url(), credentials.topic_name());
        let attempts = self.retry_delays.len() + 1;
        for attempt in 0..attempts {
            self.ensure_active(generation, latest_revision)?;
            let transport = self.transport.clone();
            let request_url = url.clone();
            let request_body = body.clone();
            let result =
                tokio::task::spawn_blocking(move || transport.post(&request_url, &request_body))
                    .await
                    .map_err(|_| RelayError::Transport)?;
            match result {
                Ok(status) if (200..300).contains(&status) => return Ok(()),
                Ok(status) if status == 429 || status >= 500 => {
                    if let Some(delay) = self.retry_delays.get(attempt) {
                        tokio::time::sleep(*delay).await;
                        continue;
                    }
                    return Err(RelayError::Rejected(status));
                }
                Ok(status) => return Err(RelayError::Rejected(status)),
                Err(_) => {
                    if let Some(delay) = self.retry_delays.get(attempt) {
                        tokio::time::sleep(*delay).await;
                        continue;
                    }
                    return Err(RelayError::Transport);
                }
            }
        }
        Err(RelayError::Transport)
    }

    fn ensure_active(
        &self,
        generation: u64,
        latest_revision: Option<(&AtomicU64, u64)>,
    ) -> Result<(), RelayError> {
        if self.rotation_pending.load(Ordering::SeqCst)
            || self.generation.load(Ordering::SeqCst) != generation
            || latest_revision
                .is_some_and(|(revision, expected)| revision.load(Ordering::SeqCst) != expected)
        {
            Err(RelayError::Superseded)
        } else {
            Ok(())
        }
    }
}

struct LatestPublishState {
    pending: Option<(u64, u64, SyncPayload)>,
    worker_running: bool,
}

/// At most one in-flight snapshot and one replaceable pending snapshot are retained.
#[derive(Clone)]
pub struct RelayLatestPublisher {
    publisher: RelayPublisher,
    state: Arc<Mutex<LatestPublishState>>,
    revision: Arc<AtomicU64>,
}

impl RelayLatestPublisher {
    pub fn new(publisher: RelayPublisher) -> Self {
        Self {
            publisher,
            state: Arc::new(Mutex::new(LatestPublishState {
                pending: None,
                worker_running: false,
            })),
            revision: Arc::new(AtomicU64::new(0)),
        }
    }

    pub fn submit(&self, snapshot: SyncPayload) -> Result<(), RelayError> {
        let should_start = {
            let mut state = self.state.lock().map_err(|_| RelayError::Storage)?;
            let generation = self.publisher.generation.load(Ordering::SeqCst);
            let revision = self.revision.fetch_add(1, Ordering::SeqCst) + 1;
            state.pending = Some((generation, revision, snapshot));
            if state.worker_running {
                false
            } else {
                state.worker_running = true;
                true
            }
        };
        if should_start {
            tokio::spawn(self.clone().drain());
        }
        Ok(())
    }

    pub fn pending_count(&self) -> usize {
        usize::from(
            self.state
                .lock()
                .unwrap_or_else(std::sync::PoisonError::into_inner)
                .pending
                .is_some(),
        )
    }

    async fn drain(self) {
        loop {
            let next = {
                let mut state = self
                    .state
                    .lock()
                    .unwrap_or_else(std::sync::PoisonError::into_inner);
                match state.pending.take() {
                    Some(next) => next,
                    None => {
                        state.worker_running = false;
                        return;
                    }
                }
            };
            let (generation, revision, snapshot) = next;
            let result = self
                .publisher
                .publish_for_generation(
                    snapshot.clone(),
                    generation,
                    Some((&self.revision, revision)),
                )
                .await;
            if result.is_err()
                && !matches!(result, Err(RelayError::Superseded))
                && generation == self.publisher.generation.load(Ordering::SeqCst)
                && revision == self.revision.load(Ordering::SeqCst)
            {
                {
                    let mut state = self
                        .state
                        .lock()
                        .unwrap_or_else(std::sync::PoisonError::into_inner);
                    if state.pending.is_none() {
                        state.pending = Some((generation, revision, snapshot));
                    }
                }
                tokio::time::sleep(Duration::from_secs(1)).await;
            }
        }
    }
}

pub fn credential_path(directory: &Path) -> PathBuf {
    directory.join("relay-credential-v1.bin")
}

pub fn sequence_path(directory: &Path) -> PathBuf {
    directory.join("relay-sequence-v1.bin")
}
