package com.codex.quota.android.runtime

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.codex.quota.android.protocol.ChatGptState
import com.codex.quota.android.protocol.RelayCipher
import com.codex.quota.android.protocol.RelayPairingDeepLinkContract
import com.codex.quota.android.security.PairingCredentialStore
import java.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assume.assumeTrue
import org.junit.Test

class RelayPublicSmokeInstrumentedTest {
  @Test
  fun publicCachedSnapshotUsesFormalSubscriberAndRejectsTamperReplayAndRollback() = runBlocking {
    val applicationContext = ApplicationProvider.getApplicationContext<Context>()
    val pairingFile = applicationContext.filesDir.resolve("foundation01v-pairing-link.txt")
    val eventFile = applicationContext.filesDir.resolve("foundation01v-ntfy-event.json")
    assumeTrue("Foundation 01V public smoke handoff is not installed", pairingFile.isFile && eventFile.isFile)

    val credentials =
      RelayPairingDeepLinkContract.decode(Uri.parse(pairingFile.readText(Charsets.UTF_8).trim()))
    val cachedEvent = eventFile.readText(Charsets.UTF_8).trim()
    val context = isolatedCredentialContext(applicationContext)
    val store = PairingCredentialStore(context)
    store.clearRelay()
    store.saveRelay(credentials)

    val firstRepository = RuntimeStateRepository()
    val firstScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val firstClient = RelayWebSocketClient(firstScope, firstRepository, store)
    try {
      firstClient.start(credentials)
      awaitSyntheticState(firstRepository, store)
    } finally {
      firstClient.stop()
      firstScope.cancel()
    }

    // Reset only the cursor/sequence in the isolated test store, then prove that a new formal
    // subscription can recover ntfy's cached latest message without another Windows publish.
    store.saveRelay(credentials)
    val recoveredRepository = RuntimeStateRepository()
    val recoveredScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val recoveredClient = RelayWebSocketClient(recoveredScope, recoveredRepository, store)
    try {
      recoveredClient.start(credentials)
      awaitSyntheticState(recoveredRepository, store)
    } finally {
      recoveredClient.stop()
      recoveredScope.cancel()
    }

    val processor = RelayMessageProcessor(credentials, store, recoveredRepository)
    val trustedState = recoveredRepository.state.value
    assertEquals(RelayProcessingResult.IgnoredReplay, processor.process(cachedEvent, true))
    assertSame(trustedState, recoveredRepository.state.value)

    val tamperedEvent = replaceMessage(cachedEvent, tamperedMessage(cachedEvent))
    assertEquals(RelayProcessingResult.IgnoredInvalid, processor.process(tamperedEvent, true))
    assertSame(trustedState, recoveredRepository.state.value)

    val lowerSequenceMessage = lowerSequenceMessage(credentials, cachedEvent)
    val rollbackEvent = replaceMessage(cachedEvent, lowerSequenceMessage)
    assertEquals(RelayProcessingResult.IgnoredReplay, processor.process(rollbackEvent, true))
    assertSame(trustedState, recoveredRepository.state.value)

    store.clearRelay()
    context.getSharedPreferences("pairing-credentials", Context.MODE_PRIVATE).edit().clear().commit()
  }

  private suspend fun awaitSyntheticState(
    repository: RuntimeStateRepository,
    store: PairingCredentialStore,
  ) {
    withTimeout(30_000L) {
      while (true) {
        val state = repository.state.value
        if (
          store.relayLastSequence() == 42L &&
            state.fiveHourQuota?.remainingPercent == 73 &&
            state.weeklyQuota?.remainingPercent == 41 &&
            state.chatGptState == ChatGptState.Running
        ) {
          return@withTimeout
        }
        delay(100L)
      }
    }
  }

  private fun tamperedMessage(event: String): String {
    val json = Json.parseToJsonElement(event).jsonObject
    val envelope = Json.parseToJsonElement(json.getValue("message").jsonPrimitive.content).jsonObject
    val ciphertext = Base64.getUrlDecoder().decode(envelope.getValue("ciphertext").jsonPrimitive.content)
    ciphertext[ciphertext.lastIndex] = (ciphertext.last().toInt() xor 1).toByte()
    return envelopeWithCiphertext(envelope, Base64.getUrlEncoder().withoutPadding().encodeToString(ciphertext))
  }

  private fun lowerSequenceMessage(
    credentials: com.codex.quota.android.security.RelayCredentials,
    event: String,
  ): String {
    val root = Json.parseToJsonElement(event).jsonObject
    val message = root.getValue("message").jsonPrimitive.content
    val envelope = RelayCipher.decodeEnvelope(message)
    val plaintext = Json.parseToJsonElement(RelayCipher.decrypt(credentials, envelope)).jsonObject
    val lower =
      JsonObject(
        plaintext.mapValues { (name, value) ->
          if (name == "sequence") JsonPrimitive(41L) else value
        },
      )
    val encrypted = RelayCipher.encryptForTest(credentials, lower.toString())
    return buildJsonObject {
      put("version", JsonPrimitive(encrypted.version))
      put("nonce", JsonPrimitive(encrypted.nonce))
      put("ciphertext", JsonPrimitive(encrypted.ciphertext))
    }.toString()
  }

  private fun envelopeWithCiphertext(envelope: JsonObject, ciphertext: String): String =
    buildJsonObject {
      put("version", envelope.getValue("version"))
      put("nonce", envelope.getValue("nonce"))
      put("ciphertext", JsonPrimitive(ciphertext))
    }.toString()

  private fun replaceMessage(event: String, message: String): String {
    val root = Json.parseToJsonElement(event).jsonObject
    return JsonObject(
      root.mapValues { (name, value) ->
        if (name == "message") JsonPrimitive(message) else value
      },
    ).toString()
  }

  private fun isolatedCredentialContext(base: Context): Context =
    object : ContextWrapper(base) {
      override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences =
        base.getSharedPreferences("foundation01v-${requireNotNull(name)}", mode)
    }
}
