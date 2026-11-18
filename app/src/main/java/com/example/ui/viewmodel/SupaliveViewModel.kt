package com.example.ui.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.AppNotification
import com.example.data.model.DashboardStats
import com.example.data.model.MonitoringLog
import com.example.data.model.SupabaseProject
import com.example.data.model.UserProfile
import com.example.data.repository.SupaliveRepository
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface UiState<out T> {
  data object Loading : UiState<Nothing>
  data class Success<T>(val data: T) : UiState<T>
  data class Error(val message: String) : UiState<Nothing>
}

data class ActionFeedback(
  val message: String,
  val isError: Boolean = false,
)

class SupaliveViewModel(
  private val repository: SupaliveRepository,
  private val currentUserId: String,
  userEmail: String?,
  userDisplayName: String?,
) : ViewModel() {

  val profileState: StateFlow<UiState<UserProfile?>> =
    repository
      .observeUserProfile(currentUserId)
      .map<UserProfile?, UiState<UserProfile?>> { UiState.Success(it) }
      .catch { error -> emit(unwrapFirestoreError(error, "Failed to load profile")) }
      .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = UiState.Loading,
      )

  val projectsState: StateFlow<UiState<List<SupabaseProject>>> =
    repository
      .observeProjects(currentUserId)
      .map<List<SupabaseProject>, UiState<List<SupabaseProject>>> { UiState.Success(it) }
      .catch { error -> emit(unwrapFirestoreError(error, "Failed to load projects")) }
      .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = UiState.Loading,
      )

  private val logsLimitFlow = MutableStateFlow(INITIAL_LOGS_LIMIT)

  @OptIn(ExperimentalCoroutinesApi::class)
  val logsState: StateFlow<UiState<List<MonitoringLog>>> =
    logsLimitFlow
      .flatMapLatest { limit ->
        repository
          .observeLogs(currentUserId, limit = limit)
          .map<List<MonitoringLog>, UiState<List<MonitoringLog>>> { UiState.Success(it) }
          .catch { error -> emit(unwrapFirestoreError(error, "Failed to load logs")) }
      }
      .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = UiState.Loading,
      )

  val hasMoreLogs: StateFlow<Boolean> =
    combine(logsState, logsLimitFlow) { state, limit ->
        val count = (state as? UiState.Success)?.data?.size ?: 0
        count >= limit && limit < MAX_LOGS_LIMIT
      }
      .distinctUntilChanged()
      .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = false,
      )

  val notificationsState: StateFlow<UiState<List<AppNotification>>> =
    repository
      .observeNotifications(currentUserId, limit = 25L)
      .map<List<AppNotification>, UiState<List<AppNotification>>> { UiState.Success(it) }
      .catch { error -> emit(unwrapFirestoreError(error, "Failed to load notifications")) }
      .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = UiState.Loading,
      )

  val dashboardStats: StateFlow<DashboardStats> =
    combine(projectsState, logsState) { projUi, logUi ->
        val projects = (projUi as? UiState.Success)?.data.orEmpty()
        val logs = (logUi as? UiState.Success)?.data?.take(30).orEmpty()
        DashboardStats.compute(projects, logs)
      }
      .distinctUntilChanged()
      .flowOn(Dispatchers.Default)
      .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = DashboardStats(),
      )

  val unreadAlertsCount: StateFlow<Int> =
    notificationsState
      .map { state ->
        (state as? UiState.Success)?.data?.count { !it.readFlag } ?: 0
      }
      .distinctUntilChanged()
      .flowOn(Dispatchers.Default)
      .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = 0,
      )

  private val _checkingProjectIds = MutableStateFlow<Set<String>>(emptySet())
  val checkingProjectIds: StateFlow<Set<String>> = _checkingProjectIds.asStateFlow()

  private val _isRefreshingDashboard = MutableStateFlow(false)
  val isRefreshingDashboard: StateFlow<Boolean> = _isRefreshingDashboard.asStateFlow()

  private val _isSubmittingProject = MutableStateFlow(false)
  val isSubmittingProject: StateFlow<Boolean> = _isSubmittingProject.asStateFlow()

  private val _feedback = MutableStateFlow<ActionFeedback?>(null)
  val feedback: StateFlow<ActionFeedback?> = _feedback.asStateFlow()

  private var refreshJob: Job? = null

  init {
    viewModelScope.launch {
      repository.ensureUserProfile(currentUserId, userEmail, userDisplayName)
    }
  }

  fun loadMoreLogs() {
    logsLimitFlow.update { current -> (current + LOGS_PAGE_SIZE).coerceAtMost(MAX_LOGS_LIMIT) }
  }

  fun clearFeedback() {
    _feedback.value = null
  }

  fun registerProject(
    projectName: String,
    projectUrl: String,
    publishableKey: String,
    onSuccess: () -> Unit,
    onError: (String) -> Unit,
  ) {
    viewModelScope.launch {
      _isSubmittingProject.value = true
      val result =
        repository.registerProject(
          userId = currentUserId,
          projectName = projectName,
          rawUrl = projectUrl,
          rawPublishableKey = publishableKey,
          runInitialCheck = true,
        )
      _isSubmittingProject.value = false
      result.fold(
        onSuccess = { project ->
          _feedback.value =
            ActionFeedback(
              message = "${project.projectName} registered (${project.statusEnum.displayLabel}).",
              isError = false,
            )
          onSuccess()
        },
        onFailure = { error ->
          onError(error.localizedMessage ?: "Failed to register project.")
        },
      )
    }
  }

  fun updateProject(
    project: SupabaseProject,
    newName: String,
    newUrl: String,
    newKeyOptional: String?,
    onSuccess: () -> Unit,
    onError: (String) -> Unit,
  ) {
    viewModelScope.launch {
      _isSubmittingProject.value = true
      val result =
        repository.updateProjectCredentials(
          userId = currentUserId,
          project = project,
          newName = newName,
          newUrl = newUrl,
          newKeyOptional = newKeyOptional,
        )
      _isSubmittingProject.value = false
      result.fold(
        onSuccess = {
          _feedback.value = ActionFeedback("Updated ${newName.trim()}.")
          onSuccess()
        },
        onFailure = { error ->
          onError(error.localizedMessage ?: "Failed to update project.")
        },
      )
    }
  }

  fun runCheck(project: SupabaseProject, checkType: String = "manual") {
    if (_checkingProjectIds.value.contains(project.id)) return
    viewModelScope.launch {
      _checkingProjectIds.update { it + project.id }
      val result = repository.runHealthCheck(currentUserId, project, checkType = checkType)
      _checkingProjectIds.update { it - project.id }
      result.fold(
        onSuccess = { (_, outcome) ->
          _feedback.value =
            ActionFeedback(
              message =
                if (outcome.success) {
                  "${project.projectName}: ${outcome.resultingStatus.displayLabel} (${outcome.responseTimeMs}ms)"
                } else {
                  "${project.projectName}: ${outcome.resultingStatus.displayLabel} — ${outcome.errorMessage}"
                },
              isError = !outcome.success,
            )
        },
        onFailure = { error ->
          _feedback.value =
            ActionFeedback(error.localizedMessage ?: "Health check failed.", isError = true)
        },
      )
    }
  }

  fun runDueChecksNow() {
    viewModelScope.launch {
      val count = repository.runAllDueScheduledChecks(currentUserId)
      _feedback.value =
        ActionFeedback(
          if (count == 0) "All active projects are ahead of their 12h schedule."
          else "Executed $count due scheduled health check(s)."
        )
    }
  }

  fun refreshDashboardHealthChecks() {
    if (_isRefreshingDashboard.value) return
    val projects = (projectsState.value as? UiState.Success)?.data ?: return
    if (projects.isEmpty()) {
      _feedback.value = ActionFeedback("Add a Supabase project first to run a health check.")
      return
    }
    val targets = projects.filter { it.monitoringEnabled }.ifEmpty { projects }

    refreshJob?.cancel()
    refreshJob =
      viewModelScope.launch {
        _isRefreshingDashboard.value = true
      try {
        val targetIds = targets.map { it.id }.toSet()
        _checkingProjectIds.update { it + targetIds }

        val results = coroutineScope {
          targets
            .map { project ->
              async {
                try {
                  repository.runHealthCheck(currentUserId, project, checkType = "manual")
                } finally {
                  _checkingProjectIds.update { it - project.id }
                }
              }
            }
            .awaitAll()
        }

        if (results.size == 1) {
          val singleResult = results.first()
          val project = targets.first()
          singleResult.fold(
            onSuccess = { (_, outcome) ->
              _feedback.value =
                ActionFeedback(
                  message =
                    if (outcome.success) {
                      "${project.projectName}: ${outcome.resultingStatus.displayLabel} (${outcome.responseTimeMs}ms)"
                    } else {
                      "${project.projectName}: ${outcome.resultingStatus.displayLabel} — ${outcome.errorMessage}"
                    },
                  isError = !outcome.success,
                )
            },
            onFailure = { error ->
              _feedback.value =
                ActionFeedback(error.localizedMessage ?: "Health check failed.", isError = true)
            },
          )
        } else {
          val healthyCount = results.count { it.getOrNull()?.second?.success == true }
          val failedCount = results.size - healthyCount
          _feedback.value =
            ActionFeedback(
              message =
                if (failedCount == 0) {
                  "Checked ${results.size} projects: all healthy."
                } else {
                  "Checked ${results.size} projects: $healthyCount healthy, $failedCount issue(s)."
                },
              isError = failedCount > 0,
            )
        }
      } finally {
        _isRefreshingDashboard.value = false
      }
    }
  }

  fun toggleMonitoring(project: SupabaseProject, enabled: Boolean) {
    if (_checkingProjectIds.value.contains(project.id)) return
    viewModelScope.launch {
      _checkingProjectIds.update { it + project.id }
      val result = repository.toggleMonitoring(currentUserId, project, enabled)
      _checkingProjectIds.update { it - project.id }
      result.fold(
        onSuccess = {
          _feedback.value =
            ActionFeedback(
              if (enabled) "Monitoring enabled for ${project.projectName}."
              else "Monitoring paused for ${project.projectName}."
            )
        },
        onFailure = { error ->
          _feedback.value =
            ActionFeedback(error.localizedMessage ?: "Unable to update monitoring state.", isError = true)
        },
      )
    }
  }

  fun deleteProject(project: SupabaseProject, onDeleted: () -> Unit = {}) {
    viewModelScope.launch {
      val result = repository.deleteProject(currentUserId, project.id, project.projectName)
      result.fold(
        onSuccess = {
          _feedback.value = ActionFeedback("Removed ${project.projectName} and deleted credentials.")
          onDeleted()
        },
        onFailure = { error ->
          _feedback.value =
            ActionFeedback(error.localizedMessage ?: "Failed to delete project.", isError = true)
        },
      )
    }
  }

  fun markNotificationRead(notificationId: String) {
    viewModelScope.launch {
      repository.markNotificationRead(currentUserId, notificationId)
    }
  }

  fun markAllNotificationsRead() {
    val unreadIds =
      (notificationsState.value as? UiState.Success)?.data
        ?.filter { !it.readFlag }
        ?.map { it.id }
        .orEmpty()
    if (unreadIds.isEmpty()) return
    viewModelScope.launch {
      repository.markAllNotificationsRead(currentUserId, unreadIds)
    }
  }

  fun updatePreferences(
    fullName: String,
    emailAlertsEnabled: Boolean,
    inAppAlertsEnabled: Boolean,
    failureThreshold: Long,
  ) {
    viewModelScope.launch {
      val result =
        repository.updateNotificationPreferences(
          userId = currentUserId,
          fullName = fullName,
          emailAlertsEnabled = emailAlertsEnabled,
          inAppAlertsEnabled = inAppAlertsEnabled,
          failureThreshold = failureThreshold,
        )
      result.fold(
        onSuccess = { _feedback.value = ActionFeedback("Preferences saved.") },
        onFailure = { error ->
          _feedback.value =
            ActionFeedback(error.localizedMessage ?: "Failed to save preferences.", isError = true)
        },
      )
    }
  }

  private fun unwrapFirestoreError(error: Throwable, fallback: String): UiState.Error {
    val firestoreError =
      generateSequence(error) { it.cause }
        .filterIsInstance<FirebaseFirestoreException>()
        .firstOrNull()
    if (firestoreError == null && error is CancellationException) throw error
    val rootError = firestoreError ?: error
    Log.w(TAG, fallback, rootError)
    return UiState.Error(rootError.message ?: fallback)
  }

  override fun onCleared() {
    refreshJob?.cancel()
    super.onCleared()
  }

  private companion object {
    const val TAG = "SupaliveVM"
    const val STOP_TIMEOUT_MS = 5000L
    const val INITIAL_LOGS_LIMIT = 25L
    const val LOGS_PAGE_SIZE = 25L
    const val MAX_LOGS_LIMIT = 200L
  }
}
