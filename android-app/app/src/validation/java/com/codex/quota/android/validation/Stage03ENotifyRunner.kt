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
  val connectedNodeCount: Int = 0,
  val band9ProMatchCount: Int = 0,
  val nodeSelection: String = "NONE",
) {
  fun report(): String = buildString {
    appendLine("CodexQuota Stage 03E")
    appendLine("Node: $nodeResult")
    appendLine("ConnectedNodes: $connectedNodeCount")
    appendLine("Band9ProMatches: $band9ProMatchCount")
    appendLine("NodeSelection: $nodeSelection")
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

internal data class Stage03ENodeChoice(
  val index: Int?,
  val matchCount: Int,
  val selection: String,
)

internal fun chooseStage03ENode(names: List<String?>): Stage03ENodeChoice {
  val matches = names.indices.filter { isBand9ProNodeName(names[it]) }
  return when {
    matches.size == 1 -> Stage03ENodeChoice(matches.single(), 1, "NAME_MATCH")
    matches.size > 1 -> Stage03ENodeChoice(null, matches.size, "AMBIGUOUS_NAME_MATCH")
    names.size == 1 -> Stage03ENodeChoice(0, 0, "SINGLE_NODE_FALLBACK")
    else -> Stage03ENodeChoice(null, 0, "NONE")
  }
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
      // Stage 03E is validation-only. Stop as soon as XMS returns any connected node so
      // the result can distinguish "no node" from "node name did not match".
      ready = { nodes -> nodes.isNotEmpty() },
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

    val nodes = query.value.orEmpty()
    val choice = chooseStage03ENode(nodes.map { it.name })
    val node = choice.index?.let(nodes::get)
    if (node == null) {
      return Stage03ENotifyResult(
        nodeResult = if (choice.selection == "AMBIGUOUS_NAME_MATCH") "NODE_AMBIGUOUS" else "NODE_NOT_FOUND",
        notifyPermission = "NOT_CHECKED",
        notifyRequest = "NOT_SENT",
        nodeAttempt = query.attempt,
        connectedNodeCount = nodes.size,
        band9ProMatchCount = choice.matchCount,
        nodeSelection = choice.selection,
      )
    }

    val permission = ensureNotifyPermission(node.id)
    if (!permission) {
      return Stage03ENotifyResult(
        nodeResult = "NODE_FOUND",
        notifyPermission = "DENIED",
        notifyRequest = "NOT_SENT",
        nodeAttempt = query.attempt,
        connectedNodeCount = nodes.size,
        band9ProMatchCount = choice.matchCount,
        nodeSelection = choice.selection,
      )
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
      connectedNodeCount = nodes.size,
      band9ProMatchCount = choice.matchCount,
      nodeSelection = choice.selection,
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
