# Stage 03A true-watchface bridge probe

Date prepared: 2026-09-16. Base: `e8c1ccc8a3160edfd6d022221fddd6b77ba56ed1`.
Status: **PASS on Xiaomi Smart Band 9 Pro real device for the warm-background bridge path**.

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
  -> /data/quickapp/files/io.github.rogerlang.codexquota.validation/watchface_sequence.txt
  -> Stage 03A Lua watchface
  -> SEQ 42 / SEQ 43
```

Real-device probing showed that this Band 9 Pro firmware exposes the Quick App `internal://files`
physical path under `/data/quickapp/files/<app_id>/`, not the initially assumed `/data/files/<app_id>/`.
The Stage 03A2 watchface therefore tries known candidate roots and can scan for the probe file rather
than hard-coding only one physical path.

The RPK also writes `internal://files/watchface_state.json` for diagnostics. Only the integer sequence
is read by the Lua probe.

## Why this was a probe rather than an assumed architecture

Community evidence was inconsistent about Band 9 Pro cross-sandbox file access. Some Lua/watchface
projects demonstrate broad filesystem reads, while other Band 9 Pro development notes report that a
real watchface cannot read another Quick App's private path. Stage 03A therefore tested both boundaries:

1. background delivery to the Quick App while its page is no longer foreground; and
2. Lua access to the Quick App persisted file.

The first fixed path attempt failed with `CROSS FILE UNREADABLE`; Stage 03A2 then discovered the real
Quick App root at `/data/quickapp/files` and read the same persisted sequence successfully. This shows
that the earlier failure was a path assumption problem, not evidence of cross-sandbox denial on this
device/firmware.

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
not by itself prove the Lua watchface can read the file.

## Real-device result

The user performed the intended sequence test on a Xiaomi Smart Band 9 Pro:

1. Stage 03A Probe RPK was opened once.
2. Android sent sequence `42` and the probe persisted it.
3. The user returned to the Stage 03A2 Lua watchface.
4. Without reopening the RPK, Android sent sequence `43`.
5. The same watchface visibly changed to `SEQ 43` and displayed `RPK FILE READ OK`.
6. The watchface reported the discovered root as `/data/quickapp/files`.

Result:

```text
Android -> XMS / Mi Fitness -> non-foreground RPK -> internal://files
-> /data/quickapp/files/<app_id>/... -> Lua watchface -> live refresh
```

**Stage 03A PASS.**

This is the first real-device evidence that the true-watchface data bridge is technically viable on the
target Band 9 Pro without replacing Mi Fitness, root, LSPosed, Gadgetbridge, or another BLE manager.

## Remaining lifecycle boundary

Stage 03A PASS is a warm-background result: the RPK page was no longer foreground, but this test does
not prove that Xiaomi/Vela will cold-start or wake the Quick App after its process has been killed or
after a band reboot.

Before relying on the bridge as an unattended product path, run a separate lifecycle hardening check:

1. reboot the Band 9 Pro;
2. wait for Mi Fitness/XMS to reconnect;
3. select the Stage 03A2 watchface but do **not** open the Probe RPK;
4. send sequence `42` from the existing Android validation APK;
5. observe both Android ACK and the watchface value.

If the RPK is automatically woken and the watchface changes, cold-start delivery is supported. If not,
the product needs an explicit lifecycle strategy even though the warm-background bridge itself is valid.

## Interpretation table retained for regressions

| Android send result | Watchface result | Interpretation |
| --- | --- | --- |
| `ACK_PERSISTED` | sequence changes | bridge works end-to-end |
| `ACK_TIMEOUT` | unchanged | RPK did not receive/persist or ACK path was unavailable |
| `ACK_PERSISTED` | unchanged | persistence works; Lua read/refresh path failed |
| any | `FOUND BUT UNREADABLE` | path exists but Lua read is denied |
| any | `SCAN NO FILE` | Lua cannot locate the persisted file in visible `/data` roots |

## Acceptance boundary

Stage 03A passes when the watchface visibly changes `42 -> 43` without reopening the RPK between the
two sends. That condition has now been met on the real Band 9 Pro.

Passing does not yet validate:

- cold-start/reboot wake behavior;
- formal product signing/identity;
- real Windows -> ntfy -> Android -> Band quota/task data;
- final 336x480 product watchface UI;
- AOD.

Do not merge this branch to `main` as final product support until the next lifecycle/formal-identity
steps are completed or explicitly accepted with their remaining limitations.
