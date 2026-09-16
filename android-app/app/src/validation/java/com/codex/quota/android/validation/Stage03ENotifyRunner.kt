package com.codex.quota.android.validation

import android.content.Context
import com.codex.quota.android.runtime.XiaomiWearableBackend
import com.xiaomi.xms.wearable.Wearable
import com.xiaomi.xms.wearable.auth.Permission
import com.xiaomi.xms.wearable.tasks.Task
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class Stage03ENotifyResult(
  val nodeResult: String,
  val notifyPermission: String,
  val notifyRequest: String,
  val nodeAttempt: Int = 0,
) {
  fun report(): String = buildString {
    appendLine("CodexQuota Stage 03E")
    appendLine("Node: $nodeResult")
    appendLine("NotifyPermission: $notifyPermission")
    appendLine("NotifyRequest: $notifyRequest")
    appendLine("NodeAttempt: $nodeAttempt")
    appendLine("TitleMarker: ${Stage03ENotifyProbe.TITLE}")
    appendLine("BodyMarker: ${Stage03ENotifyProbe.BODY}")
    append("BandReceipt: USER_OBSERVATION")
  }
}

internal object Stage03ENotifyProbe {
  const val TITLE = "CQNOTIFY-47-A9F3"
  const val BODY = "SEQ47-WEEK38-RUN2"
}

internal class Stage03ENotifyRunner(context: Context) {
  private val appContext = context.applicationContext
  private val apis = XiaomiWearableBackend.initializer(appContext).initialize {
    arrayOf(
      Wearable.getNodeApi(appContext),
      Wearable.getAuthApi(appContext),
      Wearable.getNotifyApi(appContext),
    )
  }
  private val nodeApi = apis[0] as com.xiaomi.xms.wearable.node.NodeApi
  private val authApi = apis[1] as com.xiaomi.xms.wearable.auth.AuthApi
  private val notifyApi = apis[2] as com.xiaomi.xms.wearable.notify.NotifyApi

  suspend fun sendProbe(): Stage03ENotifyResult {
    val query = retryStage02(
      query = { await(nodeApi.connectedNodes) },
      ready = { nodes -> nodes.any { isBand9ProNodeName(it.name) } },
      pause = { delay(it) },
    )
    if (query.error != null) {
      return Stage03ENotifyResult(
        nodeResult = serviceResult(query.error),
        notifyPermission = "NOT_CHECKED",
        notifyRequest = "NOT_SENT",
        nodeAttempt = query.attempt,
      )
    }
    val node = query.value.orEmpty().firstOrNull { isBand9ProNodeName(it.name) }
      ?: return Stage03ENotifyResult("NODE_NOT_FOUND", "NOT_CHECKED", "NOT_SENT", query.attempt)

    val permission = ensureNotifyPermission(node.id)
    if (!permission) {
      return Stage03ENotifyResult("NODE_FOUND", "DENIED", "NOT_SENT", query.attempt)
    }

    val requestResult = try {
      val callback = withTimeoutOrNull(NOTIFY_CALLBACK_OBSERVE_MS) {
        await(notifyApi.sendNotify(node.id, Stage03ENotifyProbe.TITLE, Stage03ENotifyProbe.BODY))
      }
      if (callback != null) "CALLBACK_SUCCESS" else "REQUESTED_CALLBACK_TIMEOUT"
    } catch (error: Exception) {
      rethrowCancellation(error)
      "REQUEST_FAILED"
    }

    return Stage03ENotifyResult(
      nodeResult = "NODE_FOUND",
      notifyPermission = "PASS",
      notifyRequest = requestResult,
      nodeAttempt = query.attempt,
    )
  }

  private suspend fun ensureNotifyPermission(nodeId: String): Boolean = try {
    if (await(authApi.checkPermission(nodeId, Permission.NOTIFY))) return true
    await(authApi.requestPermission(nodeId, Permission.NOTIFY))
    await(authApi.checkPermission(nodeId, Permission.NOTIFY))
  } catch (error: Exception) {
    rethrowCancellation(error)
    false
  }

  private fun rethrowCancellation(error: Exception) {
    if (error is CancellationException && error !is TimeoutCancellationException) throw error
  }

  private suspend fun <T> await(task: Task<T>): T = withTimeout(API_TIMEOUT_MS) {
    suspendCancellableCoroutine { continuation ->
      task.addOnSuccessListener { if (continuation.isActive) continuation.resume(it) }
      task.addOnFailureListener { if (continuation.isActive) continuation.resumeWithException(it) }
    }
  }

  private companion object {
    const val API_TIMEOUT_MS = 8_000L
    const val NOTIFY_CALLBACK_OBSERVE_MS = 2_500L
  }
}
