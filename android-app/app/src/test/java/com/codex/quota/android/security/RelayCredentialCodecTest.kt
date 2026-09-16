package com.codex.quota.android.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RelayCredentialCodecTest {
  @Test
  fun relayCredentialCodecRoundTripsThePinnedRelayMaterial() {
    val credentials =
      RelayCredentials.create(
        "https://ntfy.sh",
        ByteArray(32) { it.toByte() },
        ByteArray(32) { (it + 32).toByte() },
        ByteArray(16) { (it + 64).toByte() },
      )
    val restored = RelayCredentialCodec.decode(RelayCredentialCodec.encode(credentials))
    assertEquals("https://ntfy.sh", restored.baseUrl)
    assertArrayEquals(credentials.topic, restored.topic)
    assertArrayEquals(credentials.key, restored.key)
    assertArrayEquals(credentials.deviceId, restored.deviceId)
  }

  @Test
  fun rejectsNonHttpsRelayAndWrongKeySizes() {
    assertThrows(IllegalArgumentException::class.java) {
      RelayCredentials.create("http://ntfy.sh", ByteArray(32), ByteArray(32), ByteArray(16))
    }
    assertThrows(IllegalArgumentException::class.java) {
      RelayCredentials.create("https://ntfy.sh", ByteArray(31), ByteArray(32), ByteArray(16))
    }
  }
}
