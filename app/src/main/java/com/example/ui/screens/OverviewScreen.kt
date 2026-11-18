package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.model.DashboardStats
import com.example.data.model.HealthStatus
import com.example.data.model.MonitoringLog
import com.example.data.model.SupabaseProject
import com.example.ui.components.ProjectSummaryCard
import com.example.ui.components.StatusIcon
import com.example.ui.components.StatusIconBadge
import com.example.ui.components.TimeFormatters
import com.example.ui.components.color
import com.example.ui.components.contentColor
import com.example.ui.viewmodel.UiState

private const val MAX_OVERVIEW_PROJECTS = 15
private val OverviewStripShape = RoundedCornerShape(14.dp)
private val OverviewCardShape = RoundedCornerShape(16.dp)
private val OverviewButtonShape = RoundedCornerShape(12.dp)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OverviewScreen(
  stats: DashboardStats,
  projectsState: UiState<List<SupabaseProject>>,
  logsState: UiState<List<MonitoringLog>>,
  checkingProjectIds: Set<String>,
  isRefreshing: Boolean = false,
  onRefresh: () -> Unit = {},
  onAddProject: () -> Unit,
  onOpenSqlGuide: () -> Unit,
  onRunDueChecks: () -> Unit,
  onProjectClick: (SupabaseProject) -> Unit,
  onCheckNow: (SupabaseProject) -> Unit,
  onToggleMonitoring: (SupabaseProject, Boolean) -> Unit,
  onViewAllLogs: () -> Unit,
  onViewAllProjects: () -> Unit = {},
  listState: LazyListState = rememberLazyListState(),
) {
  when (projectsState) {
    is UiState.Loading -> {
      Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
      }
    }
    is UiState.Error -> {
      Box(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        contentAlignment = Alignment.Center,
      ) {
        Text(
          text = projectsState.message,
          style = MaterialTheme.typography.bodyLarge,
          color = MaterialTheme.colorScheme.error,
        )
      }
    }
    is UiState.Success -> {
      val projects = projectsState.data
      val displayedProjects =
        remember(projects) {
          if (projects.size > MAX_OVERVIEW_PROJECTS) projects.take(MAX_OVERVIEW_PROJECTS)
          else projects
        }
      val recentLogs =
        remember(logsState) {
          (logsState as? UiState.Success)?.data?.take(5).orEmpty()
        }
      val pullRefreshState = rememberPullToRefreshState()
      val surfaceColor = MaterialTheme.colorScheme.surface
      val outlineColor = MaterialTheme.colorScheme.outline
      val stripBorderColor = remember(outlineColor) { outlineColor.copy(alpha = 0.5f) }
      val recentBorderColor = remember(outlineColor) { outlineColor.copy(alpha = 0.45f) }

      PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        state = pullRefreshState,
        indicator = {
          PullToRefreshDefaults.Indicator(
            state = pullRefreshState,
            isRefreshing = isRefreshing,
            containerColor = surfaceColor,
            color = MaterialTheme.colorScheme.primary,
            modifier =
              Modifier.align(Alignment.TopCenter).testTag("overview_refresh_indicator"),
          )
        },
        modifier = Modifier.fillMaxSize().testTag("overview_pull_to_refresh"),
      ) {
        LazyColumn(
          state = listState,
          contentPadding = PaddingValues(16.dp),
          verticalArrangement = Arrangement.spacedBy(12.dp),
          modifier = Modifier.fillMaxSize().testTag("overview_list"),
        ) {
          // Lightweight single-bar telemetry summary
          item(key = "summary_strip") {
            Row(
              horizontalArrangement = Arrangement.SpaceEvenly,
              verticalAlignment = Alignment.CenterVertically,
              modifier =
                Modifier.fillMaxWidth()
                  .clip(OverviewStripShape)
                  .background(surfaceColor, OverviewStripShape)
                  .border(1.dp, stripBorderColor, OverviewStripShape)
                  .padding(vertical = 12.dp, horizontal = 8.dp),
            ) {
              CompactStatItem(
                label = "Healthy",
                value = stats.healthyCount.toString(),
                accent = HealthStatus.HEALTHY.contentColor(),
                status = HealthStatus.HEALTHY,
                modifier = Modifier.weight(1f).testTag("summary_stat_healthy"),
              )
              CompactStatItem(
                label = "Warning",
                value = stats.warningCount.toString(),
                accent =
                  if (stats.warningCount > 0) HealthStatus.WARNING.contentColor()
                  else MaterialTheme.colorScheme.onSurfaceVariant,
                status = HealthStatus.WARNING,
                modifier = Modifier.weight(1f).testTag("summary_stat_warning"),
              )
              CompactStatItem(
                label = "Critical",
                value = stats.issueCount.toString(),
                accent =
                  if (stats.issueCount > 0) HealthStatus.UNREACHABLE.contentColor()
                  else MaterialTheme.colorScheme.onSurfaceVariant,
                status = HealthStatus.UNREACHABLE,
                modifier = Modifier.weight(1f).testTag("summary_stat_critical"),
              )
              CompactStatItem(
                label = "Avg Latency",
                value = if (stats.averageLatencyMs > 0L) "${stats.averageLatencyMs}ms" else "—",
                accent = MaterialTheme.colorScheme.secondary,
                status = null,
                modifier = Modifier.weight(1f),
              )
            }
          }

          // Quick Actions Row
          item(key = "quick_actions") {
            Row(
              horizontalArrangement = Arrangement.spacedBy(10.dp),
              modifier = Modifier.fillMaxWidth(),
            ) {
              OutlinedButton(
                onClick = onRunDueChecks,
                shape = OverviewButtonShape,
                modifier = Modifier.weight(1f).testTag("run_due_checks_button"),
              ) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Run Due", maxLines = 1, overflow = TextOverflow.Ellipsis)
              }
              OutlinedButton(
                onClick = onOpenSqlGuide,
                shape = OverviewButtonShape,
                modifier = Modifier.weight(1f).testTag("open_sql_guide_button"),
              ) {
                Icon(
                  painter = painterResource(R.drawable.ic_code),
                  contentDescription = null,
                  modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("Setup SQL", maxLines = 1, overflow = TextOverflow.Ellipsis)
              }
            }
          }

          // Supabase Health Statuses
          if (projects.isEmpty()) {
            item(key = "empty_projects") {
              Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier =
                  Modifier.fillMaxWidth()
                    .clip(OverviewCardShape)
                    .background(surfaceColor, OverviewCardShape)
                    .border(1.dp, stripBorderColor, OverviewCardShape)
                    .padding(24.dp),
              ) {
                Icon(
                  painter = painterResource(R.drawable.ic_monitor_heart),
                  contentDescription = null,
                  tint = MaterialTheme.colorScheme.primary,
                  modifier = Modifier.size(32.dp),
                )
                Text(
                  text = stringResource(R.string.empty_projects_title),
                  style = MaterialTheme.typography.titleMedium,
                )
                Text(
                  text = stringResource(R.string.empty_projects_body),
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                  onClick = onAddProject,
                  shape = OverviewButtonShape,
                  modifier = Modifier.testTag("empty_add_project_button"),
                ) {
                  Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                  Spacer(modifier = Modifier.width(6.dp))
                  Text(stringResource(R.string.action_add_project))
                }
              }
            }
          } else {
            item(key = "projects_header", contentType = "section_header") {
              Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
              ) {
                Text(
                  text = "Health Status",
                  style = MaterialTheme.typography.titleMedium,
                  color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                  text = "${projects.size} total",
                  style = MaterialTheme.typography.labelMedium,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            }

            items(
              items = displayedProjects,
              key = { it.id },
              contentType = { "project_card" },
            ) { project ->
              ProjectSummaryCard(
                project = project,
                isChecking = checkingProjectIds.contains(project.id),
                onClick = onProjectClick,
                onCheckNow = onCheckNow,
                onToggleMonitoring = onToggleMonitoring,
              )
            }
            if (projects.size > MAX_OVERVIEW_PROJECTS) {
              item(key = "view_all_projects", contentType = "action_link") {
                TextButton(
                  onClick = onViewAllProjects,
                  modifier = Modifier.fillMaxWidth(),
                ) {
                  Text("View all ${projects.size} projects")
                }
              }
            }
          }

          // Recent Activity Logs
          if (recentLogs.isNotEmpty()) {
            item(key = "recent_header") {
              Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
              ) {
                Text(
                  text = "Recent Activity",
                  style = MaterialTheme.typography.titleMedium,
                  color = MaterialTheme.colorScheme.onBackground,
                  modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onViewAllLogs) {
                  Text("View All", maxLines = 1)
                }
              }
            }

            item(key = "recent_list") {
              Column(
                modifier =
                  Modifier.fillMaxWidth()
                    .clip(OverviewStripShape)
                    .background(surfaceColor, OverviewStripShape)
                    .border(1.dp, recentBorderColor, OverviewStripShape)
              ) {
                recentLogs.forEachIndexed { index, log ->
                  RecentActivityRow(log = log)
                  if (index < recentLogs.lastIndex) {
                    HorizontalDivider(
                      color = outlineColor.copy(alpha = 0.25f)
                    )
                  }
                }
              }
            }
          }

          item(key = "bottom_spacer") { Spacer(modifier = Modifier.height(72.dp)) }
        }
      }
    }
  }
}

