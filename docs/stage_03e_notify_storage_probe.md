# Stage 03E NotifyApi -> system-storage -> Lua probe

Date prepared: 2026-09-16.
Status: **real-device storage test still inconclusive; node discovery is fixed, but the synthetic notification has not yet been sent because XMS wearable authorization is not currently granted.**

## Why this stage exists

Stage 03A already proved the warm-background bridge on the target Xiaomi Smart Band 9 Pro:

```text
Android -> XMS / Mi Fitness -> non-foreground validation RPK
-> internal://files -> /data/quickapp/files/<app_id>/...
-> Lua true watchface
```

The unresolved product boundary is cold-start / post-reboot delivery. Stage 03E tests a different data route that does not depend on waking a Quick App:

```text
Android validation APK
-> Xiaomi NotifyApi / Mi Fitness
-> Band system notification handling
-> readable system notification persistence, if any
-> Lua true watchface
```

A matching companion RPK may still need to remain **installed** because Xiaomi XMS authorization is tied to the Android/wearable app pair. Stage 03E never opens that RPK and does not use it to transport the marker.

## XMS authorization prerequisite

The repository's earlier official Xiaomi Wearable Demo probe documented the following behavior:

- with only the Android APK installed, `requestPermissions` returned `APP not installed`;
- after installing the package/signature-matched RPK, Xiaomi Wearable permissions could be granted;
- the official flow requested both `DEVICE_MANAGER` and `NOTIFY` before sending through `NotifyApi`.

Stage 03E originally requested only `NOTIFY` and collapsed all permission exceptions into `DENIED`. That made `APP not installed`, explicit denial, and other request failures indistinguishable.

The hardened Stage 03E retry now checks and reports, in order:

```text
WearAppInstalled
DEVICE_MANAGER
NOTIFY
NotifyRequest
```

It also distinguishes `RPK_REQUIRED`, `DENIED`, `REQUEST_FAILED`, and related states without exposing node IDs, MAC addresses, serial numbers, or raw node names.

## Public evidence behind the candidate storage paths

The candidate paths are hypotheses, not established notification-payload locations:

- openVela projects publicly reference `/data/persist.db` as a persistent UnQLite database;
- community Xiaomi/Vela tooling publicly references `/data/persist.db.bk` and `/data/app/notifications/icon/` plus `/data/app/notifications/small/` as cache/storage paths.

Those references justify a read-only test. They do **not** establish that notification title/body text is stored there in clear text on this Band 9 Pro firmware.

## Safety boundary

Stage 03E must remain strictly inside these limits:

1. Keep Mi Fitness as the normal wearable connection manager.
2. A previously verified package/signature-matched validation RPK may remain installed solely to satisfy XMS authorization. Do not launch or wake it during Stage 03E. Do not use Stage 03C/03D experimental RPKs as the test baseline; prefer the known-good Stage 03A/03B validation RPK if an RPK reinstall is required.
3. Android may send exactly one synthetic notification through `NotifyApi` per user button press.
4. The Lua watchface may only open candidate files for reading and search for the exact synthetic markers.
5. Do not delete, truncate, rename, chmod, copy over, or otherwise mutate `/data/persist.db`, its backup, or `/data/app/notifications`.
6. Do not use root, LSPosed, Gadgetbridge, Notify for Xiaomi, or BLE takeover.
7. Do not send Codex/OpenAI credentials, prompts, task content, or real quota data during this probe.
8. Keep the probe I/O bounded: only database tail windows and a small capped set of notification files are scanned, at a low cadence, for a finite probe window.
9. The validation APK may use a single connected node fallback only when XMS returns exactly one node and the Band 9 Pro display-name matcher finds no match. This is diagnostic-only and must not be copied into the formal runtime without separate approval.

## Synthetic markers

Android sends:

```text
Title: CQNOTIFY-47-A9F3
Body:  SEQ47-WEEK38-RUN2
```

The watchface searches for those exact byte strings in bounded read-only windows:

```text
/data/persist.db                 -> last 512 KiB only
/data/persist.db.bk              -> last 512 KiB only
/data/app/notifications/**/*     -> max 16 files, max 128 KiB/file
```

The directory scan is best-effort. If `io.popen` is unavailable, the fixed database checks still run. The probe scans immediately, then at most every 15 seconds, and stops after 12 scans (about 3 minutes) or after `FOUND BOTH`.

## Build artifacts

Expected local-only artifacts:

