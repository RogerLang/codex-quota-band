package org.zxor.oronbox.xms;

/** Selects the companion service used by the clean-room XMS client. */
public enum WearableBackend {
    /** Prefer OronBox, then fall back to Xiaomi Health. */
    AUTO,
    /** Only bind to OronBox. */
    ORONBOX,
    /** Only bind to Xiaomi Health / Xiaomi Wear. */
    XIAOMI
}
