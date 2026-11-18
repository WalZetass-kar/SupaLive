package com.example.security

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI

data class ValidatedSupabaseUrl(
  val normalizedUrl: String,
  val projectRef: String,
  val host: String,
)

sealed class UrlValidationResult {
  data class Valid(val url: ValidatedSupabaseUrl) : UrlValidationResult()
  data class Invalid(val reason: String) : UrlValidationResult()
}

/**
 * Enforces strict SSRF protections for Supabase Project URLs:
 * - HTTPS scheme only
 * - No userinfo, explicit ports, paths, query params, or fragments
 * - Host must match official Supabase Cloud `^[a-z0-9-]+\.supabase\.co$`
 * - DNS resolution check rejects loopback, link-local, site-local, multicast, CGNAT, and IPv6 ULA
 */
object SupabaseUrlValidator {
  private val SUPABASE_HOST_REGEX = Regex("^([a-z0-9-]{3,64})\\.supabase\\.co$")
  private val IPV4_LITERAL_REGEX = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")

  fun validateFormat(rawUrl: String): UrlValidationResult {
    val trimmed = rawUrl.trim().trimEnd('/')
    if (trimmed.isEmpty()) {
      return UrlValidationResult.Invalid("Project URL is required.")
    }
    if (trimmed.length > 256) {
      return UrlValidationResult.Invalid("Project URL is too long.")
    }
    val uri =
      try {
        URI(trimmed)
      } catch (_: Exception) {
        return UrlValidationResult.Invalid("Invalid URL syntax.")
      }

    if (!uri.scheme.equals("https", ignoreCase = true)) {
      return UrlValidationResult.Invalid("Only HTTPS Supabase URLs are permitted.")
    }
    if (!uri.userInfo.isNullOrEmpty()) {
      return UrlValidationResult.Invalid("URL credentials are not allowed.")
    }
    if (uri.port != -1) {
      return UrlValidationResult.Invalid("Custom ports are not allowed.")
    }
    if (!uri.rawQuery.isNullOrEmpty() || !uri.rawFragment.isNullOrEmpty()) {
      return UrlValidationResult.Invalid("URL must not contain query parameters or fragments.")
    }
    val path = uri.path ?: ""
    if (path.isNotEmpty() && path != "/") {
      return UrlValidationResult.Invalid("Enter only the base Supabase Project URL (e.g. https://xyz.supabase.co).")
    }

    val host = uri.host?.lowercase()
      ?: return UrlValidationResult.Invalid("Missing hostname in URL.")

    if (host == "localhost" || host.endsWith(".local") || host.endsWith(".internal") ||
      IPV4_LITERAL_REGEX.matches(host) || host.contains(":")
    ) {
      return UrlValidationResult.Invalid("Localhost and IP-literal hosts are blocked.")
    }

    val match = SUPABASE_HOST_REGEX.matchEntire(host)
      ?: return UrlValidationResult.Invalid("Only official *.supabase.co HTTPS domains are allowed.")

    val projectRef = match.groupValues[1]
    val normalized = "https://$host"
    return UrlValidationResult.Valid(
      ValidatedSupabaseUrl(normalizedUrl = normalized, projectRef = projectRef, host = host)
    )
  }

  /**
   * Resolves DNS and blocks private/loopback/link-local/CGNAT/ULA addresses to mitigate DNS rebinding.
   * Must be called before executing each outbound health-check HTTP request.
   */
  fun validateAndResolveDns(
    rawUrl: String,
    resolver: (String) -> Array<InetAddress> = { InetAddress.getAllByName(it) },
  ): UrlValidationResult {
    val formatResult = validateFormat(rawUrl)
    if (formatResult !is UrlValidationResult.Valid) return formatResult

    val addresses =
      try {
        resolver(formatResult.url.host)
      } catch (_: Exception) {
        return UrlValidationResult.Invalid("DNS resolution failed for ${formatResult.url.host}.")
      }

    if (addresses.isEmpty()) {
      return UrlValidationResult.Invalid("DNS returned no addresses for ${formatResult.url.host}.")
    }

    for (addr in addresses) {
      if (isBlockedAddress(addr)) {
        return UrlValidationResult.Invalid("Resolved IP address violates SSRF network policy.")
      }
    }

    return formatResult
  }

  fun isBlockedAddress(addr: InetAddress): Boolean {
    if (addr.isAnyLocalAddress ||
      addr.isLoopbackAddress ||
      addr.isLinkLocalAddress ||
      addr.isSiteLocalAddress ||
      addr.isMulticastAddress
    ) {
      return true
    }

    val bytes = addr.address
    if (addr is Inet4Address && bytes.size == 4) {
      val b0 = bytes[0].toInt() and 0xFF
      val b1 = bytes[1].toInt() and 0xFF
      // 100.64.0.0/10 CGNAT
      if (b0 == 100 && b1 in 64..127) return true
      // 0.0.0.0/8, 127.0.0.0/8, 10.0.0.0/8, 169.254.0.0/16, 172.16.0.0/12, 192.168.0.0/16
      if (b0 == 0 || b0 == 127 || b0 == 10) return true
      if (b0 == 169 && b1 == 254) return true
      if (b0 == 172 && b1 in 16..31) return true
      if (b0 == 192 && b1 == 168) return true
      // 198.18.0.0/15 benchmark
      if (b0 == 198 && (b1 == 18 || b1 == 19)) return true
    } else if (addr is Inet6Address && bytes.size == 16) {
      val b0 = bytes[0].toInt() and 0xFF
      // fc00::/7 Unique Local Address
      if ((b0 and 0xFE) == 0xFC) return true
    }
    return false
  }
}
