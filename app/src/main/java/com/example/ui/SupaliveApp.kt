package com.example.ui

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.R
import com.example.data.model.SupabaseProject
import com.example.data.repository.SupaliveRepository
import com.example.ui.auth.AuthScreen
import com.example.ui.auth.authStateFlow
import com.example.ui.auth.signOut
import com.example.ui.components.AddOrEditProjectDialog
import com.example.ui.components.SqlSetupDialog
import com.example.ui.screens.AlertsScreen
import com.example.ui.screens.LogsScreen
import com.example.ui.screens.OverviewScreen
import com.example.ui.screens.ProjectDetailScreen
import com.example.ui.screens.ProjectsScreen
import com.example.ui.screens.SettingsDialog
import com.example.ui.theme.ThemeMode
import com.example.ui.viewmodel.SupaliveViewModel
import com.example.ui.viewmodel.UiState
import com.example.worker.WorkScheduler
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private enum class MainTab(
  val route: String,
  val labelRes: Int,
  val selectedIcon: ImageVector?,
  val unselectedIcon: ImageVector?,
  val drawableRes: Int? = null,
) {
  OVERVIEW("overview", R.string.nav_overview, Icons.Rounded.Home, Icons.Outlined.Home),
  PROJECTS(
    "projects",
    R.string.nav_projects,
    Icons.AutoMirrored.Rounded.List,
    Icons.AutoMirrored.Outlined.List,
  ),
  LOGS("logs", R.string.nav_logs, null, null, R.drawable.ic_history),
  ALERTS(
    "alerts",
    R.string.nav_alerts,
    Icons.Rounded.Notifications,
    Icons.Outlined.Notifications,
  ),
}

@Composable
fun SupaliveApp(
  themeMode: ThemeMode = ThemeMode.SYSTEM,
  isDarkTheme: Boolean = false,
  onThemeModeChange: (ThemeMode) -> Unit = {},
  auth: FirebaseAuth = Firebase.auth,
) {
  val currentUser by auth.authStateFlow().collectAsStateWithLifecycle(initialValue = auth.currentUser)
  val user = currentUser

  if (user == null) {
    AuthScreen(
      isDarkTheme = isDarkTheme,
      onToggleTheme = {
        onThemeModeChange(if (isDarkTheme) ThemeMode.LIGHT else ThemeMode.DARK)
      },
      onAuthSuccess = {},
    )
  } else {
    key(user.uid) {
      AuthenticatedSupaliveShell(
        currentUserId = user.uid,
        userEmail = user.email,
        userDisplayName = user.displayName,
        themeMode = themeMode,
        isDarkTheme = isDarkTheme,
        onThemeModeChange = onThemeModeChange,
      )
    }
  }
}

