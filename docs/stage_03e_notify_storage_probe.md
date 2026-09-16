# Stage 03E NotifyApi -> system-storage -> Lua probe

Date prepared: 2026-09-16.
Status: **prepared for build and real-device validation; no device result yet**.

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

## Synthetic markers

Android sends:

```text
Title: CQNOTIFY-47-A9F3
Body:  SEQ47-WEEK38-RUN2
```

The watchface searches for those exact byte strings in:

```text
/data/persist.db
/data/persist.db.bk
/data/app/notifications/**/*
```

The directory scan is best-effort. If `io.popen` is unavailable, the fixed database checks still run.

## Build artifacts

Expected local-only artifacts:

```text
out/stage03e/CodexQuota-Stage03E-validation.apk
out/stage03e/CodexQuota-Stage03E-notify-storage.face
```

No RPK should be produced for Stage 03E.

The validation APK keeps the isolated validation identity and signing setup already used by earlier
Band 9 Pro probes. The watchface uses EasyFace device type `367` and 336x480 Lua/LVGL layout.

## Real-device procedure

1. Build the validation APK and Stage 03E `.face` only.
2. Install the `.face` and select it as the current watchface.
3. Install/update the Stage 03E validation APK on the Android phone.
4. Do not open any previous probe RPK during this test.
5. In the Android validation app, press `发送测试通知` once.
6. Confirm whether the Band 9 Pro receives and vibrates for the synthetic notification.
7. Return to the Stage 03E watchface and wait at least one 5-second scan cycle.
8. Record the watchface result and copy the Android sanitized report.

If the band becomes unstable, reboots unexpectedly, or the watchface repeatedly stalls, stop the probe
and restore a known-good watchface before any further experiment.

## Interpretation

| Band receives NotifyApi notification | Watchface result | Interpretation |
| --- | --- | --- |
| yes | `FOUND BOTH` | Positive evidence that both synthetic notification fields are readable through the tested system-storage route. |
| yes | `PARTIAL` | One field is visible; promising but insufficient for a reliable quota payload bridge. |
| yes | `NOT FOUND` + sources readable | Negative result for these candidate paths / clear-text representation only. |
| yes | sources unreadable | Inconclusive: notification delivery works, but Lua cannot read the tested storage. |
| no | any | Inconclusive for storage; first diagnose NotifyApi delivery / permission / connection. |

`REQUESTED_CALLBACK_TIMEOUT` on Android does not by itself mean NotifyApi delivery failed; as in the
previous real-device probe, actual band receipt is the decisive observation for notification delivery.

## Acceptance boundary

Stage 03E is considered a **positive bridge candidate** only if the Band receives the synthetic
notification and the Lua watchface subsequently shows `FOUND BOTH` without any RPK being opened.

A positive result would justify a follow-up Stage 03F that determines the exact storage schema and whether
quota/task values can be encoded safely without creating user-visible notification spam. A negative or
permission-limited result means this route should be dropped rather than expanded.
