package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.model.HealthSeverity
import com.example.data.model.HealthStatus
import com.example.data.model.SupabaseProject
import com.example.ui.theme.LocalIsDarkTheme
import com.example.ui.theme.StatusError
import com.example.ui.theme.StatusErrorTextDark
import com.example.ui.theme.StatusErrorTextLight
import com.example.ui.theme.StatusHealthy
import com.example.ui.theme.StatusHealthyTextDark
import com.example.ui.theme.StatusHealthyTextLight
import com.example.ui.theme.StatusInfo
import com.example.ui.theme.StatusMuted
import com.example.ui.theme.StatusWarning
import com.example.ui.theme.StatusWarningTextDark
import com.example.ui.theme.StatusWarningTextLight
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val StatusPillShape = RoundedCornerShape(999.dp)
private val ProjectCardShape = RoundedCornerShape(16.dp)
private val BannerAndPillShape = RoundedCornerShape(10.dp)

fun HealthStatus.color(): Color =
  when (severity) {
    HealthSeverity.HEALTHY -> StatusHealthy
    HealthSeverity.WARNING -> StatusWarning
    HealthSeverity.CRITICAL -> StatusError
    HealthSeverity.NEUTRAL ->
      if (this == HealthStatus.PENDING) StatusInfo else StatusMuted
  }

@Composable
fun HealthStatus.contentColor(): Color {
  val isDark = LocalIsDarkTheme.current
  return when (severity) {
    HealthSeverity.HEALTHY -> if (isDark) StatusHealthyTextDark else StatusHealthyTextLight
    HealthSeverity.WARNING -> if (isDark) StatusWarningTextDark else StatusWarningTextLight
    HealthSeverity.CRITICAL -> if (isDark) StatusErrorTextDark else StatusErrorTextLight
    HealthSeverity.NEUTRAL ->
      if (this == HealthStatus.PENDING) StatusInfo else StatusMuted
  }
}

fun HealthStatus.icon(): ImageVector =
  when (this) {
    HealthStatus.HEALTHY -> Icons.Rounded.CheckCircle
    HealthStatus.WARNING -> Icons.Rounded.Warning
    HealthStatus.UNREACHABLE,
    HealthStatus.CREDENTIAL_ERROR,
    HealthStatus.CONFIGURATION_ERROR,
    HealthStatus.SUSPECTED_PAUSED -> Icons.Rounded.Close
    HealthStatus.MONITORING_DISABLED -> Icons.Rounded.Warning
    HealthStatus.PENDING -> Icons.Rounded.Info
  }

@Composable
fun StatusIcon(
  status: HealthStatus,
  modifier: Modifier = Modifier,
  tint: Color = status.color(),
) {
  when (status.severity) {
    HealthSeverity.HEALTHY ->
      Icon(
        imageVector = Icons.Rounded.CheckCircle,
        contentDescription = null,
        tint = tint,
        modifier = modifier,
      )
    HealthSeverity.WARNING ->
      Icon(
        imageVector = Icons.Rounded.Warning,
        contentDescription = null,
        tint = tint,
        modifier = modifier,
      )
    HealthSeverity.CRITICAL ->
      Icon(
        painter = painterResource(R.drawable.ic_status_critical),
        contentDescription = null,
        tint = tint,
        modifier = modifier,
      )
    HealthSeverity.NEUTRAL ->
      if (status == HealthStatus.MONITORING_DISABLED) {
        Icon(
          painter = painterResource(R.drawable.ic_status_pause),
          contentDescription = null,
          tint = tint,
          modifier = modifier,
        )
      } else {
        Icon(
          imageVector = Icons.Rounded.Info,
          contentDescription = null,
          tint = tint,
          modifier = modifier,
        )
      }
  }
}

