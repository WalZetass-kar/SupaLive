package com.example.security

import android.util.Base64
import org.json.JSONObject
import java.nio.charset.StandardCharsets

sealed class KeyValidationResult {
  data class Valid(val key: String, val maskedPreview: String, val isLegacyAnon: Boolean) :
    KeyValidationResult()
  data class Invalid(val reason: String) : KeyValidationResult()
}

/**
 * Validates Supabase client credentials:
 * - Accepts `sb_publishable_...` keys and legacy `anon` JWT keys
 * - Strictly rejects `sb_secret_...`, `service_role` JWTs, and PostgreSQL connection strings
 */
object PublishableKeyValidator {

  fun validate(rawKey: String): KeyValidationResult {
    val trimmed = rawKey.trim()
    if (trimmed.isEmpty()) {
      return KeyValidationResult.Invalid("Publishable key is required.")
    }
    if (trimmed.length < 20 || trimmed.length > 1024) {
      return KeyValidationResult.Invalid("Key length is invalid.")
    }
    if (trimmed.any { it.isWhitespace() }) {
      return KeyValidationResult.Invalid("Key must not contain whitespace.")
    }

    val lower = trimmed.lowercase()
    if (lower.startsWith("sb_secret_") ||
      lower.contains("service_role") ||
      lower.startsWith("postgres://") ||
      lower.startsWith("postgresql://")
    ) {
      return KeyValidationResult.Invalid(
        "Secret keys, service_role tokens, and database connection strings are prohibited. Use only a Publishable Key."
      )
    }

    if (trimmed.startsWith("sb_publishable_")) {
      if (trimmed.length < 28) {
        return KeyValidationResult.Invalid("Publishable key appears incomplete.")
      }
      return KeyValidationResult.Valid(
        key = trimmed,
        maskedPreview = maskKey(trimmed),
        isLegacyAnon = false,
      )
    }

    // Legacy Supabase anon JWT validation (check payload role != service_role)
    val parts = trimmed.split('.')
    if (parts.size == 3 && trimmed.startsWith("eyJ")) {
      val role = extractJwtRole(parts[1])
      if (role == "service_role") {
        return KeyValidationResult.Invalid(
          "service_role JWT keys are strictly prohibited. Use the public anon or sb_publishable key."
        )
      }
      if (role == "anon") {
        return KeyValidationResult.Valid(
          key = trimmed,
          maskedPreview = maskKey(trimmed),
          isLegacyAnon = true,
        )
      }
      return KeyValidationResult.Invalid("JWT key must have role \"anon\".")
    }

    return KeyValidationResult.Invalid(
      "Unsupported key format. Provide a Supabase Publishable Key (sb_publishable_...) or legacy anon key."
    )
  }

  fun maskKey(key: String): String {
    val trimmed = key.trim()
    if (trimmed.length <= 10) return "••••••••"
    val prefix =
      when {
        trimmed.startsWith("sb_publishable_") -> "sb_publishable_"
        else -> trimmed.take(8)
      }
    val suffix = trimmed.takeLast(4)
    return "${prefix}••••$suffix"
  }

  private fun extractJwtRole(payloadB64Url: String): String? {
    return try {
      val decoded =
        try {
          Base64.decode(payloadB64Url, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        } catch (_: Throwable) {
          decodeBase64UrlCompat(payloadB64Url)
        }
      val json = JSONObject(String(decoded, StandardCharsets.UTF_8))
      json.optString("role", "").ifEmpty { null }
    } catch (_: Exception) {
      null
    }
  }

  private fun decodeBase64UrlCompat(input: String): ByteArray {
    val clean = input.trimEnd('=')
    val out = ByteArray((clean.length * 3) / 4)
    var buffer = 0
    var bitsLeft = 0
    var outIndex = 0
    for (ch in clean) {
      val value =
        when (ch) {
          in 'A'..'Z' -> ch - 'A'
          in 'a'..'z' -> ch - 'a' + 26
          in '0'..'9' -> ch - '0' + 52
          '-', '+' -> 62
          '_', '/' -> 63
          else -> throw IllegalArgumentException("Invalid Base64URL character")
        }
      buffer = (buffer shl 6) or value
      bitsLeft += 6
      if (bitsLeft >= 8) {
        bitsLeft -= 8
        out[outIndex++] = ((buffer shr bitsLeft) and 0xFF).toByte()
      }
    }
    return if (outIndex == out.size) out else out.copyOf(outIndex)
  }
}
