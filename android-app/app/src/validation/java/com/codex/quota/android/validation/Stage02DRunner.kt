package com.codex.quota.android.validation

import android.content.Context
import com.codex.quota.android.runtime.XiaomiWearableBackend
import com.xiaomi.xms.wearable.Wearable
import com.xiaomi.xms.wearable.auth.Permission
import com.xiaomi.xms.wearable.message.OnMessageReceivedListener
import com.xiaomi.xms.wearable.tasks.Task
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal enum class Stage02DItem(val label: String) {
  Service("小米运动健康 XMS"), Node("Band 9 Pro"), InstallBefore("安装查询 · 授权前"),
  DeviceManager("DEVICE_MANAGER"), NotifyPermission("NOTIFY"), InstallAfter("安装查询 · 授权后"),
  PhoneToBand("手机 → 手环"), BandToPhone("手环 → 手机"),
  NotificationApi("通知 API 回执"), Notification("通知 + 振动")
}

internal enum class Stage02DPhase { Idle, Running, OpenProbe, TapBandButton, ConfirmNotification, Done }

internal data class Stage02DProgress(
  val results: Map<Stage02DItem, String> = Stage02DItem.entries.associateWith { "WAITING" },
  val phase: Stage02DPhase = Stage02DPhase.Idle,
  val serviceCode: String = "WAITING",
  val nodeAttempt: Int = 0,
  val bandDiagnosis: String = "NOT_REPORTED",
) {
  fun result(item: Stage02DItem) = results.getValue(item)
  fun update(item: Stage02DItem, value: String) = copy(results = results + (item to value))
  fun withNotificationApi(value: String) = update(Stage02DItem.NotificationApi, value)
  fun withNotificationObservation(value: String) = update(Stage02DItem.Notification, value).copy(phase = Stage02DPhase.Done)
  val overall: String get() {
    if (phase != Stage02DPhase.Done) return "WAITING"
    if (result(Stage02DItem.Node) != "NODE_FOUND") return "FAIL"
    val core = listOf(Stage02DItem.DeviceManager, Stage02DItem.NotifyPermission,
      Stage02DItem.PhoneToBand, Stage02DItem.BandToPhone, Stage02DItem.Notification)
    if (core.all { result(it) == "PASS" }) {
      return if (result(Stage02DItem.NotificationApi) != "SUCCESS") "PASS_WITH_NOTIFY_API_WARNING"
      else if (result(Stage02DItem.InstallBefore) != "TRUE" || result(Stage02DItem.InstallAfter) != "TRUE")
        "PASS_WITH_INSTALL_CHECK_WARNING"
      else if (bandDiagnosis != "OK") "PASS_WITH_DIAGNOSTIC_WARNING" else "PASS"
    }
    return if (core.any { result(it) == "PASS" }) "PARTIAL_PASS" else "FAIL"
  }
  fun report(): String = buildString {
    appendLine("CodexQuota Stage 02D")
    appendLine("Service: ${result(Stage02DItem.Service)}")
    appendLine("ServiceCode: $serviceCode")
    appendLine("Node: ${result(Stage02DItem.Node)}")
    appendLine("NodeAttempt: $nodeAttempt")
    appendLine("InstallBefore: ${result(Stage02DItem.InstallBefore)}")
    appendLine("DeviceManager: ${result(Stage02DItem.DeviceManager)}")
    appendLine("NotifyPermission: ${result(Stage02DItem.NotifyPermission)}")
    appendLine("InstallAfter: ${result(Stage02DItem.InstallAfter)}")
    appendLine("BandDiagnosis: $bandDiagnosis")
    appendLine("PhoneToBand: ${result(Stage02DItem.PhoneToBand)}")
    appendLine("BandToPhone: ${result(Stage02DItem.BandToPhone)}")
    appendLine("NotificationApi: ${result(Stage02DItem.NotificationApi)}")
    appendLine("Notification: ${result(Stage02DItem.Notification)}")
    append("Overall: $overall")
  }
}

internal class Stage02DRunner(context: Context) {
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

