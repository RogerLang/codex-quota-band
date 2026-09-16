package org.zxor.oronbox.xms.internal;

import org.junit.Test;
import org.zxor.oronbox.xms.WearableBackend;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class BackendAttemptPlanTest {
    @Test public void autoPlanIsFiniteAndPrefersOronBox() {
        BackendAttemptPlan plan = new BackendAttemptPlan(WearableBackend.AUTO);

        assertEquals(WearableClient.ORONBOX_PACKAGE, plan.next());
        assertEquals(WearableClient.MI_HEALTH_PACKAGE, plan.next());
        assertEquals(WearableClient.MI_WEAR_PACKAGE, plan.next());
        assertFalse(plan.hasNext());
        assertNull(plan.next());
        assertEquals(3, plan.size());
    }

    @Test public void explicitOronBoxDoesNotSilentlySwitchBackends() {
        BackendAttemptPlan plan = new BackendAttemptPlan(WearableBackend.ORONBOX);

        assertTrue(plan.hasNext());
        assertEquals(WearableClient.ORONBOX_PACKAGE, plan.next());
        assertFalse(plan.hasNext());
        assertNull(plan.next());
    }

    @Test public void xiaomiPlanKeepsHealthBeforeWear() {
        BackendAttemptPlan plan = new BackendAttemptPlan(WearableBackend.XIAOMI);

        assertEquals(WearableClient.MI_HEALTH_PACKAGE, plan.next());
        assertEquals(WearableClient.MI_WEAR_PACKAGE, plan.next());
        assertFalse(plan.hasNext());
    }
}
