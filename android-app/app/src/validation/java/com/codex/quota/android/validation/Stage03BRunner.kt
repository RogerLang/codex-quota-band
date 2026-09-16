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

internal data class Stage03BWakeResult(
  val sequence: Int,
  val launchResult: String,
  val messageResult: String,
  val sendAttempts: Int = 0,
  val nodeAttempt: Int = 0,
) {
  val overall: String
    get() = when {
      launchResult != "SUCCESS" -> "LAUNCH_FAILED"
      messageResult == "ACK_PERSISTED" -> "WAKE_AND_PERSIST_PASS"
      else -> "WAKE_OK_MESSAGE_FAILED"
    }

  fun report(): String = buildString {
    appendLine("CodexQuota Stage 03B")
    appendLine("Sequence: $sequence")
    appendLine("LaunchWearApp: $launchResult")
    appendLine("Message: $messageResult")
    appendLine("SendAttempts: $sendAttempts")
    appendLine("NodeAttempt: $nodeAttempt")
    append("Overall: $overall")
  }
}

internal class Stage03BRunner(context: Context) {
  private val appContext = context.applicationContext
  private val apis = XiaomiWearableBackend.initializer(appContext).initialize {
    arrayOf(Wearable.getNodeApi(appContext), Wearable.getAuthApi(appContext), Wearable.getMessageApi(appContext))
  }
  private val nodeApi = apis[0] as com.xiaomi.xms.wearable.node.NodeApi
  private val authApi = apis[1] as com.xiaomi.xms.wearable.auth.AuthApi
  private val messageApi = apis[2] as com.xiaomi.xms.wearable.message.MessageApi

  suspend fun wakeAndSend(sequence: Int = 44): Stage03BWakeResult {
    require(sequence in 0..999_999)
    val query = retryStage02(
      query = { await(nodeApi.connectedNodes) },
      ready = { nodes -> nodes.any { isBand9ProNodeName(it.name) } },
      pause = { delay(it) },
    )
    if (query.error != null) {
      return Stage03BWakeResult(sequence, serviceResult(query.error), "NOT_SENT", nodeAttempt = query.attempt)
    }
    val node = query.value.orEmpty().firstOrNull { isBand9ProNodeName(it.name) }
      ?: return Stage03BWakeResult(sequence, "NODE_NOT_FOUND", "NOT_SENT", nodeAttempt = query.attempt)

    if (!ensurePermission(node.id, Permission.DEVICE_MANAGER)) {
      return Stage03BWakeResult(sequence, "DEVICE_MANAGER_DENIED", "NOT_SENT", nodeAttempt = query.attempt)
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
        return Stage03BWakeResult(sequence, "LISTENER_FAILED", "NOT_SENT", nodeAttempt = query.attempt)
      }

      val launch = try {
        await(nodeApi.launchWearApp(node.id, WEAR_ROUTE))
        "SUCCESS"
      } catch (error: Exception) {
        rethrowCancellation(error)
        if (error is TimeoutCancellationException) "TIMEOUT" else "FAILED"
      }
      if (launch != "SUCCESS") {
        return Stage03BWakeResult(sequence, launch, "NOT_SENT", nodeAttempt = query.attempt)
      }

      // launchWearApp returning only means the launch request was accepted. Give the Vela
      // runtime a short window to create the Quick App, then retry the same idempotent state
      // message while waiting for the RPK's persisted ACK.
      delay(INITIAL_WAKE_DELAY_MS)
      val nonce = Stage03AMessage.randomNonce()
      val payload = Stage03AMessage.encodeState(sequence, nonce)
      for (attempt in 1..MAX_SEND_ATTEMPTS) {
        try {
          await(messageApi.sendMessage(node.id, payload))
        } catch (error: Exception) {
          rethrowCancellation(error)
          if (attempt == MAX_SEND_ATTEMPTS) {
            return Stage03BWakeResult(sequence, launch, "SEND_FAILED", attempt, query.attempt)
          }
          delay(RETRY_DELAY_MS)
          continue
        }

        val ack = withTimeoutOrNull(ACK_SLICE_MS) {
          while (true) {
            val candidate = replies.receive()
            if (candidate.sequence == sequence && candidate.nonce == nonce) return@withTimeoutOrNull candidate
          }
          @Suppress("UNREACHABLE_CODE")
          null
        }
        if (ack != null) {
          return Stage03BWakeResult(
            sequence = sequence,
            launchResult = launch,
            messageResult = if (ack.persisted == true) "ACK_PERSISTED" else "ACK_NOT_PERSISTED",
            sendAttempts = attempt,
            nodeAttempt = query.attempt,
          )
        }
        if (attempt < MAX_SEND_ATTEMPTS) delay(RETRY_DELAY_MS)
      }
      return Stage03BWakeResult(sequence, launch, "ACK_TIMEOUT", MAX_SEND_ATTEMPTS, query.attempt)
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
    const val WEAR_ROUTE = "pages/index"
    const val API_TIMEOUT_MS = 8_000L
    const val INITIAL_WAKE_DELAY_MS = 700L
    const val RETRY_DELAY_MS = 500L
    const val ACK_SLICE_MS = 1_200L
    const val MAX_SEND_ATTEMPTS = 6
  }
}
