package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.model.HealthStatus
import com.example.data.model.MonitoringLog
import com.example.data.model.SupabaseProject
import com.example.ui.components.StatusBadge
import com.example.ui.components.StatusIconBadge
import com.example.ui.components.TimeFormatters
import com.example.ui.components.color
import com.example.ui.components.contentColor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectDetailScreen(
  project: SupabaseProject,
  logs: List<MonitoringLog>,
  isChecking: Boolean,
  onBack: () -> Unit,
  onCheckNow: () -> Unit,
  onToggleMonitoring: (Boolean) -> Unit,
  onEdit: () -> Unit,
  onShowSql: () -> Unit,
  onDelete: () -> Unit,
) {
  BackHandler(onBack = onBack)

  val context = LocalContext.current
  var showDeleteConfirm by remember { mutableStateOf(false) }
  val status = project.statusEnum

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text(project.projectName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = {
          IconButton(onClick = onBack, modifier = Modifier.testTag("detail_back_button")) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
          }
        },
        actions = {
          IconButton(onClick = onEdit, modifier = Modifier.testTag("edit_project_button")) {
            Icon(Icons.Rounded.Edit, contentDescription = "Edit project")
          }
          IconButton(
            onClick = { showDeleteConfirm = true },
            modifier = Modifier.testTag("delete_project_button"),
          ) {
            Icon(
              Icons.Rounded.Delete,
              contentDescription = "Delete project",
              tint = MaterialTheme.colorScheme.error,
            )
          }
        },
      )
    }
  ) { innerPadding ->
    LazyColumn(
      contentPadding = PaddingValues(16.dp),
      verticalArrangement = Arrangement.spacedBy(14.dp),
      modifier = Modifier.fillMaxSize().padding(innerPadding),
    ) {
      // Status Card
      item {
        Card(
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
          border = BorderStroke(1.dp, status.color().copy(alpha = 0.5f)),
          shape = RoundedCornerShape(16.dp),
          modifier = Modifier.fillMaxWidth(),
        ) {
          Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(16.dp),
          ) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.SpaceBetween,
              modifier = Modifier.fillMaxWidth(),
            ) {
              Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
              ) {
                StatusIconBadge(status = status, size = 32.dp, iconSize = 18.dp)
                Text("Health Status", style = MaterialTheme.typography.titleMedium)
              }
              StatusBadge(status = status)
            }
            Text(
              text = status.summary,
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (project.lastSanitizedError.isNotBlank()) {
              Text(
                text = project.lastSanitizedError,
                style = MaterialTheme.typography.labelMedium,
                color = status.contentColor(),
                modifier =
                  Modifier.fillMaxWidth()
                    .background(status.color().copy(alpha = 0.12f), RoundedCornerShape(10.dp))
                    .padding(10.dp),
              )
            }
            if (status == HealthStatus.SUSPECTED_PAUSED || status == HealthStatus.UNREACHABLE) {
              OutlinedButton(
                onClick = {
                  val url = "https://supabase.com/dashboard/project/${project.projectRef}"
                  context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                },
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth(),
              ) {
                Icon(
                  Icons.AutoMirrored.Rounded.ArrowForward,
                  contentDescription = null,
                  modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.action_open_supabase_dashboard))
              }
            }
          }
        }
      }

      // Action Buttons
      item {
        Row(
          horizontalArrangement = Arrangement.spacedBy(10.dp),
          modifier = Modifier.fillMaxWidth(),
        ) {
          Button(
            onClick = onCheckNow,
            enabled = !isChecking,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.weight(1f).testTag("detail_check_now_button"),
          ) {
            if (isChecking) {
              CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary,
              )
              Spacer(modifier = Modifier.width(8.dp))
              Text("Checking...")
            } else {
              Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
              Spacer(modifier = Modifier.width(6.dp))
              Text(stringResource(R.string.action_check_now))
            }
          }
          OutlinedButton(
            onClick = onShowSql,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.weight(1f),
          ) {
            Icon(
              painter = painterResource(R.drawable.ic_code),
              contentDescription = null,
              modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text("Setup SQL")
          }
        }
      }

      // Schedule & Telemetry Card
      item {
        Card(
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
          border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
          shape = RoundedCornerShape(16.dp),
          modifier = Modifier.fillMaxWidth(),
        ) {
          Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(16.dp),
          ) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.SpaceBetween,
              modifier = Modifier.fillMaxWidth(),
            ) {
              Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                Text("12-Hour Monitoring", style = MaterialTheme.typography.titleMedium)
                Text(
                  text = "Offset: +${project.phaseOffsetMinutes}m",
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis,
                )
              }
              Switch(
                checked = project.monitoringEnabled,
                onCheckedChange = onToggleMonitoring,
                modifier = Modifier.testTag("detail_monitoring_switch"),
              )
            }
            val lastCheckedStr =
              remember(project.lastCheckedEpochMs) { TimeFormatters.formatEpoch(project.lastCheckedEpochMs) }
            val lastSuccessStr =
              remember(project.lastSuccessEpochMs) { TimeFormatters.formatEpoch(project.lastSuccessEpochMs) }
            val nextScheduledStr =
              remember(project.monitoringEnabled, project.nextCheckEpochMs) {
                if (project.monitoringEnabled) TimeFormatters.formatEpoch(project.nextCheckEpochMs)
                else "Disabled"
              }
            DetailRow("Project URL", project.projectUrl)
            DetailRow("Last Checked", lastCheckedStr)
            DetailRow("Last Success", lastSuccessStr)
            DetailRow("Next Scheduled", nextScheduledStr)
            DetailRow("Consecutive Failures", project.consecutiveFailures.toString())
            DetailRow(
              "Last Latency",
              if (project.lastLatencyMs > 0L) "${project.lastLatencyMs} ms" else "—",
            )
          }
        }
      }

      // Encrypted Credential Card
      item {
        Card(
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
          border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
          shape = RoundedCornerShape(16.dp),
          modifier = Modifier.fillMaxWidth(),
        ) {
          Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(16.dp),
          ) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              Icon(
                Icons.Rounded.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
              )
              Text("Publishable Key", style = MaterialTheme.typography.titleMedium)
            }
            Text(
              text = project.maskedKey,
              style = MaterialTheme.typography.labelMedium,
              color = MaterialTheme.colorScheme.onSurface,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
            Text(
              text = "AES-256-GCM encrypted (v${project.keyVersion})",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      }

      // Project Logs
      item {
        Text(
          text = "Check History (${logs.size})",
          style = MaterialTheme.typography.titleLarge,
        )
      }

      if (logs.isEmpty()) {
        item {
          Text(
            text = "No checks recorded for this project yet.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      } else {
        items(
          items = logs.take(25),
          key = { it.id },
          contentType = { "log_card" },
        ) { log ->
          LogEntryCard(log = log)
        }
      }

      item { Spacer(modifier = Modifier.height(24.dp)) }
    }
  }

  if (showDeleteConfirm) {
    AlertDialog(
      onDismissRequest = { showDeleteConfirm = false },
      title = { Text("Delete ${project.projectName}?") },
      text = {
        Text(
          "This stops future monitoring for this project and permanently removes its encrypted publishable key."
        )
      },
      confirmButton = {
        Button(
          onClick = {
            showDeleteConfirm = false
            onDelete()
          },
          colors =
            ButtonDefaults.buttonColors(
              containerColor = MaterialTheme.colorScheme.error,
              contentColor = MaterialTheme.colorScheme.onError,
            ),
        ) {
          Text(stringResource(R.string.action_delete))
        }
      },
      dismissButton = {
        TextButton(onClick = { showDeleteConfirm = false }) {
          Text(stringResource(R.string.action_cancel))
        }
      },
    )
  }
}

@Composable
private fun DetailRow(label: String, value: String) {
  Row(
    horizontalArrangement = Arrangement.spacedBy(12.dp),
    verticalAlignment = Alignment.CenterVertically,
    modifier = Modifier.fillMaxWidth(),
  ) {
    Text(
      text = label,
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.weight(0.42f),
    )
    Text(
      text = value,
      style = MaterialTheme.typography.labelMedium,
      color = MaterialTheme.colorScheme.onSurface,
      textAlign = TextAlign.End,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.weight(0.58f),
    )
  }
}
