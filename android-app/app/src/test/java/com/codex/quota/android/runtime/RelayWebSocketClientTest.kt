package com.codex.quota.android.runtime

import com.codex.quota.android.protocol.RelayCipher
import com.codex.quota.android.protocol.RelayProtocolTest
import com.codex.quota.android.security.RelayCredentials
import com.codex.quota.android.security.RelayStateStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayWebSocketClientTest {
  private val credentials =
    RelayCredentials.create(
      "https://ntfy.sh",
      ByteArray(32) { (it + 1).toByte() },
      ByteArray(32) { (it + 33).toByte() },
      ByteArray(16) { (it + 65).toByte() },
    )

  @Test
  fun firstConnectionUsesLatestAndReconnectUsesMessageCursor() {
    val store = FakeRelayStateStore()
    val client = RelayWebSocketClient(CoroutineScope(SupervisorJob()), RuntimeStateRepository(), store)
    assertTrue(client.subscriptionUrl(credentials).endsWith("/ws?since=latest"))
    store.messageId = "ntfy123"
    assertTrue(client.subscriptionUrl(credentials).endsWith("/ws?since=ntfy123"))
  }

  @Test
  fun cachedLatestMessageRestoresSnapshotAndReplayIsRejected() {
    val store = FakeRelayStateStore()
    val repository = RuntimeStateRepository()
    val processor = RelayMessageProcessor(credentials, store, repository)
    val envelope = RelayCipher.encryptForTest(credentials, RelayProtocolTest.FULL_PAYLOAD)
    val event =
      """{"id":"cached1","time":1789516800,"event":"message","topic":"${credentials.topicName()}","message":"${escape(envelopeJson(envelope))}"}"""
    assertEquals(RelayProcessingResult.Accepted, processor.process(event, reconnect = false))
    assertEquals(17L, store.sequence)
    assertEquals("cached1", store.messageId)
    assertEquals(RelayProcessingResult.IgnoredReplay, processor.process(event, reconnect = true))
  }

  @Test
  fun persistedCursorResumesWithTheNextCachedSequence() {
    val store = FakeRelayStateStore()
    val repository = RuntimeStateRepository()
    val processor = RelayMessageProcessor(credentials, store, repository)
    val first = RelayCipher.encryptForTest(credentials, RelayProtocolTest.FULL_PAYLOAD)
    val firstEvent =
      """{"id":"cached1","time":1789516800,"event":"message","topic":"${credentials.topicName()}","message":"${escape(envelopeJson(first))}"}"""
    assertEquals(RelayProcessingResult.Accepted, processor.process(firstEvent, reconnect = false))

    val client = RelayWebSocketClient(CoroutineScope(SupervisorJob()), repository, store)
    assertTrue(client.subscriptionUrl(credentials).endsWith("/ws?since=cached1"))

    val secondPayload = RelayProtocolTest.FULL_PAYLOAD.replace("\"sequence\": 17", "\"sequence\": 18")
    val second = RelayCipher.encryptForTest(credentials, secondPayload)
    val secondEvent =
      """{"id":"cached2","time":1789516801,"event":"message","topic":"${credentials.topicName()}","message":"${escape(envelopeJson(second))}"}"""
    assertEquals(RelayProcessingResult.Accepted, processor.process(secondEvent, reconnect = true))
    assertEquals(18L, store.sequence)
    assertEquals("cached2", store.messageId)
  }

  private fun envelopeJson(envelope: com.codex.quota.android.protocol.RelayEnvelope): String =
    """{"version":${envelope.version},"nonce":"${envelope.nonce}","ciphertext":"${envelope.ciphertext}"}"""

  private fun escape(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")

  private class FakeRelayStateStore : RelayStateStore {
    var sequence = 0L
    var messageId: String? = null

    override fun acceptRelaySequence(sequence: Long): Boolean {
      if (sequence <= this.sequence) return false
      this.sequence = sequence
      return true
    }

    override fun relayLastSequence(): Long = sequence
    override fun saveRelayMessageId(messageId: String) { this.messageId = messageId }
    override fun relayLastMessageId(): String? = messageId
  }
}
