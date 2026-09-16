package com.codex.quota.android.security

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import java.security.SecureRandom
import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayCredentialStoreInstrumentedTest {
  @Test
  fun relayCredentialIsEncryptedRestoredClearedAndRejectsCorruption() {
    val applicationContext = ApplicationProvider.getApplicationContext<Context>()
    val context = isolatedCredentialContext(applicationContext)
    val store = PairingCredentialStore(context)
    store.clearRelay()
    context.getSharedPreferences("pairing-credentials", Context.MODE_PRIVATE).edit().clear().commit()

    val random = SecureRandom()
    val topic = ByteArray(32).also(random::nextBytes)
    val key = ByteArray(32).also(random::nextBytes)
    val deviceId = ByteArray(16).also(random::nextBytes)
    val expectedTopic = topic.copyOf()
    val expectedKey = key.copyOf()
    val expectedDeviceId = deviceId.copyOf()
    val pairingQr = pairingDeepLink(topic, key, deviceId)

    saveRelayInSeparateScope(store, topic, key, deviceId)
    topic.fill(0)
    key.fill(0)
    deviceId.fill(0)

    val rawPreferences =
      context.getSharedPreferences("pairing-credentials", android.content.Context.MODE_PRIVATE)
        .all.values.joinToString(separator = "|") { it.toString() }
    assertFalse(rawPreferences.contains(base64Url(expectedKey)))
    assertFalse(rawPreferences.contains(base64Url(expectedTopic + expectedKey)))
    assertFalse(rawPreferences.contains(pairingQr))

    val restored = requireNotNull(PairingCredentialStore(context).loadRelay())
    assertEquals("https://ntfy.sh", restored.baseUrl)
    assertArrayEquals(expectedTopic, restored.topic)
    assertArrayEquals(expectedKey, restored.key)
    assertArrayEquals(expectedDeviceId, restored.deviceId)

    store.clearRelay()
    assertNull(PairingCredentialStore(context).loadRelay())

    saveRelayInSeparateScope(store, expectedTopic, expectedKey, expectedDeviceId)
    val preferences =
      context.getSharedPreferences("pairing-credentials", android.content.Context.MODE_PRIVATE)
    val encoded = requireNotNull(preferences.getString("relay-encrypted-envelope-v1", null))
    val corrupted = encoded.toCharArray().also { chars ->
      val index = chars.lastIndex
      chars[index] = if (chars[index] == 'A') 'B' else 'A'
    }.concatToString()
    assertTrue(preferences.edit().putString("relay-encrypted-envelope-v1", corrupted).commit())
    assertNull(PairingCredentialStore(context).loadRelay())
    assertFalse(preferences.contains("relay-encrypted-envelope-v1"))

    store.clearRelay()
    expectedTopic.fill(0)
    expectedKey.fill(0)
    expectedDeviceId.fill(0)
  }

  private fun saveRelayInSeparateScope(
    store: PairingCredentialStore,
    topic: ByteArray,
    key: ByteArray,
    deviceId: ByteArray,
  ) {
    store.saveRelay(RelayCredentials.create("https://ntfy.sh", topic, key, deviceId))
  }

  private fun pairingDeepLink(topic: ByteArray, key: ByteArray, deviceId: ByteArray): String {
    val payload =
      """{"protocolVersion":1,"type":"relay_pairing","relayBaseUrl":"https://ntfy.sh","topic":"${base64Url(topic)}","key":"${base64Url(key)}","deviceId":"${base64Url(deviceId)}"}"""
    return "codexquota://pair?relay=${base64Url(payload.toByteArray())}"
  }

  private fun base64Url(value: ByteArray): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(value)

  private fun isolatedCredentialContext(base: Context): Context =
    object : ContextWrapper(base) {
      override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences =
        base.getSharedPreferences("foundation01v-${requireNotNull(name)}", mode)
    }
}
