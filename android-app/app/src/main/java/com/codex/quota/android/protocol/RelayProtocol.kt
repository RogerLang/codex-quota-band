package com.codex.quota.android.protocol

import android.net.Uri
import com.codex.quota.android.security.RelayCredentials
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

data class RelayEnvelope(
  val version: Int,
  val nonce: String,
  val ciphertext: String,
) {
  fun nonceBytes(): ByteArray = decodeBase64Url(nonce).also { require(it.size == NONCE_BYTES) }
}

object RelayCipher {
  private val json = Json { ignoreUnknownKeys = false }

  fun decodeEnvelope(payload: String): RelayEnvelope {
    require(payload.toByteArray().size <= MAX_ENVELOPE_BYTES) { "relay envelope is too large" }
    val wire = json.decodeFromString<RelayEnvelopeWire>(payload)
    require(wire.version == RELAY_PROTOCOL_VERSION) { "unsupported relay envelope" }
    return RelayEnvelope(wire.version, wire.nonce, wire.ciphertext).also {
      it.nonceBytes()
      require(decodeBase64Url(it.ciphertext).size >= GCM_TAG_BYTES) { "invalid relay ciphertext" }
    }
  }

  fun encodeEnvelope(envelope: RelayEnvelope): String =
    json.encodeToString(RelayEnvelopeWire(envelope.version, envelope.nonce, envelope.ciphertext))

  fun decrypt(credentials: RelayCredentials, envelope: RelayEnvelope): String {
    require(envelope.version == RELAY_PROTOCOL_VERSION) { "unsupported relay envelope" }
    val ciphertext = decodeBase64Url(envelope.ciphertext)
    try {
      val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
      cipher.init(
        Cipher.DECRYPT_MODE,
        SecretKeySpec(credentials.key, "AES"),
        GCMParameterSpec(GCM_TAG_BITS, envelope.nonceBytes()),
      )
      cipher.updateAAD(aad(credentials))
      val plaintext = cipher.doFinal(ciphertext)
      return try {
        plaintext.toString(Charsets.UTF_8)
      } finally {
        plaintext.fill(0)
      }
    } finally {
      ciphertext.fill(0)
    }
  }

  fun encrypt(credentials: RelayCredentials, plaintext: String): RelayEnvelope {
    val nonce = ByteArray(NONCE_BYTES).also(SecureRandom()::nextBytes)
    val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
    cipher.init(
      Cipher.ENCRYPT_MODE,
      SecretKeySpec(credentials.key, "AES"),
      GCMParameterSpec(GCM_TAG_BITS, nonce),
    )
    cipher.updateAAD(aad(credentials))
    val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
    return RelayEnvelope(
      version = RELAY_PROTOCOL_VERSION,
      nonce = encodeBase64Url(nonce),
      ciphertext = encodeBase64Url(ciphertext),
    )
  }


  internal fun encryptForTest(credentials: RelayCredentials, plaintext: String): RelayEnvelope =
    encrypt(credentials, plaintext)

  private fun aad(credentials: RelayCredentials): ByteArray = RELAY_AAD_MAGIC + credentials.topic

  private const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
  private const val GCM_TAG_BITS = 128
  private const val GCM_TAG_BYTES = 16
  private const val MAX_ENVELOPE_BYTES = 64 * 1024
}

@Serializable
private data class RelayEnvelopeWire(
  val version: Int,
  val nonce: String,
  val ciphertext: String,
)

data class RelaySnapshot(
  val sequence: Long,
  val generatedAtMs: Long,
  val quota: QuotaSnapshot,
  val tasks: TaskSnapshot,
)

object RelayPayloadWireContract {
  private val json = Json { ignoreUnknownKeys = false }

  fun decode(payload: String): RelaySnapshot {
    require(payload.toByteArray().size <= MAX_PLAINTEXT_BYTES) { "relay payload is too large" }
    val root = json.parseToJsonElement(payload).jsonObject
    require(root.keys == EXPECTED_KEYS) { "invalid relay payload fields" }
    require(root.getValue("protocolVersion").jsonPrimitive.int == RELAY_PROTOCOL_VERSION) {
      "unsupported relay payload"
    }
    val sequence = root.getValue("sequence").jsonPrimitive.long
    val generatedAtMs = root.getValue("generatedAtMs").jsonPrimitive.long
    require(sequence > 0 && generatedAtMs >= 0) { "invalid relay metadata" }
    val quota = QuotaWireContract.decode(root.getValue("quota").toString(), 3)
    val taskPayload =
      buildJsonObject {
        put("protocolVersion", 1)
        put("sequence", sequence)
        put("generatedAtMs", generatedAtMs)
        put("chatGptState", root.getValue("chatGptState"))
        put("chatGptFocused", root.getValue("chatGptFocused"))
        put("tasks", root.getValue("tasks"))
      }
    val tasks = TaskWireContract.decode(taskPayload.toString())
    return RelaySnapshot(sequence, generatedAtMs, quota, tasks)
  }

