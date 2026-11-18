package com.example.network

import com.example.data.model.HealthStatus
import com.example.security.LogSanitizer
import com.example.security.SupabaseUrlValidator
import com.example.security.UrlValidationResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.IOException
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import kotlin.random.Random

data class HealthCheckOutcome(
  val success: Boolean,
  val resultingStatus: HealthStatus,
  val httpStatus: Int,
  val responseTimeMs: Long,
  val retryCount: Int,
  val errorCode: String,
  val errorMessage: String,
)

/**
 * Performs read-only GET requests to Supabase REST Database API:
 * `GET /rest/v1/supalive_health?id=eq.1&select=id`
 *
 * Security & Reliability guarantees:
 * - Re-validates URL and DNS resolution before each request (DNS rebinding guard)
 * - Redirects disabled (`followRedirects = false`)
 * - 10s request timeout
 * - Max 2 retries (3 total attempts) with exponential backoff + jitter for transient failures only
 * - Never retries 401/403/404/configuration errors
 * - Never writes to business tables or logs API keys
 */
class SupabaseHealthClient(
  private val httpClient: OkHttpClient = SHARED_CLIENT,
  private val dnsResolver: (String) -> Array<InetAddress> = { InetAddress.getAllByName(it) },
  private val sleeper: suspend (Long) -> Unit = { delay(it) },
) {

  suspend fun checkHealth(
    projectUrl: String,
    publishableKey: String,
    previousConsecutiveFailures: Long,
  ): HealthCheckOutcome =
    withContext(Dispatchers.IO) {
      val dnsValidation = SupabaseUrlValidator.validateAndResolveDns(projectUrl, dnsResolver)
      if (dnsValidation is UrlValidationResult.Invalid) {
        return@withContext HealthCheckOutcome(
          success = false,
          resultingStatus = HealthStatus.CONFIGURATION_ERROR,
          httpStatus = 0,
          responseTimeMs = 0L,
          retryCount = 0,
          errorCode = "SSRF_OR_URL_BLOCKED",
          errorMessage = LogSanitizer.sanitize(dnsValidation.reason),
        )
      }

      val validatedUrl = (dnsValidation as UrlValidationResult.Valid).url
      val endpoint = "${validatedUrl.normalizedUrl}/rest/v1/supalive_health?id=eq.1&select=id"

      var attempt = 0
      var lastOutcome: HealthCheckOutcome? = null

      while (attempt <= MAX_RETRIES) {
        val outcome = executeSingleAttempt(endpoint, publishableKey, attempt, previousConsecutiveFailures)
        lastOutcome = outcome

        if (outcome.success || !isTransientFailure(outcome)) {
          return@withContext outcome
        }

        if (attempt < MAX_RETRIES) {
          val backoffMs = (BASE_BACKOFF_MS * (1L shl attempt)) + Random.nextLong(50L, 250L)
          sleeper(backoffMs)
        }
        attempt++
      }

      lastOutcome ?: HealthCheckOutcome(
        success = false,
        resultingStatus = HealthStatus.UNREACHABLE,
        httpStatus = 0,
        responseTimeMs = 0L,
        retryCount = MAX_RETRIES,
        errorCode = "UNKNOWN",
        errorMessage = "Health check did not produce an outcome.",
      )
    }

  private fun executeSingleAttempt(
    endpoint: String,
    publishableKey: String,
    retryCount: Int,
    previousConsecutiveFailures: Long,
  ): HealthCheckOutcome {
    val startNs = System.nanoTime()
    val request =
      Request.Builder()
        .url(endpoint)
        .get()
        .header("apikey", publishableKey)
        .header("Accept", "application/json")
        .build()

    return try {
      httpClient.newCall(request).execute().use { response ->
        val elapsedMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)
        val status = response.code
        val bodyText = readBoundedBody(response.body)

        when {
          status == 200 -> evaluate200Response(bodyText, elapsedMs, retryCount)
          status == 401 || status == 403 ->
            HealthCheckOutcome(
              success = false,
              resultingStatus = HealthStatus.CREDENTIAL_ERROR,
              httpStatus = status,
              responseTimeMs = elapsedMs,
              retryCount = retryCount,
              errorCode = "HTTP_$status",
              errorMessage = "Publishable key rejected or RLS select policy missing (HTTP $status).",
            )
          status == 404 || isMissingTableResponse(bodyText) ->
            HealthCheckOutcome(
              success = false,
              resultingStatus = HealthStatus.CONFIGURATION_ERROR,
              httpStatus = status,
              responseTimeMs = elapsedMs,
              retryCount = retryCount,
              errorCode = "MISSING_HEALTH_TABLE",
              errorMessage = "Table public.supalive_health not found or not exposed via PostgREST.",
            )
          status == 429 || status >= 500 -> {
            val nextFailures = previousConsecutiveFailures + 1L
            val resulting =
              if (nextFailures >= SUSPECTED_PAUSED_THRESHOLD) HealthStatus.SUSPECTED_PAUSED
              else HealthStatus.UNREACHABLE
            HealthCheckOutcome(
              success = false,
              resultingStatus = resulting,
              httpStatus = status,
              responseTimeMs = elapsedMs,
              retryCount = retryCount,
              errorCode = "HTTP_$status",
              errorMessage = LogSanitizer.sanitize("Supabase API returned HTTP $status."),
            )
          }
          else ->
            HealthCheckOutcome(
              success = false,
              resultingStatus = HealthStatus.CONFIGURATION_ERROR,
              httpStatus = status,
              responseTimeMs = elapsedMs,
              retryCount = retryCount,
              errorCode = "HTTP_$status",
              errorMessage = LogSanitizer.sanitize("Unexpected response HTTP $status."),
            )
        }
      }
    } catch (e: SocketTimeoutException) {
      val elapsedMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)
      val nextFailures = previousConsecutiveFailures + 1L
      val resulting =
        if (nextFailures >= SUSPECTED_PAUSED_THRESHOLD) HealthStatus.SUSPECTED_PAUSED
        else HealthStatus.UNREACHABLE
      HealthCheckOutcome(
        success = false,
        resultingStatus = resulting,
        httpStatus = 0,
        responseTimeMs = elapsedMs,
        retryCount = retryCount,
        errorCode = "TIMEOUT",
        errorMessage = "Request timed out after 10s (${LogSanitizer.sanitize(e.message)}).",
      )
    } catch (e: IOException) {
      val elapsedMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)
      val nextFailures = previousConsecutiveFailures + 1L
      val resulting =
        if (nextFailures >= SUSPECTED_PAUSED_THRESHOLD) HealthStatus.SUSPECTED_PAUSED
        else HealthStatus.UNREACHABLE
      HealthCheckOutcome(
        success = false,
        resultingStatus = resulting,
        httpStatus = 0,
        responseTimeMs = elapsedMs,
        retryCount = retryCount,
        errorCode = "NETWORK_IO",
        errorMessage = LogSanitizer.sanitize(e.message ?: "Network error reaching Supabase."),
      )
    }
  }

  private fun evaluate200Response(
    bodyText: String,
    elapsedMs: Long,
    retryCount: Int,
  ): HealthCheckOutcome {
    return try {
      val array = JSONArray(bodyText)
      var foundRow = false
      for (i in 0 until array.length()) {
        val obj = array.optJSONObject(i)
        if (obj != null && obj.optInt("id", -1) == 1) {
          foundRow = true
          break
        }
      }
      if (!foundRow) {
        return HealthCheckOutcome(
          success = false,
          resultingStatus = HealthStatus.CONFIGURATION_ERROR,
          httpStatus = 200,
          responseTimeMs = elapsedMs,
          retryCount = retryCount,
          errorCode = "MISSING_SEED_ROW",
          errorMessage = "Table public.supalive_health responded, but seed row id=1 is missing (or hidden by RLS).",
        )
      }

      val status =
        if (elapsedMs > SLOW_LATENCY_WARNING_MS || retryCount > 0) {
          HealthStatus.WARNING
        } else {
          HealthStatus.HEALTHY
        }

      HealthCheckOutcome(
        success = true,
        resultingStatus = status,
        httpStatus = 200,
        responseTimeMs = elapsedMs,
        retryCount = retryCount,
        errorCode = "",
        errorMessage = if (status == HealthStatus.WARNING) "High latency (${elapsedMs}ms) or transient retry occurred." else "",
      )
    } catch (_: Exception) {
      HealthCheckOutcome(
        success = false,
        resultingStatus = HealthStatus.CONFIGURATION_ERROR,
        httpStatus = 200,
        responseTimeMs = elapsedMs,
        retryCount = retryCount,
        errorCode = "INVALID_JSON_PAYLOAD",
        errorMessage = "Expected JSON array from /rest/v1/supalive_health.",
      )
    }
  }

  private fun isTransientFailure(outcome: HealthCheckOutcome): Boolean {
    if (outcome.errorCode == "TIMEOUT" || outcome.errorCode == "NETWORK_IO") return true
    return outcome.httpStatus == 429 || outcome.httpStatus in 500..599
  }

  private fun isMissingTableResponse(bodyText: String): Boolean {
    val lower = bodyText.lowercase()
    return lower.contains("pgrst205") ||
      lower.contains("42p01") ||
      lower.contains("does not exist") ||
      lower.contains("could not find the table")
  }

  private fun readBoundedBody(body: okhttp3.ResponseBody?): String {
    if (body == null) return ""
    return try {
      val source = body.source()
      source.request(MAX_BODY_BYTES)
      val buffer = source.buffer
      val bytesToRead = minOf(buffer.size, MAX_BODY_BYTES)
      buffer.clone().readString(bytesToRead, Charsets.UTF_8)
    } catch (_: Exception) {
      ""
    }
  }

  companion object {
    const val MAX_RETRIES = 2
    const val BASE_BACKOFF_MS = 400L
    const val SLOW_LATENCY_WARNING_MS = 2500L
    const val SUSPECTED_PAUSED_THRESHOLD = 3L
    const val MAX_BODY_CHARS = 32_768
    const val MAX_BODY_BYTES = 32_768L

    private val SHARED_CLIENT: OkHttpClient by lazy { defaultClient() }

    fun defaultClient(): OkHttpClient =
      OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .build()
  }
}
