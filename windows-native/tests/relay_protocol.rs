use codex_quota_windows_core::network::SyncPayload;
use codex_quota_windows_core::relay::{
    RelayCredentials, RelayEnvelope, RelayError, RelayHttpTransport, RelayLatestPublisher,
    RelayPublisher, RelaySequenceStore, decrypt_payload_for_test,
};
use codex_quota_windows_core::{
    ChatGptState, CodexLinkStatus, ComputerLinkStatus, QuotaLink, QuotaSnapshot, QuotaSourceStatus,
    ResetInventorySnapshot, ResetInventoryStatus, TaskSyncSnapshot, UpstreamFreshness,
};
use std::sync::Arc;
use std::sync::Mutex;
use std::sync::mpsc;
use std::time::Duration;

fn sample_payload() -> SyncPayload {
    SyncPayload {
        quota: QuotaSnapshot {
            protocol_version: 3,
            generated_at: "2026-09-16T00:00:00.000Z".to_string(),
            source_status: QuotaSourceStatus::Ok,
            limits_collected_at: Some("2026-09-16T00:00:00.000Z".to_string()),
            windows: vec![],
            reset_inventory: ResetInventorySnapshot {
                status: ResetInventoryStatus::Missing,
                available_count: None,
                cached_at: None,
                items: vec![],
            },
            link: QuotaLink {
                computer: ComputerLinkStatus::Online,
                codex: CodexLinkStatus::Ok,
            },
            upstream_freshness: UpstreamFreshness::default(),
        },
        tasks: TaskSyncSnapshot {
            protocol_version: 1,
            sequence: 7,
            generated_at_ms: 1_789_516_800_000,
            chat_gpt_state: ChatGptState::Running,
            chat_gpt_focused: false,
            tasks: vec![],
        },
    }
}

#[test]
fn aes_gcm_round_trip_uses_fresh_96_bit_nonces() {
    let credentials = RelayCredentials::generate("https://ntfy.sh").unwrap();
    let first = RelayEnvelope::encrypt(&credentials, 41, &sample_payload()).unwrap();
    let second = RelayEnvelope::encrypt(&credentials, 42, &sample_payload()).unwrap();

    assert_eq!(first.nonce_bytes().unwrap().len(), 12);
    assert_ne!(first.nonce, second.nonce);
    let plaintext = decrypt_payload_for_test(&credentials, &first).unwrap();
    assert_eq!(plaintext["protocolVersion"], 1);
    assert_eq!(plaintext["sequence"], 41);
}

#[test]
fn wrong_key_and_modified_ciphertext_are_rejected() {
    let credentials = RelayCredentials::generate("https://ntfy.sh").unwrap();
    let wrong = RelayCredentials::generate("https://ntfy.sh").unwrap();
    let envelope = RelayEnvelope::encrypt(&credentials, 1, &sample_payload()).unwrap();
    assert!(decrypt_payload_for_test(&wrong, &envelope).is_err());

    let mut modified = envelope.clone();
    let last = modified.ciphertext.len() - 1;
    modified.ciphertext.replace_range(
        last..,
        if &modified.ciphertext[last..] == "A" {
            "B"
        } else {
            "A"
        },
    );
    assert!(decrypt_payload_for_test(&credentials, &modified).is_err());
}

#[test]
fn relay_plaintext_has_only_the_versioned_snapshot_whitelist() {
    let credentials = RelayCredentials::generate("https://ntfy.sh").unwrap();
    let envelope = RelayEnvelope::encrypt(&credentials, 9, &sample_payload()).unwrap();
    let plaintext = decrypt_payload_for_test(&credentials, &envelope).unwrap();
    let mut keys = plaintext
        .as_object()
        .unwrap()
        .keys()
        .cloned()
        .collect::<Vec<_>>();
    keys.sort();
    assert_eq!(
        keys,
        [
            "chatGptFocused",
            "chatGptState",
            "generatedAtMs",
            "protocolVersion",
            "quota",
            "sequence",
            "tasks"
        ]
    );
    let serialized = plaintext.to_string();
    for forbidden in [
        "prompt",
        "response",
        "toolArgs",
        "terminalOutput",
        "filePath",
        "cookie",
        "accessToken",
    ] {
        assert!(!serialized.contains(forbidden));
    }
}

