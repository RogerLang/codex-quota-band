package com.codex.quota.android.validation

import android.content.Context
import android.os.Build
import android.util.Base64
import com.codex.quota.android.protocol.ChatGptState
import com.codex.quota.android.protocol.CodexLinkStatus
import com.codex.quota.android.protocol.ComputerLinkStatus
import com.codex.quota.android.protocol.QuotaSnapshot
import com.codex.quota.android.protocol.QuotaSourceStatus
import com.codex.quota.android.protocol.QuotaWindow
import com.codex.quota.android.protocol.QuotaWindowStatus
import com.codex.quota.android.protocol.RelayCipher
import com.codex.quota.android.protocol.RelayEnvelope
import com.codex.quota.android.protocol.RelayPayloadWireContract
import com.codex.quota.android.protocol.RelaySnapshot
import com.codex.quota.android.protocol.ResetInventorySnapshot
import com.codex.quota.android.protocol.ResetInventoryStatus
import com.codex.quota.android.protocol.TaskSnapshot
import com.codex.quota.android.protocol.UpstreamDatasetFreshness
import com.codex.quota.android.protocol.UpstreamFreshness
import com.codex.quota.android.protocol.UpstreamFreshnessStatus
import com.codex.quota.android.runtime.RelayMessageProcessor
import com.codex.quota.android.runtime.RelayProcessingResult
import com.codex.quota.android.runtime.RelayWebSocketClient
import com.codex.quota.android.runtime.RuntimeStateRepository
import com.codex.quota.android.security.PairingCredentialStore
import com.codex.quota.android.security.RelayCredentials
import java.io.IOException
import java.security.SecureRandom
import java.time.Instant
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrl

internal enum class ValidationItem(val label: String) {
  Keystore("安全密钥存储"),
  Relay("ntfy 实时接收"),
  Encryption("加密解密"),
  LatestRecovery("无 cursor 最新缓存恢复"),
  CursorRecovery("持久 cursor 断线恢复"),
  Tamper("篡改拒绝"),
  Replay("重复消息拒绝"),
  Rollback("旧消息拒绝"),
}

internal enum class ValidationStatus {
  Waiting,
  Running,
  Pass,
  Fail,
}

internal data class ValidationProgress(
  val statuses: Map<ValidationItem, ValidationStatus> =
    ValidationItem.entries.associateWith { ValidationStatus.Waiting },
  val running: Boolean = false,
  val finished: Boolean = false,
  val errorCode: String? = null,
  val completedAtMs: Long? = null,
) {
  val overallPass: Boolean
    get() = finished && statuses.values.all { it == ValidationStatus.Pass }

  fun sanitizedReport(context: Context): String {
    val version =
      context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
    val result = if (overallPass) "PASS" else "FAIL"
    return buildString {
      appendLine("App version: $version")
      appendLine("Android version: ${Build.VERSION.RELEASE}")
      ValidationItem.entries.forEach { item ->
        val status = if (statuses[item] == ValidationStatus.Pass) "PASS" else "FAIL"
        appendLine("${item.label}: $status")
      }
      errorCode?.let { appendLine("Error code: $it") }
      appendLine(
        "Test time: ${DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(completedAtMs ?: 0L))}",
      )
      append("Overall: $result")
    }
  }
}