object TimeFormatters {
  private val dateTimeFormatter =
    object : ThreadLocal<SimpleDateFormat>() {
      override fun initialValue(): SimpleDateFormat =
        SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())
    }

  fun formatEpoch(epochMs: Long): String {
    if (epochMs <= 0L) return "Never"
    return requireNotNull(dateTimeFormatter.get()).format(Date(epochMs))
  }

  fun formatRelativeOrFuture(epochMs: Long, nowMs: Long = System.currentTimeMillis()): String {
    if (epochMs <= 0L) return "Not scheduled"
    val diffMs = epochMs - nowMs
    val absMin = kotlin.math.abs(diffMs) / 60_000L
    return when {
      diffMs > 0L && absMin < 1L -> "In <1m"
      diffMs > 0L && absMin < 60L -> "In ${absMin}m"
      diffMs > 0L -> "In ${absMin / 60L}h ${absMin % 60L}m"
      absMin < 1L -> "Just now"
      absMin < 60L -> "${absMin}m ago"
      absMin < 1440L -> "${absMin / 60L}h ago"
      else -> formatEpoch(epochMs)
    }
  }
}

@Composable
fun StatusIconBadge(
  status: HealthStatus,
  size: Dp = 36.dp,
  iconSize: Dp = 20.dp,
  modifier: Modifier = Modifier,
) {
  val accent = status.color()
  val isDark = LocalIsDarkTheme.current
  val bgColor = remember(accent, isDark) { accent.copy(alpha = if (isDark) 0.18f else 0.13f) }
  val borderColor = remember(accent) { accent.copy(alpha = 0.45f) }
  Box(
    contentAlignment = Alignment.Center,
    modifier =
      modifier
        .size(size)
        .background(bgColor, CircleShape)
        .border(1.dp, borderColor, CircleShape)
        .semantics { contentDescription = status.severity.label }
        .testTag("status_icon_${status.firestoreValue}"),
  ) {
    StatusIcon(
      status = status,
      tint = accent,
      modifier = Modifier.size(iconSize),
    )
  }
}

@Composable
fun StatusBadge(
  status: HealthStatus,
  modifier: Modifier = Modifier,
) {
  val accent = status.color()
  val textColor = status.contentColor()
  val isDark = LocalIsDarkTheme.current
  val bgColor = remember(accent, isDark) { accent.copy(alpha = if (isDark) 0.18f else 0.13f) }
  val borderColor = remember(accent) { accent.copy(alpha = 0.55f) }
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(5.dp),
    modifier =
      modifier
        .background(bgColor, StatusPillShape)
        .border(1.dp, borderColor, StatusPillShape)
        .padding(horizontal = 10.dp, vertical = 4.dp)
        .semantics(mergeDescendants = true) {
          contentDescription = "${status.severity.label}: ${status.displayLabel}"
        }
        .testTag("status_chip_${status.firestoreValue}"),
  ) {
    StatusIcon(
      status = status,
      tint = accent,
      modifier = Modifier.size(14.dp),
    )
    Text(
      text = status.displayLabel,
      style = MaterialTheme.typography.labelSmall,
      fontWeight = FontWeight.SemiBold,
      color = textColor,
      maxLines = 1,
    )
  }
}

