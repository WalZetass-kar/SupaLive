package com.example.data.model

import androidx.annotation.Keep
import androidx.compose.runtime.Immutable
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp

@Immutable
@Keep
@IgnoreExtraProperties
data class UserProfile(
  @DocumentId val id: String = "",
  val userId: String = "",
  val email: String = "",
  val fullName: String = "",
  val emailAlertsEnabled: Boolean = false,
  val inAppAlertsEnabled: Boolean = true,
  val failureThreshold: Long = 2L,
  @ServerTimestamp val createdAt: Timestamp? = null,
  @ServerTimestamp val updatedAt: Timestamp? = null,
)

@Immutable
@Keep
@IgnoreExtraProperties
data class SupabaseProject(
  @DocumentId val id: String = "",
  val userId: String = "",
  val projectName: String = "",
  val projectUrl: String = "",
  val projectRef: String = "",
  val maskedKey: String = "",
  val publishableKeyEncrypted: String = "",
  val publishableKeyServerEncrypted: String = "",
  val keyVersion: Long = 1L,
  val healthStatus: String = HealthStatus.PENDING.firestoreValue,
  val monitoringEnabled: Boolean = true,
  val intervalHours: Long = 12L,
  val phaseOffsetMinutes: Long = 0L,
  val nextCheckEpochMs: Long = 0L,
  val lastCheckedEpochMs: Long = 0L,
  val lastSuccessEpochMs: Long = 0L,
  val lastLatencyMs: Long = 0L,
  val consecutiveFailures: Long = 0L,
  val lastSanitizedError: String = "",
  @ServerTimestamp val createdAt: Timestamp? = null,
  @ServerTimestamp val updatedAt: Timestamp? = null,
) {
  val statusEnum: HealthStatus
    get() = HealthStatus.fromFirestore(healthStatus)
}

@Immutable
@Keep
@IgnoreExtraProperties
data class MonitoringLog(
  @DocumentId val id: String = "",
  val userId: String = "",
  val projectId: String = "",
  val projectName: String = "",
  val checkType: String = "manual",
  val result: String = "success",
  val resultingStatus: String = HealthStatus.HEALTHY.firestoreValue,
  val httpStatus: Long = 200L,
  val responseTimeMs: Long = 0L,
  val errorCode: String = "",
  val errorMessage: String = "",
  val retryCount: Long = 0L,
  val checkedAtEpochMs: Long = 0L,
  @ServerTimestamp val createdAt: Timestamp? = null,
) {
  val statusEnum: HealthStatus
    get() = HealthStatus.fromFirestore(resultingStatus)
}

@Immutable
@Keep
@IgnoreExtraProperties
data class AppNotification(
  @DocumentId val id: String = "",
  val userId: String = "",
  val projectId: String = "",
  val projectName: String = "",
  val notificationType: String = "health_alert",
  val title: String = "",
  val message: String = "",
  val readFlag: Boolean = false,
  val createdAtEpochMs: Long = 0L,
  @ServerTimestamp val createdAt: Timestamp? = null,
)

@Immutable
@Keep
@IgnoreExtraProperties
data class AuditLog(
  @DocumentId val id: String = "",
  val userId: String = "",
  val action: String = "",
  val resourceType: String = "project",
  val resourceId: String = "",
  val summary: String = "",
  val createdAtEpochMs: Long = 0L,
  @ServerTimestamp val createdAt: Timestamp? = null,
)

@Immutable
data class DashboardStats(
  val totalProjects: Int = 0,
  val healthyCount: Int = 0,
  val warningCount: Int = 0,
  val issueCount: Int = 0,
  val disabledCount: Int = 0,
  val checksToday: Int = 0,
  val successRatePercent: Int = 0,
  val averageLatencyMs: Long = 0L,
) {
  companion object {
    fun compute(
      projects: List<SupabaseProject>,
      logs: List<MonitoringLog>,
      nowEpochMs: Long = System.currentTimeMillis(),
    ): DashboardStats {
      val healthy = projects.count { it.statusEnum == HealthStatus.HEALTHY }
      val warning = projects.count { it.statusEnum == HealthStatus.WARNING }
      val disabled = projects.count { !it.monitoringEnabled || it.statusEnum == HealthStatus.MONITORING_DISABLED }
      val issues = projects.count {
        it.statusEnum in setOf(
          HealthStatus.UNREACHABLE,
          HealthStatus.CREDENTIAL_ERROR,
          HealthStatus.CONFIGURATION_ERROR,
          HealthStatus.SUSPECTED_PAUSED,
        )
      }
      val oneDayAgo = nowEpochMs - 86_400_000L
      val todayLogs = logs.filter { it.checkedAtEpochMs >= oneDayAgo }
      val successLogs = logs.count { it.result == "success" }
      val rate = if (logs.isEmpty()) 0 else ((successLogs * 100.0) / logs.size).toInt()
      val latencies = logs.filter { it.responseTimeMs > 0L }.map { it.responseTimeMs }
      val avgLatency = if (latencies.isEmpty()) 0L else latencies.sum() / latencies.size
      return DashboardStats(
        totalProjects = projects.size,
        healthyCount = healthy,
        warningCount = warning,
        issueCount = issues,
        disabledCount = disabled,
        checksToday = todayLogs.size,
        successRatePercent = rate,
        averageLatencyMs = avgLatency,
      )
    }
  }
}

object SupabaseSqlTemplates {
  const val HEALTH_TABLE_SQL = """create table if not exists public.supalive_health (
  id integer primary key,
  label text not null default 'supalive'
);

insert into public.supalive_health (id, label)
values (1, 'supalive')
on conflict (id) do nothing;

alter table public.supalive_health enable row level security;

drop policy if exists "supalive_health_read" on public.supalive_health;

create policy "supalive_health_read"
  on public.supalive_health
  for select
  to anon, authenticated
  using (id = 1);

grant usage on schema public to anon, authenticated;
grant select on public.supalive_health to anon, authenticated;"""
}
