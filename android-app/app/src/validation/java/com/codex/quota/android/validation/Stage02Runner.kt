package com.codex.quota.android.validation

import android.content.Context
import android.os.SystemClock
import com.codex.quota.android.runtime.XiaomiWearableBackend
import com.xiaomi.xms.wearable.Wearable
import com.xiaomi.xms.wearable.auth.Permission
import com.xiaomi.xms.wearable.message.OnMessageReceivedListener
import com.xiaomi.xms.wearable.tasks.Task
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal enum class Stage02Item(val label: String) {
  Service("小米运动健康 XMS"), Node("找到 Band 9 Pro"), Probe("Probe RPK"),
  DeviceManager("DEVICE_MANAGER"), NotifyPermission("NOTIFY"),
  PhoneToBand("手机 → 手环"), BandToPhone("手环 → 手机"), Notification("通知 + 振动")
}

internal enum class Stage02State { Waiting, Running, Pass, Fail }
internal enum class Stage02Phase { Idle, Running, InstallRequired, OpenProbe, ConfirmNotification, Done }

internal data class Stage02Progress(
  val states: Map<Stage02Item, Stage02State> = Stage02Item.entries.associateWith { Stage02State.Waiting },
  val phase: Stage02Phase = Stage02Phase.Idle,
  val errorCode: String? = null,
) {
  val overallPass get() = phase == Stage02Phase.Done && states.values.all { it == Stage02State.Pass }
  fun update(item: Stage02Item, state: Stage02State) = copy(states = states + (item to state))
  fun report(): String = buildString {
    appendLine("CodexQuota Stage 02")
    Stage02Item.entries.forEach {
      val result = if (it == Stage02Item.Node && states[it] == Stage02State.Pass) "NODE_FOUND" else states[it].toString().uppercase()
      appendLine("${it.name}: $result")
    }
    errorCode?.let { appendLine("Error code: $it") }
    append("Overall: ${if (overallPass) "PASS" else "FAIL"}")
  }
}

private class Stage02Failure(val code: String) : Exception(code)

internal fun isBand9ProNodeName(name: String?): Boolean =
  name != null && Regex("(?:band|手环)\\s*9\\s*pro", RegexOption.IGNORE_CASE).containsMatchIn(name)

internal class Stage02Runner(context: Context) {
  private val appContext = context.applicationContext
  private val apis = XiaomiWearableBackend.initializer(appContext).initialize {
    arrayOf(Wearable.getNodeApi(appContext), Wearable.getAuthApi(appContext), Wearable.getMessageApi(appContext), Wearable.getNotifyApi(appContext))
  }
  private val nodeApi = apis[0] as com.xiaomi.xms.wearable.node.NodeApi
  private val authApi = apis[1] as com.xiaomi.xms.wearable.auth.AuthApi
  private val messageApi = apis[2] as com.xiaomi.xms.wearable.message.MessageApi
  private val notifyApi = apis[3] as com.xiaomi.xms.wearable.notify.NotifyApi
  private var nodeId: String? = null
  private var openProbeGate: CompletableDeferred<Unit>? = null

  fun continueAfterOpeningProbe() { openProbeGate?.complete(Unit) }