#[test]
fn sequence_persists_and_increments_across_store_instances() {
    let directory = tempfile::tempdir().unwrap();
    let path = directory.path().join("relay-sequence-v1.bin");
    let mut first = RelaySequenceStore::load(path.clone()).unwrap();
    assert_eq!(first.next().unwrap(), 1);
    assert_eq!(first.next().unwrap(), 2);
    let mut reopened = RelaySequenceStore::load(path).unwrap();
    assert_eq!(reopened.next().unwrap(), 3);
}

struct AlwaysFail;

impl RelayHttpTransport for AlwaysFail {
    fn post(&self, _url: &str, _body: &str) -> Result<u16, RelayError> {
        Err(RelayError::Transport)
    }
}

#[tokio::test]
async fn publish_failure_is_bounded_and_does_not_mutate_local_snapshot() {
    let directory = tempfile::tempdir().unwrap();
    let credentials = RelayCredentials::generate("https://ntfy.sh").unwrap();
    let publisher = RelayPublisher::with_transport(
        credentials,
        directory.path().join("sequence.bin"),
        Arc::new(AlwaysFail),
        vec![],
    )
    .unwrap();
    let snapshot = sample_payload();
    let original = snapshot.clone();
    assert!(publisher.publish(snapshot).await.is_err());
    assert_eq!(original, sample_payload());
}

#[derive(Default)]
struct RecordingTransport {
    request: Mutex<Option<(String, String)>>,
}

impl RelayHttpTransport for RecordingTransport {
    fn post(&self, url: &str, body: &str) -> Result<u16, RelayError> {
        *self.request.lock().unwrap() = Some((url.to_string(), body.to_string()));
        Ok(200)
    }
}

#[tokio::test]
async fn publishes_ciphertext_envelope_to_a_replaceable_ntfy_endpoint() {
    let directory = tempfile::tempdir().unwrap();
    let credentials = RelayCredentials::generate("https://relay.example").unwrap();
    let decrypt_credentials = credentials.clone();
    let transport = Arc::new(RecordingTransport::default());
    let publisher = RelayPublisher::with_transport(
        credentials,
        directory.path().join("sequence.bin"),
        transport.clone(),
        vec![],
    )
    .unwrap();
    let before_publish_ms = chrono::Utc::now().timestamp_millis();
    publisher.publish(sample_payload()).await.unwrap();
    let (url, body) = transport.request.lock().unwrap().clone().unwrap();
    assert!(url.starts_with("https://relay.example/"));
    let envelope: serde_json::Value = serde_json::from_str(&body).unwrap();
    let mut keys = envelope
        .as_object()
        .unwrap()
        .keys()
        .cloned()
        .collect::<Vec<_>>();
    keys.sort();
    assert_eq!(keys, ["ciphertext", "nonce", "version"]);
    assert!(!body.contains("chatGptState"));
    assert!(!body.contains("quota"));
    let payload = decrypt_payload_for_test(
        &decrypt_credentials,
        &serde_json::from_str::<RelayEnvelope>(&body).unwrap(),
    )
    .unwrap();
    assert!(payload["generatedAtMs"].as_i64().unwrap() >= before_publish_ms);
}

#[test]
fn pairing_qr_contains_only_versioned_relay_credentials() {
    let credentials = RelayCredentials::generate("https://ntfy.sh").unwrap();
    let link = credentials.pairing_deep_link().unwrap();
    assert!(link.starts_with("codexquota://pair?relay="));
    assert!(!link.contains("192.168."));
    assert!(!link.contains("pairingCode"));
}

struct FirstPostFailsAfterRelease {
    requests: Mutex<Vec<(String, String)>>,
    first_started: Mutex<Option<mpsc::Sender<()>>>,
    release_first: Mutex<Option<mpsc::Receiver<()>>>,
}

impl FirstPostFailsAfterRelease {
    fn new() -> (Arc<Self>, mpsc::Receiver<()>, mpsc::Sender<()>) {
        let (started_tx, started_rx) = mpsc::channel();
        let (release_tx, release_rx) = mpsc::channel();
        (
            Arc::new(Self {
                requests: Mutex::new(Vec::new()),
                first_started: Mutex::new(Some(started_tx)),
                release_first: Mutex::new(Some(release_rx)),
            }),
            started_rx,
            release_tx,
        )
    }
}