internal class Foundation01ValidationRunner(
  private val context: Context,
  private val httpClient: OkHttpClient = OkHttpClient(),
) {
  private val secureRandom = SecureRandom()
  private val store = PairingCredentialStore(context)

  suspend fun run(onProgress: (ValidationProgress) -> Unit): ValidationProgress {
    val statuses = ValidationItem.entries.associateWith { ValidationStatus.Waiting }.toMutableMap()
    var firstError: String? = null
    var credentials: RelayCredentials? = null
    var activeClient: RelayWebSocketClient? = null
    var activeScope: CoroutineScope? = null

    fun publishProgress(finished: Boolean = false) {
      onProgress(
        ValidationProgress(
          statuses = statuses.toMap(),
          running = !finished,
          finished = finished,
          errorCode = firstError,
          completedAtMs = if (finished) System.currentTimeMillis() else null,
        ),
      )
    }

    fun start(item: ValidationItem) {
      statuses[item] = ValidationStatus.Running
      publishProgress()
    }

    fun pass(item: ValidationItem) {
      statuses[item] = ValidationStatus.Pass
      publishProgress()
    }

    fun fail(item: ValidationItem, code: String) {
      statuses[item] = ValidationStatus.Fail
      if (firstError == null) firstError = code
      publishProgress()
    }

    fun failWaiting(code: String) {
      statuses.entries.filter { it.value == ValidationStatus.Waiting || it.value == ValidationStatus.Running }
        .forEach { it.setValue(ValidationStatus.Fail) }
      if (firstError == null) firstError = code
    }

    store.clearRelay()
    try {
      start(ValidationItem.Keystore)
      credentials = randomCredentials()
      runKeystoreValidation(credentials)
      pass(ValidationItem.Keystore)

      val sequence = randomSequence()
      val snapshot = syntheticSnapshot(sequence)
      start(ValidationItem.Encryption)
      val plaintext = RelayPayloadWireContract.encode(snapshot)
      val envelope = RelayCipher.encrypt(credentials, plaintext)
      val envelopeJson = RelayCipher.encodeEnvelope(envelope)
      val roundTrip =
        RelayPayloadWireContract.decode(
          RelayCipher.decrypt(credentials, RelayCipher.decodeEnvelope(envelopeJson)),
        )
      requireSyntheticState(roundTrip, sequence)
      pass(ValidationItem.Encryption)

      store.saveRelay(credentials)
      start(ValidationItem.Relay)
      val firstRepository = RuntimeStateRepository(relayMode = true)
      activeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
      activeClient = RelayWebSocketClient(activeScope, firstRepository, store)
      activeClient.start(credentials)
      delay(500L)
      val publishError = runCatching { publish(credentials, envelopeJson) }.exceptionOrNull()
      if (publishError != null) {
        val code = (publishError as? ValidationFailure)?.code ?: "RELAY_UNREACHABLE"
        fail(ValidationItem.Relay, code)
      } else {
        val received = runCatching { awaitSyntheticState(firstRepository, sequence) }.isSuccess
        if (received) pass(ValidationItem.Relay) else fail(ValidationItem.Relay, "RELAY_TIMEOUT")
      }
      activeClient.stop()
      activeScope.cancel()
      activeClient = null
      activeScope = null

      start(ValidationItem.LatestRecovery)
      if (statuses[ValidationItem.Relay] == ValidationStatus.Pass) {
        delay(500L)
        // Deliberately clear the cursor only for the no-cursor since=latest case.
        store.saveRelay(credentials)
        val recoveredRepository = RuntimeStateRepository(relayMode = true)
        activeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        activeClient = RelayWebSocketClient(activeScope, recoveredRepository, store)
        activeClient.start(credentials)
        val recovered = runCatching { awaitSyntheticState(recoveredRepository, sequence) }.isSuccess
        if (recovered) pass(ValidationItem.LatestRecovery)
        else fail(ValidationItem.LatestRecovery, "RELAY_LATEST_TIMEOUT")
        activeClient.stop()
        activeScope.cancel()
        activeClient = null
        activeScope = null
      } else {
        fail(ValidationItem.LatestRecovery, "RELAY_DEPENDENCY_FAILED")
      }

      start(ValidationItem.CursorRecovery)
      if (statuses[ValidationItem.LatestRecovery] == ValidationStatus.Pass) {
        // Keep the accepted sequence and message ID. Publish a newer synthetic state while
        // disconnected, then recover that missed message using the persisted ntfy cursor.
        val missedSequence = sequence + 1
        val missedSnapshot = syntheticSnapshot(missedSequence)
        val missedEnvelope =
          RelayCipher.encodeEnvelope(
            RelayCipher.encrypt(credentials, RelayPayloadWireContract.encode(missedSnapshot)),
          )
        val cursorBefore = store.relayLastMessageId()
        if (cursorBefore == null || store.relayLastSequence() != sequence) {
          fail(ValidationItem.CursorRecovery, "RELAY_CURSOR_MISSING")
        } else {
          val publishMissedError = runCatching { publish(credentials, missedEnvelope) }.exceptionOrNull()
          if (publishMissedError != null) {
            fail(ValidationItem.CursorRecovery, "RELAY_UNREACHABLE")
          } else {
            val cursorRepository = RuntimeStateRepository(relayMode = true)
            activeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            activeClient = RelayWebSocketClient(activeScope, cursorRepository, store)
            activeClient.start(credentials)
            val cursorRecovered =
              runCatching { awaitSyntheticState(cursorRepository, missedSequence) }.isSuccess
            if (cursorRecovered && store.relayLastMessageId() != cursorBefore) {
              pass(ValidationItem.CursorRecovery)
            } else {
              fail(ValidationItem.CursorRecovery, "RELAY_CURSOR_TIMEOUT")
            }
            activeClient.stop()
            activeScope.cancel()
            activeClient = null
            activeScope = null
          }
        }
      } else {
        fail(ValidationItem.CursorRecovery, "RELAY_DEPENDENCY_FAILED")
      }

      runProtocolRejectionChecks(credentials, snapshot, envelope)
        .forEach { (item, result) ->
          start(item)
          if (result) pass(item) else fail(item, item.failureCode())
        }
    } catch (failure: ValidationFailure) {
      if (statuses[failure.item] != ValidationStatus.Pass) fail(failure.item, failure.code)
      failWaiting("DEPENDENCY_FAILED")
    } catch (_: Throwable) {
      failWaiting("VALIDATION_INTERNAL_ERROR")
    } finally {
      activeClient?.stop()
      activeScope?.cancel()
      if (runCatching { store.clearRelayAndKey() }.isFailure) {
        statuses[ValidationItem.Keystore] = ValidationStatus.Fail
        if (firstError == null) firstError = "KEYSTORE_CLEANUP"
      }
      context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE).edit().clear().commit()
      credentials?.destroy()
    }
    return ValidationProgress(
      statuses = statuses.toMap(),
      running = false,
      finished = true,
      errorCode = firstError,
      completedAtMs = System.currentTimeMillis(),
    ).also(onProgress)
  }

  private fun runKeystoreValidation(credentials: RelayCredentials) {
    store.saveRelay(credentials)
    val rawPreferences =
      context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        .all.values.joinToString(separator = "|") { it.toString() }
    require(!rawPreferences.contains(base64Url(credentials.key))) {
      throw ValidationFailure(ValidationItem.Keystore, "KEYSTORE_PLAINTEXT")
    }
    val restored = PairingCredentialStore(context).loadRelay()
      ?: throw ValidationFailure(ValidationItem.Keystore, "KEYSTORE_READ")
    try {
      require(
        restored.baseUrl == credentials.baseUrl &&
          restored.topic.contentEquals(credentials.topic) &&
          restored.key.contentEquals(credentials.key) &&
          restored.deviceId.contentEquals(credentials.deviceId),
      ) { throw ValidationFailure(ValidationItem.Keystore, "KEYSTORE_MISMATCH") }
    } finally {
      restored.destroy()
    }

    store.clearRelay()
    require(PairingCredentialStore(context).loadRelay() == null) {
      throw ValidationFailure(ValidationItem.Keystore, "KEYSTORE_CLEAR")
    }

    store.saveRelay(credentials)
    val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    val encoded = preferences.getString(KEY_RELAY_ENVELOPE, null)
      ?: throw ValidationFailure(ValidationItem.Keystore, "KEYSTORE_STORAGE")
    val corrupted = Base64.decode(encoded, Base64.NO_WRAP).also { bytes ->
      bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
    }
    preferences.edit()
      .putString(KEY_RELAY_ENVELOPE, Base64.encodeToString(corrupted, Base64.NO_WRAP))
      .commit()
    corrupted.fill(0)
    require(PairingCredentialStore(context).loadRelay() == null) {
      throw ValidationFailure(ValidationItem.Keystore, "KEYSTORE_CORRUPTION_ACCEPTED")
    }
  }

  private suspend fun publish(credentials: RelayCredentials, envelope: String) {
    val url =
      credentials.baseUrl.toHttpUrl().newBuilder()
        .addPathSegment(credentials.topicName())
        .build()
    val request =
      Request.Builder()
        .url(url)
        .post(envelope.toRequestBody("text/plain; charset=utf-8".toMediaType()))
        .build()
    withContext(Dispatchers.IO) {
      try {
        httpClient.newCall(request).execute().use { response ->
          if (!response.isSuccessful) {
            val code = if (response.code == 429 || response.code >= 500) "RELAY_UNREACHABLE" else "RELAY_REJECTED"
            throw ValidationFailure(ValidationItem.Relay, code)
          }
        }
      } catch (failure: ValidationFailure) {
        throw failure
      } catch (_: IOException) {
        throw ValidationFailure(ValidationItem.Relay, "RELAY_UNREACHABLE")
      }
    }
  }

  private suspend fun awaitSyntheticState(repository: RuntimeStateRepository, sequence: Long) {
    withTimeout(RELAY_TIMEOUT_MS) {
      while (true) {
        val state = repository.state.value
        if (
          store.relayLastSequence() == sequence &&
            store.relayLastMessageId() != null &&
            state.fiveHourQuota?.remainingPercent == 73 &&
            state.weeklyQuota?.remainingPercent == 41 &&
            state.chatGptState == ChatGptState.Running
        ) return@withTimeout
        delay(100L)
      }
    }
  }

  private fun runProtocolRejectionChecks(
    credentials: RelayCredentials,
    snapshot: RelaySnapshot,
    envelope: RelayEnvelope,
  ): Map<ValidationItem, Boolean> {
    store.saveRelay(credentials)
    val repository = RuntimeStateRepository(relayMode = true)
    val processor = RelayMessageProcessor(credentials, store, repository)
    val originalEvent = ntfyEvent("validation-original", credentials, RelayCipher.encodeEnvelope(envelope))
    check(processor.process(originalEvent, reconnect = false) == RelayProcessingResult.Accepted)
    val trusted = repository.state.value

    val ciphertext = java.util.Base64.getUrlDecoder().decode(envelope.ciphertext)
    ciphertext[ciphertext.lastIndex] = (ciphertext.last().toInt() xor 1).toByte()
    val tamperedEnvelope =
      envelope.copy(
        ciphertext = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ciphertext),
      )
    ciphertext.fill(0)
    val tamperResult =
      processor.process(
        ntfyEvent("validation-tamper", credentials, RelayCipher.encodeEnvelope(tamperedEnvelope)),
        reconnect = true,
      ) == RelayProcessingResult.IgnoredInvalid && repository.state.value === trusted

    val replayResult =
      processor.process(originalEvent, reconnect = true) == RelayProcessingResult.IgnoredReplay &&
        repository.state.value === trusted

    val lowerSequence = snapshot.sequence - 1
    val lowerSnapshot =
      snapshot.copy(
        sequence = lowerSequence,
        tasks = snapshot.tasks.copy(sequence = lowerSequence),
      )
    val lowerEnvelope =
      RelayCipher.encrypt(credentials, RelayPayloadWireContract.encode(lowerSnapshot))
    val rollbackResult =
      processor.process(
        ntfyEvent("validation-rollback", credentials, RelayCipher.encodeEnvelope(lowerEnvelope)),
        reconnect = true,
      ) == RelayProcessingResult.IgnoredReplay && repository.state.value === trusted

    return linkedMapOf(
      ValidationItem.Tamper to tamperResult,
      ValidationItem.Replay to replayResult,
      ValidationItem.Rollback to rollbackResult,
    )
  }

  private fun ntfyEvent(id: String, credentials: RelayCredentials, envelope: String): String =
    buildJsonObject {
      put("id", id)
      put("time", System.currentTimeMillis() / 1_000L)
      put("event", "message")
      put("topic", credentials.topicName())
      put("message", JsonPrimitive(envelope))
    }.toString()

  private fun randomCredentials(): RelayCredentials {
    val topic = ByteArray(32).also(secureRandom::nextBytes)
    val key = ByteArray(32).also(secureRandom::nextBytes)
    val deviceId = ByteArray(16).also(secureRandom::nextBytes)
    return try {
      RelayCredentials.create("https://ntfy.sh", topic, key, deviceId)
    } finally {
      topic.fill(0)
      key.fill(0)
      deviceId.fill(0)
    }
  }

  private fun randomSequence(): Long =
    (secureRandom.nextLong() and Long.MAX_VALUE).coerceIn(2L, Long.MAX_VALUE - 1)

  private fun syntheticSnapshot(sequence: Long): RelaySnapshot {
    val now = System.currentTimeMillis()
    val currentFreshness =
      UpstreamDatasetFreshness(
        status = UpstreamFreshnessStatus.Current,
        lastAttemptAtMs = now,
        lastSuccessAtMs = now,
      )
    return RelaySnapshot(
      sequence = sequence,
      generatedAtMs = now,
      quota =
        QuotaSnapshot(
          generatedAtMs = now,
          sourceStatus = QuotaSourceStatus.Ok,
          limitsCollectedAtMs = now,
          windows =
            listOf(
              QuotaWindow(
                id = "validation-five-hour",
                name = "five_hour",
                windowMinutes = 300,
                remainingPercent = 73,
                resetsAtMs = now + 5 * 60 * 60 * 1_000L,
                status = QuotaWindowStatus.Current,
              ),
              QuotaWindow(
                id = "validation-weekly",
                name = "weekly",
                windowMinutes = 10_080,
                remainingPercent = 41,
                resetsAtMs = now + 7 * 24 * 60 * 60 * 1_000L,
                status = QuotaWindowStatus.Current,
              ),
            ),
          resetInventory =
            ResetInventorySnapshot(
              status = ResetInventoryStatus.Missing,
              availableCount = null,
              cachedAtMs = null,
              items = emptyList(),
            ),
          computerLink = ComputerLinkStatus.Online,
          codexLink = CodexLinkStatus.Ok,
          upstreamFreshness =
            UpstreamFreshness(
              usage = currentFreshness,
              resetInventory =
                UpstreamDatasetFreshness(
                  status = UpstreamFreshnessStatus.Unavailable,
                  lastAttemptAtMs = null,
                  lastSuccessAtMs = null,
                ),
            ),
        ),
      tasks =
        TaskSnapshot(
          sequence = sequence,
          generatedAtMs = now,
          chatGptState = ChatGptState.Running,
          chatGptFocused = false,
          tasks = emptyList(),
        ),
    )
  }

  private fun requireSyntheticState(snapshot: RelaySnapshot, sequence: Long) {
    require(snapshot.sequence == sequence)
    require(snapshot.quota.windows.single { it.name == "five_hour" }.remainingPercent == 73)
    require(snapshot.quota.windows.single { it.name == "weekly" }.remainingPercent == 41)
    require(snapshot.tasks.chatGptState == ChatGptState.Running)
  }

  private fun base64Url(value: ByteArray): String =
    java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(value)

  private fun ValidationItem.failureCode(): String =
    when (this) {
      ValidationItem.Tamper -> "TAMPER_ACCEPTED"
      ValidationItem.Replay -> "REPLAY_ACCEPTED"
      ValidationItem.Rollback -> "ROLLBACK_ACCEPTED"
      else -> "VALIDATION_FAILED"
    }

  private class ValidationFailure(
    val item: ValidationItem,
    val code: String,
  ) : RuntimeException()

  private companion object {
    const val PREFERENCES_NAME = "pairing-credentials"
    const val KEY_RELAY_ENVELOPE = "relay-encrypted-envelope-v1"
    const val RELAY_TIMEOUT_MS = 30_000L
  }
}
