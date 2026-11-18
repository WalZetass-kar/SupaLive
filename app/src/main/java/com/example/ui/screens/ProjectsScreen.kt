package com.example.ui.screens

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.model.HealthSeverity
import com.example.data.model.HealthStatus
import com.example.data.model.SupabaseProject
import com.example.ui.components.ProjectSummaryCard
import com.example.ui.components.StatusIcon
import com.example.ui.components.color
import com.example.ui.components.contentColor
import com.example.ui.viewmodel.UiState

private enum class ProjectFilter(val label: String, val badgeStatus: HealthStatus?) {
  ALL("All", null),
  HEALTHY("Healthy", HealthStatus.HEALTHY),
  WARNING("Warning", HealthStatus.WARNING),
  CRITICAL("Critical", HealthStatus.UNREACHABLE),
  DISABLED("Paused", HealthStatus.MONITORING_DISABLED),
}

private const val PROJECTS_PAGE_SIZE = 15
private val SearchInputShape = RoundedCornerShape(14.dp)
private val LoadMoreShape = RoundedCornerShape(12.dp)

@Composable
fun ProjectsScreen(
  projectsState: UiState<List<SupabaseProject>>,
  checkingProjectIds: Set<String>,
  onProjectClick: (SupabaseProject) -> Unit,
  onCheckNow: (SupabaseProject) -> Unit,
  onToggleMonitoring: (SupabaseProject, Boolean) -> Unit,
  listState: LazyListState = rememberLazyListState(),
) {
  var searchQuery by remember { mutableStateOf("") }
  var selectedFilter by remember { mutableStateOf(ProjectFilter.ALL) }
  var visibleLimit by remember(searchQuery, selectedFilter) { mutableIntStateOf(PROJECTS_PAGE_SIZE) }

  when (projectsState) {
    is UiState.Loading -> {
      Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
      }
    }
    is UiState.Error -> {
      Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text = projectsState.message, color = MaterialTheme.colorScheme.error)
      }
    }
    is UiState.Success -> {
      val filtered =
        remember(projectsState.data, searchQuery, selectedFilter) {
          projectsState.data.filter { proj ->
            val matchesQuery =
              searchQuery.isBlank() ||
                proj.projectName.contains(searchQuery, ignoreCase = true) ||
                proj.projectRef.contains(searchQuery, ignoreCase = true)
            val matchesFilter =
              when (selectedFilter) {
                ProjectFilter.ALL -> true
                ProjectFilter.HEALTHY -> proj.statusEnum.severity == HealthSeverity.HEALTHY
                ProjectFilter.WARNING -> proj.statusEnum.severity == HealthSeverity.WARNING
                ProjectFilter.CRITICAL -> proj.statusEnum.severity == HealthSeverity.CRITICAL
                ProjectFilter.DISABLED -> !proj.monitoringEnabled
              }
            matchesQuery && matchesFilter
          }
        }

      Column(modifier = Modifier.fillMaxSize()) {
        Column(
          verticalArrangement = Arrangement.spacedBy(10.dp),
          modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
          OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("Search by name or project ref...") },
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
            singleLine = true,
            shape = SearchInputShape,
            modifier = Modifier.fillMaxWidth().testTag("search_projects_input"),
          )

          LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(
              items = ProjectFilter.entries,
              key = { it.name },
            ) { filter ->
              val status = filter.badgeStatus
              val accent = status?.color()
              val labelColor = status?.contentColor()
              FilterChip(
                selected = selectedFilter == filter,
                onClick = { selectedFilter = filter },
                leadingIcon =
                  if (status != null) {
                    {
                      StatusIcon(
                        status = status,
                        tint = accent ?: MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                      )
                    }
                  } else null,
                colors =
                  if (accent != null && labelColor != null) {
                    FilterChipDefaults.filterChipColors(
                      selectedContainerColor = accent.copy(alpha = 0.16f),
                      selectedLabelColor = labelColor,
                      selectedLeadingIconColor = accent,
                    )
                  } else {
                    FilterChipDefaults.filterChipColors()
                  },
                label = { Text(filter.label) },
                modifier = Modifier.testTag("filter_chip_${filter.name.lowercase()}"),
              )
            }
          }
        }

        if (filtered.isEmpty()) {
          Box(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            contentAlignment = Alignment.Center,
          ) {
            Column(
              horizontalAlignment = Alignment.CenterHorizontally,
              verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
              Text(
                text =
                  if (projectsState.data.isEmpty()) stringResource(R.string.empty_projects_title)
                  else "No matching projects",
                style = MaterialTheme.typography.titleMedium,
              )
              Text(
                text =
                  if (projectsState.data.isEmpty()) stringResource(R.string.empty_projects_body)
                  else "Try clearing your search or changing the status filter.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
        } else {
          val pagedProjects =
            remember(filtered, visibleLimit) {
              if (filtered.size > visibleLimit) filtered.take(visibleLimit) else filtered
            }
          val hasMore = filtered.size > visibleLimit
          val shouldAutoLoadMore by
            remember(hasMore, listState) {
              derivedStateOf {
                if (!hasMore) false
                else {
                  val info = listState.layoutInfo
                  val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
                  info.totalItemsCount > 0 && lastVisible >= (info.totalItemsCount - 3).coerceAtLeast(0)
                }
              }
            }
          LaunchedEffect(shouldAutoLoadMore, pagedProjects.size) {
            if (shouldAutoLoadMore && visibleLimit < filtered.size) {
              visibleLimit += PROJECTS_PAGE_SIZE
            }
          }

          LazyColumn(
            state = listState,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 92.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize().testTag("projects_list"),
          ) {
            items(
              items = pagedProjects,
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
            if (hasMore) {
              item(key = "load_more_projects", contentType = "action_button") {
                OutlinedButton(
                  onClick = { visibleLimit += PROJECTS_PAGE_SIZE },
                  shape = LoadMoreShape,
                  modifier = Modifier.fillMaxWidth().testTag("load_more_projects_button"),
                ) {
                  Text("Show more (${filtered.size - pagedProjects.size} remaining)")
                }
              }
            }
            item(key = "bottom_pad", contentType = "spacer") { Spacer(modifier = Modifier.height(12.dp)) }
          }
        }
      }
    }
  }
}
