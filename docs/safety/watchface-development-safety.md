# Band 9 Pro watchface development safety gate

This gate applies to any future physical-device candidate. Work is simulator-first.
Successful compilation alone does not qualify a new Lua face for device testing.

```text
source change
  ↓
static review
  ↓
Band 9 Pro simulator validation
  ↓
normal load/unload test
  ↓
screen off/on lifecycle test
  ↓
simulated reboot / reload test
  ↓
rollback/recovery review
  ↓
owner approval
  ↓
physical-device candidate
```

## Risk review

Mark a probe **HIGH-RISK** if it uses `topic.subscribe`, uORB or system topics,
lifecycle callbacks, the system filesystem, boot/reload behavior, or persistent automatic
activation. Each HIGH-RISK probe requires a separate safety review before it can advance.

Before any physical-device test, document answers to all four questions:

1. If the face freezes, how can it be exited?
2. How can a known working face be restored?
3. How can the candidate face be uninstalled?
4. Will the candidate automatically reload after a restart?

If a recovery path cannot be established, the candidate does not advance to a physical
device. Experimental faces must not be left as the long-term default face.

## Current stop conditions

- Stage 03G is **QUARANTINED / DO_NOT_REDEPLOY**. Only the owner may explicitly lift this
  quarantine, after a separate safety review. Preserve the source for forensic review.
- Pause all physical-device watchface development until the current Band 9 Pro device's
  recovery is confirmed.
- This repository hygiene task does not attempt device recovery or any device operation.

See the [Stage 03G incident](../incidents/2026-09-17-stage03g-watchface-freeze.md).
