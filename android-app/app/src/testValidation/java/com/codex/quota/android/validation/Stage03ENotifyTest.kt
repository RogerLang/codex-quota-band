package com.codex.quota.android.validation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Stage03ENotifyTest {
  @Test
  fun syntheticMarkersRemainStableAndNonSensitive() {
    assertEquals("CQNOTIFY-47-A9F3", Stage03ENotifyProbe.TITLE)
    assertEquals("SEQ47-WEEK38-RUN2", Stage03ENotifyProbe.BODY)
    assertFalse(Stage03ENotifyProbe.TITLE.contains("sk-", ignoreCase = true))
    assertFalse(Stage03ENotifyProbe.BODY.contains("token", ignoreCase = true))
  }

  @Test
  fun sanitizedReportContainsOnlyProbeStateAndMarkers() {
    val report = Stage03ENotifyResult(
      nodeResult = "NODE_FOUND",
      notifyPermission = "PASS",
      notifyRequest = "CALLBACK_SUCCESS",
      nodeAttempt = 2,
    ).report()

    assertTrue(report.contains("CodexQuota Stage 03E"))
    assertTrue(report.contains("TitleMarker: CQNOTIFY-47-A9F3"))
    assertTrue(report.contains("BodyMarker: SEQ47-WEEK38-RUN2"))
    assertTrue(report.contains("BandReceipt: USER_OBSERVATION"))
    assertFalse(report.contains("deviceId", ignoreCase = true))
    assertFalse(report.contains("authorization", ignoreCase = true))
  }
}
