package com.codex.quota.android.validation

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Stage03AMessageTest {
  @Test fun stateRoundTripIsStrict() {
    val nonce = "0123456789abcdef0123456789abcdef"
    val parsed = Stage03AMessage.parse(Stage03AMessage.encodeState(42, nonce))
    assertEquals(Stage03AMessage.STATE, parsed?.type)
    assertEquals(42, parsed?.sequence)
    assertEquals(nonce, parsed?.nonce)
  }

  @Test fun ackParsesPersistedFlag() {
    val payload = buildJsonObject {
      put("type", Stage03AMessage.ACK)
      put("version", Stage03AMessage.VERSION)
      put("sequence", 43)
      put("nonce", "fedcba9876543210fedcba9876543210")
      put("persisted", true)
    }.toString().toByteArray()
    val parsed = Stage03AMessage.parse(payload)
    assertEquals(43, parsed?.sequence)
    assertTrue(parsed?.persisted == true)
  }

  @Test fun unknownOrExtraFieldsAreRejected() {
    val unknown = "{\"type\":\"other\",\"version\":1,\"sequence\":42,\"nonce\":\"0123456789abcdef0123456789abcdef\"}".toByteArray()
    val extra = "{\"type\":\"stage03_state\",\"version\":1,\"sequence\":42,\"nonce\":\"0123456789abcdef0123456789abcdef\",\"extra\":1}".toByteArray()
    assertNull(Stage03AMessage.parse(unknown))
    assertNull(Stage03AMessage.parse(extra))
  }
}
