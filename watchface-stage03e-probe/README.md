# Stage 03E notification-storage watchface probe

Target: Xiaomi Smart Band 9 Pro, 336x480, EasyFace device type `367`.

This is a validation-only Lua watchface. It does **not** launch a Quick App, install an RPK, delete files,
or modify the notification database. Its only purpose is to test whether notification text delivered by
Xiaomi `NotifyApi` becomes visible to a true Lua watchface through system storage that is readable on the
target firmware.

## Synthetic markers

The Android validation APK sends one notification with:

- title: `CQNOTIFY-47-A9F3`
- body: `SEQ47-WEEK38-RUN2`

The watchface scans only these candidate locations:

- `/data/persist.db`
- `/data/persist.db.bk`
- regular files below `/data/app/notifications/`

Public openVela/community evidence makes these paths reasonable candidates, but it does **not** establish
that user-visible notification title/body text is stored there. Stage 03E exists specifically to test that
boundary on the real Band 9 Pro.

## Read-only behavior

The probe opens candidate files in `rb` mode and searches for the two exact synthetic markers. Directory
enumeration, when available, uses `find /data/app/notifications -type f` through `io.popen`; no shell
command writes, removes, renames, or changes permissions on any system path.

The screen reports:

- `FOUND BOTH`: both exact markers were readable from candidate system storage;
- `PARTIAL`: exactly one marker was readable;
- `NOT FOUND` + `sources readable`: candidate storage was readable but neither marker was observed;
- `NOT FOUND` + `sources unreadable`: the probe could not read the fixed database files or any enumerated
  notification file;
- `DIR_ENUM_UNAVAILABLE`: this firmware/runtime did not expose `io.popen` directory enumeration. The fixed
  database checks still run.

`FOUND BOTH` is positive evidence for a system-storage bridge candidate. `NOT FOUND` only rules out the
candidate paths and representation checked by this probe; it does not prove that notifications are never
persisted elsewhere or are not encoded/transformed.

## Build

Run from Windows PowerShell:

```powershell
.\watchface-stage03e-probe\build.ps1
```

The script pins `FangAiden/LuaDevTemplate` to commit
`0eb8346ce0c9c11f2316c6b154ed91fd4a0d419d`, generates the compiler-required preview bitmap locally, and
builds:

`out/stage03e/CodexQuota-Stage03E-notify-storage.face`

The build script does not install the watchface on a device.