  suspend fun run(onProgress: (Stage02DProgress) -> Unit): Stage02DProgress {
    nodeId = null
    var progress = Stage02DProgress(phase = Stage02DPhase.Running)
    fun show() = onProgress(progress)
    fun mark(item: Stage02DItem, value: String) { progress = progress.update(item, value); show() }
    show()
    mark(Stage02DItem.Service, "RUNNING")
    val query = retryStage02(
      query = { await(nodeApi.connectedNodes) },
      ready = { nodes -> nodes.any { isBand9ProNodeName(it.name) } },
      pause = { delay(it) },
    )
    progress = progress.copy(nodeAttempt = query.attempt)
    if (query.error != null) {
      progress = progress.copy(serviceCode = serviceResult(query.error))
      mark(Stage02DItem.Service, "ERROR")
      mark(Stage02DItem.Node, nodeResult(query.error))
      progress = progress.copy(phase = Stage02DPhase.Done)
      show()
      return progress
    }
    progress = progress.copy(serviceCode = "XMS_SERVICE_OK")
    mark(Stage02DItem.Service, "PASS")
    val nodes = query.value.orEmpty()
    val node = nodes.firstOrNull { isBand9ProNodeName(it.name) }
    if (node == null) {
      mark(Stage02DItem.Node, if (nodes.isEmpty()) "NO_CONNECTED_NODE" else "BAND_9_PRO_NOT_FOUND")
      progress = progress.copy(phase = Stage02DPhase.Done)
      show()
      return progress
    }
    nodeId = node.id
    mark(Stage02DItem.Node, "NODE_FOUND")

    runPermissionComparison(
      installCheck = { installCheck(node.id) },
      devicePermission = { permissionCheck(node.id, Permission.DEVICE_MANAGER) },
      notifyPermission = { permissionCheck(node.id, Permission.NOTIFY) },
      report = { item, result -> mark(item, result) },
    )

    val gate = CompletableDeferred<Unit>()
    openProbeGate = gate
    progress = progress.copy(phase = Stage02DPhase.OpenProbe)
    show()
    try { gate.await() } finally { openProbeGate = null }
    progress = progress.copy(phase = Stage02DPhase.Running)
    show()

    val replies = Channel<Stage02Message>(Channel.UNLIMITED)
    val listener = OnMessageReceivedListener { sender, bytes ->
      if (sender == node.id) Stage02Message.parse(bytes)?.let { replies.trySend(it) }
    }
    var listening = false
    try {
      try {
        await(messageApi.addListener(node.id, listener))
        listening = true
      } catch (error: Exception) {
        rethrowCancellation(error)
        mark(Stage02DItem.PhoneToBand, "LISTENER_FAILED")
        mark(Stage02DItem.BandToPhone, "LISTENER_FAILED")
      }
      if (listening) {
        mark(Stage02DItem.PhoneToBand, phoneToBand(node.id, replies))
        progress = progress.copy(phase = Stage02DPhase.TapBandButton)
        show()
        mark(Stage02DItem.BandToPhone, bandToPhone(node.id, replies))
        progress = progress.copy(phase = Stage02DPhase.Running)
        show()
      }
    } finally {
      if (listening) {
        try { withTimeout(3000) { await(messageApi.removeListener(node.id)) } }
        catch (error: Exception) { rethrowCancellation(error) }
      }
      replies.close()
    }

    if (progress.result(Stage02DItem.NotifyPermission) == "PASS") {
      progress = progress.copy(phase = Stage02DPhase.ConfirmNotification)
    } else {
      progress = progress.update(Stage02DItem.NotificationApi, "SKIPPED_NO_PERMISSION")
        .update(Stage02DItem.Notification, "SKIPPED_NO_PERMISSION").copy(phase = Stage02DPhase.Done)
    }
    show()
    return progress
  }

  suspend fun sendNotification(): String {
    val id = nodeId ?: return "NO_CONNECTED_NODE"
    return try {
      await(notifyApi.sendNotify(id, "CodexQuota 测试", "Stage 02D 通知验证"))
      "SUCCESS"
    } catch (error: Exception) {
      rethrowCancellation(error)
      notifyApiResult(error)
    }
  }

  private suspend fun installCheck(id: String): String = try {
    if (await(nodeApi.isWearAppInstalled(id))) "TRUE" else "FALSE"
  } catch (error: Exception) {
    rethrowCancellation(error)
    installResult(error)
  }

  private suspend fun permissionCheck(id: String, permission: Permission): String = try {
    if (await(authApi.checkPermission(id, permission))) return "PASS"
    try { await(authApi.requestPermission(id, permission)) }
    catch (error: Exception) { rethrowCancellation(error) }
    if (await(authApi.checkPermission(id, permission))) "PASS" else "DENIED"
  } catch (error: Exception) {
    rethrowCancellation(error)
    if (error is com.xiaomi.xms.wearable.exception.PermissionDeniedException) "DENIED" else "ERROR"
  }

  private suspend fun phoneToBand(id: String, replies: Channel<Stage02Message>): String {
    val nonce = Stage02Message.randomNonce()
    try { await(messageApi.sendMessage(id, Stage02Message.encode(Stage02Message.PING, nonce))) }
    catch (error: Exception) { rethrowCancellation(error); return "SEND_FAILED" }
    val reply = withTimeoutOrNull(8000) { replies.receive() } ?: return "TIMEOUT"
    return if (reply.type == Stage02Message.PONG && reply.nonce == nonce) "PASS" else "INVALID_REPLY"
  }

  private suspend fun bandToPhone(id: String, replies: Channel<Stage02Message>): String {
    val ping = withTimeoutOrNull(30000) { replies.receive() } ?: return "TIMEOUT"
    if (ping.type != Stage02Message.BAND_PING) return "INVALID_REPLY"
    try { await(messageApi.sendMessage(id, Stage02Message.encode(Stage02Message.ANDROID_PONG, ping.nonce))) }
    catch (error: Exception) { rethrowCancellation(error); return "SEND_FAILED" }
    val reply = withTimeoutOrNull(8000) { replies.receive() } ?: return "TIMEOUT"
    return if (reply.type == Stage02Message.PONG && reply.nonce == ping.nonce) "PASS" else "INVALID_REPLY"
  }

  private fun rethrowCancellation(error: Exception) {
    if (error is CancellationException && error !is TimeoutCancellationException) throw error
  }

  private suspend fun <T> await(task: Task<T>): T = withTimeout(8000) {
    suspendCancellableCoroutine { continuation ->
      task.addOnSuccessListener { if (continuation.isActive) continuation.resume(it) }
      task.addOnFailureListener { if (continuation.isActive) continuation.resumeWithException(it) }
    }
  }
}