@Composable
private fun CompactStatItem(
  label: String,
  value: String,
  accent: Color,
  status: HealthStatus?,
  modifier: Modifier = Modifier,
) {
  Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(2.dp),
    modifier = modifier.padding(horizontal = 4.dp),
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      if (status != null) {
        StatusIcon(
          status = status,
          tint = status.color(),
          modifier = Modifier.size(14.dp),
        )
      }
      Text(
        text = value,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        color = accent,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
    Text(
      text = label,
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
}

@Composable
private fun RecentActivityRow(log: MonitoringLog) {
  val status = log.statusEnum
  val isOk = log.result == "success"
  val summaryText =
    remember(log.checkType, log.httpStatus, log.responseTimeMs, status, isOk) {
      if (isOk) "${log.checkType} · HTTP ${log.httpStatus} · ${log.responseTimeMs}ms"
      else "${log.checkType} · ${status.displayLabel}"
    }
  val timeText =
    remember(log.checkedAtEpochMs) {
      TimeFormatters.formatRelativeOrFuture(log.checkedAtEpochMs)
    }
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(10.dp),
    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
  ) {
    StatusIconBadge(
      status = status,
      size = 30.dp,
      iconSize = 16.dp,
    )
    Column(modifier = Modifier.weight(1f).padding(end = 6.dp)) {
      Text(
        text = log.projectName,
        style = MaterialTheme.typography.titleSmall,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Text(
        text = summaryText,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
    Text(
      text = timeText,
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      maxLines = 1,
      softWrap = false,
    )
  }
}