@Composable
fun ProjectSummaryCard(
  project: SupabaseProject,
  isChecking: Boolean,
  onClick: (SupabaseProject) -> Unit,
  onCheckNow: (SupabaseProject) -> Unit,
  onToggleMonitoring: (SupabaseProject, Boolean) -> Unit,
  modifier: Modifier = Modifier,
) {
  val status = project.statusEnum
  val outlineColor = MaterialTheme.colorScheme.outline
  val borderColor =
    remember(status, outlineColor) {
      when (status.severity) {
        HealthSeverity.WARNING,
        HealthSeverity.CRITICAL -> status.color().copy(alpha = 0.5f)
        else -> outlineColor.copy(alpha = 0.5f)
      }
    }
  val lastCheckText =
    remember(project.lastCheckedEpochMs) {
      TimeFormatters.formatRelativeOrFuture(project.lastCheckedEpochMs)
    }
  val nextCheckText =
    remember(project.monitoringEnabled, project.nextCheckEpochMs) {
      if (project.monitoringEnabled) {
        TimeFormatters.formatRelativeOrFuture(project.nextCheckEpochMs)
      } else {
        "Disabled"
      }
    }
  val latencyText =
    remember(project.lastLatencyMs) {
      if (project.lastLatencyMs > 0L) "${project.lastLatencyMs}ms" else "—"
    }

  Column(
    verticalArrangement = Arrangement.spacedBy(12.dp),
    modifier =
      modifier
        .fillMaxWidth()
        .clip(ProjectCardShape)
        .background(MaterialTheme.colorScheme.surface, ProjectCardShape)
        .border(1.dp, borderColor, ProjectCardShape)
        .clickable { onClick(project) }
        .padding(16.dp)
        .testTag("project_card_${project.id}"),
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween,
      modifier = Modifier.fillMaxWidth(),
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.weight(1f).padding(end = 8.dp),
      ) {
        StatusIconBadge(status = status)
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = project.projectName,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          Text(
            text = project.projectRef,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
      }
      StatusBadge(status = status)
    }

    // Telemetry metrics strip
    Row(
      horizontalArrangement = Arrangement.spacedBy(12.dp),
      modifier = Modifier.fillMaxWidth(),
    ) {
      MetricPill(
        title = "Last Check",
        value = lastCheckText,
        modifier = Modifier.weight(1f),
      )
      MetricPill(
        title = "Next (12h)",
        value = nextCheckText,
        modifier = Modifier.weight(1f),
      )
      MetricPill(
        title = "Latency",
        value = latencyText,
        modifier = Modifier.weight(0.8f),
      )
    }

    if (project.lastSanitizedError.isNotBlank()) {
      Text(
        text = project.lastSanitizedError,
        style = MaterialTheme.typography.bodySmall,
        color = status.contentColor(),
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier =
          Modifier.fillMaxWidth()
            .background(status.color().copy(alpha = 0.1f), BannerAndPillShape)
            .padding(horizontal = 10.dp, vertical = 7.dp),
      )
    }

    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween,
      modifier = Modifier.fillMaxWidth(),
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.weight(1f).padding(end = 8.dp),
      ) {
        Switch(
          checked = project.monitoringEnabled,
          onCheckedChange = { enabled -> onToggleMonitoring(project, enabled) },
          modifier = Modifier.testTag("toggle_monitoring_${project.id}"),
        )
        Text(
          text = if (project.monitoringEnabled) "12h active" else "Paused",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }

      FilledTonalButton(
        onClick = { onCheckNow(project) },
        enabled = !isChecking,
        shape = BannerAndPillShape,
        modifier = Modifier.minimumInteractiveComponentSize().testTag("check_now_${project.id}"),
      ) {
        if (isChecking) {
          CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
          Spacer(modifier = Modifier.width(6.dp))
          Text("Checking", maxLines = 1)
        } else {
          Icon(
            imageVector = if (project.lastCheckedEpochMs == 0L) Icons.Rounded.PlayArrow else Icons.Rounded.Refresh,
            contentDescription = "Run manual health check",
            modifier = Modifier.size(18.dp),
          )
          Spacer(modifier = Modifier.width(6.dp))
          Text("Check Now", maxLines = 1)
        }
      }
    }
  }
}

@Composable
private fun MetricPill(
  title: String,
  value: String,
  modifier: Modifier = Modifier,
) {
  Column(
    verticalArrangement = Arrangement.spacedBy(2.dp),
    modifier =
      modifier
        .background(
          MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
          BannerAndPillShape,
        )
        .padding(horizontal = 10.dp, vertical = 7.dp),
  ) {
    Text(
      text = title,
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      maxLines = 1,
    )
    Text(
      text = value,
      style = MaterialTheme.typography.labelMedium,
      color = MaterialTheme.colorScheme.onSurface,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
}
