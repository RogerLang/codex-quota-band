# Upstream provenance

- Repository: https://github.com/OrPudding/XMS_Wearable_SDK_CleanRoom
- Pinned commit: `6483f939785e9c1dd011465d573931f669a6adab`
- License: MIT (see `LICENSE` in this directory)

CodexQuota vendors this clean-room implementation so Android builds are reproducible without a
developer-private Xiaomi AAR or an unverified third-party Maven repository. The public
`com.xiaomi.xms.wearable.*` API remains source-compatible with the existing bridge. CodexQuota
explicitly selects `WearableBackend.XIAOMI` before creating any Wearable API so the runtime continues
to bind to the official Xiaomi Health/Xiaomi Wear XMS service rather than OronBox.
