package com.codex.quota.android.protocol

import com.codex.quota.android.security.RelayCredentials
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RelayProtocolTest {
  private val credentials =
    RelayCredentials.create(
      baseUrl = "https://ntfy.sh",
      topic = ByteArray(32) { (it + 1).toByte() },
      key = ByteArray(32) { (it + 33).toByte() },
      deviceId = ByteArray(16) { (it + 65).toByte() },
    )

  @Test
  fun aesGcmRoundTripUsesUniqueTwelveByteNonces() {
    val first = RelayCipher.encryptForTest(credentials, VALID_PAYLOAD)
    val second = RelayCipher.encryptForTest(credentials, VALID_PAYLOAD)
    assertEquals(12, first.nonceBytes().size)
    assertNotEquals(first.nonce, second.nonce)
    assertEquals(VALID_PAYLOAD, RelayCipher.decrypt(credentials, first))
  }

  @Test
  fun wrongKeyAndModifiedTagAreRejected() {
    val envelope = RelayCipher.encryptForTest(credentials, VALID_PAYLOAD)
    val wrong =
      RelayCredentials.create(
        "https://ntfy.sh",
        credentials.topic,
        ByteArray(32) { 9 },
        credentials.deviceId,
      )
    assertThrows(Exception::class.java) { RelayCipher.decrypt(wrong, envelope) }
    val modifiedBytes = java.util.Base64.getUrlDecoder().decode(envelope.ciphertext)
    modifiedBytes[modifiedBytes.lastIndex] = (modifiedBytes.last().toInt() xor 1).toByte()
    val modified =
      envelope.copy(
        ciphertext = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(modifiedBytes),
      )
    assertThrows(Exception::class.java) { RelayCipher.decrypt(credentials, modified) }
  }

  @Test
  fun parsesNtfyMessageAndIgnoresOpenAndKeepalive() {
    assertEquals(NtfyEventKind.Open, NtfyEventParser.parse("""{"id":"a","time":1,"event":"open","topic":"t"}""").kind)
    assertEquals(NtfyEventKind.Keepalive, NtfyEventParser.parse("""{"id":"b","time":2,"event":"keepalive","topic":"t"}""").kind)
    val message = NtfyEventParser.parse("""{"id":"c","time":3,"event":"message","topic":"t","message":"ciphertext"}""")
    assertEquals(NtfyEventKind.Message, message.kind)
    assertEquals("ciphertext", message.message)
  }

  @Test
  fun replayGuardRejectsDuplicateAndRollbackAcrossRestarts() {
    var persisted = 0L
    val guard = RelayReplayGuard({ persisted }, { persisted = it })
    assertEquals(RelaySequenceDecision.Accepted, guard.accept(10))
    assertEquals(RelaySequenceDecision.RejectedReplay, guard.accept(10))
    val reopened = RelayReplayGuard({ persisted }, { persisted = it })
    assertEquals(RelaySequenceDecision.RejectedReplay, reopened.accept(9))
    assertEquals(RelaySequenceDecision.Accepted, reopened.accept(11))
  }

  @Test
  fun relayPayloadRestoresExistingQuotaAndTaskDomainModels() {
    val snapshot = RelayPayloadWireContract.decode(FULL_PAYLOAD)
    assertEquals(17L, snapshot.sequence)
    assertEquals(1_789_516_800_000L, snapshot.quota.generatedAtMs)
    assertEquals(ChatGptState.Running, snapshot.tasks.chatGptState)
    assertEquals("Foundation", snapshot.tasks.tasks.single().title)
  }

  @Test
  fun formalRelaySerializerCipherAndEnvelopeRoundTrip() {
    val snapshot = RelayPayloadWireContract.decode(FULL_PAYLOAD)
    val plaintext = RelayPayloadWireContract.encode(snapshot)
    assertEquals(snapshot, RelayPayloadWireContract.decode(plaintext))

    val envelope = RelayCipher.encrypt(credentials, plaintext)
    val encodedEnvelope = RelayCipher.encodeEnvelope(envelope)
    val decodedEnvelope = RelayCipher.decodeEnvelope(encodedEnvelope)
    assertEquals(snapshot, RelayPayloadWireContract.decode(RelayCipher.decrypt(credentials, decodedEnvelope)))
  }

  @Test
  fun relayPairingAcceptsOnlyHttpsAndFixedSizeRandomMaterial() {
    val payload =
      """{"protocolVersion":1,"type":"relay_pairing","relayBaseUrl":"https://ntfy.sh","topic":"${java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(credentials.topic)}","key":"${java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(credentials.key)}","deviceId":"${java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(credentials.deviceId)}"}"""
    val decoded = RelayPairingWireContract.decode(payload)
    assertEquals("https://ntfy.sh", decoded.baseUrl)
    assertThrows(IllegalArgumentException::class.java) {
      RelayPairingWireContract.decode(payload.replace("https://", "http://"))
    }
    assertThrows(Exception::class.java) {
      RelayPairingWireContract.decode(payload.dropLast(1) + ",\"quota\":42}")
    }
  }

  companion object {
    const val VALID_PAYLOAD = """{"protocolVersion":1,"sequence":1}"""
    const val FULL_PAYLOAD =
      """
      {
        "protocolVersion": 1,
        "sequence": 17,
        "generatedAtMs": 1789516800000,
        "quota": {
          "protocolVersion": 3,
          "generatedAt": "2026-09-16T00:00:00Z",
          "sourceStatus": "ok",
          "limitsCollectedAt": "2026-09-16T00:00:00Z",
          "windows": [],
          "resetInventory": {"status":"missing","availableCount":null,"cachedAt":null,"items":[]},
          "link": {"computer":"online","codex":"ok"},
          "upstreamFreshness": {
            "usage":{"status":"current","lastAttemptAt":"2026-09-16T00:00:00Z","lastSuccessAt":"2026-09-16T00:00:00Z"},
            "resetInventory":{"status":"unavailable","lastAttemptAt":null,"lastSuccessAt":null}
          }
        },
        "tasks": [{"conversationId":"thread-1","title":"Foundation","state":"running","updatedAtMs":1789516800000}],
        "chatGptState": "running",
        "chatGptFocused": false
      }
      """
  }
}
