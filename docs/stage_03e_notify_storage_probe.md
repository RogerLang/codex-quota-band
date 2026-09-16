# Stage 03E NotifyApi -> system-storage -> Lua probe

Date prepared: 2026-09-16.
Status: **real-device storage test still inconclusive; Android notification was not sent because node discovery did not select the connected wearable.**

## Why this stage exists

Stage 03A already proved the warm-background bridge on the target Xiaomi Smart Band 9 Pro:

```text
Android -> XMS / Mi Fitness -> non-foreground validation RPK
-> internal://files -> /data/quickapp/files/<app_id>/...
-> Lua true watchface
```

The unresolved product boundary is cold-start / post-reboot delivery. Stage 03E deliberately tests a
different route that does not depend on waking a Quick App at all:

```text
Android validation APK
-> Xiaomi NotifyApi / Mi Fitness
-> Band system notification handling
-> readable system notification persistence, if any
-> Lua true watchface
```

This is a narrow feasibility probe, not a new product architecture.

## Public evidence behind the candidate paths

The candidate paths are hypotheses, not established notification-payload locations:

- openVela projects publicly reference `/data/persist.db` as a persistent UnQLite database;
- community Xiaomi/Vela tooling publicly references `/data/persist.db.bk` and
  `/data/app/notifications/icon/` plus `/data/app/notifications/small/` as cache/storage paths.

Those references justify a read-only test. They do **not** establish that notification title/body text is
stored there in clear text on this Band 9 Pro firmware.

## Safety boundary

Stage 03E must remain strictly inside these limits:

1. Keep Mi Fitness as the normal wearable connection manager.
2. Do not install, launch, wake, or modify any Stage 03E RPK. Stage 03E has no RPK artifact.
3. Android may send exactly one synthetic notification through `NotifyApi` per user button press.
4. The Lua watchface may only open candidate files for reading and search for the exact synthetic markers.
5. Do not delete, truncate, rename, chmod, copy over, or otherwise mutate `/data/persist.db`, its backup,
   or `/data/app/notifications`.
6. Do not use root, LSPosed, Gadgetbridge, Notify for Xiaomi, or BLE takeover.
7. Do not send Codex/OpenAI credentials, prompts, task content, or real quota data during this probe.
8. Keep the probe I/O bounded: only database tail windows and a small capped set of notification files are
   scanned, at a low cadence, for a finite probe window.
9. The validation APK may use a **single connected node fallback only when XMS returns exactly one node and
   the Band 9 Pro display-name matcher finds no match**. This is diagnostic-only and must not be copied into
   the formal runtime without separate approval.

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

The directory scan is best-effort. If `io.popen` is unavailable, the fixed database checks still run.
The probe scans immediately, then at most every 15 seconds, and stops after 12 scans (about 3 minutes) or
after `FOUND BOTH`.

## Build artifacts

Expected local-only artifacts:

```text
out/stage03e/CodexQuota-Stage03E-validation.apk
out/stage03e/CodexQuota-Stage03E-notify-storage.face
```

No RPK should be produced for Stage 03E.

The validation APK keeps the isolated validation identity and signing setup already used by earlier
Band 9 Pro probes. The watchface uses EasyFace device type `367` and 336x480 Lua/LVGL layout.

## First real-device attempt

The hardened Stage 03E watchface rendered normally and reported:

```text
NOT FOUND
SCAN 2/12
DB R 1/2
DIR R 0
DIR_ENUM_UNAVAILABLE
sources readable
```

The paired Android validation APK, however, reported twice:

```text
Node: NODE_NOT_FOUND
NotifyPermission: NOT_CHECKED
NotifyRequest: NOT_SENT
NodeAttempt: 3
```

Therefore the synthetic marker notification was never sent. The visible `NOT FOUND` result cannot be
interpreted as evidence against the notification-storage bridge. It only establishes that one of the two
bounded database candidates was readable on this firmware and that `io.popen` directory enumeration was
unavailable in the watchface runtime.

The retry APK now reports only sanitized node diagnostics:

```text
ConnectedNodes: <count>
Band9ProMatches: <count>
NodeSelection: NAME_MATCH | SINGLE_NODE_FALLBACK | AMBIGUOUS_NAME_MATCH | NONE
```

It never reports node id, MAC address, raw node name, serial number, or another device identifier.

## Real-device retry procedure

1. Keep the already-installed hardened Stage 03E `.face`; no watchface rebuild is needed for this retry.
2. Do not open any old Probe RPK.
3. Install/update only the new Stage 03E validation APK.
4. Confirm Mi Fitness shows the Band 9 Pro connected.
5. Press `发送测试通知` once.
6. Copy the sanitized Android report.
7. If `NotifyRequest` is `CALLBACK_SUCCESS` or `REQUESTED_CALLBACK_TIMEOUT` and the band visibly receives
   the notification, return to the Stage 03E watchface.
8. Wait 15–45 seconds and record the watchface result.
9. If the Android report still says `NOT_SENT`, stop; the storage result remains inconclusive.
10. Restore the user's normal watchface after the result is recorded.

If the band becomes unstable, reboots unexpectedly, or the watchface repeatedly stalls, stop the probe
and restore a known-good watchface before any further experiment.

## Interpretation

| Band receives NotifyApi notification | Watchface result | Interpretation |
| --- | --- | --- |
| yes | `FOUND BOTH` | Positive evidence that both synthetic notification fields are readable through the tested system-storage route. |
| yes | `PARTIAL` | One field is visible; promising but insufficient for a reliable quota payload bridge. |
| yes | `NOT FOUND` + sources readable | Negative result for these candidate paths / bounded clear-text representation only. |
| yes | sources unreadable | Inconclusive: notification delivery works, but Lua cannot read the tested storage. |
| no / not sent | any | Inconclusive for storage; first diagnose NotifyApi delivery / permission / connection. |

`REQUESTED_CALLBACK_TIMEOUT` on Android does not by itself mean NotifyApi delivery failed; as in the
previous real-device probe, actual band receipt is the decisive observation for notification delivery.

## Acceptance boundary

Stage 03E is considered a **positive bridge candidate** only if the Band receives the synthetic
notification and the Lua watchface subsequently shows `FOUND BOTH` without any RPK being opened.

A positive result would justify a follow-up Stage 03F that determines the exact storage schema and whether
quota/task values can be encoded safely without creating user-visible notification spam. A negative or
permission-limited result means this route should be dropped rather than expanded.
