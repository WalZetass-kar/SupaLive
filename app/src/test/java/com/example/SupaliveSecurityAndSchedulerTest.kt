package com.example

import com.example.data.model.HealthStatus
import com.example.data.model.SupabaseSqlTemplates
import com.example.network.SchedulerCalculator
import com.example.network.SupabaseHealthClient
import com.example.security.KeyProvider
import com.example.security.KeyValidationResult
import com.example.security.LogSanitizer
import com.example.security.PublishableKeyValidator
import com.example.security.SupabaseUrlValidator
import com.example.security.SupaliveKeyManager
import com.example.security.UrlValidationResult
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetAddress
import java.util.Base64
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SupaliveSecurityAndSchedulerTest {

  private class InMemoryKeyProvider : KeyProvider {
    private val key = SecretKeySpec(ByteArray(32) { (it + 7).toByte() }, "AES")
    override fun getOrCreateSecretKey(version: Int): SecretKey = key
  }

  @Test
  fun urlValidator_acceptsValidHttpsSupabaseUrl() {
    val result = SupabaseUrlValidator.validateFormat("https://xyzcompany.supabase.co/")
    assertTrue(result is UrlValidationResult.Valid)
    val valid = (result as UrlValidationResult.Valid).url
    assertEquals("https://xyzcompany.supabase.co", valid.normalizedUrl)
    assertEquals("xyzcompany", valid.projectRef)
  }

  @Test
  fun urlValidator_blocksSsrfAndNonSupabaseHosts() {
    val invalidUrls = listOf(
      "http://xyzcompany.supabase.co",
      "https://localhost",
      "https://127.0.0.1",
      "https://169.254.169.254",
      "https://evil.example.com",
      "https://user:pass@xyzcompany.supabase.co",
      "https://xyzcompany.supabase.co:8443",
      "https://xyzcompany.supabase.co/rest/v1",
    )
    for (url in invalidUrls) {
      assertTrue("Expected blocked URL: $url", SupabaseUrlValidator.validateFormat(url) is UrlValidationResult.Invalid)
    }
  }

  @Test
  fun urlValidator_blocksPrivateDnsResolution() {
    val privateResult =
      SupabaseUrlValidator.validateAndResolveDns("https://xyzcompany.supabase.co") {
        arrayOf(InetAddress.getByAddress(byteArrayOf(10, 0, 0, 5)))
      }
    assertTrue(privateResult is UrlValidationResult.Invalid)

    val cgnatResult =
      SupabaseUrlValidator.validateAndResolveDns("https://xyzcompany.supabase.co") {
        arrayOf(InetAddress.getByAddress(byteArrayOf(100, 64, 1, 2)))
      }
    assertTrue(cgnatResult is UrlValidationResult.Invalid)

    val publicResult =
      SupabaseUrlValidator.validateAndResolveDns("https://xyzcompany.supabase.co") {
        arrayOf(InetAddress.getByAddress(byteArrayOf(104, 18, 10, 20)))
      }
    assertTrue(publicResult is UrlValidationResult.Valid)
  }

  @Test
  fun keyValidator_acceptsPublishableAndAnon_rejectsSecretAndServiceRole() {
    val validPub = PublishableKeyValidator.validate("sb_publishable_1234567890abcdefghij")
    assertTrue(validPub is KeyValidationResult.Valid)
    assertTrue((validPub as KeyValidationResult.Valid).maskedPreview.startsWith("sb_publishable_••••"))

    val anonPayload = Base64.getUrlEncoder().withoutPadding().encodeToString("""{"role":"anon"}""".toByteArray())
    val anonJwt = "eyJhbGciOiJIUzI1NiJ9.$anonPayload.signaturepart12345"
    assertTrue(PublishableKeyValidator.validate(anonJwt) is KeyValidationResult.Valid)

    val servicePayload = Base64.getUrlEncoder().withoutPadding().encodeToString("""{"role":"service_role"}""".toByteArray())
    val serviceJwt = "eyJhbGciOiJIUzI1NiJ9.$servicePayload.signaturepart12345"
    assertTrue(PublishableKeyValidator.validate(serviceJwt) is KeyValidationResult.Invalid)
    assertTrue(PublishableKeyValidator.validate("sb_secret_1234567890abcdefghij") is KeyValidationResult.Invalid)
    assertTrue(PublishableKeyValidator.validate("postgresql://postgres:pass@db.supabase.co:5432/postgres") is KeyValidationResult.Invalid)
  }

  @Test
  fun keyManager_encryptsWithUniqueIvAndDecryptsRoundTrip() {
    val manager = SupaliveKeyManager(keyProvider = InMemoryKeyProvider(), currentVersion = 1)
    val secret = "sb_publishable_test_key_9876543210"
    val (cipher1, ver1) = manager.encrypt(secret)
    val (cipher2, _) = manager.encrypt(secret)

    assertEquals(1, ver1)
    assertTrue(cipher1.startsWith("v1:"))
    assertNotEquals(cipher1, cipher2)
    assertEquals(secret, manager.decrypt(cipher1).getOrThrow())
    assertEquals(secret, manager.decrypt(cipher2).getOrThrow())
  }

  @Test
  fun logSanitizer_redactsSensitiveTokens() {
    val raw = "Failed calling with apikey=sb_publishable_secret1234567890 and eyJhbGciOiJIUzI1.eyJyb2xlIjoiYW5vbiJ9.sig123456789"
    val sanitized = LogSanitizer.sanitize(raw)
    assertFalse(sanitized.contains("sb_publishable_secret"))
    assertFalse(sanitized.contains("eyJhbGciOiJIUzI1"))
  }

  @Test
  fun schedulerCalculator_advancesWithoutCumulativeDrift() {
    val anchor = 1_700_000_000_000L
    val twelveHours = SchedulerCalculator.TWELVE_HOURS_MS
    val next1 = SchedulerCalculator.advanceScheduleWithoutDrift(anchor, anchor + 5_000L)
    assertEquals(anchor + twelveHours, next1)

    // Even if delayed by 14 hours, schedule aligns to exact 24h anchor (+2 * 12h)
    val next2 = SchedulerCalculator.advanceScheduleWithoutDrift(anchor, anchor + twelveHours + 7_200_000L)
    assertEquals(anchor + (2L * twelveHours), next2)
  }

  @Test
  fun healthClient_evaluates200_401_404_andTransientRetries() = runTest {
    val publicDns: (String) -> Array<InetAddress> = {
      arrayOf(InetAddress.getByAddress(byteArrayOf(104, 18, 10, 20)))
    }

    // 1. Healthy 200
    val healthyClient =
      SupabaseHealthClient(
        httpClient = mockClient(listOf(200 to """[{"id":1}]""")),
        dnsResolver = publicDns,
        sleeper = {},
      )
    val healthyOutcome = healthyClient.checkHealth("https://xyzcompany.supabase.co", "sb_publishable_abc", 0L)
    assertTrue(healthyOutcome.success)
    assertEquals(HealthStatus.HEALTHY, healthyOutcome.resultingStatus)

    // 2. Empty table -> CONFIGURATION_ERROR, no retries
    var calls404 = 0
    val configClient =
      SupabaseHealthClient(
        httpClient = mockClient(listOf(200 to "[]")) { calls404++ },
        dnsResolver = publicDns,
        sleeper = {},
      )
    val configOutcome = configClient.checkHealth("https://xyzcompany.supabase.co", "sb_publishable_abc", 0L)
    assertFalse(configOutcome.success)
    assertEquals(HealthStatus.CONFIGURATION_ERROR, configOutcome.resultingStatus)
    assertEquals(1, calls404)

    // 3. 401 -> CREDENTIAL_ERROR, no retries
    var calls401 = 0
    val credClient =
      SupabaseHealthClient(
        httpClient = mockClient(listOf(401 to """{"message":"Invalid API key"}""")) { calls401++ },
        dnsResolver = publicDns,
        sleeper = {},
      )
    val credOutcome = credClient.checkHealth("https://xyzcompany.supabase.co", "sb_publishable_abc", 0L)
    assertFalse(credOutcome.success)
    assertEquals(HealthStatus.CREDENTIAL_ERROR, credOutcome.resultingStatus)
    assertEquals(1, calls401)

    // 4. Transient 503 then 200 -> retries and succeeds with WARNING
    var retryCalls = 0
    val retryClient =
      SupabaseHealthClient(
        httpClient = mockClient(listOf(503 to "unavailable", 200 to """[{"id":1}]""")) { retryCalls++ },
        dnsResolver = publicDns,
        sleeper = {},
      )
    val retryOutcome = retryClient.checkHealth("https://xyzcompany.supabase.co", "sb_publishable_abc", 0L)
    assertTrue(retryOutcome.success)
    assertEquals(HealthStatus.WARNING, retryOutcome.resultingStatus)
    assertEquals(2, retryCalls)
    assertEquals(1, retryOutcome.retryCount)

    // 5. Repeated failures -> SUSPECTED_PAUSED
    val pausedClient =
      SupabaseHealthClient(
        httpClient = mockClient(listOf(503 to "err", 503 to "err", 503 to "err")),
        dnsResolver = publicDns,
        sleeper = {},
      )
    val pausedOutcome = pausedClient.checkHealth("https://xyzcompany.supabase.co", "sb_publishable_abc", 2L)
    assertFalse(pausedOutcome.success)
    assertEquals(HealthStatus.SUSPECTED_PAUSED, pausedOutcome.resultingStatus)
  }

  @Test
  fun healthTableSql_isValidAndIdempotent() {
    val sql = SupabaseSqlTemplates.HEALTH_TABLE_SQL
    assertFalse(sql.isBlank())
    assertEquals(sql.count { it == '(' }, sql.count { it == ')' })
    assertFalse(sql.contains(",)"))
    assertFalse(sql.contains(",\n)"))
    assertTrue(sql.contains("create table if not exists public.supalive_health"))
    assertTrue(sql.contains("drop policy if exists \"supalive_health_read\" on public.supalive_health;"))
    assertTrue(sql.contains("create policy \"supalive_health_read\""))
    assertTrue(sql.contains("grant select on public.supalive_health to anon, authenticated;"))
  }

  private fun mockClient(
    responses: List<Pair<Int, String>>,
    onCall: () -> Unit = {},
  ): OkHttpClient {
    var index = 0
    return OkHttpClient.Builder()
      .addInterceptor { chain ->
        onCall()
        val (code, body) = responses[index.coerceAtMost(responses.lastIndex)]
        index++
        Response.Builder()
          .request(chain.request())
          .protocol(Protocol.HTTP_1_1)
          .code(code)
          .message("Mock")
          .body(body.toResponseBody("application/json".toMediaType()))
          .build()
      }
      .build()
  }
}
