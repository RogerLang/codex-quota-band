package com.codex.quota.android.validation

import android.content.Context
import com.codex.quota.android.runtime.XiaomiWearableBackend
import com.xiaomi.xms.wearable.Wearable
import com.xiaomi.xms.wearable.auth.Permission
import com.xiaomi.xms.wearable.exception.AppNotInstalledException
import com.xiaomi.xms.wearable.exception.PermissionDeniedException
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
  val wearAppInstalled: String = "NOT_CHECKED",
  val deviceManagerPermission: String = "NOT_CHECKED",
) {
  fun report(): String = buildString {
    appendLine("CodexQuota Stage 03E")
    appendLine("Node: $nodeResult")
    appendLine("ConnectedNodes: $connectedNodeCount")
    appendLine("Band9ProMatches: $band9ProMatchCount")
    appendLine("NodeSelection: $nodeSelection")
    appendLine("WearAppInstalled: $wearAppInstalled")
    appendLine("DeviceManagerPermission: $deviceManagerPermission")
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

    val installed = wearAppInstalledState(node.id)
    if (installed != "TRUE") {
      return Stage03ENotifyResult(
        nodeResult = "NODE_FOUND",
        notifyPermission = "NOT_CHECKED",
        notifyRequest = "NOT_SENT",
        nodeAttempt = query.attempt,
        connectedNodeCount = nodes.size,
        band9ProMatchCount = choice.matchCount,
        nodeSelection = choice.selection,
        wearAppInstalled = installed,
      )
    }

    val deviceManager = ensurePermission(node.id, Permission.DEVICE_MANAGER)
    if (deviceManager != "PASS") {
      return Stage03ENotifyResult(
        nodeResult = "NODE_FOUND",
        notifyPermission = "NOT_CHECKED",
        notifyRequest = "NOT_SENT",
        nodeAttempt = query.attempt,
        connectedNodeCount = nodes.size,
        band9ProMatchCount = choice.matchCount,
        nodeSelection = choice.selection,
        wearAppInstalled = installed,
        deviceManagerPermission = deviceManager,
      )
    }

    val notifyPermission = ensurePermission(node.id, Permission.NOTIFY)
    if (notifyPermission != "PASS") {
      return Stage03ENotifyResult(
        nodeResult = "NODE_FOUND",
        notifyPermission = notifyPermission,
        notifyRequest = "NOT_SENT",
        nodeAttempt = query.attempt,
        connectedNodeCount = nodes.size,
        band9ProMatchCount = choice.matchCount,
        nodeSelection = choice.selection,
        wearAppInstalled = installed,
        deviceManagerPermission = deviceManager,
      )
    }

    val requestResult = try {
      val callback = withTimeoutOrNull(NOTIFY_CALLBACK_OBSERVE_MS) {
        await(notifyApi.sendNotify(node.id, Stage03ENotifyProbe.TITLE, Stage03ENotifyProbe.BODY))
      }
      if (callback != null) "CALLBACK_SUCCESS" else "REQUESTED_CALLBACK_TIMEOUT"
    } catch (error: Exception) {
      rethrowCancellation(error)
      when (error) {
        is AppNotInstalledException -> "RPK_REQUIRED"
        is PermissionDeniedException -> "PERMISSION_DENIED"
        else -> "REQUEST_FAILED"
      }
    }

    return Stage03ENotifyResult(
      nodeResult = "NODE_FOUND",
      notifyPermission = notifyPermission,
      notifyRequest = requestResult,
      nodeAttempt = query.attempt,
      connectedNodeCount = nodes.size,
      band9ProMatchCount = choice.matchCount,
      nodeSelection = choice.selection,
      wearAppInstalled = installed,
      deviceManagerPermission = deviceManager,
    )
  }

  private suspend fun wearAppInstalledState(nodeId: String): String = try {
    if (await(nodeApi.isWearAppInstalled(nodeId))) "TRUE" else "FALSE"
  } catch (error: Exception) {
    rethrowCancellation(error)
    when (error) {
      is AppNotInstalledException -> "FALSE"
      is PermissionDeniedException -> "PERMISSION_DENIED"
      else -> "ERROR"
    }
  }

  private suspend fun ensurePermission(nodeId: String, permission: Permission): String {
    try {
      if (await(authApi.checkPermission(nodeId, permission))) return "PASS"
    } catch (error: Exception) {
      rethrowCancellation(error)
      if (error is AppNotInstalledException) return "RPK_REQUIRED"
    }

    try {
      await(authApi.requestPermission(nodeId, permission))
    } catch (error: Exception) {
      rethrowCancellation(error)
      return when (error) {
        is AppNotInstalledException -> "RPK_REQUIRED"
        is PermissionDeniedException -> "DENIED"
        else -> if (error.message == "permission denied") "DENIED" else "REQUEST_FAILED"
      }
    }

    return try {
      if (await(authApi.checkPermission(nodeId, permission))) "PASS" else "DENIED"
    } catch (error: Exception) {
      rethrowCancellation(error)
      when (error) {
        is AppNotInstalledException -> "RPK_REQUIRED"
        is PermissionDeniedException -> "DENIED"
        else -> "CHECK_FAILED"
      }
    }
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
