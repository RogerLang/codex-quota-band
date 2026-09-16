package com.codex.quota.android.validation

import java.security.SecureRandom
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal data class Stage02Message(val type: String, val nonce: String) {
  companion object {
    const val PING = "stage02_ping"
    const val PONG = "stage02_pong"
    const val BAND_PING = "stage02_band_ping"
    const val ANDROID_PONG = "stage02_android_pong"
    private val types = setOf(PING, PONG, BAND_PING, ANDROID_PONG)
    private val noncePattern = Regex("[0-9a-f]{32}")
    private val random = SecureRandom()

    fun randomNonce(): String = random.generateSeed(16).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    fun encode(type: String, nonce: String): ByteArray {
      require(type in types && noncePattern.matches(nonce))
      return buildJsonObject { put("type", type); put("nonce", nonce) }.toString().toByteArray(Charsets.UTF_8)
    }

    fun parse(bytes: ByteArray): Stage02Message? = runCatching {
      if (bytes.size > 256) return null
      val obj = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
      if (obj.keys != setOf("type", "nonce")) return null
      val type = (obj["type"] as? JsonPrimitive)?.content ?: return null
      val nonce = (obj["nonce"] as? JsonPrimitive)?.content ?: return null
      if (type !in types || !noncePattern.matches(nonce)) return null
      Stage02Message(type, nonce)
    }.getOrNull()

    fun parseExpected(bytes: ByteArray, type: String, nonce: String): Stage02Message? =
      parse(bytes)?.takeIf { it.type == type && it.nonce == nonce }
  }
}

internal class Stage02ReplyGate(private val type: String, private val nonce: String, private val deadlineMs: Long) {
  enum class Result { Waiting, Matched, TimedOut, WrongType, WrongNonce }

  fun check(message: Stage02Message?, nowMs: Long): Result = when {
    nowMs >= deadlineMs -> Result.TimedOut
    message == null -> Result.Waiting
    message.type != type -> Result.WrongType
    message.nonce != nonce -> Result.WrongNonce
    else -> Result.Matched
  }
}
