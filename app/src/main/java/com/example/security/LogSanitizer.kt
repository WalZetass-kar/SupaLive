package com.example.security

/**
 * Strips any accidental API key, JWT, or authorization bearer material
 * before error strings are persisted to Firestore or displayed in UI.
 */
object LogSanitizer {
  private val PUBLISHABLE_KEY_REGEX = Regex("sb_(publishable|secret)_[A-Za-z0-9_\\-]+")
  private val JWT_REGEX = Regex("eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}")
  private val BEARER_REGEX = Regex("(?i)(bearer|apikey)\\s*[:=]?\\s*[A-Za-z0-9._\\-]{8,}")

  fun sanitize(raw: String?, maxLength: Int = 480): String {
    if (raw.isNullOrBlank()) return ""
    var cleaned = raw.trim()
    cleaned = PUBLISHABLE_KEY_REGEX.replace(cleaned, "[REDACTED_KEY]")
    cleaned = JWT_REGEX.replace(cleaned, "[REDACTED_JWT]")
    cleaned = BEARER_REGEX.replace(cleaned, "$1 [REDACTED]")
    return if (cleaned.length > maxLength) cleaned.take(maxLength - 3) + "..." else cleaned
  }
}
