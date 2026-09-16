package org.zxor.oronbox.xms.internal;

import org.zxor.oronbox.xms.WearableBackend;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Finite backend selection plan used by the binder state machine.
 *
 * <p>A binding request being accepted does not mean that the service is usable,
 * so each candidate is selected at most once and must pass the readiness probe
 * before the next API call is released.</p>
 */
final class BackendAttemptPlan {
    private final List<String> packages;
    private int nextIndex;

    BackendAttemptPlan(WearableBackend backend) {
        WearableBackend selected = backend == null ? WearableBackend.AUTO : backend;
        switch (selected) {
            case ORONBOX:
                packages = Collections.singletonList(WearableClient.ORONBOX_PACKAGE);
                break;
            case XIAOMI:
                packages = Collections.unmodifiableList(Arrays.asList(
                        WearableClient.MI_HEALTH_PACKAGE,
                        WearableClient.MI_WEAR_PACKAGE
                ));
                break;
            case AUTO:
            default:
                packages = Collections.unmodifiableList(Arrays.asList(
                        WearableClient.ORONBOX_PACKAGE,
                        WearableClient.MI_HEALTH_PACKAGE,
                        WearableClient.MI_WEAR_PACKAGE
                ));
                break;
        }
    }

    boolean hasNext() {
        return nextIndex < packages.size();
    }

    String next() {
        return hasNext() ? packages.get(nextIndex++) : null;
    }

    int size() {
        return packages.size();
    }
}
