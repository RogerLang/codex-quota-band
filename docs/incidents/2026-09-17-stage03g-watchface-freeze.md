# Stage 03G Band 9 Pro watchface freeze

Date: 2026-09-17

Target device: Xiaomi Smart Band 9 Pro

```text
Incident status: REAL_DEVICE_FAILURE
Experiment status: QUARANTINED
Deployment status: DO_NOT_REDEPLOY
```

## Confirmed observations

- The Stage 03G Lua watchface was installed and selected.
- On entering the face, the device UI froze completely; touch input did not work normally.
- After a forced restart, the device automatically entered Stage 03G and froze again.
- Mi Fitness could not connect to the band. During forced restart it did not show even a
  brief connected state.
- The face cannot currently be removed or switched through the normal Bluetooth path.
- The owner has contacted Xiaomi after-sales support.
- The device's recovery status has not been confirmed.

## Evidence boundary and quarantine

There is currently no evidence identifying a particular `topic.subscribe()` call, Lua
lifecycle behavior, or other component as the cause. The observations do not establish a
firmware brick and do not establish hardware damage.

The [Stage 03G source](../../experiments/watchface/stage03g-uorb-event-observer/)
and [probe design](../stages/stage03/stage_03g_uorb_event_observer.md) are retained for
forensic review. Do not reinstall or redeploy Stage 03G to a physical Band 9 Pro unless
the owner explicitly lifts the quarantine after a separate safety review. Physical-device
watchface development is paused pending device recovery. See the
[watchface safety rules](../safety/watchface-development-safety.md).