  suspend fun run(onProgress: (Stage02Progress) -> Unit): Stage02Progress {
    var progress = Stage02Progress(phase = Stage02Phase.Running)
    fun mark(item: Stage02Item, state: Stage02State) { progress = progress.update(item, state); onProgress(progress) }
    fun fail(item: Stage02Item, code: String): Stage02Progress {
      progress = progress.update(item, Stage02State.Fail).copy(phase = Stage02Phase.Done, errorCode = code)
      onProgress(progress)
      return progress
    }
    onProgress(progress)
    mark(Stage02Item.Service, Stage02State.Running)
    val nodes = try { await(nodeApi.connectedNodes) } catch (_: Exception) {
      return fail(Stage02Item.Service, "XMS_SERVICE_UNAVAILABLE")
    }
    mark(Stage02Item.Service, Stage02State.Pass)
    mark(Stage02Item.Node, Stage02State.Running)
    if (nodes.isEmpty()) return fail(Stage02Item.Node, "NO_CONNECTED_NODE")
    val node = nodes.firstOrNull { isBand9ProNodeName(it.name) }
      ?: return fail(Stage02Item.Node, "BAND_9_PRO_NOT_FOUND")
    nodeId = node.id
    mark(Stage02Item.Node, Stage02State.Pass)
    mark(Stage02Item.Probe, Stage02State.Running)
    val installed = try { await(nodeApi.isWearAppInstalled(node.id)) } catch (_: Exception) {
      return fail(Stage02Item.Probe, "WEAR_APP_CHECK_FAILED")
    }
    if (!installed) {
      progress = progress.update(Stage02Item.Probe, Stage02State.Fail).copy(phase = Stage02Phase.InstallRequired, errorCode = "RPK_NOT_INSTALLED")
      onProgress(progress)
      return progress
    }
    mark(Stage02Item.Probe, Stage02State.Pass)
    for ((permission, item, denied) in listOf(
      Triple(Permission.DEVICE_MANAGER, Stage02Item.DeviceManager, "DEVICE_MANAGER_DENIED"),
      Triple(Permission.NOTIFY, Stage02Item.NotifyPermission, "NOTIFY_DENIED"),
    )) {
      mark(item, Stage02State.Running)
      val granted = try { await(authApi.checkPermission(node.id, permission)) } catch (_: Exception) {
        return fail(item, "PERMISSION_API_FAILED")
      }
      if (!granted) {
        var requestError: Exception? = null
        try {
          await(authApi.requestPermission(node.id, permission))
        } catch (error: Exception) { requestError = error }
        // A refusal may be surfaced as a failed Task. Recheck once, without another prompt.
        val after = try { await(authApi.checkPermission(node.id, permission)) } catch (_: Exception) {
          return fail(item, "PERMISSION_API_FAILED")
        }
        if (!after) {
          val explicitDenial = requestError?.message == "permission denied" ||
            requestError is com.xiaomi.xms.wearable.exception.PermissionDeniedException
          return fail(item, if (requestError != null && !explicitDenial) "PERMISSION_API_FAILED" else denied)
        }
      }
      mark(item, Stage02State.Pass)
    }

    val gate = CompletableDeferred<Unit>()
    openProbeGate = gate
    progress = progress.copy(phase = Stage02Phase.OpenProbe)
    onProgress(progress)
    try {
      gate.await()
    } finally {
      openProbeGate = null
    }
    progress = progress.copy(phase = Stage02Phase.Running)
    onProgress(progress)
    mark(Stage02Item.PhoneToBand, Stage02State.Running)
    val messages = Channel<Stage02Message>(Channel.UNLIMITED)
    val listener = OnMessageReceivedListener { sender, bytes ->
      if (sender == node.id) Stage02Message.parse(bytes)?.let { messages.trySend(it) }
    }
    try {
      await(messageApi.addListener(node.id, listener))
      val phoneNonce = Stage02Message.randomNonce()
      await(messageApi.sendMessage(node.id, Stage02Message.encode(Stage02Message.PING, phoneNonce)))
      expect(messages, Stage02Message.PONG, phoneNonce, "PHONE_TO_BAND_TIMEOUT")
      mark(Stage02Item.PhoneToBand, Stage02State.Pass)
      mark(Stage02Item.BandToPhone, Stage02State.Running)
      val bandPing = withTimeoutOrNull(8000) { messages.receive() }
        ?: throw Stage02Failure("BAND_TO_PHONE_TIMEOUT")
      if (bandPing.type != Stage02Message.BAND_PING) throw Stage02Failure("MESSAGE_TYPE_MISMATCH")
      await(messageApi.sendMessage(node.id, Stage02Message.encode(Stage02Message.ANDROID_PONG, bandPing.nonce)))
      expect(messages, Stage02Message.PONG, bandPing.nonce, "BAND_TO_PHONE_TIMEOUT")
      mark(Stage02Item.BandToPhone, Stage02State.Pass)
    } catch (error: Stage02Failure) {
      return fail(if (progress.states[Stage02Item.PhoneToBand] == Stage02State.Pass) Stage02Item.BandToPhone else Stage02Item.PhoneToBand, error.code)
    } catch (_: Exception) {
      return fail(if (progress.states[Stage02Item.PhoneToBand] == Stage02State.Pass) Stage02Item.BandToPhone else Stage02Item.PhoneToBand, "MESSAGE_API_FAILED")
    } finally {
      runCatching { withTimeout(3000) { await(messageApi.removeListener(node.id)) } }
      messages.close()
    }
    progress = progress.copy(phase = Stage02Phase.ConfirmNotification)
    onProgress(progress)
    return progress
  }

  suspend fun sendNotification(): String? {
    val id = nodeId ?: return "NO_CONNECTED_NODE"
    return try {
      await(notifyApi.sendNotify(id, "CodexQuota 测试", "Stage 02 手环提醒"))
      null
    } catch (_: Exception) { "NOTIFY_API_FAILED" }
  }

  private suspend fun expect(messages: Channel<Stage02Message>, type: String, nonce: String, timeoutCode: String) {
    val gate = Stage02ReplyGate(type, nonce, SystemClock.elapsedRealtime() + 8000)
    val message = withTimeoutOrNull(8000) { messages.receive() } ?: throw Stage02Failure(timeoutCode)
    when (gate.check(message, SystemClock.elapsedRealtime())) {
      Stage02ReplyGate.Result.Matched -> Unit
      Stage02ReplyGate.Result.WrongType -> throw Stage02Failure("MESSAGE_TYPE_MISMATCH")
      Stage02ReplyGate.Result.WrongNonce -> throw Stage02Failure("MESSAGE_NONCE_MISMATCH")
      Stage02ReplyGate.Result.TimedOut, Stage02ReplyGate.Result.Waiting -> throw Stage02Failure(timeoutCode)
    }
  }

  private suspend fun <T> await(task: Task<T>): T = withTimeout(8000) {
    suspendCancellableCoroutine { continuation ->
      task.addOnSuccessListener { if (continuation.isActive) continuation.resume(it) }
      task.addOnFailureListener { if (continuation.isActive) continuation.resumeWithException(it) }
    }
  }
}
