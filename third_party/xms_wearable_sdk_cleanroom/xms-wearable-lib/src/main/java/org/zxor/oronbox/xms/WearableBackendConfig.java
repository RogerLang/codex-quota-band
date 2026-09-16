package org.zxor.oronbox.xms;

import android.content.Context;

import org.zxor.oronbox.xms.internal.WearableClient;

/** Process-wide backend configuration. Configure before requesting an API from Wearable. */
public final class WearableBackendConfig {
    private WearableBackendConfig() {}

    public static void setBackend(Context context, WearableBackend backend) {
        if (context == null) throw new NullPointerException("context");
        if (backend == null) throw new NullPointerException("backend");
        WearableClient.configure(context.getApplicationContext(), backend);
    }

    public static WearableBackend getBackend(Context context) {
        if (context == null) throw new NullPointerException("context");
        return WearableClient.get(context.getApplicationContext()).getConfiguredBackend();
    }
}