```text
out/stage03e/CodexQuota-Stage03E-validation.apk
out/stage03e/CodexQuota-Stage03E-notify-storage.face
```

No new Stage 03E RPK is produced. If XMS authorization needs a wearable companion to be reinstalled, reuse the already verified validation RPK/signing identity from Stage 03A/03B rather than creating another experimental wearable app.

The validation APK keeps the isolated validation identity and signing setup already used by earlier Band 9 Pro probes. The watchface uses EasyFace device type `367` and 336x480 Lua/LVGL layout.

## Real-device results so far

The hardened Stage 03E watchface rendered normally. Its bounded scan eventually reported:

```text
NOT FOUND
SCAN 12/12
DB R 1/2
DIR R 0
DIR_ENUM_UNAVAILABLE
sources readable
```

This does **not** yet count as a negative storage result because the marker notification was not sent.

### Attempt 1: node discovery failure

The first Android build reported:

```text
Node: NODE_NOT_FOUND
NotifyPermission: NOT_CHECKED
NotifyRequest: NOT_SENT
NodeAttempt: 3
```

The validation node matcher was then hardened with sanitized counts and a single-node diagnostic fallback.

### Attempt 2: node found, wearable permission not granted

The next real-device report was:

```text
Node: NODE_FOUND
ConnectedNodes: 1
Band9ProMatches: 1
NodeSelection: NAME_MATCH
NotifyPermission: DENIED
NotifyRequest: NOT_SENT
NodeAttempt: 1
```

This proves XMS node discovery is functioning and the correct Band 9 Pro node is selected. It does not test the notification-storage hypothesis because the notification still was not sent.

The next APK therefore checks the package/signature-matched RPK installation state and follows the already-proven authorization order:

```text
isWearAppInstalled
-> DEVICE_MANAGER
-> NOTIFY
-> NotifyApi.sendNotify
```

## Real-device retry procedure

1. Keep the already-installed hardened Stage 03E `.face`; no watchface rebuild is needed.
2. Install/update the newest Stage 03E validation APK.
3. Confirm Mi Fitness shows the Band 9 Pro connected.
4. Press `检查权限并发送测试通知` once and copy the sanitized report.
5. If `WearAppInstalled` is not `TRUE`, install/overwrite the known-good Stage 03A/03B validation RPK with the matching validation signing identity. Do **not** open it. Reconnect Mi Fitness and retry once.
6. If Xiaomi/Mi Fitness presents authorization UI for `DEVICE_MANAGER` and `NOTIFY`, approve both for this validation pair.
7. Continue only when the report shows `WearAppInstalled: TRUE`, `DeviceManagerPermission: PASS`, and `NotifyPermission: PASS`.
8. If `NotifyRequest` is `CALLBACK_SUCCESS` or `REQUESTED_CALLBACK_TIMEOUT` and the band visibly receives the synthetic notification, return to the Stage 03E watchface.
9. Wait 15–45 seconds and record the watchface result. The fixed database scan can continue to its bounded 12-scan endpoint if needed.
10. If the Android report still says `NOT_SENT`, stop; the storage result remains inconclusive.
11. Restore the user's normal watchface after the result is recorded.

If the band becomes unstable, reboots unexpectedly, or the watchface repeatedly stalls, stop the probe and restore a known-good watchface before any further experiment.

## Interpretation

| Band receives NotifyApi notification | Watchface result | Interpretation |
| --- | --- | --- |
| yes | `FOUND BOTH` | Positive evidence that both synthetic notification fields are readable through the tested system-storage route. |
| yes | `PARTIAL` | One field is visible; promising but insufficient for a reliable quota payload bridge. |
| yes | `NOT FOUND` + sources readable | Negative result for these candidate paths / bounded clear-text representation only. |
| yes | sources unreadable | Inconclusive: notification delivery works, but Lua cannot read the tested storage. |
| no / not sent | any | Inconclusive for storage; first satisfy/diagnose XMS authorization and notification delivery. |

`REQUESTED_CALLBACK_TIMEOUT` on Android does not by itself mean NotifyApi delivery failed; actual band receipt is the decisive observation for notification delivery.

## Acceptance boundary

Stage 03E is considered a positive bridge candidate only if the Band receives the synthetic notification and the Lua watchface subsequently shows `FOUND BOTH` without the companion RPK being opened.

A positive result would justify a follow-up Stage 03F that determines the exact storage schema and whether quota/task values can be encoded safely without creating user-visible notification spam. A negative result after a **confirmed delivered notification** means this route should be dropped rather than expanded.
