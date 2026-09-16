package com.codex.quota.android.validation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Stage02MessageTest {
  @Test fun targetNodeNameMustMatchBand9Pro() {
    assertEquals(true, isBand9ProNodeName("Xiaomi Smart Band 9 Pro"))
    assertEquals(true, isBand9ProNodeName("小米手环 9 Pro"))
    assertEquals(false, isBand9ProNodeName("Xiaomi Smart Band 10"))
  }
  @Test fun roundTrip() {
    val nonce = "00112233445566778899aabbccddeeff"
    val bytes = Stage02Message.encode(Stage02Message.PING, nonce)
    assertEquals(Stage02Message.PING, Stage02Message.parse(bytes)?.type)
    assertEquals(nonce, Stage02Message.parse(bytes)?.nonce)
  }

  @Test fun wrongNonceRejected() {
    val bytes = Stage02Message.encode(Stage02Message.PONG, "00112233445566778899aabbccddeeff")
    assertNull(Stage02Message.parseExpected(bytes, Stage02Message.PONG, "ffeeddccbbaa99887766554433221100"))
    val gate = Stage02ReplyGate(Stage02Message.PONG, "ffeeddccbbaa99887766554433221100", 1000)
    assertEquals(Stage02ReplyGate.Result.WrongNonce, gate.check(Stage02Message.parse(bytes), 999))
  }

  @Test fun unknownMessageRejected() {
    assertNull(Stage02Message.parse("{\"type\":\"quota_snapshot\",\"nonce\":\"00112233445566778899aabbccddeeff\"}".toByteArray()))
    assertNull(Stage02Message.parse("{\"type\":\"stage02_ping\",\"nonce\":\"00112233445566778899aabbccddeeff\",\"data\":1}".toByteArray()))
  }

  @Test fun timeoutFails() {
    val gate = Stage02ReplyGate(Stage02Message.PONG, "00112233445566778899aabbccddeeff", 1000)
    assertEquals(Stage02ReplyGate.Result.Waiting, gate.check(null, 999))
    assertEquals(Stage02ReplyGate.Result.TimedOut, gate.check(null, 1000))
  }
}
