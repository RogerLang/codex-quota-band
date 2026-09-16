# Stage 03A true-watchface bridge probe

Date prepared: 2026-09-16. Base: `e8c1ccc8a3160edfd6d022221fddd6b77ba56ed1`.
Status: **PARTIAL PASS on Xiaomi Smart Band 9 Pro: Quick App file -> Lua watchface works; Android -> non-foreground RPK delivery does not.**

## Question

Can Android deliver a synthetic sequence to the Band 9 Pro after the probe RPK is no longer in the foreground, can the RPK persist it, and can a Lua watchface observe the persisted value?

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
  -> SEQ <n>
```

Real-device probing showed that this Band 9 Pro firmware exposes the Quick App `internal://files`
physical path under `/data/quickapp/files/<app_id>/`, not the initially assumed `/data/files/<app_id>/`.
The Stage 03A2 watchface therefore tries known candidate roots and can locate/read the persisted sequence.

## Real-device result

Two platform boundaries were tested separately.

### 1. RPK file -> Lua watchface

PASS.

When the RPK had received and persisted a sequence while active, the Lua watchface successfully read the
same value from:

```text
/data/quickapp/files/io.github.rogerlang.codexquota.validation/watchface_sequence.txt
```

The watchface visibly rendered the persisted sequence and displayed `RPK FILE READ OK`. This proves the
Quick App sandbox file is readable from the true Lua watchface on this target Band 9 Pro firmware.

### 2. Android -> non-foreground RPK

FAIL.

The user then kept the Lua watchface active, did **not** reopen the RPK, and sent the next sequence from the
Android validation APK. The watchface did not change. The RPK was therefore not receiving/persisting the
new message while it was no longer active.

The later visible `SEQ 43` observation was obtained only after the RPK had been active again; it proves the
file-read path, not background delivery.

Result:

```text
Android -> active RPK -> internal://files                 PASS
RPK persisted file -> /data/quickapp/files -> Lua face    PASS
Android -> inactive/non-foreground RPK                    FAIL
```

**Stage 03A is PARTIAL PASS, not end-to-end background PASS.**

## Stage 03B follow-up

Stage 03B used `NodeApi.launchWearApp()` before sending the message. Real-device validation showed:

```text
LaunchWearApp: SUCCESS
Message: ACK_PERSISTED
```

The band visibly switched away from the Lua watchface into the RPK, and after the user manually returned
to the watchface it showed the newly persisted sequence. Therefore explicit foreground launch can bridge
the data, but it disrupts the watchface and is not an acceptable unattended product path by itself.

## Synthetic protocol

Android sends only validation data:

```json
{"type":"stage03_state","version":1,"sequence":42,"nonce":"<32 lowercase hex>"}
```

The RPK validates exact fields, writes the sequence, then replies only after persistence succeeds:

```json
{"type":"stage03_state_ack","version":1,"sequence":42,"nonce":"<same>","persisted":true}
```

`ACK_PERSISTED` proves only that the RPK handled the message and its file callback succeeded. It does not
prove that an inactive RPK can be woken by `MessageApi` alone.

## Acceptance boundary

For an unattended true-watchface bridge, all of the following would be required:

```text
Android -> band without foreground disruption
-> persistent band-side state
-> Lua watchface live read
```

Stage 03A proved only the persistent-state -> Lua half. Stage 03B proved that explicit app launch can fill
the missing Android -> RPK half, but at the cost of visibly taking over the screen.

Do not describe Stage 03A as a warm-background end-to-end PASS.