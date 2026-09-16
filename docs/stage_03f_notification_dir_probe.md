# Stage 03F notification-directory probe

Date prepared: 2026-09-16.
Status: **prepared for build and real-device validation; no device result yet**.

## Question

After a synthetic NotifyApi notification is physically confirmed on the Band 9 Pro, can a true Lua watchface read the same marker from `/data/app/notifications` without launching any RPK?

## Why this is separate from Stage 03E

Stage 03E completed a bounded read-only scan of `/data/persist.db` and `/data/persist.db.bk` after confirmed notification delivery and found neither marker. Its attempt to enumerate `/data/app/notifications` failed only because `io.popen` is unavailable in the watchface runtime.

Stage 03F switches directory enumeration to the Lua/LVGL filesystem binding. Community Lua code for Xiaomi wearables uses:

```lua
local dir = lvgl.fs.open_dir(path)
local entry = dir:read()
dir:close()
```

Band 9 Pro-specific filesystem evidence also identifies the known file:

```text
/data/app/notifications/noti_reply_sms.db
```

## Safety limits

Stage 03F is narrower than Stage 03E:

- no APK change; reuse the Stage 03E validation APK;
- no RPK change and no RPK launch;
- no shell execution;
- no recursive scan of unrelated `/data` paths;
- root directory enumeration is capped at 32 entries and only counts entries/files;
- file names are not shown in the UI/report;
- only the known `noti_reply_sms.db` is read;
- at most the last 512 KiB of that database is read per scan;
- at most 8 scans, 15 seconds apart;
- all file opens are read-only.

## Synthetic markers

Reuse Stage 03E:

```text
CQNOTIFY-47-A9F3
SEQ47-WEEK38-RUN2
```

## Expected artifact

```text
out/stage03f/CodexQuota-Stage03F-notification-dir.face
```

## Real-device procedure

1. Keep the known-good package/signature-matched validation RPK installed only for XMS authorization; do not open it.
2. Install and select the Stage 03F `.face`.
3. Confirm the watchface renders normally.
4. Use the already-built Stage 03E validation APK to send the synthetic notification once.
5. Confirm the band displays the exact notification and vibrates.
6. Return to the Stage 03F watchface and wait through the bounded scan window.
7. Record the final result.

## Interpretation

- `FOUND BOTH`: notification title/body are readable from the known notification database; this route remains viable.
- `PARTIAL`: one marker is visible; investigate representation/schema once more.
- `NOT FOUND` + `ROOT OK` + `REPLYDB READ`: directory and known DB are accessible but do not expose the marker in the tested window; drop the current NotifyApi storage-bridge route.
- `ROOT OK` + `REPLYDB UNREADABLE`: directory is visible but the known DB cannot be read; route remains technically inconclusive but should not be expanded without new evidence.
- `ROOT FAIL`: `/data/app/notifications` is not available through this Lua filesystem binding on the target firmware.
