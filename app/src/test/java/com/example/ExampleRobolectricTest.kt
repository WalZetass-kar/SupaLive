package com.example

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.DashboardStats
import com.example.data.model.HealthStatus
import com.example.data.model.MonitoringLog
import com.example.data.model.SupabaseProject
import com.example.ui.screens.LogsScreen
import com.example.ui.screens.OverviewScreen
import com.example.ui.screens.ProjectsScreen
import com.example.ui.theme.SupaliveTheme
import com.example.ui.theme.ThemeMode
import com.example.ui.theme.ThemePreferences
import com.example.ui.viewmodel.UiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("Supalive", appName)
  }

  @Test
  fun `theme preferences save and load light and dark modes`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    ThemePreferences.save(context, ThemeMode.LIGHT)
    assertEquals(ThemeMode.LIGHT, ThemePreferences.load(context))

    ThemePreferences.save(context, ThemeMode.DARK)
    assertEquals(ThemeMode.DARK, ThemePreferences.load(context))

    ThemePreferences.save(context, ThemeMode.SYSTEM)
    assertEquals(ThemeMode.SYSTEM, ThemePreferences.load(context))
  }

  @OptIn(ExperimentalTestApi::class)
  @Test
  fun `overview dashboard displays health status and recent activity logs`() {
    val sampleProjects =
      listOf(
        SupabaseProject(
          id = "proj_1",
          projectName = "Prod DB",
          projectUrl = "https://abcxyz.supabase.co",
          projectRef = "abcxyz",
          healthStatus = HealthStatus.HEALTHY.firestoreValue,
          lastLatencyMs = 84L,
          lastCheckedEpochMs = System.currentTimeMillis() - 60_000L,
          nextCheckEpochMs = System.currentTimeMillis() + 3_600_000L,
        )
      )
    val sampleLogs =
      listOf(
        MonitoringLog(
          id = "log_1",
          projectId = "proj_1",
          projectName = "Prod DB",
          checkType = "manual",
          result = "success",
          resultingStatus = HealthStatus.HEALTHY.firestoreValue,
          httpStatus = 200L,
          responseTimeMs = 84L,
          checkedAtEpochMs = System.currentTimeMillis() - 60_000L,
        )
      )
    val stats = DashboardStats.compute(sampleProjects, sampleLogs)

    composeTestRule.setContent {
      SupaliveTheme(darkTheme = false) {
        OverviewScreen(
          stats = stats,
          projectsState = UiState.Success(sampleProjects),
          logsState = UiState.Success(sampleLogs),
          checkingProjectIds = emptySet(),
          onAddProject = {},
          onOpenSqlGuide = {},
          onRunDueChecks = {},
          onProjectClick = {},
          onCheckNow = {},
          onToggleMonitoring = { _, _ -> },
          onViewAllLogs = {},
        )
      }
    }

    composeTestRule.onNodeWithText("Health Status").assertIsDisplayed()
    composeTestRule.onAllNodesWithText("Healthy").assertCountEquals(2)

    composeTestRule.onNodeWithTag("overview_list").performScrollToKey("recent_header")
    composeTestRule.onNodeWithText("Recent Activity").assertIsDisplayed()
    composeTestRule.onNodeWithTag("overview_list").performScrollToKey("recent_list")
    composeTestRule.onNodeWithText("manual · HTTP 200 · 84ms").assertIsDisplayed()
  }

  @Test
  fun `pull to refresh on dashboard list triggers manual health check`() {
    val sampleProjects =
      listOf(
        SupabaseProject(
          id = "proj_1",
          projectName = "Prod DB",
          projectUrl = "https://abcxyz.supabase.co",
          projectRef = "abcxyz",
          healthStatus = HealthStatus.HEALTHY.firestoreValue,
          lastLatencyMs = 84L,
          lastCheckedEpochMs = System.currentTimeMillis() - 60_000L,
          nextCheckEpochMs = System.currentTimeMillis() + 3_600_000L,
        )
      )
    val stats = DashboardStats.compute(sampleProjects, emptyList())
    var refreshTriggerCount = 0

    composeTestRule.setContent {
      var refreshing by remember { mutableStateOf(false) }
      var count by remember { mutableIntStateOf(0) }
      SupaliveTheme(darkTheme = false) {
        OverviewScreen(
          stats = stats,
          projectsState = UiState.Success(sampleProjects),
          logsState = UiState.Success(emptyList()),
          checkingProjectIds = emptySet(),
          isRefreshing = refreshing,
          onRefresh = {
            count++
            refreshTriggerCount = count
            refreshing = true
          },
          onAddProject = {},
          onOpenSqlGuide = {},
          onRunDueChecks = {},
          onProjectClick = {},
          onCheckNow = {},
          onToggleMonitoring = { _, _ -> },
          onViewAllLogs = {},
        )
      }
    }

    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("overview_list").performTouchInput {
      swipeDown(startY = top + 40f, endY = bottom - 40f)
    }
    composeTestRule.waitForIdle()

    assertEquals(1, refreshTriggerCount)
    composeTestRule.onNodeWithTag("overview_refresh_indicator").assertExists()
  }

  @Test
  fun `dashboard displays visual status chips and icons for healthy warning and critical`() {
    val sampleProjects =
      listOf(
        SupabaseProject(
          id = "proj_healthy",
          projectName = "Prod DB",
          projectUrl = "https://abcxyz.supabase.co",
          projectRef = "abcxyz",
          healthStatus = HealthStatus.HEALTHY.firestoreValue,
          lastLatencyMs = 75L,
          lastCheckedEpochMs = System.currentTimeMillis() - 60_000L,
        ),
        SupabaseProject(
          id = "proj_warning",
          projectName = "Staging DB",
          projectUrl = "https://defuvw.supabase.co",
          projectRef = "defuvw",
          healthStatus = HealthStatus.WARNING.firestoreValue,
          lastLatencyMs = 1950L,
          lastCheckedEpochMs = System.currentTimeMillis() - 60_000L,
        ),
        SupabaseProject(
          id = "proj_critical",
          projectName = "Legacy DB",
          projectUrl = "https://ghijkl.supabase.co",
          projectRef = "ghijkl",
          healthStatus = HealthStatus.UNREACHABLE.firestoreValue,
          lastCheckedEpochMs = System.currentTimeMillis() - 60_000L,
        ),
      )
    val stats = DashboardStats.compute(sampleProjects, emptyList())

    composeTestRule.setContent {
      SupaliveTheme(darkTheme = false) {
        OverviewScreen(
          stats = stats,
          projectsState = UiState.Success(sampleProjects),
          logsState = UiState.Success(emptyList()),
          checkingProjectIds = emptySet(),
          onAddProject = {},
          onOpenSqlGuide = {},
          onRunDueChecks = {},
          onProjectClick = {},
          onCheckNow = {},
          onToggleMonitoring = { _, _ -> },
          onViewAllLogs = {},
        )
      }
    }

    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("summary_stat_healthy").assertIsDisplayed()
    composeTestRule.onNodeWithTag("summary_stat_warning").assertIsDisplayed()
    composeTestRule.onNodeWithTag("summary_stat_critical").assertIsDisplayed()

    composeTestRule.onNodeWithTag("status_chip_healthy").assertContentDescriptionEquals("Healthy: Healthy")
    composeTestRule
      .onNodeWithTag("status_icon_healthy", useUnmergedTree = true)
      .assertContentDescriptionEquals("Healthy")

    composeTestRule.onNodeWithTag("overview_list").performScrollToKey("proj_warning")
    composeTestRule.onNodeWithTag("status_chip_warning").assertContentDescriptionEquals("Warning: Warning")
    composeTestRule
      .onNodeWithTag("status_icon_warning", useUnmergedTree = true)
      .assertContentDescriptionEquals("Warning")

    composeTestRule.onNodeWithTag("overview_list").performScrollToKey("proj_critical")
    composeTestRule.onNodeWithTag("status_chip_unreachable").assertContentDescriptionEquals("Critical: Unreachable")
    composeTestRule
      .onNodeWithTag("status_icon_unreachable", useUnmergedTree = true)
      .assertContentDescriptionEquals("Critical")
  }

  @Test
  fun `projects screen paginates long project lists across multiple pages`() {
    val manyProjects =
      (1..45).map { idx ->
        SupabaseProject(
          id = "proj_$idx",
          projectName = "DB $idx",
          projectUrl = "https://proj$idx.supabase.co",
          projectRef = "proj$idx",
          healthStatus = HealthStatus.HEALTHY.firestoreValue,
          lastLatencyMs = 50L + idx,
        )
      }

    composeTestRule.setContent {
      SupaliveTheme(darkTheme = false, reducedMotion = true) {
        ProjectsScreen(
          projectsState = UiState.Success(manyProjects),
          checkingProjectIds = emptySet(),
          onProjectClick = {},
          onCheckNow = {},
          onToggleMonitoring = { _, _ -> },
        )
      }
    }

    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithText("DB 1").assertIsDisplayed()
    composeTestRule.onNodeWithTag("projects_list").performScrollToKey("proj_15")
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("projects_list").performScrollToKey("proj_30")
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("projects_list").performScrollToKey("proj_45")
    composeTestRule.onNodeWithText("DB 45").assertIsDisplayed()
  }

  @Test
  fun `logs screen shows load older logs button and triggers callback when hasMoreLogs is true`() {
    val logs =
      listOf(
        MonitoringLog(
          id = "log_1",
          projectId = "proj_1",
          projectName = "Prod DB",
          checkType = "manual",
          result = "success",
          resultingStatus = HealthStatus.HEALTHY.firestoreValue,
          httpStatus = 200L,
          responseTimeMs = 64L,
          checkedAtEpochMs = System.currentTimeMillis() - 60_000L,
        )
      )
    var loadMoreCalls = 0

    composeTestRule.setContent {
      SupaliveTheme(darkTheme = false, reducedMotion = true) {
        LogsScreen(
          logsState = UiState.Success(logs),
          hasMoreLogs = true,
          onLoadMoreLogs = { loadMoreCalls++ },
        )
      }
    }

    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("logs_list").performScrollToKey("load_more_logs")
    composeTestRule.onNodeWithTag("load_more_logs_button").assertIsDisplayed().performClick()
    composeTestRule.waitForIdle()

    assertEquals(1, loadMoreCalls)
  }
}
