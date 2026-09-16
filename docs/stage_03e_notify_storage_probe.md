# Stage 03E NotifyApi -> system-storage -> Lua probe

Date prepared: 2026-09-16.
Status: **real-device notification delivery PASS; bounded persist-db marker scan NEGATIVE; notification-directory branch moved to Stage 03F.**

## Why this stage exists

Stage 03A proved that a true Lua watchface on the target Band 9 Pro can read persisted Quick App data from `/data/quickapp/files/<app_id>/`, but it also showed that `MessageApi` does not update an inactive/non-foreground RPK. Stage 03B proved that `NodeApi.launchWearApp()` can explicitly wake the RPK and persist new state, but this visibly takes over the band UI.

Stage 03E therefore tested whether the already-proven Xiaomi `NotifyApi` path could avoid waking a Quick App entirely:

```text
Android validation APK
-> Xiaomi NotifyApi / Mi Fitness
-> Band system notification handling
-> readable system notification persistence, if any
-> Lua true watchface
```

A package/signature-matched validation RPK remained installed only because Xiaomi XMS authorization is tied to the Android/wearable app pair. The RPK was not opened or used to transport the marker.

## Confirmed Android / XMS result

The final validation APK reported:

```text
Node: NODE_FOUND
ConnectedNodes: 1
Band9ProMatches: 1
NodeSelection: NAME_MATCH
WearAppInstalled: TRUE
DeviceManagerPermission: PASS
NotifyPermission: PASS
NotifyRequest: REQUESTED_CALLBACK_TIMEOUT
NodeAttempt: 1
```

The Band 9 Pro visibly displayed the exact synthetic notification and vibrated:

```text
Title: CQNOTIFY-47-A9F3
Body:  SEQ47-WEEK38-RUN2
```

Therefore Android -> XMS / Mi Fitness -> Band 9 Pro system notification delivery is **PASS**. As in Stage 02D, `REQUESTED_CALLBACK_TIMEOUT` is an API callback observation and does not mean delivery failed when the physical band receipt is confirmed.

## Bounded persist-db result

The hardened Stage 03E Lua watchface completed all 12 scans and reported:

```text
NOT FOUND
SCAN 12/12
DB R 1/2
DIR R 0
DIR_ENUM_UNAVAILABLE
sources readable
```

The probe scanned bounded read-only windows of:

```text
/data/persist.db
/data/persist.db.bk
```

Result: neither synthetic marker was visible in the tested persist-db windows after a notification that was independently confirmed delivered to the band.

This is a **negative result for the bounded `/data/persist.db` / `.bk` clear-text hypothesis**. It is not evidence that notification text is never persisted elsewhere.

## Remaining notification-directory boundary

The Stage 03E watchface attempted to enumerate `/data/app/notifications` through `io.popen`, but this Lua runtime reported `DIR_ENUM_UNAVAILABLE`. Therefore Stage 03E never actually inspected files under that directory.

Band 9 Pro community filesystem evidence identifies at least:

```text
/data/app/notifications/noti_reply_sms.db
```

and community Lua code on Xiaomi wearables uses `lvgl.fs.open_dir()` / directory handles. That narrow remaining branch moved to Stage 03F.

## Safety boundary retained

- Keep Mi Fitness as the normal wearable connection manager.
- Do not launch/wake the validation RPK during notification-storage probes.
- No root, LSPosed, Gadgetbridge, Notify for Xiaomi, or BLE takeover.
- Do not delete, truncate, rename, chmod, overwrite, or otherwise mutate system storage.
- Use only synthetic markers; no real Codex prompts, credentials, task content, or quota data.
- Restore the user's normal watchface after validation.

## Interpretation

Stage 03E establishes:

```text
NotifyApi delivery to Band 9 Pro          PASS
persist.db/.bk bounded marker search      NEGATIVE
/data/app/notifications enumeration       NOT TESTED IN 03E
```

Stage 03E is therefore **not a positive bridge candidate by itself**. Stage 03F is the final narrow test for the notification-directory branch.