package com.example.data.model

enum class HealthSeverity(val label: String) {
  HEALTHY("Healthy"),
  WARNING("Warning"),
  CRITICAL("Critical"),
  NEUTRAL("Inactive"),
}

enum class HealthStatus(
  val firestoreValue: String,
  val displayLabel: String,
  val summary: String,
  val severity: HealthSeverity,
) {
  PENDING(
    firestoreValue = "pending",
    displayLabel = "Pending",
    summary = "Waiting for first check.",
    severity = HealthSeverity.NEUTRAL,
  ),
  HEALTHY(
    firestoreValue = "healthy",
    displayLabel = "Healthy",
    summary = "REST endpoint responded HTTP 200.",
    severity = HealthSeverity.HEALTHY,
  ),
  WARNING(
    firestoreValue = "warning",
    displayLabel = "Warning",
    summary = "Responded with elevated latency or retries.",
    severity = HealthSeverity.WARNING,
  ),
  UNREACHABLE(
    firestoreValue = "unreachable",
    displayLabel = "Unreachable",
    summary = "Network timeout or server error.",
    severity = HealthSeverity.CRITICAL,
  ),
  CREDENTIAL_ERROR(
    firestoreValue = "credential_error",
    displayLabel = "Credential Error",
    summary = "HTTP 401/403 — check key or RLS policy.",
    severity = HealthSeverity.CRITICAL,
  ),
  CONFIGURATION_ERROR(
    firestoreValue = "configuration_error",
    displayLabel = "Config Error",
    summary = "Table supalive_health or row id=1 missing.",
    severity = HealthSeverity.CRITICAL,
  ),
  SUSPECTED_PAUSED(
    firestoreValue = "suspected_paused",
    displayLabel = "Suspected Paused",
    summary = "Repeatedly unreachable. Check Supabase Dashboard.",
    severity = HealthSeverity.CRITICAL,
  ),
  MONITORING_DISABLED(
    firestoreValue = "monitoring_disabled",
    displayLabel = "Paused (Off)",
    summary = "12h monitoring is turned off.",
    severity = HealthSeverity.NEUTRAL,
  );

  companion object {
    fun fromFirestore(raw: String?): HealthStatus =
      entries.firstOrNull { it.firestoreValue == raw } ?: PENDING
  }
}
