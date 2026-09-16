package com.codex.quota.android.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import java.nio.ByteBuffer
import java.net.URI
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class PairingCredentials private constructor(
  val computerFingerprintHex: String,
  val phoneTokenHex: String,
) {
  companion object {
    fun fromHex(computerFingerprintHex: String, phoneTokenHex: String): PairingCredentials {
      return PairingCredentials(
        computerFingerprintHex = normalizeSha256Hex(computerFingerprintHex),
        phoneTokenHex = normalizeSha256Hex(phoneTokenHex),
      )
    }
  }
}

internal object PairingCredentialCodec {
  fun encode(credentials: PairingCredentials): ByteArray =
    hexToBytes(credentials.computerFingerprintHex) + hexToBytes(credentials.phoneTokenHex)

  fun decode(payload: ByteArray): PairingCredentials {
    require(payload.size == PAYLOAD_BYTES) { "invalid pairing credential payload" }
    return PairingCredentials.fromHex(
      computerFingerprintHex = payload.copyOfRange(0, SHA256_BYTES).toHex(),
      phoneTokenHex = payload.copyOfRange(SHA256_BYTES, PAYLOAD_BYTES).toHex(),
    )
  }

  private const val SHA256_BYTES = 32
  private const val PAYLOAD_BYTES = SHA256_BYTES * 2
}

interface RelayStateStore {
  fun acceptRelaySequence(sequence: Long): Boolean
  fun relayLastSequence(): Long
  fun saveRelayMessageId(messageId: String)
  fun relayLastMessageId(): String?
}

class PairingCredentialStore(context: Context) : RelayStateStore {
  private val preferences =
    context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

  fun save(credentials: PairingCredentials) {
    val plaintext = PairingCredentialCodec.encode(credentials)
    try {
      val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
      cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
      val ciphertext = cipher.doFinal(plaintext)
      val envelope =
        ByteBuffer.allocate(1 + 1 + cipher.iv.size + ciphertext.size)
          .put(ENVELOPE_VERSION)
          .put(cipher.iv.size.toByte())
          .put(cipher.iv)
          .put(ciphertext)
          .array()
      preferences.edit {
        putString(KEY_CREDENTIAL_ENVELOPE, Base64.encodeToString(envelope, Base64.NO_WRAP))
      }
    } finally {
      plaintext.fill(0)
    }
  }

  fun load(): PairingCredentials? {
    val encoded = preferences.getString(KEY_CREDENTIAL_ENVELOPE, null) ?: return null
    return runCatching {
        val envelope = Base64.decode(encoded, Base64.NO_WRAP)
        require(envelope.size > MIN_ENVELOPE_BYTES) { "invalid encrypted credentials" }
        val buffer = ByteBuffer.wrap(envelope)
        require(buffer.get() == ENVELOPE_VERSION) { "unsupported credential envelope" }
        val ivSize = buffer.get().toInt() and 0xff
        require(ivSize in 12..16 && buffer.remaining() > ivSize) { "invalid credential IV" }
        val iv = ByteArray(ivSize).also(buffer::get)
        val ciphertext = ByteArray(buffer.remaining()).also(buffer::get)
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        val plaintext = cipher.doFinal(ciphertext)
        try {
          PairingCredentialCodec.decode(plaintext)
        } finally {
          plaintext.fill(0)
          ciphertext.fill(0)
        }
      }
      .getOrElse {
        clear()
        null
      }
  }

  fun clear() {
    preferences.edit { remove(KEY_CREDENTIAL_ENVELOPE) }
  }

