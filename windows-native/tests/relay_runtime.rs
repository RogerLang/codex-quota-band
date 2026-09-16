use codex_quota_windows_core::relay_host::{RelayHost, RelayHostPaths};

#[test]
fn relay_host_starts_without_lan_listener_or_udp_discovery() {
    let directory = tempfile::tempdir().unwrap();
    let host = RelayHost::start(
        RelayHostPaths::in_data_directory(directory.path()),
        "https://ntfy.sh",
    )
    .unwrap();
    assert!(host.relay_ready());

    let entrypoint = include_str!("../src/bin/codex_quota_windows.rs");
    assert!(entrypoint.contains("RelayHost::start"));
    assert!(!entrypoint.contains("WindowsHost::start"));
    assert!(!entrypoint.contains("private_ipv4_addresses"));
    assert!(!entrypoint.contains("spawn_broadcast"));
}