  fun encode(snapshot: RelaySnapshot): String {
    require(snapshot.tasks.sequence == snapshot.sequence) { "relay/task sequence mismatch" }
    require(snapshot.tasks.generatedAtMs == snapshot.generatedAtMs) { "relay/task time mismatch" }
    val taskRoot = json.parseToJsonElement(TaskWireContract.encode(snapshot.tasks)).jsonObject
    return buildJsonObject {
      put("protocolVersion", RELAY_PROTOCOL_VERSION)
      put("sequence", snapshot.sequence)
      put("generatedAtMs", snapshot.generatedAtMs)
      put("quota", json.parseToJsonElement(QuotaWireContract.encodeV3(snapshot.quota)))
      put("tasks", taskRoot.getValue("tasks"))
      put("chatGptState", taskRoot.getValue("chatGptState"))
      put("chatGptFocused", taskRoot.getValue("chatGptFocused"))
    }.toString()
  }

  private val EXPECTED_KEYS =
    setOf(
      "protocolVersion",
      "sequence",
      "generatedAtMs",
      "quota",
      "tasks",
      "chatGptState",
      "chatGptFocused",
    )
  private const val MAX_PLAINTEXT_BYTES = 64 * 1024
}

enum class NtfyEventKind {
  Open,
  Keepalive,
  Message,
  Other,
}

data class NtfyEvent(
  val id: String,
  val topic: String,
  val kind: NtfyEventKind,
  val message: String?,
)

object NtfyEventParser {
  private val json = Json { ignoreUnknownKeys = true }

  fun parse(payload: String): NtfyEvent {
    require(payload.toByteArray().size <= MAX_NTFY_EVENT_BYTES) { "ntfy event is too large" }
    val root = json.parseToJsonElement(payload).jsonObject
    val id = root["id"]?.jsonPrimitive?.contentOrNull.orEmpty()
    val topic = root["topic"]?.jsonPrimitive?.contentOrNull.orEmpty()
    require(id.length in 1..64 && topic.length in 1..128) { "invalid ntfy event identity" }
    val kind =
      when (root["event"]?.jsonPrimitive?.contentOrNull) {
        "open" -> NtfyEventKind.Open
        "keepalive" -> NtfyEventKind.Keepalive
        "message" -> NtfyEventKind.Message
        else -> NtfyEventKind.Other
      }
    val message = root["message"]?.jsonPrimitive?.contentOrNull
    if (kind == NtfyEventKind.Message) require(message != null) { "missing ntfy message" }
    return NtfyEvent(id, topic, kind, message)
  }

  private const val MAX_NTFY_EVENT_BYTES = 96 * 1024
}

enum class RelaySequenceDecision {
  Accepted,
  RejectedReplay,
}

class RelayReplayGuard(
  loadLast: () -> Long,
  private val saveLast: (Long) -> Unit,
) {
  private var lastSequence = loadLast()

  @Synchronized
  fun accept(sequence: Long): RelaySequenceDecision {
    require(sequence > 0) { "invalid relay sequence" }
    if (sequence <= lastSequence) return RelaySequenceDecision.RejectedReplay
    saveLast(sequence)
    lastSequence = sequence
    return RelaySequenceDecision.Accepted
  }
}

object RelayPairingDeepLinkContract {
  fun decode(uri: Uri): RelayCredentials {
    require(uri.scheme == "codexquota" && uri.host == "pair") { "invalid pairing link" }
    val encoded = requireNotNull(uri.getQueryParameter("relay")) { "missing relay pairing payload" }
    val payload = decodeBase64Url(encoded)
    require(payload.size <= MAX_PAIRING_BYTES) { "relay pairing payload is too large" }
    return RelayPairingWireContract.decode(payload.toString(Charsets.UTF_8))
  }
}

object RelayPairingWireContract {
  private val json = Json { ignoreUnknownKeys = false }

  fun decode(payload: String): RelayCredentials {
    val wire = json.decodeFromString<RelayPairingWire>(payload)
    require(wire.protocolVersion == RELAY_PROTOCOL_VERSION && wire.type == RelayPairingType.RelayPairing) {
      "unsupported relay pairing payload"
    }
    return RelayCredentials.create(
      baseUrl = wire.relayBaseUrl,
      topic = decodeSized(wire.topic, 32),
      key = decodeSized(wire.key, 32),
      deviceId = decodeSized(wire.deviceId, 16),
    )
  }
}

@Serializable
private data class RelayPairingWire(
  val protocolVersion: Int,
  val type: RelayPairingType,
  val relayBaseUrl: String,
  val topic: String,
  val key: String,
  val deviceId: String,
)

@Serializable
private enum class RelayPairingType {
  @SerialName("relay_pairing") RelayPairing,
}

private fun decodeSized(value: String, size: Int): ByteArray =
  decodeBase64Url(value).also { require(it.size == size) { "invalid relay pairing material" } }

private fun encodeBase64Url(value: ByteArray): String =
  Base64.getUrlEncoder().withoutPadding().encodeToString(value)

private fun decodeBase64Url(value: String): ByteArray =
  runCatching { Base64.getUrlDecoder().decode(value) }
    .getOrElse { throw IllegalArgumentException("invalid base64url", it) }

private const val RELAY_PROTOCOL_VERSION = 1
private const val NONCE_BYTES = 12
private const val MAX_PAIRING_BYTES = 4 * 1024
private val RELAY_AAD_MAGIC = "CQ-RELAY-V1\u0000".toByteArray(Charsets.US_ASCII)
