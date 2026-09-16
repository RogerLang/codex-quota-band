package com.codex.quota.android.validation

import android.content.Context
import com.codex.quota.android.runtime.XiaomiWearableBackend
import com.xiaomi.xms.wearable.Wearable
import com.xiaomi.xms.wearable.auth.Permission
import com.xiaomi.xms.wearable.message.OnMessageReceivedListener
import com.xiaomi.xms.wearable.tasks.Task
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class Stage03ASendResult(
  val sequence: Int,
  val result: String,
  val nodeAttempt: Int = 0,
) {
  fun reportLine(): String = "Send$sequence: $result"
}

internal class Stage03ARunner(context: Context) {
  private val appContext = context.applicationContext
  private val apis = XiaomiWearableBackend.initializer(appContext).initialize {
    arrayOf(Wearable.getNodeApi(appContext), Wearable.getAuthApi(appContext), Wearable.getMessageApi(appContext))
  }
  private val nodeApi = apis[0] as com.xiaomi.xms.wearable.node.NodeApi
  private val authApi = apis[1] as com.xiaomi.xms.wearable.auth.AuthApi
  private val messageApi = apis[2] as com.xiaomi.xms.wearable.message.MessageApi

  suspend fun sendState(sequence: Int): Stage03ASendResult {
    require(sequence in 0..999_999)
    val query = retryStage02(
      query = { await(nodeApi.connectedNodes) },
      ready = { nodes -> nodes.any { isBand9ProNodeName(it.name) } },
      pause = { delay(it) },
    )
    if (query.error != null) return Stage03ASendResult(sequence, serviceResult(query.error), query.attempt)
    val node = query.value.orEmpty().firstOrNull { isBand9ProNodeName(it.name) }
      ?: return Stage03ASendResult(sequence, "NODE_NOT_FOUND", query.attempt)

    if (!ensurePermission(node.id, Permission.DEVICE_MANAGER)) {
      return Stage03ASendResult(sequence, "DEVICE_MANAGER_DENIED", query.attempt)
    }

    val replies = Channel<Stage03AMessage>(Channel.UNLIMITED)
    val listener = OnMessageReceivedListener { sender, bytes ->
      if (sender == node.id) {
        Stage03AMessage.parse(bytes)?.takeIf { it.type == Stage03AMessage.ACK }?.let(replies::trySend)
      }
    }
    var listening = false
    try {
      try {
        await(messageApi.addListener(node.id, listener))
        listening = true
      } catch (error: Exception) {
        rethrowCancellation(error)
        return Stage03ASendResult(sequence, "LISTENER_FAILED", query.attempt)
      }

      val nonce = Stage03AMessage.randomNonce()
      try {
        await(messageApi.sendMessage(node.id, Stage03AMessage.encodeState(sequence, nonce)))
      } catch (error: Exception) {
        rethrowCancellation(error)
        return Stage03ASendResult(sequence, "SEND_FAILED", query.attempt)
      }

      val deadline = System.currentTimeMillis() + ACK_TIMEOUT_MS
      while (true) {
        val remaining = deadline - System.currentTimeMillis()
        if (remaining <= 0) return Stage03ASendResult(sequence, "ACK_TIMEOUT", query.attempt)
        val ack = withTimeoutOrNull(remaining) { replies.receive() }
          ?: return Stage03ASendResult(sequence, "ACK_TIMEOUT", query.attempt)
        if (ack.sequence == sequence && ack.nonce == nonce) {
          return Stage03ASendResult(sequence, if (ack.persisted == true) "ACK_PERSISTED" else "ACK_NOT_PERSISTED", query.attempt)
        }
      }
    } finally {
      if (listening) {
        try { withTimeout(3000) { await(messageApi.removeListener(node.id)) } }
        catch (error: Exception) { rethrowCancellation(error) }
      }
      replies.close()
    }
  }

  private suspend fun ensurePermission(id: String, permission: Permission): Boolean = try {
    if (await(authApi.checkPermission(id, permission))) return true
    await(authApi.requestPermission(id, permission))
    await(authApi.checkPermission(id, permission))
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
    const val ACK_TIMEOUT_MS = 10_000L
  }
}
