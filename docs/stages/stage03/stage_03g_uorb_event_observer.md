# Stage 03G uORB event observer

Date prepared: 2026-09-17.
Status: **REAL_DEVICE_FAILURE / QUARANTINED / DO_NOT_REDEPLOY**.
Real-device result date: 2026-09-17.

> **Do not reinstall or redeploy this probe to a physical Band 9 Pro
> without explicit owner authorization after a separate safety review.**
> The design and procedure below are retained as historical evidence, not as current instructions.
> See the [incident record](../../incidents/2026-09-17-stage03g-watchface-freeze.md)
> and [watchface safety rules](../../safety/watchface-development-safety.md).

## Real-device result

The Stage 03G Lua watchface was installed and selected on a Xiaomi Smart Band 9 Pro. After the
face opened, the device UI froze completely and touch input did not work normally. After a
forced restart, the device automatically entered Stage 03G and froze again. Mi Fitness could
not connect to the band and did not briefly show a connected state during forced restart.
Normal Bluetooth removal or switching of the face is currently unavailable. The owner has
contacted Xiaomi after-sales support; device recovery remains unconfirmed.

The observation does not identify which, if any, `topic.subscribe()` call, Lua lifecycle
behavior, or other component caused the freeze. It does not establish a firmware brick or
hardware damage. The Stage 03G source is retained for later forensic review. Its quarantine
must remain in force until the owner explicitly lifts it.

## Why Stage 03G exists

The previous true-watchface probes established the following boundaries on the target Xiaomi Smart Band 9 Pro:

```text
Android -> XMS -> active RPK -> Quick App file       PASS
Quick App file -> /data/quickapp/files -> Lua face   PASS
Android -> XMS -> inactive/non-foreground RPK        FAIL
NodeApi.launchWearApp -> RPK -> file                  PASS, but visibly takes over the UI
NotifyApi -> Band notification                         PASS
NotifyApi -> readable system file -> Lua face          NEGATIVE (Stage 03E/F)
```

The remaining question is whether Band system events are already exposed to a true Lua watchface through the internal `topic` / uORB bus, avoiding both an active RPK and notification-file scraping.

## Evidence motivating this probe

Public Band/Vela Lua sources show that true watchfaces can call `topic.subscribe(...)`. Band 9 Pro filesystem dumps also expose uORB nodes such as:

```text
/data/uorb/miwear_event0
/data/uorb/app_data_update0
/data/uorb/event_data_sync0
```

Reverse-engineered Vela Lua documentation lists these system topics among the registered uORB topics:

```text
miwear_event
system_event
app_data_update
event_data_sync
```

This evidence establishes that the system bus and Lua subscription API exist. It does **not** establish that `NotifyApi.sendNotify()` publishes notification payloads onto any of those topics. Stage 03G tests only that missing link.

## Probe design

The Stage 03G watchface subscribes to exactly four topics:

```text
miwear_event
system_event
app_data_update
event_data_sync
```

The lower-level `lc_proto_msg_rx` topic is deliberately excluded from this first probe because it may be substantially noisier and is not needed unless the documented system-topic set fails.

For each callback the watchface records only:

```text
callback count
status argument
Lua value type
length, if the value is a string/table
```

It never renders, saves, or logs the raw payload.

## Warmup / attribution

For the first 10 seconds after the watchface loads, callbacks are ignored. At 10 seconds the watchface resets all counters and changes state to:

```text
READY
```

Only events after `READY` are counted. This creates a clean local baseline before the phone sends the synthetic notification.

The face also displays an uptime counter. If the notification overlay causes the watchface process to restart, the reset phase/uptime makes that visible and the test is treated as inconclusive.

## Safety boundary

Stage 03G must not:

- change the Android APK;
- build, install, launch, or wake a new RPK;
- read or write system files;
- use shell commands;
- access the network;
- use root, LSPosed, Gadgetbridge, Notify for Xiaomi, or another BLE manager;
- render or persist raw uORB payloads;
- subscribe to broad/high-frequency sensor or protocol topics beyond the four explicit candidates.

The known-good validation RPK may remain installed solely because Xiaomi XMS authorization requires the package/signature-matched wearable companion. It must not be opened during this probe.

## Existing Android sender

Reuse the already-validated Stage 03E Android APK. It sends exactly:

```text
Title: CQNOTIFY-47-A9F3
Body:  SEQ47-WEEK38-RUN2
```

The Band 9 Pro has already been confirmed to display this notification and vibrate, despite the SDK callback timing out.

## Expected artifact

```text
out/stage03g/CodexQuota-Stage03G-uorb-observer.face
```

No APK or RPK should be produced for this stage.

## Real-device procedure

1. Keep Mi Fitness as the active wearable manager.
2. Install/select the Stage 03G `.face`.
3. Wait until it shows `READY`.
4. Note the four counters immediately before sending; ideally all are zero after the warmup reset.
5. Do not open any probe RPK.
6. In the existing Stage 03E validation APK, send one synthetic notification.
7. Confirm the Band displays the exact marker notification and vibrates.
8. Return immediately to the Stage 03G watchface.
9. Record:
   - phase (`READY` vs restarted `WARMUP`),
   - uptime,
   - all four counters,
   - status/type/length metadata.
10. If exactly one or a small subset of topics increments, wait about 10 seconds and send one second identical notification to test repeatability.

## Interpretation

### Positive candidate

A topic is a positive candidate only if:

1. the watchface remains alive (`READY`, uptime not reset);
2. the topic increments after a physically confirmed notification delivery;
3. the same topic increments again after a second delivered notification;
4. it is not continuously incrementing on its own between sends.

A positive Stage 03G result would justify a separate Stage 03H to inspect the **synthetic** payload structure for only that one topic.

### Negative

If all four counters remain unchanged across two physically confirmed notification deliveries while the watchface remains alive, the tested system-topic route is negative.

### Inconclusive

The test is inconclusive if the watchface restarts across the notification overlay or if a candidate topic is so noisy that notification-linked increments cannot be distinguished from background events.
