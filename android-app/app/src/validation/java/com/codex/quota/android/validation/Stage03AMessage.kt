package com.codex.quota.android.validation

import java.security.SecureRandom
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal data class Stage03AMessage(
  val type: String,
  val sequence: Int,
  val nonce: String,
  val persisted: Boolean? = null,
) {
  companion object {
    const val VERSION = 1
    const val STATE = "stage03_state"
    const val ACK = "stage03_state_ack"
    private val noncePattern = Regex("[0-9a-f]{32}")
    private val random = SecureRandom()

    fun randomNonce(): String = ByteArray(16).also(random::nextBytes)
      .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    fun encodeState(sequence: Int, nonce: String): ByteArray {
      require(sequence in 0..999_999 && noncePattern.matches(nonce))
      return buildJsonObject {
        put("type", STATE)
        put("version", VERSION)
        put("sequence", sequence)
        put("nonce", nonce)
      }.toString().toByteArray(Charsets.UTF_8)
    }

    fun parse(bytes: ByteArray): Stage03AMessage? = runCatching {
      if (bytes.size > 384) return null
      val obj = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
      val type = (obj["type"] as? JsonPrimitive)?.content ?: return null
      val version = obj["version"]?.jsonPrimitive?.intOrNull ?: return null
      val sequence = obj["sequence"]?.jsonPrimitive?.intOrNull ?: return null
      val nonce = (obj["nonce"] as? JsonPrimitive)?.content ?: return null
      if (version != VERSION || sequence !in 0..999_999 || !noncePattern.matches(nonce)) return null
      when (type) {
        STATE -> {
          if (obj.keys != setOf("type", "version", "sequence", "nonce")) return null
          Stage03AMessage(type, sequence, nonce)
        }
        ACK -> {
          if (obj.keys != setOf("type", "version", "sequence", "nonce", "persisted")) return null
          val persisted = obj["persisted"]?.jsonPrimitive?.booleanOrNull ?: return null
          Stage03AMessage(type, sequence, nonce, persisted)
        }
        else -> null
      }
    }.getOrNull()
  }
}
