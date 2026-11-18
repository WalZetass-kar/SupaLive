package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ExitToApp
import androidx.compose.material.icons.rounded.Done
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.R
import com.example.data.model.AppNotification
import com.example.data.model.MonitoringLog
import com.example.data.model.UserProfile
import com.example.ui.components.StatusBadge
import com.example.ui.components.StatusIconBadge
import com.example.ui.components.TimeFormatters
import com.example.ui.theme.ThemeMode
import com.example.ui.viewmodel.UiState

private enum class LogFilter(val label: String) {
  ALL("All"),
  SUCCESS("Success"),
  FAILURE("Failures"),
}

private val LogAndAlertCardShape = RoundedCornerShape(14.dp)
private val LoadMoreLogsButtonShape = RoundedCornerShape(12.dp)

@Composable
fun LogsScreen(
  logsState: UiState<List<MonitoringLog>>,
  hasMoreLogs: Boolean = false,
  onLoadMoreLogs: () -> Unit = {},
  listState: LazyListState = rememberLazyListState(),
) {
  var selectedFilter by remember { mutableStateOf(LogFilter.ALL) }

  when (logsState) {
    is UiState.Loading -> {
      Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
      }
    }
    is UiState.Error -> {
      Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text = logsState.message, color = MaterialTheme.colorScheme.error)
      }
    }
    is UiState.Success -> {
      val logs =
        remember(logsState.data, selectedFilter) {
          when (selectedFilter) {
            LogFilter.ALL -> logsState.data
            LogFilter.SUCCESS -> logsState.data.filter { it.result == "success" }
            LogFilter.FAILURE -> logsState.data.filter { it.result == "failure" }
          }
        }

      Column(modifier = Modifier.fillMaxSize()) {
        Row(
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
          LogFilter.entries.forEach { filter ->
            FilterChip(
              selected = selectedFilter == filter,
              onClick = { selectedFilter = filter },
              label = { Text(filter.label) },
            )
          }
        }

        if (logs.isEmpty()) {
          Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Column(
              horizontalAlignment = Alignment.CenterHorizontally,
              verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
              Text(stringResource(R.string.empty_logs_title), style = MaterialTheme.typography.titleMedium)
              Text(
                stringResource(R.string.empty_logs_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
        } else {
          LazyColumn(
            state = listState,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 92.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize().testTag("logs_list"),
          ) {
            items(
              items = logs,
              key = { it.id },
              contentType = { "log_card" },
            ) { log -> LogEntryCard(log = log) }
            if (hasMoreLogs) {
              item(key = "load_more_logs", contentType = "action_button") {
                OutlinedButton(
                  onClick = onLoadMoreLogs,
                  shape = LoadMoreLogsButtonShape,
                  modifier = Modifier.fillMaxWidth().testTag("load_more_logs_button"),
                ) {
                  Text("Load older logs")
                }
              }
            }
          }
        }
      }
    }
  }
}

@Composable
fun LogEntryCard(log: MonitoringLog) {
  val status = log.statusEnum
  val summaryParts =
    remember(log.checkType, log.httpStatus, log.responseTimeMs, log.retryCount) {
      buildList {
        add(log.checkType.uppercase())
        add(if (log.httpStatus > 0L) "HTTP ${log.httpStatus}" else "Network")
        add("${log.responseTimeMs}ms")
        if (log.retryCount > 0L) add("${log.retryCount} retries")
      }.joinToString(" · ")
    }
  val formattedTime =
    remember(log.checkedAtEpochMs) { TimeFormatters.formatEpoch(log.checkedAtEpochMs) }
  val outlineColor = MaterialTheme.colorScheme.outline
  val borderColor = remember(outlineColor) { outlineColor.copy(alpha = 0.45f) }

  Column(
    verticalArrangement = Arrangement.spacedBy(8.dp),
    modifier =
      Modifier.fillMaxWidth()
        .clip(LogAndAlertCardShape)
        .background(MaterialTheme.colorScheme.surface, LogAndAlertCardShape)
        .border(1.dp, borderColor, LogAndAlertCardShape)
        .padding(14.dp),
  ) {
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
        StatusIconBadge(
          status = status,
          size = 26.dp,
          iconSize = 14.dp,
        )
        Text(
          text = log.projectName,
          style = MaterialTheme.typography.titleSmall,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
      StatusBadge(status = status)
    }

    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      modifier = Modifier.fillMaxWidth(),
    ) {
      Text(
        text = summaryParts,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f),
      )
      Text(
        text = formattedTime,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        softWrap = false,
      )
    }

    if (log.errorMessage.isNotBlank()) {
      Text(
        text = log.errorMessage,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

@Composable
fun AlertsScreen(
  notificationsState: UiState<List<AppNotification>>,
  onMarkRead: (String) -> Unit,
  onMarkAllRead: () -> Unit,
  listState: LazyListState = rememberLazyListState(),
) {
  when (notificationsState) {
    is UiState.Loading -> {
      Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
      }
    }
    is UiState.Error -> {
      Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text = notificationsState.message, color = MaterialTheme.colorScheme.error)
      }
    }
    is UiState.Success -> {
      val list = notificationsState.data
      val unreadCount = remember(list) { list.count { !it.readFlag } }

      Column(modifier = Modifier.fillMaxSize()) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween,
          modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
          Text(
            text = if (unreadCount > 0) "$unreadCount unread alert(s)" else "All caught up",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          if (unreadCount > 0) {
            TextButton(onClick = onMarkAllRead, modifier = Modifier.testTag("mark_all_read_button")) {
              Icon(Icons.Rounded.Done, contentDescription = null, modifier = Modifier.size(16.dp))
              Spacer(modifier = Modifier.width(6.dp))
              Text(stringResource(R.string.action_mark_all_read))
            }
          }
        }

        if (list.isEmpty()) {
          Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Column(
              horizontalAlignment = Alignment.CenterHorizontally,
              verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              Icon(
                imageVector = Icons.Rounded.Notifications,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(36.dp),
              )
              Text(stringResource(R.string.empty_alerts_title), style = MaterialTheme.typography.titleMedium)
              Text(
                stringResource(R.string.empty_alerts_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
        } else {
          LazyColumn(
            state = listState,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 92.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize(),
          ) {
            items(
              items = list,
              key = { it.id },
              contentType = { "alert_card" },
            ) { notif ->
              val containerColor =
                if (notif.readFlag) MaterialTheme.colorScheme.surface
                else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
              val borderColor =
                if (notif.readFlag) MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
                else MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
              val timeText =
                remember(notif.createdAtEpochMs) {
                  TimeFormatters.formatRelativeOrFuture(notif.createdAtEpochMs)
                }

              Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier =
                  Modifier.fillMaxWidth()
                    .clip(LogAndAlertCardShape)
                    .background(containerColor, LogAndAlertCardShape)
                    .border(1.dp, borderColor, LogAndAlertCardShape)
                    .clickable(enabled = !notif.readFlag) { onMarkRead(notif.id) }
                    .padding(14.dp),
              ) {
                Row(
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.spacedBy(8.dp),
                  modifier = Modifier.fillMaxWidth(),
                ) {
                  Text(
                    text = notif.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                  )
                  Text(
                    text = timeText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false,
                  )
                }
                Text(
                  text = notif.message,
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            }
          }
        }
      }
    }
  }
}

@Composable
fun SettingsDialog(
  profile: UserProfile?,
  userEmail: String?,
  themeMode: ThemeMode,
  onThemeModeChange: (ThemeMode) -> Unit,
  onDismiss: () -> Unit,
  onSavePreferences: (fullName: String, emailEnabled: Boolean, inAppEnabled: Boolean, threshold: Long) -> Unit,
  onSignOut: () -> Unit,
) {
  val context = LocalContext.current
  var fullName by remember(profile) { mutableStateOf(profile?.fullName ?: "Supalive Developer") }
  var emailAlerts by remember(profile) { mutableStateOf(profile?.emailAlertsEnabled ?: false) }
  var inAppAlerts by remember(profile) { mutableStateOf(profile?.inAppAlertsEnabled ?: true) }
  var threshold by remember(profile) { mutableLongStateOf(profile?.failureThreshold ?: 2L) }

  var notificationPermissionGranted by remember {
    mutableStateOf(
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
          PackageManager.PERMISSION_GRANTED
      } else {
        true
      }
    )
  }

  val permissionLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
      notificationPermissionGranted = granted
    }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Settings") },
    text = {
      Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.verticalScroll(rememberScrollState()),
      ) {
        if (!userEmail.isNullOrBlank()) {
          Text(
            text = userEmail,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
          Text("Appearance", style = MaterialTheme.typography.labelLarge)
          Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth(),
          ) {
            ThemeMode.entries.forEach { mode ->
              FilterChip(
                selected = themeMode == mode,
                onClick = { onThemeModeChange(mode) },
                label = { Text(mode.label, maxLines = 1) },
                modifier = Modifier.weight(1f).testTag("theme_chip_${mode.name.lowercase()}"),
              )
            }
          }
        }

        OutlinedTextField(
          value = fullName,
          onValueChange = { fullName = it },
          label = { Text("Display Name") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
        )

        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween,
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text(
            text = "In-App Alerts",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f).padding(end = 8.dp),
          )
          Switch(checked = inAppAlerts, onCheckedChange = { inAppAlerts = it })
        }

        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween,
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text(
            text = "Email Alerts",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f).padding(end = 8.dp),
          )
          Switch(checked = emailAlerts, onCheckedChange = { emailAlerts = it })
        }

        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween,
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text(
            text = "Failure Threshold: $threshold",
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(end = 8.dp),
          )
          Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(
              onClick = { if (threshold > 1L) threshold-- },
              contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            ) {
              Text("-")
            }
            OutlinedButton(
              onClick = { if (threshold < 10L) threshold++ },
              contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            ) {
              Text("+")
            }
          }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notificationPermissionGranted) {
          OutlinedButton(
            onClick = { permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
            modifier = Modifier.fillMaxWidth(),
          ) {
            Icon(Icons.Rounded.Notifications, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("Allow Notifications", maxLines = 1)
          }
        }

        OutlinedButton(
          onClick = {
            onDismiss()
            onSignOut()
          },
          modifier = Modifier.fillMaxWidth().testTag("sign_out_button"),
        ) {
          Icon(Icons.AutoMirrored.Rounded.ExitToApp, contentDescription = null, modifier = Modifier.size(16.dp))
          Spacer(modifier = Modifier.width(6.dp))
          Text(stringResource(R.string.sign_out))
        }
      }
    },
    confirmButton = {
      Button(
        onClick = {
          onSavePreferences(fullName, emailAlerts, inAppAlerts, threshold)
          onDismiss()
        }
      ) {
        Text(stringResource(R.string.action_save))
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text(stringResource(R.string.action_cancel))
      }
    },
  )
}
