package com.codex.quota.android.validation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
      connectedNodeCount = 1,
      band9ProMatchCount = 0,
      nodeSelection = "SINGLE_NODE_FALLBACK",
    ).report()

    assertTrue(report.contains("CodexQuota Stage 03E"))
    assertTrue(report.contains("ConnectedNodes: 1"))
    assertTrue(report.contains("Band9ProMatches: 0"))
    assertTrue(report.contains("NodeSelection: SINGLE_NODE_FALLBACK"))
    assertTrue(report.contains("TitleMarker: CQNOTIFY-47-A9F3"))
    assertTrue(report.contains("BodyMarker: SEQ47-WEEK38-RUN2"))
    assertTrue(report.contains("BandReceipt: USER_OBSERVATION"))
    assertFalse(report.contains("deviceId", ignoreCase = true))
    assertFalse(report.contains("authorization", ignoreCase = true))
  }

  @Test
  fun exactBandNameMatchWins() {
    val choice = chooseStage03ENode(listOf("Other wearable", "Xiaomi Smart Band 9 Pro"))
    assertEquals(1, choice.index)
    assertEquals(1, choice.matchCount)
    assertEquals("NAME_MATCH", choice.selection)
  }

  @Test
  fun oneConnectedNodeCanUseValidationOnlyFallback() {
    val choice = chooseStage03ENode(listOf("M2140B1"))
    assertEquals(0, choice.index)
    assertEquals(0, choice.matchCount)
    assertEquals("SINGLE_NODE_FALLBACK", choice.selection)
  }

  @Test
  fun multipleUnknownNodesAreNotSelected() {
    val choice = chooseStage03ENode(listOf("Wearable A", "Wearable B"))
    assertNull(choice.index)
    assertEquals(0, choice.matchCount)
    assertEquals("NONE", choice.selection)
  }

  @Test
  fun multipleBandNameMatchesAreRejectedAsAmbiguous() {
    val choice = chooseStage03ENode(listOf("Band 9 Pro", "Xiaomi Band 9 Pro"))
    assertNull(choice.index)
    assertEquals(2, choice.matchCount)
    assertEquals("AMBIGUOUS_NAME_MATCH", choice.selection)
  }
}
