# Stage 03F notification-directory probe

Date prepared: 2026-09-16.
Status: **NEGATIVE on Xiaomi Smart Band 9 Pro real device; current NotifyApi -> readable-file -> Lua bridge route is closed.**

## Question

After a synthetic NotifyApi notification is physically confirmed on the Band 9 Pro, can a true Lua watchface read the same marker from `/data/app/notifications` without launching any RPK?

## Why this is separate from Stage 03E

Stage 03E completed a bounded read-only scan of `/data/persist.db` and `/data/persist.db.bk` after confirmed notification delivery and found neither marker. Its attempt to enumerate `/data/app/notifications` failed only because `io.popen` is unavailable in the watchface runtime.

Stage 03F switched directory enumeration to the Lua/LVGL filesystem binding. Community Lua code for Xiaomi wearables uses:

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

Stage 03F remained narrower than Stage 03E:

- no APK change; reused the Stage 03E validation APK;
- no RPK change and no RPK launch;
- no shell execution;
- no recursive scan of unrelated `/data` paths;
- root directory enumeration capped at 32 entries and only counted entries/files;
- file names were not shown in the UI/report;
- only the known `noti_reply_sms.db` was read;
- at most the last 512 KiB of that database was read per scan;
- at most 8 scans, 15 seconds apart;
- all file opens were read-only.

## Synthetic markers

Reused Stage 03E:

```text
CQNOTIFY-47-A9F3
SEQ47-WEEK38-RUN2
```

The Android validation path was already confirmed immediately before Stage 03F:

```text
Node: NODE_FOUND
ConnectedNodes: 1
Band9ProMatches: 1
NodeSelection: NAME_MATCH
WearAppInstalled: TRUE
DeviceManagerPermission: PASS
NotifyPermission: PASS
NotifyRequest: REQUESTED_CALLBACK_TIMEOUT
```

The Band 9 Pro physically displayed the marker notification and vibrated, so notification delivery itself was independently confirmed PASS.

## Real-device result

The Stage 03F watchface completed all 8 scans and reported:

```text
NOT FOUND
SCAN 8/8
ROOT OK
ENTRIES 3
ROOTFILES 1
REPLYDB READ
no marker in known DB
SRC --
```

Interpretation:

- `/data/app/notifications` is visible to the Lua watchface through `lvgl.fs.open_dir()`;
- the directory contains readable content;
- the known `noti_reply_sms.db` is readable;
- neither synthetic marker is present in the tested database window after a notification that was physically confirmed delivered to the band.

## Final decision for this route

Combining Stage 03E and Stage 03F:

```text
NotifyApi delivery to Band 9 Pro                PASS
/data/persist.db(.bk) bounded clear-text scan   NEGATIVE
/data/app/notifications directory access        PASS
noti_reply_sms.db marker scan                    NEGATIVE
```

Therefore the current **NotifyApi -> readable system file -> Lua watchface** bridge is considered **NEGATIVE / CLOSED**.

Do not expand the filesystem scan to broader `/data` paths without new, specific evidence identifying another notification-payload store. The current negative result is sufficiently clean to stop this branch rather than continue blind filesystem probing.

This does not change the separate conclusions that:

- a Lua watchface can read Quick App persisted files under `/data/quickapp/files/<app_id>/` on this firmware;
- `MessageApi` alone did not update an inactive/non-foreground RPK in Stage 03A;
- explicit `NodeApi.launchWearApp()` can wake the RPK and persist state, but visibly takes over the band UI.