@Composable
private fun AuthenticatedSupaliveShell(
  currentUserId: String,
  userEmail: String?,
  userDisplayName: String?,
  themeMode: ThemeMode,
  isDarkTheme: Boolean,
  onThemeModeChange: (ThemeMode) -> Unit,
) {
  val context = LocalContext.current

  val viewModel: SupaliveViewModel =
    viewModel(
      key = currentUserId,
      factory =
        viewModelFactory {
          initializer {
            val app =
              checkNotNull(this[APPLICATION_KEY]) {
                "APPLICATION_KEY missing from CreationExtras"
              }
            val databaseId = app.getString(R.string.firestore_database_id)
            val db = FirebaseFirestore.getInstance(databaseId)
            SupaliveViewModel(
              repository = SupaliveRepository(db = db),
              currentUserId = currentUserId,
              userEmail = userEmail,
              userDisplayName = userDisplayName,
            )
          }
        },
    )

  LaunchedEffect(currentUserId) {
    withContext(Dispatchers.IO) {
      // Scheduled 12-hour monitoring now runs server-side via GitHub Actions.
      // Cancel any legacy local WorkManager schedule once to avoid duplicate pings and startup overhead.
      val prefs = context.getSharedPreferences("supalive_prefs", Context.MODE_PRIVATE)
      if (!prefs.getBoolean("legacy_worker_cancelled", false)) {
        WorkScheduler.cancelSchedule(context)
        prefs.edit().putBoolean("legacy_worker_cancelled", true).apply()
      }
    }
  }

  val overviewListState = rememberLazyListState()
  val projectsListState = rememberLazyListState()
  val logsListState = rememberLazyListState()
  val alertsListState = rememberLazyListState()

  var currentTab by remember { mutableStateOf(MainTab.OVERVIEW) }
  var selectedProjectId by remember { mutableStateOf<String?>(null) }
  var showAddProjectDialog by remember { mutableStateOf(false) }
  var editingProject by remember { mutableStateOf<SupabaseProject?>(null) }
  var showSqlDialog by remember { mutableStateOf(false) }
  var showSettingsDialog by remember { mutableStateOf(false) }

  val activeProjectId = selectedProjectId
  if (activeProjectId != null) {
    ProjectDetailRoute(
      viewModel = viewModel,
      projectId = activeProjectId,
      onBack = { selectedProjectId = null },
      onEdit = { editingProject = it },
      onShowSql = { showSqlDialog = true },
    )
  } else {
    MainTabScaffold(
      viewModel = viewModel,
      currentTab = currentTab,
      isDarkTheme = isDarkTheme,
      onTabSelected = { currentTab = it },
      onToggleTheme = {
        onThemeModeChange(if (isDarkTheme) ThemeMode.LIGHT else ThemeMode.DARK)
      },
      onOpenSettings = { showSettingsDialog = true },
      onOpenAddProject = { showAddProjectDialog = true },
      onOpenSqlGuide = { showSqlDialog = true },
      onOpenProject = { selectedProjectId = it.id },
      overviewListState = overviewListState,
      projectsListState = projectsListState,
      logsListState = logsListState,
      alertsListState = alertsListState,
    )
  }

  if (showAddProjectDialog) {
    AddProjectDialogHost(
      viewModel = viewModel,
      onDismiss = { showAddProjectDialog = false },
    )
  }

  editingProject?.let { proj ->
    EditProjectDialogHost(
      viewModel = viewModel,
      project = proj,
      onDismiss = { editingProject = null },
    )
  }

  if (showSqlDialog) {
    SqlSetupDialog(onDismiss = { showSqlDialog = false })
  }

  if (showSettingsDialog) {
    SettingsDialogRoute(
      viewModel = viewModel,
      userEmail = userEmail,
      themeMode = themeMode,
      onThemeModeChange = onThemeModeChange,
      onDismiss = { showSettingsDialog = false },
    )
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainTabScaffold(
  viewModel: SupaliveViewModel,
  currentTab: MainTab,
  isDarkTheme: Boolean,
  onTabSelected: (MainTab) -> Unit,
  onToggleTheme: () -> Unit,
  onOpenSettings: () -> Unit,
  onOpenAddProject: () -> Unit,
  onOpenSqlGuide: () -> Unit,
  onOpenProject: (SupabaseProject) -> Unit,
  overviewListState: LazyListState,
  projectsListState: LazyListState,
  logsListState: LazyListState,
  alertsListState: LazyListState,
) {
  val useNavRail = LocalConfiguration.current.screenWidthDp >= 600

  Scaffold(
      snackbarHost = { FeedbackSnackbarHost(viewModel = viewModel) },
      topBar = {
        TopAppBar(
          title = {
            Text(
              text = stringResource(R.string.app_name),
              style = MaterialTheme.typography.titleLarge,
            )
          },
          actions = {
            IconButton(
              onClick = onToggleTheme,
              modifier = Modifier.testTag("theme_toggle_button"),
            ) {
              Icon(
                painter =
                  painterResource(
                    if (isDarkTheme) R.drawable.ic_light_mode else R.drawable.ic_dark_mode
                  ),
                contentDescription =
                  if (isDarkTheme) "Switch to light mode" else "Switch to dark mode",
              )
            }
            IconButton(
              onClick = onOpenSettings,
              modifier = Modifier.testTag("settings_button"),
            ) {
              Icon(Icons.Rounded.Settings, contentDescription = "Settings")
            }
          },
        )
      },
      floatingActionButton = {
        if (currentTab == MainTab.OVERVIEW || currentTab == MainTab.PROJECTS) {
          FloatingActionButton(
            onClick = onOpenAddProject,
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.testTag("add_project_fab"),
          ) {
            Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.action_add_project))
          }
        }
      },
      bottomBar = {
        if (!useNavRail) {
          NavigationBar {
            MainTab.entries.forEach { tab ->
              val selected = currentTab == tab
              NavigationBarItem(
                selected = selected,
                onClick = { onTabSelected(tab) },
                icon = {
                  if (tab == MainTab.ALERTS) {
                    AlertsTabIcon(viewModel = viewModel, tab = tab, selected = selected)
                  } else {
                    TabIcon(tab = tab, selected = selected, unreadCount = 0)
                  }
                },
                label = { Text(stringResource(tab.labelRes)) },
                modifier = Modifier.testTag("nav_${tab.route}"),
              )
            }
          }
        }
      },
      contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { innerPadding ->
      Row(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
        if (useNavRail) {
          NavigationRail {
            MainTab.entries.forEach { tab ->
              val selected = currentTab == tab
              NavigationRailItem(
                selected = selected,
                onClick = { onTabSelected(tab) },
                icon = {
                  if (tab == MainTab.ALERTS) {
                    AlertsTabIcon(viewModel = viewModel, tab = tab, selected = selected)
                  } else {
                    TabIcon(tab = tab, selected = selected, unreadCount = 0)
                  }
                },
                label = { Text(stringResource(tab.labelRes)) },
                modifier = Modifier.testTag("rail_${tab.route}"),
              )
            }
          }
        }

        Box(
          contentAlignment = Alignment.TopCenter,
          modifier = Modifier.fillMaxSize(),
        ) {
          Box(modifier = Modifier.widthIn(max = 840.dp).fillMaxSize()) {
            when (currentTab) {
              MainTab.OVERVIEW ->
                OverviewRoute(
                  viewModel = viewModel,
                  listState = overviewListState,
                  onAddProject = onOpenAddProject,
                  onOpenSqlGuide = onOpenSqlGuide,
                  onProjectClick = onOpenProject,
                  onViewAllLogs = { onTabSelected(MainTab.LOGS) },
                  onViewAllProjects = { onTabSelected(MainTab.PROJECTS) },
                )
              MainTab.PROJECTS ->
                ProjectsRoute(
                  viewModel = viewModel,
                  listState = projectsListState,
                  onProjectClick = onOpenProject,
                )
              MainTab.LOGS ->
                LogsRoute(
                  viewModel = viewModel,
                  listState = logsListState,
                )
              MainTab.ALERTS ->
                AlertsRoute(
                  viewModel = viewModel,
                  listState = alertsListState,
                )
            }
          }
        }
      }
    }
}

@Composable
private fun FeedbackSnackbarHost(viewModel: SupaliveViewModel) {
  val feedback by viewModel.feedback.collectAsStateWithLifecycle()
  val snackbarHostState = remember { SnackbarHostState() }
  LaunchedEffect(feedback) {
    feedback?.let {
      snackbarHostState.showSnackbar(it.message)
      viewModel.clearFeedback()
    }
  }
  SnackbarHost(hostState = snackbarHostState)
}

@Composable
private fun AlertsTabIcon(
  viewModel: SupaliveViewModel,
  tab: MainTab,
  selected: Boolean,
) {
  val unreadCount by viewModel.unreadAlertsCount.collectAsStateWithLifecycle()
  TabIcon(tab = tab, selected = selected, unreadCount = unreadCount)
}

@Composable
private fun OverviewRoute(
  viewModel: SupaliveViewModel,
  listState: LazyListState,
  onAddProject: () -> Unit,
  onOpenSqlGuide: () -> Unit,
  onProjectClick: (SupabaseProject) -> Unit,
  onViewAllLogs: () -> Unit,
  onViewAllProjects: () -> Unit,
) {
  val stats by viewModel.dashboardStats.collectAsStateWithLifecycle()
  val projectsState by viewModel.projectsState.collectAsStateWithLifecycle()
  val logsState by viewModel.logsState.collectAsStateWithLifecycle()
  val checkingIds by viewModel.checkingProjectIds.collectAsStateWithLifecycle()
  val isRefreshing by viewModel.isRefreshingDashboard.collectAsStateWithLifecycle()

  val onRefresh = remember(viewModel) { { viewModel.refreshDashboardHealthChecks() } }
  val onRunDue = remember(viewModel) { { viewModel.runDueChecksNow() } }
  val onCheckNow: (SupabaseProject) -> Unit = remember(viewModel) { { viewModel.runCheck(it) } }
  val onToggleMonitoring: (SupabaseProject, Boolean) -> Unit =
    remember(viewModel) { { proj, enabled -> viewModel.toggleMonitoring(proj, enabled) } }

  OverviewScreen(
    stats = stats,
    projectsState = projectsState,
    logsState = logsState,
    checkingProjectIds = checkingIds,
    isRefreshing = isRefreshing,
    onRefresh = onRefresh,
    onAddProject = onAddProject,
    onOpenSqlGuide = onOpenSqlGuide,
    onRunDueChecks = onRunDue,
    onProjectClick = onProjectClick,
    onCheckNow = onCheckNow,
    onToggleMonitoring = onToggleMonitoring,
    onViewAllLogs = onViewAllLogs,
    onViewAllProjects = onViewAllProjects,
    listState = listState,
  )
}

@Composable
private fun ProjectsRoute(
  viewModel: SupaliveViewModel,
  listState: LazyListState,
  onProjectClick: (SupabaseProject) -> Unit,
) {
  val projectsState by viewModel.projectsState.collectAsStateWithLifecycle()
  val checkingIds by viewModel.checkingProjectIds.collectAsStateWithLifecycle()
  val onCheckNow: (SupabaseProject) -> Unit = remember(viewModel) { { viewModel.runCheck(it) } }
  val onToggleMonitoring: (SupabaseProject, Boolean) -> Unit =
    remember(viewModel) { { proj, enabled -> viewModel.toggleMonitoring(proj, enabled) } }

  ProjectsScreen(
    projectsState = projectsState,
    checkingProjectIds = checkingIds,
    onProjectClick = onProjectClick,
    onCheckNow = onCheckNow,
    onToggleMonitoring = onToggleMonitoring,
    listState = listState,
  )
}

@Composable
private fun LogsRoute(
  viewModel: SupaliveViewModel,
  listState: LazyListState,
) {
  val logsState by viewModel.logsState.collectAsStateWithLifecycle()
  val hasMoreLogs by viewModel.hasMoreLogs.collectAsStateWithLifecycle()
  val onLoadMoreLogs = remember(viewModel) { { viewModel.loadMoreLogs() } }

  LogsScreen(
    logsState = logsState,
    hasMoreLogs = hasMoreLogs,
    onLoadMoreLogs = onLoadMoreLogs,
    listState = listState,
  )
}

@Composable
private fun AlertsRoute(
  viewModel: SupaliveViewModel,
  listState: LazyListState,
) {
  val notificationsState by viewModel.notificationsState.collectAsStateWithLifecycle()
  val onMarkRead: (String) -> Unit = remember(viewModel) { { viewModel.markNotificationRead(it) } }
  val onMarkAllRead = remember(viewModel) { { viewModel.markAllNotificationsRead() } }

  AlertsScreen(
    notificationsState = notificationsState,
    onMarkRead = onMarkRead,
    onMarkAllRead = onMarkAllRead,
    listState = listState,
  )
}

@Composable
private fun ProjectDetailRoute(
  viewModel: SupaliveViewModel,
  projectId: String,
  onBack: () -> Unit,
  onEdit: (SupabaseProject) -> Unit,
  onShowSql: () -> Unit,
) {
  val projectsState by viewModel.projectsState.collectAsStateWithLifecycle()
  val logsState by viewModel.logsState.collectAsStateWithLifecycle()
  val checkingIds by viewModel.checkingProjectIds.collectAsStateWithLifecycle()

  val projects = (projectsState as? UiState.Success)?.data.orEmpty()
  val project = remember(projects, projectId) { projects.firstOrNull { it.id == projectId } }

  if (project == null) {
    LaunchedEffect(projectId) { onBack() }
    return
  }

  val allLogs = (logsState as? UiState.Success)?.data.orEmpty()
  val projectLogs = remember(allLogs, project.id) { allLogs.filter { it.projectId == project.id } }

  ProjectDetailScreen(
    project = project,
    logs = projectLogs,
    isChecking = checkingIds.contains(project.id),
    onBack = onBack,
    onCheckNow = { viewModel.runCheck(project) },
    onToggleMonitoring = { enabled -> viewModel.toggleMonitoring(project, enabled) },
    onEdit = { onEdit(project) },
    onShowSql = onShowSql,
    onDelete = { viewModel.deleteProject(project, onDeleted = onBack) },
  )
}

@Composable
private fun AddProjectDialogHost(
  viewModel: SupaliveViewModel,
  onDismiss: () -> Unit,
) {
  val isSubmitting by viewModel.isSubmittingProject.collectAsStateWithLifecycle()
  AddOrEditProjectDialog(
    existingProject = null,
    isSubmitting = isSubmitting,
    onDismiss = onDismiss,
    onSubmit = { name, url, key, onError ->
      viewModel.registerProject(
        projectName = name,
        projectUrl = url,
        publishableKey = key,
        onSuccess = onDismiss,
        onError = onError,
      )
    },
  )
}

@Composable
private fun EditProjectDialogHost(
  viewModel: SupaliveViewModel,
  project: SupabaseProject,
  onDismiss: () -> Unit,
) {
  val isSubmitting by viewModel.isSubmittingProject.collectAsStateWithLifecycle()
  AddOrEditProjectDialog(
    existingProject = project,
    isSubmitting = isSubmitting,
    onDismiss = onDismiss,
    onSubmit = { name, url, key, onError ->
      viewModel.updateProject(
        project = project,
        newName = name,
        newUrl = url,
        newKeyOptional = key.ifBlank { null },
        onSuccess = onDismiss,
        onError = onError,
      )
    },
  )
}

@Composable
private fun SettingsDialogRoute(
  viewModel: SupaliveViewModel,
  userEmail: String?,
  themeMode: ThemeMode,
  onThemeModeChange: (ThemeMode) -> Unit,
  onDismiss: () -> Unit,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val credentialManager = remember(context) { CredentialManager.create(context) }
  val profileState by viewModel.profileState.collectAsStateWithLifecycle()
  val profile = (profileState as? UiState.Success)?.data

  SettingsDialog(
    profile = profile,
    userEmail = userEmail,
    themeMode = themeMode,
    onThemeModeChange = onThemeModeChange,
    onDismiss = onDismiss,
    onSavePreferences = { name, emailEnabled, inAppEnabled, threshold ->
      viewModel.updatePreferences(name, emailEnabled, inAppEnabled, threshold)
    },
    onSignOut = {
      WorkScheduler.cancelSchedule(context)
      signOut(credentialManager = credentialManager, scope = scope)
    },
  )
}

@Composable
private fun TabIcon(
  tab: MainTab,
  selected: Boolean,
  unreadCount: Int,
) {
  val label = stringResource(tab.labelRes)
  val iconContent: @Composable () -> Unit = {
    if (tab.drawableRes != null) {
      Icon(painter = painterResource(tab.drawableRes), contentDescription = label)
    } else {
      val vec = if (selected) tab.selectedIcon!! else tab.unselectedIcon!!
      Icon(vec, contentDescription = label)
    }
  }
  if (unreadCount > 0) {
    BadgedBox(badge = { Badge { Text(unreadCount.coerceAtMost(99).toString()) } }) {
      iconContent()
    }
  } else {
    iconContent()
  }
}
