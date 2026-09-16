# Stage 03A true-watchface bridge probe

Date prepared: 2026-09-16. Base: `e8c1ccc8a3160edfd6d022221fddd6b77ba56ed1`.
Status: **implementation prepared on an isolated branch; real-device result pending**.

## Question

Can Android deliver a synthetic sequence to the Band 9 Pro after the probe RPK is no longer in the
foreground, can the RPK persist it, and can a Lua watchface observe the persisted value?

This stage deliberately uses the already-proven validation identity
`io.github.rogerlang.codexquota.validation`. It does not test the formal product identity, real quota,
AOD, or the final watchface UI.

## Test chain

```text
Android Stage 03A validation APK
  -> XMS / Mi Fitness
  -> Stage 03A validation RPK
  -> internal://files/watchface_sequence.txt
  -> /data/files/io.github.rogerlang.codexquota.validation/watchface_sequence.txt
  -> Stage 03A Lua watchface
  -> SEQ 42 / SEQ 43
```

The RPK also writes `internal://files/watchface_state.json` for diagnostics. Only the integer sequence
is read by the Lua probe.

## Why this is a probe rather than an assumed architecture

Community evidence is inconsistent about Band 9 Pro cross-sandbox file access. Some Lua/watchface
projects demonstrate broad filesystem reads, while other Band 9 Pro development notes report that a
real watchface cannot read another Quick App's private path. Therefore Stage 03A tests both boundaries:

1. background/wake delivery to the Quick App; and
2. Lua access to the Quick App persisted file.

A failure is useful evidence and must not be hidden by root, LSPosed, Mi Fitness patches, Gadgetbridge,
or another BLE manager.

## Synthetic protocol

Android sends only validation data:

```json
{"type":"stage03_state","version":1,"sequence":42,"nonce":"<32 lowercase hex>"}
```

The RPK validates exact fields, writes the sequence, then replies only after the sequence file write
succeeds:

```json
{"type":"stage03_state_ack","version":1,"sequence":42,"nonce":"<same>","persisted":true}
```

`ACK_PERSISTED` proves only that the RPK handled the message and its file callback succeeded. It does
not prove the Lua watchface can read the file.

## Real-device procedure

1. Install `CodexQuota-Stage03A-validation.apk`.
2. Install/upgrade `CodexQuota-Stage03A-probe.rpk` with AstroBox. Mi Fitness remains the daily manager.
3. Install and select `CodexQuota-Stage03A-watchface.face`.
4. Open the Stage 03A Probe RPK once, then return to the Lua watchface.
5. On the phone tap **发送 42**. Confirm Android reports `ACK_PERSISTED` and the watchface shows `SEQ 42`.
6. Do **not** reopen the RPK. On the phone tap **发送 43**.
7. Wake the band and observe whether the same watchface changes to `SEQ 43`.
8. Copy the Android redacted report. If step 7 fails, reopen the RPK only after recording the failure
   and report the RPK's `Last persisted sequence` value.

No Windows computer, Codex token, real quota, USB, ADB, or logcat is required.

## Interpretation

| Android 43 result | Watchface after 43 | RPK value when reopened after failure | Interpretation |
| --- | --- | --- | --- |
| `ACK_PERSISTED` | `SEQ 43` | not needed | **Stage 03A PASS**: background delivery + persistence + Lua read/refresh all work |
| `ACK_TIMEOUT` | still `SEQ 42` | `42` | RPK did not receive/persist while not foreground; background/wake path is blocked or unreliable |
| `ACK_PERSISTED` | still `SEQ 42` | `43` | RPK background persistence works; Lua cross-sandbox read or refresh path is blocked |
| `ACK_TIMEOUT` | still `SEQ 42` | `43` | persistence may have occurred but ACK path failed; investigate RPK callback/message lifetime before conclusions |
| any | `CROSS FILE UNREADABLE` | `42` or `43` | Lua cannot access the Quick App file on this firmware/path; direct file bridge is not viable |

## Acceptance boundary

Stage 03A passes only when the watchface visibly changes `42 -> 43` without reopening the RPK between
the two sends. Passing does not yet validate formal product signing/identity or real Codex data.

Do not merge this branch to `main` based on build success alone. Record the user's real-device result
first.
