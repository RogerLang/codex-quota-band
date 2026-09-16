use base64::Engine;
use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use chrono::{Duration as ChronoDuration, SecondsFormat, Utc};
use codex_quota_windows_core::network::SyncPayload;
use codex_quota_windows_core::relay::{RelayCredentials, RelayPublisher, RelaySequenceStore};
use codex_quota_windows_core::{
    ChatGptState, CodexLinkStatus, ComputerLinkStatus, QuotaLink, QuotaSnapshot, QuotaSourceStatus,
    QuotaWindow, QuotaWindowStatus, ResetInventorySnapshot, ResetInventoryStatus, TaskSyncSnapshot,
    UpstreamDatasetFreshness, UpstreamFreshness, UpstreamFreshnessStatus,
};
use serde_json::Value;
use std::fs;
use std::path::PathBuf;
use std::time::Duration;

const ARTIFACT_DIRECTORY_ENV: &str = "CODEX_QUOTA_FOUNDATION01V_DIR";

fn synthetic_payload() -> SyncPayload {
    let now = Utc::now();
    let generated_at = now.to_rfc3339_opts(SecondsFormat::Millis, true);
    let generated_at_ms = now.timestamp_millis();
    let freshness = UpstreamDatasetFreshness {
        status: UpstreamFreshnessStatus::Current,
        last_attempt_at: Some(generated_at.clone()),
        last_success_at: Some(generated_at.clone()),
    };
    SyncPayload {
        quota: QuotaSnapshot {
            protocol_version: 3,
            generated_at: generated_at.clone(),
            source_status: QuotaSourceStatus::Ok,
            limits_collected_at: Some(generated_at),
            windows: vec![
                QuotaWindow {
                    id: "synthetic-five-hour".to_string(),
                    name: "five_hour".to_string(),
                    window_minutes: 300,
                    remaining_percent: Some(73),
                    resets_at: (now + ChronoDuration::hours(5))
                        .to_rfc3339_opts(SecondsFormat::Millis, true),
                    status: QuotaWindowStatus::Current,
                },
                QuotaWindow {
                    id: "synthetic-weekly".to_string(),
                    name: "weekly".to_string(),
                    window_minutes: 10_080,
                    remaining_percent: Some(41),
                    resets_at: (now + ChronoDuration::days(7))
                        .to_rfc3339_opts(SecondsFormat::Millis, true),
                    status: QuotaWindowStatus::Current,
                },
            ],
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
            upstream_freshness: UpstreamFreshness {
                usage: freshness.clone(),
                reset_inventory: freshness,
            },
        },
        tasks: TaskSyncSnapshot {
            protocol_version: 1,
            sequence: 42,
            generated_at_ms,
            chat_gpt_state: ChatGptState::Running,
            chat_gpt_focused: false,
            tasks: vec![],
        },
    }
}

fn decode_pairing_payload(deep_link: &str) -> Value {
    let encoded = deep_link.split("relay=").nth(1).expect("pairing payload");
    let bytes = URL_SAFE_NO_PAD.decode(encoded).expect("pairing base64url");
    serde_json::from_slice(&bytes).expect("pairing json")
}

async fn cached_message(client: &reqwest::Client, topic: &str) -> String {
    let url = format!("https://ntfy.sh/{topic}/json?poll=1&since=latest");
    for _ in 0..10 {
        if let Ok(response) = client.get(&url).send().await
            && response.status().is_success()
            && let Ok(body) = response.text().await
            && let Some(line) = body.lines().find(|line| !line.trim().is_empty())
        {
            let value: Value = serde_json::from_str(line).expect("ntfy event json");
            if value["event"] == "message" {
                return line.to_string();
            }
        }
        tokio::time::sleep(Duration::from_millis(500)).await;
    }
    panic!("published ntfy message was not available from cache");
}

#[test]
#[ignore = "uses the public ntfy.sh relay and writes ephemeral validation material"]
fn official_publisher_reaches_public_ntfy_with_ciphertext_only() {
    let artifact_directory = PathBuf::from(
        std::env::var_os(ARTIFACT_DIRECTORY_ENV)
            .expect("CODEX_QUOTA_FOUNDATION01V_DIR must name an isolated temporary directory"),
    );
    fs::create_dir_all(&artifact_directory).expect("create validation directory");
    let sequence_path = artifact_directory.join("relay-sequence-v1.bin");
    let mut sequence = RelaySequenceStore::load(&sequence_path).expect("load sequence store");
    for expected in 1..=41 {
        assert_eq!(sequence.next().expect("seed sequence"), expected);
    }

    let credentials = RelayCredentials::generate("https://ntfy.sh").expect("random credentials");
    let deep_link = credentials.pairing_deep_link().expect("pairing link");
    let pairing = decode_pairing_payload(&deep_link);
    let topic = pairing["topic"].as_str().expect("topic");
    let key = pairing["key"].as_str().expect("key");
    let publisher = RelayPublisher::new(credentials, &sequence_path).expect("official publisher");
    let runtime = tokio::runtime::Runtime::new().expect("validation runtime");
    let event = runtime.block_on(async {
        publisher
            .publish(synthetic_payload())
            .await
            .expect("public ntfy publish");
        let client = reqwest::Client::builder()
            .timeout(Duration::from_secs(15))
            .build()
            .expect("audit client");
        cached_message(&client, topic).await
    });
    drop(runtime);
    let visible: Value = serde_json::from_str(&event).expect("cached event json");
    let message = visible["message"].as_str().expect("cached message body");
    let envelope: Value = serde_json::from_str(message).expect("relay envelope json");
    let mut fields = envelope
        .as_object()
        .expect("relay envelope object")
        .keys()
        .cloned()
        .collect::<Vec<_>>();
    fields.sort();
    assert_eq!(fields, ["ciphertext", "nonce", "version"]);
    for forbidden in [
        "73",
        "41",
        "fiveHourRemainingPercent",
        "weeklyRemainingPercent",
        "remainingPercent",
        "chatGptState",
        "running",
        "quota",
        "tasks",
        "accessToken",
        "Authorization",
        "Bearer ",
        key,
    ] {
        assert!(
            !event.contains(forbidden),
            "ntfy cache exposed forbidden plaintext"
        );
    }
    for environment_name in ["COMPUTERNAME", "USERNAME", "USERDOMAIN"] {
        if let Some(value) =
            std::env::var_os(environment_name).and_then(|value| value.into_string().ok())
            && value.len() >= 3
        {
            assert!(!event.contains(&value), "ntfy cache exposed local identity");
        }
    }

    fs::write(artifact_directory.join("pairing-link.txt"), deep_link)
        .expect("write pairing handoff");
    fs::write(artifact_directory.join("ntfy-event.json"), event).expect("write event handoff");
}
