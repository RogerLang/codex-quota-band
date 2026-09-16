package com.codex.quota.android.validation

import com.xiaomi.xms.wearable.exception.AppNotInstalledException
import com.xiaomi.xms.wearable.exception.DeviceDisconnectedException
import com.xiaomi.xms.wearable.exception.PermissionDeniedException
import com.xiaomi.xms.wearable.exception.SignatureVerifyFailedException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test

class Stage02DiagnosticsTest {
  private fun stage02TimeoutForTest(): TimeoutCancellationException = runBlocking {
    try { withTimeout(1) { delay(50) }; error("expected timeout") }
    catch (error: TimeoutCancellationException) { error }
  }
  @Test fun installErrorsUseFixedCodes() {
    assertEquals("DISCONNECTED", installResult(DeviceDisconnectedException("device disconnected")))
    assertEquals("PERMISSION_DENIED", installResult(PermissionDeniedException("permission denied")))
    assertEquals("PACKAGE_NOT_INSTALLED", installResult(AppNotInstalledException("app not installed")))
    assertEquals("SIGNATURE_FAILED", installResult(SignatureVerifyFailedException("fingerprint verify failed")))
    assertEquals("NOT_BONDED", installResult(IllegalStateException("not bond")))
    assertEquals("OTHER_FAILURE", installResult(IllegalStateException("different")))
    assertEquals("OTHER_FAILURE", installResult(Exception("private detail")))
  }

  @Test fun timeoutUsesFixedCode() {
    assertEquals("TIMEOUT", installResult(stage02TimeoutForTest()))
    assertEquals("NODE_QUERY_TIMEOUT", nodeResult(stage02TimeoutForTest()))
    assertEquals("TIMEOUT", notifyApiResult(stage02TimeoutForTest()))
  }

  @Test fun notifyApiErrorsUseFixedCodes() {
    assertEquals("DISCONNECTED", notifyApiResult(DeviceDisconnectedException("private")))
    assertEquals("PERMISSION_DENIED", notifyApiResult(PermissionDeniedException("private")))
    assertEquals("PACKAGE_NOT_INSTALLED", notifyApiResult(AppNotInstalledException("private")))
    assertEquals("SIGNATURE_FAILED", notifyApiResult(SignatureVerifyFailedException("private")))
    assertEquals("NOT_BONDED", notifyApiResult(IllegalStateException("not bond")))
    assertEquals("OTHER_FAILURE", notifyApiResult(Exception("private")))
  }

  @Test fun retryStopsAtSecondSuccess() = runBlocking {
    var attempts = 0
    val pauses = mutableListOf<Long>()
    val result = retryStage02(
      query = { attempts++; if (attempts == 1) throw IllegalStateException("not bond"); listOf("Band 9 Pro") },
      ready = { it.isNotEmpty() },
      pause = { pauses += it },
    )
    assertEquals(2, result.attempt)
    assertEquals(listOf("Band 9 Pro"), result.value)
    assertEquals(listOf(1000L), pauses)
  }

  @Test fun retryStopsAfterThirdFailure() = runBlocking {
    var attempts = 0
    val pauses = mutableListOf<Long>()
    val result = retryStage02<List<String>>(
      query = { attempts++; throw IllegalStateException("not bond") },
      ready = { it.isNotEmpty() },
      pause = { pauses += it },
    )
    assertEquals(3, attempts)
    assertEquals(3, result.attempt)
    assertEquals(null, result.value)
    assertEquals("XMS_NOT_BONDED", serviceResult(result.error!!))
    assertEquals(listOf(1000L, 2000L), pauses)
  }

  @Test fun failedInstallChecksDoNotPreventCorePass() {
    val core = mapOf(
      Stage02DItem.Service to "PASS", Stage02DItem.Node to "NODE_FOUND",
      Stage02DItem.InstallBefore to "PERMISSION_DENIED",
      Stage02DItem.DeviceManager to "PASS", Stage02DItem.NotifyPermission to "PASS",
      Stage02DItem.InstallAfter to "OTHER_FAILURE",
      Stage02DItem.PhoneToBand to "PASS", Stage02DItem.BandToPhone to "PASS",
      Stage02DItem.Notification to "PASS",
    )
    val result = Stage02DProgress(results = core + (Stage02DItem.NotificationApi to "SUCCESS"), phase = Stage02DPhase.Done)
    assertEquals("PASS_WITH_INSTALL_CHECK_WARNING", result.overall)
    assertEquals("PERMISSION_DENIED", result.result(Stage02DItem.InstallBefore))
    assertEquals("OTHER_FAILURE", result.result(Stage02DItem.InstallAfter))
  }

  @Test fun installChecksRunSeparatelyAroundPermissions() = runBlocking {
    var installCalls = 0
    val observed = mutableListOf<Pair<Stage02DItem, String>>()
    runPermissionComparison(
      installCheck = { installCalls++; if (installCalls == 1) "PERMISSION_DENIED" else "TRUE" },
      devicePermission = { "PASS" },
      notifyPermission = { "PASS" },
      report = { item, result -> observed += item to result },
    )
    assertEquals(2, installCalls)
    assertEquals(listOf(
      Stage02DItem.InstallBefore to "PERMISSION_DENIED",
      Stage02DItem.DeviceManager to "PASS",
      Stage02DItem.NotifyPermission to "PASS",
      Stage02DItem.InstallAfter to "TRUE",
    ), observed)
  }

  @Test fun failedNotifyCallbackStillAllowsHumanObservation() {
    val core = mapOf(
      Stage02DItem.Node to "NODE_FOUND",
      Stage02DItem.InstallBefore to "TRUE", Stage02DItem.InstallAfter to "TRUE",
      Stage02DItem.DeviceManager to "PASS", Stage02DItem.NotifyPermission to "PASS",
      Stage02DItem.PhoneToBand to "PASS", Stage02DItem.BandToPhone to "PASS",
    )
    val waiting = Stage02DProgress(results = Stage02DProgress().results + core,
      phase = Stage02DPhase.ConfirmNotification, bandDiagnosis = "OK")
    val callbackFailed = waiting.withNotificationApi("TIMEOUT")
    assertEquals(Stage02DPhase.ConfirmNotification, callbackFailed.phase)
    assertEquals("WAITING", callbackFailed.result(Stage02DItem.Notification))
    val observed = callbackFailed.withNotificationObservation("PASS")
    assertEquals("PASS_WITH_NOTIFY_API_WARNING", observed.overall)
    assertEquals("TIMEOUT", observed.result(Stage02DItem.NotificationApi))
    assertEquals("PASS", observed.result(Stage02DItem.Notification))
    assertEquals(true, observed.report().contains("NotificationApi: TIMEOUT\nNotification: PASS"))
  }

}