impl RelayHttpTransport for FirstPostFailsAfterRelease {
    fn post(&self, url: &str, body: &str) -> Result<u16, RelayError> {
        let call = {
            let mut requests = self.requests.lock().unwrap();
            requests.push((url.to_string(), body.to_string()));
            requests.len()
        };
        if call == 1 {
            self.first_started
                .lock()
                .unwrap()
                .take()
                .unwrap()
                .send(())
                .unwrap();
            self.release_first
                .lock()
                .unwrap()
                .take()
                .unwrap()
                .recv_timeout(Duration::from_secs(5))
                .unwrap();
            return Err(RelayError::Transport);
        }
        Ok(200)
    }
}

async fn wait_for_requests(transport: &FirstPostFailsAfterRelease, count: usize) {
    tokio::time::timeout(Duration::from_secs(5), async {
        loop {
            if transport.requests.lock().unwrap().len() >= count {
                return;
            }
            tokio::time::sleep(Duration::from_millis(10)).await;
        }
    })
    .await
    .unwrap();
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn failed_relay_coalesces_many_updates_and_recovers_with_only_latest_snapshot() {
    let directory = tempfile::tempdir().unwrap();
    let credentials = RelayCredentials::generate("https://ntfy.sh").unwrap();
    let (transport, started, release) = FirstPostFailsAfterRelease::new();
    let publisher = RelayPublisher::with_transport(
        credentials.clone(),
        directory.path().join("sequence.bin"),
        transport.clone(),
        vec![Duration::from_millis(10)],
    )
    .unwrap();
    let latest = RelayLatestPublisher::new(publisher);
    latest.submit(sample_payload()).unwrap();
    started.recv_timeout(Duration::from_secs(5)).unwrap();

    for index in 1..=2_000 {
        let mut snapshot = sample_payload();
        snapshot.tasks.generated_at_ms += index;
        snapshot.quota.generated_at =
            format!("2026-09-16T00:{:02}:{:02}.000Z", index / 60, index % 60);
        latest.submit(snapshot).unwrap();
        assert_eq!(latest.pending_count(), 1);
    }
    release.send(()).unwrap();
    wait_for_requests(&transport, 2).await;

    let requests = transport.requests.lock().unwrap();
    assert_eq!(requests.len(), 2);
    let envelope: RelayEnvelope = serde_json::from_str(&requests[1].1).unwrap();
    let plaintext = decrypt_payload_for_test(&credentials, &envelope).unwrap();
    assert_eq!(
        plaintext["quota"]["generatedAt"],
        "2026-09-16T00:33:20.000Z"
    );
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn credential_rotation_discards_old_pending_snapshot_before_new_topic_publish() {
    let directory = tempfile::tempdir().unwrap();
    let old = RelayCredentials::generate("https://ntfy.sh").unwrap();
    let new = RelayCredentials::generate("https://ntfy.sh").unwrap();
    let old_topic = old.topic_name();
    let new_topic = new.topic_name();
    let (transport, started, release) = FirstPostFailsAfterRelease::new();
    let publisher = RelayPublisher::with_transport(
        old,
        directory.path().join("sequence.bin"),
        transport.clone(),
        vec![],
    )
    .unwrap();
    let latest = RelayLatestPublisher::new(publisher.clone());
    latest.submit(sample_payload()).unwrap();
    started.recv_timeout(Duration::from_secs(5)).unwrap();
    let mut stale = sample_payload();
    stale.tasks.generated_at_ms += 1;
    latest.submit(stale).unwrap();

    std::thread::spawn(move || {
        std::thread::sleep(Duration::from_millis(50));
        release.send(()).unwrap();
    });
    publisher.set_credentials(new).await.unwrap();
    let mut fresh = sample_payload();
    fresh.tasks.generated_at_ms += 2;
    latest.submit(fresh).unwrap();
    wait_for_requests(&transport, 2).await;

    let requests = transport.requests.lock().unwrap();
    assert_eq!(requests.len(), 2);
    assert!(requests[0].0.ends_with(&old_topic));
    assert!(requests[1].0.ends_with(&new_topic));
}
