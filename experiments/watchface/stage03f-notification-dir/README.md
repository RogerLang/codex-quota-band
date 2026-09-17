# Stage 03F notification-directory watchface probe

Target: Xiaomi Smart Band 9 Pro, 336x480, EasyFace device type `367`.

Stage 03E proved that the synthetic NotifyApi notification reaches the real Band 9 Pro and vibrates, but its exact title/body markers were not found in the bounded `/data/persist.db` / `.bk` scan. The Stage 03E watchface could not enumerate `/data/app/notifications` because `io.popen` is unavailable in this Lua runtime.

Stage 03F tests only the remaining local-storage candidate using the Lua/LVGL filesystem binding that community Band 9 Pro code already uses: `lvgl.fs.open_dir()` and `dir:read()`.

## Scope

The probe is strictly read-only. It:

- opens `/data/app/notifications` with `lvgl.fs.open_dir()`;
- counts at most 32 root entries, without displaying entry names;
- directly checks the Band 9 Pro-specific known database `/data/app/notifications/noti_reply_sms.db`;
- reads at most the last 512 KiB of that database per scan;
- searches only for the existing Stage 03E synthetic markers;
- runs at most 8 scans at 15-second intervals.

It does not recurse through unrelated `/data` paths, does not launch an RPK, and does not write/delete/rename/chmod/truncate system files.

## Markers

Use the already-built Stage 03E validation APK to send:

- title: `CQNOTIFY-47-A9F3`
- body: `SEQ47-WEEK38-RUN2`

## Result meanings

- `FOUND BOTH`: both markers were readable from the known notifications database.
- `PARTIAL`: exactly one marker was readable.
- `NOT FOUND` with `ROOT OK`: the notification directory is visible to the watchface, but the known database did not expose the markers in the bounded read window.
- `NOT FOUND` with `ROOT FAIL`: the directory itself is not available through the Lua/LVGL filesystem binding on this firmware.

Root entry/file counts are diagnostic only and intentionally do not reveal file names.

## Build

```powershell
.\experiments\watchface\stage03f-notification-dir\build.ps1
```

Expected artifact:

`out/stage03f/CodexQuota-Stage03F-notification-dir.face`

No APK or RPK needs to be rebuilt for Stage 03F.
