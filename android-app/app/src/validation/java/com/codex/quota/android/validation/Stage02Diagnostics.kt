package com.codex.quota.android.validation

import com.xiaomi.xms.wearable.exception.AppNotInstalledException
import com.xiaomi.xms.wearable.exception.DeviceDisconnectedException
import com.xiaomi.xms.wearable.exception.PermissionDeniedException
import com.xiaomi.xms.wearable.exception.SignatureVerifyFailedException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException

internal fun installResult(error: Throwable): String = when (error) {
  is TimeoutCancellationException -> "TIMEOUT"
  is DeviceDisconnectedException -> "DISCONNECTED"
  is PermissionDeniedException -> "PERMISSION_DENIED"
  is AppNotInstalledException -> "PACKAGE_NOT_INSTALLED"
  is SignatureVerifyFailedException -> "SIGNATURE_FAILED"
  is IllegalStateException -> if (error.message == "not bond") "NOT_BONDED" else "OTHER_FAILURE"
  else -> "OTHER_FAILURE"
}

internal fun serviceResult(error: Throwable): String = when (error) {
  is TimeoutCancellationException -> "XMS_SERVICE_TIMEOUT"
  is IllegalStateException -> if (error.message == "not bond") "XMS_NOT_BONDED" else "XMS_SERVICE_OTHER_FAILURE"
  else -> "XMS_SERVICE_OTHER_FAILURE"
}

internal fun nodeResult(error: Throwable): String = when (error) {
  is TimeoutCancellationException -> "NODE_QUERY_TIMEOUT"
  is DeviceDisconnectedException -> "NODE_QUERY_DISCONNECTED"
  else -> "NODE_QUERY_OTHER_FAILURE"
}

internal fun notifyApiResult(error: Throwable): String = installResult(error)

internal data class Stage02RetryResult<T>(val value: T?, val error: Throwable?, val attempt: Int)

internal suspend fun runPermissionComparison(
  installCheck: suspend () -> String,
  devicePermission: suspend () -> String,
  notifyPermission: suspend () -> String,
  report: (Stage02DItem, String) -> Unit,
) {
  report(Stage02DItem.InstallBefore, installCheck())
  report(Stage02DItem.DeviceManager, devicePermission())
  report(Stage02DItem.NotifyPermission, notifyPermission())
  report(Stage02DItem.InstallAfter, installCheck())
}

internal suspend fun <T> retryStage02(
  query: suspend () -> T,
  ready: (T) -> Boolean,
  pause: suspend (Long) -> Unit,
): Stage02RetryResult<T> {
  var lastValue: T? = null
  var lastError: Throwable? = null
  for (attempt in 1..3) {
    try {
      val value = query()
      lastValue = value
      lastError = null
      if (ready(value)) return Stage02RetryResult(value, null, attempt)
    } catch (error: Throwable) {
      if (error is CancellationException && error !is TimeoutCancellationException) throw error
      lastValue = null
      lastError = error
    }
    if (attempt < 3) pause(attempt * 1000L)
  }
  return Stage02RetryResult(lastValue, lastError, 3)
}
