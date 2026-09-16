package com.codex.quota.android.runtime

import com.codex.quota.android.notifications.TaskAlertCoordinator
import com.codex.quota.android.protocol.NtfyEventKind
import com.codex.quota.android.protocol.NtfyEventParser
import com.codex.quota.android.protocol.RelayCipher
import com.codex.quota.android.protocol.RelayPayloadWireContract
import com.codex.quota.android.security.RelayCredentials
import com.codex.quota.android.security.RelayStateStore
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

enum class RelayProcessingResult {
  Accepted,
  IgnoredControl,
  IgnoredReplay,
  IgnoredInvalid,
}

class RelayMessageProcessor(
  private val credentials: RelayCredentials,
  private val credentialStore: RelayStateStore,
  private val repository: RuntimeStateRepository,
  private val taskAlerts: TaskAlertCoordinator? = null,
) {
  fun process(rawEvent: String, reconnect: Boolean): RelayProcessingResult {
    val event = runCatching { NtfyEventParser.parse(rawEvent) }.getOrNull()
      ?: return RelayProcessingResult.IgnoredInvalid
    if (event.kind != NtfyEventKind.Message) return RelayProcessingResult.IgnoredControl
    if (event.topic != credentials.topicName()) return RelayProcessingResult.IgnoredInvalid
    val snapshot =
      runCatching {
          val envelope = RelayCipher.decodeEnvelope(requireNotNull(event.message))
          RelayPayloadWireContract.decode(RelayCipher.decrypt(credentials, envelope))
        }
        .getOrNull()
        ?: return RelayProcessingResult.IgnoredInvalid
    if (!credentialStore.acceptRelaySequence(snapshot.sequence)) {
      runCatching { credentialStore.saveRelayMessageId(event.id) }
      return RelayProcessingResult.IgnoredReplay
    }
    repository.markAuthenticatedWindowsSnapshot(snapshot.generatedAtMs)
    repository.ingestQuota(snapshot.quota)
    if (repository.ingestTasks(snapshot.tasks) == IngestResult.Accepted) {
      taskAlerts?.ingest(snapshot.tasks, reconnect)
    }
    runCatching { credentialStore.saveRelayMessageId(event.id) }
    return RelayProcessingResult.Accepted
  }
}

class RelayWebSocketClient(
  private val scope: CoroutineScope,
  private val repository: RuntimeStateRepository,
  private val credentialStore: RelayStateStore,
  private val taskAlerts: TaskAlertCoordinator? = null,
  private val client: OkHttpClient = OkHttpClient(),
) {
  private var connectionJob: Job? = null
  private var activeWebSocket: WebSocket? = null
  private var savedCredentials: RelayCredentials? = null
  private var freshnessWatchdogJob: Job? = null

  fun start(credentials: RelayCredentials) {
    savedCredentials = credentials
    startFreshnessWatchdog()
    reconnect(credentials)
  }

  /** Relay v1 refreshes cached state and freshness; it does not command Windows to query OpenAI. */
  fun refresh(): Boolean {
    val credentials = savedCredentials ?: return false
    reconnect(credentials)
    repository.reassessQuotaFreshness()
    return true
  }

  fun stop() {
    freshnessWatchdogJob?.cancel()
    freshnessWatchdogJob = null
    closeActiveConnection()
  }

  private fun reconnect(credentials: RelayCredentials) {
    closeActiveConnection()
    connectionJob =
      scope.launch {
        var attempt = 0
        while (isActive) {
          try {
            connectOnce(credentials)
            attempt = 0
          } catch (cancelled: CancellationException) {
            throw cancelled
          } catch (_: Throwable) {
            repository.markTransportDisconnected()
          }
          delay(reconnectDelayMs(attempt++))
        }
      }
  }

  private fun closeActiveConnection() {
    connectionJob?.cancel()
    connectionJob = null
    activeWebSocket?.cancel()
    activeWebSocket = null
    repository.markTransportDisconnected()
  }

  private fun startFreshnessWatchdog() {
    freshnessWatchdogJob?.cancel()
    freshnessWatchdogJob =
      scope.launch {
        while (isActive) {
          delay(FRESHNESS_REASSESSMENT_INTERVAL_MS)
          repository.reassessQuotaFreshness()
        }
      }
  }

  private suspend fun connectOnce(credentials: RelayCredentials) {
    val processor = RelayMessageProcessor(credentials, credentialStore, repository, taskAlerts)
    val firstSnapshot = AtomicBoolean(true)
    suspendCancellableCoroutine { continuation ->
      val request = Request.Builder().url(subscriptionUrl(credentials)).build()
      val listener =
        object : WebSocketListener() {
          override fun onOpen(webSocket: WebSocket, response: Response) {
            activeWebSocket = webSocket
            repository.markTransportConnected()
          }

          override fun onMessage(webSocket: WebSocket, text: String) {
            val reconnect = credentialStore.relayLastSequence() > 0 && firstSnapshot.get()
            if (processor.process(text, reconnect) == RelayProcessingResult.Accepted) {
              firstSnapshot.set(false)
            }
          }

          override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            activeWebSocket = null
            repository.markTransportDisconnected()
            if (continuation.isActive) continuation.resume(Unit)
          }

          override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            activeWebSocket = null
            repository.markTransportDisconnected()
            if (continuation.isActive) continuation.resume(Unit)
          }
        }
      val webSocket = client.newWebSocket(request, listener)
      activeWebSocket = webSocket
      continuation.invokeOnCancellation { webSocket.cancel() }
    }
  }

  internal fun subscriptionUrl(credentials: RelayCredentials): String {
    val base = credentials.baseUrl.toHttpUrl()
    val since = credentialStore.relayLastMessageId() ?: "latest"
    return base.newBuilder()
      .addPathSegment(credentials.topicName())
      .addPathSegment("ws")
      .addQueryParameter("since", since)
      .build()
      .toString()
  }

  internal companion object {
    const val FRESHNESS_REASSESSMENT_INTERVAL_MS = 5_000L

    fun reconnectDelayMs(attempt: Int): Long =
      when (attempt.coerceAtLeast(0)) {
        0 -> 1_000L
        1 -> 2_000L
        2 -> 5_000L
        3 -> 10_000L
        else -> 30_000L
      }
  }
}