  fun saveRelay(credentials: RelayCredentials) {
    val plaintext = RelayCredentialCodec.encode(credentials)
    try {
      val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
      cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey(RELAY_KEY_ALIAS))
      val ciphertext = cipher.doFinal(plaintext)
      val envelope =
        ByteBuffer.allocate(1 + 1 + cipher.iv.size + ciphertext.size)
          .put(RELAY_ENVELOPE_VERSION)
          .put(cipher.iv.size.toByte())
          .put(cipher.iv)
          .put(ciphertext)
          .array()
      preferences.edit {
        putString(KEY_RELAY_CREDENTIAL_ENVELOPE, Base64.encodeToString(envelope, Base64.NO_WRAP))
        remove(KEY_RELAY_LAST_SEQUENCE)
        remove(KEY_RELAY_LAST_MESSAGE_ID)
      }
    } finally {
      plaintext.fill(0)
    }
  }

  fun loadRelay(): RelayCredentials? {
    val encoded = preferences.getString(KEY_RELAY_CREDENTIAL_ENVELOPE, null) ?: return null
    return runCatching {
        val envelope = Base64.decode(encoded, Base64.NO_WRAP)
        require(envelope.size > MIN_ENVELOPE_BYTES) { "invalid encrypted relay credentials" }
        val buffer = ByteBuffer.wrap(envelope)
        require(buffer.get() == RELAY_ENVELOPE_VERSION) { "unsupported relay credential envelope" }
        val ivSize = buffer.get().toInt() and 0xff
        require(ivSize in 12..16 && buffer.remaining() > ivSize) { "invalid relay credential IV" }
        val iv = ByteArray(ivSize).also(buffer::get)
        val ciphertext = ByteArray(buffer.remaining()).also(buffer::get)
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(RELAY_KEY_ALIAS), GCMParameterSpec(GCM_TAG_BITS, iv))
        val plaintext = cipher.doFinal(ciphertext)
        try {
          RelayCredentialCodec.decode(plaintext)
        } finally {
          plaintext.fill(0)
          ciphertext.fill(0)
        }
      }
      .getOrElse {
        clearRelay()
        null
      }
  }

  @Synchronized
  override fun acceptRelaySequence(sequence: Long): Boolean {
    require(sequence > 0) { "invalid relay sequence" }
    val previous = preferences.getLong(KEY_RELAY_LAST_SEQUENCE, 0L)
    if (sequence <= previous) return false
    preferences.edit(commit = true) { putLong(KEY_RELAY_LAST_SEQUENCE, sequence) }
    return true
  }

  override fun relayLastSequence(): Long = preferences.getLong(KEY_RELAY_LAST_SEQUENCE, 0L)

  override fun saveRelayMessageId(messageId: String) {
    require(messageId.length in 1..64 && messageId.all { it.isLetterOrDigit() || it == '-' || it == '_' }) {
      "invalid relay cursor"
    }
    preferences.edit { putString(KEY_RELAY_LAST_MESSAGE_ID, messageId) }
  }

  override fun relayLastMessageId(): String? = preferences.getString(KEY_RELAY_LAST_MESSAGE_ID, null)

  fun clearRelay() {
    preferences.edit {
      remove(KEY_RELAY_CREDENTIAL_ENVELOPE)
      remove(KEY_RELAY_LAST_SEQUENCE)
      remove(KEY_RELAY_LAST_MESSAGE_ID)
    }
  }

  fun clearRelayAndKey() {
    clearRelay()
    val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
    if (keyStore.containsAlias(RELAY_KEY_ALIAS)) {
      keyStore.deleteEntry(RELAY_KEY_ALIAS)
    }
  }

  private fun getOrCreateKey(alias: String = KEY_ALIAS): SecretKey {
    val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
    (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
    val generator =
      KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
    generator.init(
      KeyGenParameterSpec.Builder(
          alias,
          KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        .setKeySize(256)
        .setRandomizedEncryptionRequired(true)
        .setUserAuthenticationRequired(false)
        .build(),
    )
    return generator.generateKey()
  }

  private companion object {
    const val PREFERENCES_NAME = "pairing-credentials"
    const val KEY_CREDENTIAL_ENVELOPE = "encrypted-envelope"
    const val ANDROID_KEY_STORE = "AndroidKeyStore"
    const val KEY_ALIAS = "codex-quota-pairing-v1"
    const val RELAY_KEY_ALIAS = "codex-quota-relay-pairing-v1"
    const val KEY_RELAY_CREDENTIAL_ENVELOPE = "relay-encrypted-envelope-v1"
    const val KEY_RELAY_LAST_SEQUENCE = "relay-last-sequence-v1"
    const val KEY_RELAY_LAST_MESSAGE_ID = "relay-last-message-id-v1"
    const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
    const val GCM_TAG_BITS = 128
    const val MIN_ENVELOPE_BYTES = 1 + 1 + 12 + 16
    const val ENVELOPE_VERSION: Byte = 1
    const val RELAY_ENVELOPE_VERSION: Byte = 1
  }
}

private fun normalizeSha256Hex(value: String): String {
  require(value.length == 64 && value.all(Char::isCredentialHexDigit)) {
    "invalid 256-bit credential"
  }
  return value.lowercase()
}

private fun hexToBytes(value: String): ByteArray =
  ByteArray(value.length / 2) { index ->
    value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
  }

private fun ByteArray.toHex(): String =
  joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

private fun Char.isCredentialHexDigit(): Boolean =
  this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

class RelayCredentials private constructor(
  val baseUrl: String,
  private val topicBytes: ByteArray,
  private val keyBytes: ByteArray,
  private val deviceIdBytes: ByteArray,
) {
  val topic: ByteArray get() = topicBytes.copyOf()
  val key: ByteArray get() = keyBytes.copyOf()
  val deviceId: ByteArray get() = deviceIdBytes.copyOf()

  fun topicName(): String =
    java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(topicBytes)

  internal fun topicUnsafe(): ByteArray = topicBytes
  internal fun keyUnsafe(): ByteArray = keyBytes
  internal fun deviceIdUnsafe(): ByteArray = deviceIdBytes

  internal fun destroy() {
    topicBytes.fill(0)
    keyBytes.fill(0)
    deviceIdBytes.fill(0)
  }

  companion object {
    fun create(baseUrl: String, topic: ByteArray, key: ByteArray, deviceId: ByteArray): RelayCredentials {
      val uri = runCatching { URI(baseUrl) }.getOrElse { throw IllegalArgumentException("invalid relay URL", it) }
      require(
        uri.scheme == "https" &&
          uri.host?.isNotBlank() == true &&
          uri.rawUserInfo == null &&
          uri.rawQuery == null &&
          uri.rawFragment == null &&
          (uri.rawPath.isNullOrEmpty() || uri.rawPath == "/"),
      ) { "invalid relay URL" }
      require(topic.size == 32) { "invalid relay topic" }
      require(key.size == 32) { "invalid relay key" }
      require(deviceId.size == 16) { "invalid relay device id" }
      return RelayCredentials(
        baseUrl = baseUrl.trimEnd('/'),
        topicBytes = topic.copyOf(),
        keyBytes = key.copyOf(),
        deviceIdBytes = deviceId.copyOf(),
      )
    }
  }
}

internal object RelayCredentialCodec {
  fun encode(credentials: RelayCredentials): ByteArray {
    val baseUrl = credentials.baseUrl.toByteArray(Charsets.UTF_8)
    require(baseUrl.size in 1..MAX_BASE_URL_BYTES) { "invalid relay URL length" }
    return ByteBuffer.allocate(1 + 2 + baseUrl.size + 32 + 32 + 16)
      .put(VERSION)
      .putShort(baseUrl.size.toShort())
      .put(baseUrl)
      .put(credentials.topicUnsafe())
      .put(credentials.keyUnsafe())
      .put(credentials.deviceIdUnsafe())
      .array()
  }

  fun decode(payload: ByteArray): RelayCredentials {
    require(payload.size >= MIN_BYTES) { "invalid relay credential payload" }
    val buffer = ByteBuffer.wrap(payload)
    require(buffer.get() == VERSION) { "unsupported relay credential version" }
    val baseUrlSize = buffer.short.toInt() and 0xffff
    require(baseUrlSize in 1..MAX_BASE_URL_BYTES && buffer.remaining() == baseUrlSize + 80) {
      "invalid relay credential payload"
    }
    val baseUrl = ByteArray(baseUrlSize).also(buffer::get).toString(Charsets.UTF_8)
    val topic = ByteArray(32).also(buffer::get)
    val key = ByteArray(32).also(buffer::get)
    val deviceId = ByteArray(16).also(buffer::get)
    return RelayCredentials.create(baseUrl, topic, key, deviceId)
  }

  private const val MAX_BASE_URL_BYTES = 512
  private const val MIN_BYTES = 1 + 2 + 1 + 80
  private const val VERSION: Byte = 1
}
