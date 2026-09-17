# Stage 03G uORB event observer

This is a Band 9 Pro true-watchface validation probe. It asks one narrow question:

> After Android sends the already-validated synthetic `NotifyApi` marker, does the Band system publish any corresponding event onto a Lua-visible uORB topic?

It does **not** read or render event payload contents. It only records callback count and minimal metadata (`status`, Lua value type, and length where available).

## Topics observed

The first probe subscribes only to documented/observed system topics:

- `miwear_event`
- `system_event`
- `app_data_update`
- `event_data_sync`

`lc_proto_msg_rx` is intentionally **not** subscribed in this first probe because it is a lower-level protocol topic and could be much noisier.

## Safety boundary

- no RPK launch;
- no file writes;
- no system-file scan;
- no shell execution;
- no network access;
- no BLE takeover;
- no payload text is shown or persisted;
- existing Stage 03E validation APK is reused to send the synthetic notification.

## UI behavior

For the first 10 seconds the watchface shows `WARMUP 10s`. Event callbacks during warmup are ignored.

At 10 seconds all counters are reset and the face shows `READY`. Only callbacks after `READY` count toward the result.

Each row displays:

```text
TOPIC_NAME  count  s:<status> t:<type> n:<length>
```

The `UP <n>s` timer is also displayed. If returning from a notification overlay causes the watchface to restart, the uptime/phase will make that visible.

## Real-device procedure

1. Install/select the Stage 03G `.face`.
2. Wait until the face shows `READY` and note that counters are at zero (or record any spontaneous increments before sending).
3. Do not open any probe RPK.
4. Use the already-validated Stage 03E Android APK to send one synthetic notification:
   - title: `CQNOTIFY-47-A9F3`
   - body: `SEQ47-WEEK38-RUN2`
5. Confirm the Band displays the notification and vibrates.
6. Return to the Stage 03G watchface immediately.
7. Record the four counters, status/type/length metadata, the phase, and uptime.
8. For confirmation, if one specific topic increments exactly once, send the same synthetic notification one more time after about 10 seconds and check whether the same topic increments again.

## Interpretation

- A repeatable increment on the same topic immediately after each delivered notification is positive evidence that the Band system exposes a usable event signal to Lua.
- If a topic increments but is also noisy without notifications, it is not yet a usable bridge; payload structure would need a separate follow-up.
- If all four topics remain unchanged after two independently confirmed delivered notifications, this uORB candidate set is negative.
- If the watchface restarts after the notification overlay, Stage 03G is inconclusive because callback state may be lost.
