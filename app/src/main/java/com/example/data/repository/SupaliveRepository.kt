package com.example.data.repository

import android.content.Context
import com.example.R
import com.example.data.model.AppNotification
import com.example.data.model.AuditLog
import com.example.data.model.HealthStatus
import com.example.data.model.MonitoringLog
import com.example.data.model.SupabaseProject
import com.example.data.model.UserProfile
import com.example.network.HealthCheckOutcome
import com.example.network.SchedulerCalculator
import com.example.network.SupabaseHealthClient
import com.example.security.KeyValidationResult
import com.example.security.LogSanitizer
import com.example.security.PublishableKeyValidator
import com.example.security.ServerKeyEncryptor
import com.example.security.SupabaseUrlValidator
import com.example.security.SupaliveKeyManager
import com.example.security.UrlValidationResult
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import java.util.UUID

class SupaliveRepository(
  private val db: FirebaseFirestore,
  private val auth: FirebaseAuth = Firebase.auth,
  private val keyManager: SupaliveKeyManager = SupaliveKeyManager(),
  private val healthClient: SupabaseHealthClient = SupabaseHealthClient(),
) {
  constructor(context: Context) : this(
    db = FirebaseFirestore.getInstance(
      context.applicationContext.getString(R.string.firestore_database_id)
    )
  )

  @Volatile
  private var cachedProfile: UserProfile? = null

  private fun requireMatchingUserId(expectedUserId: String): String {
    val uid = auth.currentUser?.uid
      ?: throw IllegalStateException("User must be signed in with Google before accessing Firestore.")
    check(uid == expectedUserId) { "Authenticated user changed; refusing cross-user operation." }
    return uid
  }

  private fun userDoc(uid: String) = db.collection("users").document(uid)

  fun observeUserProfile(userId: String): Flow<UserProfile?> = flow {
    val uid = requireMatchingUserId(userId)
    val path = "users/$uid"
    emitAll(
      userDoc(uid)
        .snapshots()
        .map { snapshot ->
          if (!snapshot.exists()) {
            cachedProfile = null
            null
          } else {
            snapshot.toObject(UserProfile::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
              ?.also { cachedProfile = it }
          }
        }
        .catch { error ->
          logFlowError(error, OperationType.GET, path, uid)
          throw error
        }
    )
  }.flowOn(Dispatchers.Default)

  fun observeProjects(userId: String): Flow<List<SupabaseProject>> = flow {
    val uid = requireMatchingUserId(userId)
    val path = "users/$uid/projects"
    emitAll(
      userDoc(uid)
        .collection("projects")
        .orderBy("createdAt", Query.Direction.DESCENDING)
        .snapshots()
        .map { snapshot ->
          snapshot.toObjects(SupabaseProject::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
        }
        .catch { error ->
          logFlowError(error, OperationType.LIST, path, uid)
          throw error
        }
    )
  }.flowOn(Dispatchers.Default)

  fun observeLogs(userId: String, limit: Long = 100L): Flow<List<MonitoringLog>> = flow {
    val uid = requireMatchingUserId(userId)
    val path = "users/$uid/logs"
    emitAll(
      userDoc(uid)
        .collection("logs")
        .orderBy("checkedAtEpochMs", Query.Direction.DESCENDING)
        .limit(limit)
        .snapshots()
        .map { snapshot ->
          snapshot.toObjects(MonitoringLog::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
        }
        .catch { error ->
          logFlowError(error, OperationType.LIST, path, uid)
          throw error
        }
    )
  }.flowOn(Dispatchers.Default)

  fun observeNotifications(userId: String, limit: Long = 50L): Flow<List<AppNotification>> = flow {
    val uid = requireMatchingUserId(userId)
    val path = "users/$uid/notifications"
    emitAll(
      userDoc(uid)
        .collection("notifications")
        .orderBy("createdAtEpochMs", Query.Direction.DESCENDING)
        .limit(limit)
        .snapshots()
        .map { snapshot ->
          snapshot.toObjects(AppNotification::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
        }
        .catch { error ->
          logFlowError(error, OperationType.LIST, path, uid)
          throw error
        }
    )
  }.flowOn(Dispatchers.Default)

  fun observeAuditLogs(userId: String, limit: Long = 40L): Flow<List<AuditLog>> = flow {
    val uid = requireMatchingUserId(userId)
    val path = "users/$uid/audit_logs"
    emitAll(
      userDoc(uid)
        .collection("audit_logs")
        .orderBy("createdAtEpochMs", Query.Direction.DESCENDING)
        .limit(limit)
        .snapshots()
        .map { snapshot ->
          snapshot.toObjects(AuditLog::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
        }
        .catch { error ->
          logFlowError(error, OperationType.LIST, path, uid)
          throw error
        }
    )
  }.flowOn(Dispatchers.Default)

  suspend fun ensureUserProfile(userId: String, email: String?, displayName: String?): Result<Unit> {
    val path = "users/$userId"
    return try {
      val uid = requireMatchingUserId(userId)
      val docRef = userDoc(uid)
      val snap = docRef.get().await()
      if (!snap.exists()) {
        val safeName = displayName?.trim()?.take(120)?.ifEmpty { "Supalive Developer" } ?: "Supalive Developer"
        val safeEmail = email?.trim()?.take(200) ?: ""
        docRef.set(
          mapOf(
            "userId" to uid,
            "email" to safeEmail,
            "fullName" to safeName,
            "emailAlertsEnabled" to false,
            "inAppAlertsEnabled" to true,
            "failureThreshold" to 2L,
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp(),
          )
        ).await()
        cachedProfile =
          UserProfile(
            id = uid,
            userId = uid,
            email = safeEmail,
            fullName = safeName,
            emailAlertsEnabled = false,
            inAppAlertsEnabled = true,
            failureThreshold = 2L,
          )
      } else {
        snap.toObject(UserProfile::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
          ?.let { cachedProfile = it }
      }
      Result.success(Unit)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.WRITE, path)
      Result.failure(e)
    }
  }

  suspend fun updateNotificationPreferences(
    userId: String,
    fullName: String,
    emailAlertsEnabled: Boolean,
    inAppAlertsEnabled: Boolean,
    failureThreshold: Long,
  ): Result<Unit> {
    val path = "users/$userId"
    return try {
      val uid = requireMatchingUserId(userId)
      val safeName = fullName.trim().take(120).ifEmpty { "Supalive Developer" }
      val boundedThreshold = failureThreshold.coerceIn(1L, 10L)
      userDoc(uid).update(
        mapOf(
          "fullName" to safeName,
          "emailAlertsEnabled" to emailAlertsEnabled,
          "inAppAlertsEnabled" to inAppAlertsEnabled,
          "failureThreshold" to boundedThreshold,
          "updatedAt" to FieldValue.serverTimestamp(),
        )
      ).await()
      cachedProfile =
        (cachedProfile ?: UserProfile(id = uid, userId = uid)).copy(
          fullName = safeName,
          emailAlertsEnabled = emailAlertsEnabled,
          inAppAlertsEnabled = inAppAlertsEnabled,
          failureThreshold = boundedThreshold,
        )
      writeAuditLog(
        uid = uid,
        action = "preferences_updated",
        resourceType = "preferences",
        resourceId = uid,
        summary = "Updated alert threshold ($boundedThreshold) and notification preferences",
      )
      Result.success(Unit)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.UPDATE, path)
      Result.failure(e)
    }
  }

  suspend fun registerProject(
    userId: String,
    projectName: String,
    rawUrl: String,
    rawPublishableKey: String,
    runInitialCheck: Boolean = true,
  ): Result<SupabaseProject> {
    val trimmedName = projectName.trim()
    if (trimmedName.isEmpty() || trimmedName.length > 80) {
      return Result.failure(IllegalArgumentException("Project name must be 1–80 characters."))
    }

    val urlResult = SupabaseUrlValidator.validateFormat(rawUrl)
    if (urlResult is UrlValidationResult.Invalid) {
      return Result.failure(IllegalArgumentException(urlResult.reason))
    }
    val validUrl = (urlResult as UrlValidationResult.Valid).url

    val keyResult = PublishableKeyValidator.validate(rawPublishableKey)
    if (keyResult is KeyValidationResult.Invalid) {
      return Result.failure(IllegalArgumentException(keyResult.reason))
    }
    val validKey = keyResult as KeyValidationResult.Valid

    val path = "users/$userId/projects"
    return try {
      val uid = requireMatchingUserId(userId)
      val projectId = "proj_${UUID.randomUUID().toString().replace("-", "").take(16)}"
      val (encryptedKey, keyVersion) = keyManager.encrypt(validKey.key)
      val serverEncryptedKey = ServerKeyEncryptor.encrypt(validKey.key)
      val now = System.currentTimeMillis()
      val phaseOffset = SchedulerCalculator.generateInitialPhaseOffsetMinutes(validUrl.projectRef)
      val nextCheck = SchedulerCalculator.computeInitialNextCheckEpochMs(now, phaseOffset)

      val docRef = userDoc(uid).collection("projects").document(projectId)
      docRef.set(
        mapOf(
          "userId" to uid,
          "projectName" to trimmedName,
          "projectUrl" to validUrl.normalizedUrl,
          "projectRef" to validUrl.projectRef,
          "maskedKey" to validKey.maskedPreview,
          "publishableKeyEncrypted" to encryptedKey,
          "publishableKeyServerEncrypted" to serverEncryptedKey,
          "keyVersion" to keyVersion.toLong(),
          "healthStatus" to HealthStatus.PENDING.firestoreValue,
          "monitoringEnabled" to true,
          "intervalHours" to SchedulerCalculator.DEFAULT_INTERVAL_HOURS,
          "phaseOffsetMinutes" to phaseOffset,
          "nextCheckEpochMs" to nextCheck,
          "lastCheckedEpochMs" to 0L,
          "lastSuccessEpochMs" to 0L,
          "lastLatencyMs" to 0L,
          "consecutiveFailures" to 0L,
          "lastSanitizedError" to "",
          "createdAt" to FieldValue.serverTimestamp(),
          "updatedAt" to FieldValue.serverTimestamp(),
        )
      ).await()

      writeAuditLog(
        uid = uid,
        action = "project_created",
        resourceType = "project",
        resourceId = projectId,
        summary = "Registered $trimmedName (${validUrl.projectRef})",
      )

      var savedProject =
        SupabaseProject(
          id = projectId,
          userId = uid,
          projectName = trimmedName,
          projectUrl = validUrl.normalizedUrl,
          projectRef = validUrl.projectRef,
          maskedKey = validKey.maskedPreview,
          publishableKeyEncrypted = encryptedKey,
          publishableKeyServerEncrypted = serverEncryptedKey,
          keyVersion = keyVersion.toLong(),
          healthStatus = HealthStatus.PENDING.firestoreValue,
          monitoringEnabled = true,
          intervalHours = SchedulerCalculator.DEFAULT_INTERVAL_HOURS,
          phaseOffsetMinutes = phaseOffset,
          nextCheckEpochMs = nextCheck,
        )

      if (runInitialCheck) {
        val checked = runHealthCheck(uid, savedProject, checkType = "validation")
        if (checked.isSuccess) {
          savedProject = checked.getOrNull()?.first ?: savedProject
        }
      }

      Result.success(savedProject)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.CREATE, path)
      Result.failure(e)
    }
  }

  suspend fun updateProjectCredentials(
    userId: String,
    project: SupabaseProject,
    newName: String,
    newUrl: String,
    newKeyOptional: String?,
  ): Result<Unit> {
    val trimmedName = newName.trim()
    if (trimmedName.isEmpty() || trimmedName.length > 80) {
      return Result.failure(IllegalArgumentException("Project name must be 1–80 characters."))
    }
    val urlResult = SupabaseUrlValidator.validateFormat(newUrl)
    if (urlResult is UrlValidationResult.Invalid) {
      return Result.failure(IllegalArgumentException(urlResult.reason))
    }
    val validUrl = (urlResult as UrlValidationResult.Valid).url

    var encrypted = project.publishableKeyEncrypted
    var serverEncrypted = project.publishableKeyServerEncrypted
    var masked = project.maskedKey
    var keyVer = project.keyVersion

    if (!newKeyOptional.isNullOrBlank()) {
      val keyResult = PublishableKeyValidator.validate(newKeyOptional)
      if (keyResult is KeyValidationResult.Invalid) {
        return Result.failure(IllegalArgumentException(keyResult.reason))
      }
      val validKey = keyResult as KeyValidationResult.Valid
      val pair = keyManager.encrypt(validKey.key)
      encrypted = pair.first
      keyVer = pair.second.toLong()
      serverEncrypted = ServerKeyEncryptor.encrypt(validKey.key)
      masked = validKey.maskedPreview
    }

    val path = "users/$userId/projects/${project.id}"
    return try {
      val uid = requireMatchingUserId(userId)
      userDoc(uid).collection("projects").document(project.id).update(
        mapOf(
          "projectName" to trimmedName,
          "projectUrl" to validUrl.normalizedUrl,
          "projectRef" to validUrl.projectRef,
          "maskedKey" to masked,
          "publishableKeyEncrypted" to encrypted,
          "publishableKeyServerEncrypted" to serverEncrypted,
          "keyVersion" to keyVer,
          "lastSanitizedError" to "",
          "updatedAt" to FieldValue.serverTimestamp(),
        )
      ).await()

      writeAuditLog(
        uid = uid,
        action = "project_updated",
        resourceType = "project",
        resourceId = project.id,
        summary = "Updated configuration for $trimmedName (${validUrl.projectRef})",
      )
      Result.success(Unit)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.UPDATE, path)
      Result.failure(e)
    }
  }

  suspend fun toggleMonitoring(
    userId: String,
    project: SupabaseProject,
    enabled: Boolean,
  ): Result<Unit> {
    val path = "users/$userId/projects/${project.id}"
    return try {
      val uid = requireMatchingUserId(userId)
      val now = System.currentTimeMillis()
      val nextStatus =
        if (enabled) HealthStatus.PENDING.firestoreValue
        else HealthStatus.MONITORING_DISABLED.firestoreValue
      val nextCheck =
        if (enabled) {
          SchedulerCalculator.computeInitialNextCheckEpochMs(now, project.phaseOffsetMinutes)
        } else {
          project.nextCheckEpochMs
        }

      userDoc(uid).collection("projects").document(project.id).update(
        mapOf(
          "monitoringEnabled" to enabled,
          "healthStatus" to nextStatus,
          "nextCheckEpochMs" to nextCheck,
          "updatedAt" to FieldValue.serverTimestamp(),
        )
      ).await()

      writeAuditLog(
        uid = uid,
        action = if (enabled) "monitoring_enabled" else "monitoring_disabled",
        resourceType = "project",
        resourceId = project.id,
        summary = "${if (enabled) "Enabled" else "Paused"} 12h monitoring for ${project.projectName}",
      )

      if (enabled) {
        val updated = project.copy(monitoringEnabled = true, healthStatus = nextStatus, nextCheckEpochMs = nextCheck)
        runHealthCheck(uid, updated, checkType = "validation")
      }
      Result.success(Unit)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.UPDATE, path)
      Result.failure(e)
    }
  }

  suspend fun runHealthCheck(
    userId: String,
    project: SupabaseProject,
    checkType: String = "manual",
  ): Result<Pair<SupabaseProject, HealthCheckOutcome>> {
    val path = "users/$userId/projects/${project.id}"
    return try {
      val uid = requireMatchingUserId(userId)
      val decryptedResult = keyManager.decrypt(project.publishableKeyEncrypted)
      val now = System.currentTimeMillis()

      val outcome =
        if (decryptedResult.isFailure) {
          HealthCheckOutcome(
            success = false,
            resultingStatus = HealthStatus.CREDENTIAL_ERROR,
            httpStatus = 0,
            responseTimeMs = 0L,
            retryCount = 0,
            errorCode = "KEY_DECRYPT_FAILED",
            errorMessage = "Encrypted credential could not be decrypted on this device. Re-enter Publishable Key.",
          )
        } else {
          healthClient.checkHealth(
            projectUrl = project.projectUrl,
            publishableKey = decryptedResult.getOrThrow(),
            previousConsecutiveFailures = project.consecutiveFailures,
          )
        }

      val newFailures = if (outcome.success) 0L else (project.consecutiveFailures + 1L).coerceAtMost(10000L)
      val newSuccessAt = if (outcome.success) now else project.lastSuccessEpochMs
      val newStatus =
        if (!project.monitoringEnabled && checkType == "scheduled") {
          HealthStatus.MONITORING_DISABLED
        } else {
          outcome.resultingStatus
        }

      // Preserve the regular 12h cadence without drift on scheduled checks; manual checks keep current phase
      val nextScheduledAt =
        if (checkType == "scheduled") {
          SchedulerCalculator.advanceScheduleWithoutDrift(
            previousScheduledEpochMs = project.nextCheckEpochMs,
            nowEpochMs = now,
            intervalHours = project.intervalHours,
          )
        } else if (project.nextCheckEpochMs <= 0L) {
          SchedulerCalculator.computeInitialNextCheckEpochMs(now, project.phaseOffsetMinutes)
        } else {
          project.nextCheckEpochMs
        }

      val sanitizedError = LogSanitizer.sanitize(outcome.errorMessage, maxLength = 480)

      val logId = "log_${UUID.randomUUID().toString().replace("-", "").take(18)}"
      val batch = db.batch()
      batch.update(
        userDoc(uid).collection("projects").document(project.id),
        mapOf(
          "healthStatus" to newStatus.firestoreValue,
          "nextCheckEpochMs" to nextScheduledAt,
          "lastCheckedEpochMs" to now,
          "lastSuccessEpochMs" to newSuccessAt,
          "lastLatencyMs" to outcome.responseTimeMs.coerceIn(0L, 60_000L),
          "consecutiveFailures" to newFailures,
          "lastSanitizedError" to sanitizedError,
          "updatedAt" to FieldValue.serverTimestamp(),
        ),
      )
      batch.set(
        userDoc(uid).collection("logs").document(logId),
        mapOf(
          "userId" to uid,
          "projectId" to project.id,
          "projectName" to project.projectName,
          "checkType" to checkType,
          "result" to if (outcome.success) "success" else "failure",
          "resultingStatus" to newStatus.firestoreValue,
          "httpStatus" to outcome.httpStatus.toLong().coerceIn(0L, 599L),
          "responseTimeMs" to outcome.responseTimeMs.coerceIn(0L, 60_000L),
          "errorCode" to outcome.errorCode.take(64),
          "errorMessage" to sanitizedError,
          "retryCount" to outcome.retryCount.toLong().coerceIn(0L, 5L),
          "checkedAtEpochMs" to now,
          "createdAt" to FieldValue.serverTimestamp(),
        ),
      )

      prepareAlertNotification(uid, project, outcome, newFailures, now)?.let { (notifId, payload) ->
        batch.set(userDoc(uid).collection("notifications").document(notifId), payload)
      }

      if (checkType == "manual") {
        val auditId = "audit_${UUID.randomUUID().toString().replace("-", "").take(18)}"
        batch.set(
          userDoc(uid).collection("audit_logs").document(auditId),
          mapOf(
            "userId" to uid,
            "action" to "manual_check",
            "resourceType" to "project",
            "resourceId" to project.id,
            "summary" to
              LogSanitizer.sanitize(
                "Manual check on ${project.projectName}: ${newStatus.displayLabel} (${outcome.responseTimeMs}ms)",
                240,
              ),
            "createdAtEpochMs" to System.currentTimeMillis(),
            "createdAt" to FieldValue.serverTimestamp(),
          ),
        )
      }

      batch.commit().await()

      val updatedProject =
        project.copy(
          healthStatus = newStatus.firestoreValue,
          nextCheckEpochMs = nextScheduledAt,
          lastCheckedEpochMs = now,
          lastSuccessEpochMs = newSuccessAt,
          lastLatencyMs = outcome.responseTimeMs,
          consecutiveFailures = newFailures,
          lastSanitizedError = sanitizedError,
        )
      Result.success(updatedProject to outcome)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.UPDATE, path)
      Result.failure(e)
    }
  }

  private suspend fun prepareAlertNotification(
    uid: String,
    project: SupabaseProject,
    outcome: HealthCheckOutcome,
    newFailures: Long,
    now: Long,
  ): Pair<String, Map<String, Any>>? {
    val cached = cachedProfile?.takeIf { it.userId == uid }
    val (inAppEnabled, threshold) =
      if (cached != null) {
        cached.inAppAlertsEnabled to cached.failureThreshold.coerceIn(1L, 10L)
      } else {
        val profileSnap = try {
          userDoc(uid).get().await()
        } catch (_: Exception) {
          null
        }
        profileSnap?.toObject(UserProfile::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
          ?.let { cachedProfile = it }
        val enabled = profileSnap?.getBoolean("inAppAlertsEnabled") ?: true
        val thresh = (profileSnap?.getLong("failureThreshold") ?: 2L).coerceIn(1L, 10L)
        enabled to thresh
      }
    if (!inAppEnabled) return null

    val (notifType, title, msg) =
      when {
        outcome.success && project.consecutiveFailures >= threshold ->
          Triple(
            "recovered",
            "${project.projectName} recovered",
            "Database health check succeeded (HTTP ${outcome.httpStatus}, ${outcome.responseTimeMs}ms).",
          )
        !outcome.success && newFailures >= threshold -> {
          val type =
            when (outcome.resultingStatus) {
              HealthStatus.CREDENTIAL_ERROR -> "credential_error"
              HealthStatus.CONFIGURATION_ERROR -> "configuration_error"
              HealthStatus.SUSPECTED_PAUSED -> "suspected_paused"
              else -> "health_alert"
            }
          val headline =
            when (outcome.resultingStatus) {
              HealthStatus.SUSPECTED_PAUSED -> "${project.projectName} suspected paused"
              HealthStatus.CREDENTIAL_ERROR -> "Credential error on ${project.projectName}"
              HealthStatus.CONFIGURATION_ERROR -> "Setup issue on ${project.projectName}"
              else -> "Health check failed for ${project.projectName}"
            }
          Triple(
            type,
            headline,
            LogSanitizer.sanitize(outcome.errorMessage.ifBlank { "Check failed (${outcome.resultingStatus.displayLabel})" }, 480),
          )
        }
        else -> return null
      }

    val notifId = "notif_${UUID.randomUUID().toString().replace("-", "").take(18)}"
    val payload =
      mapOf(
        "userId" to uid,
        "projectId" to project.id,
        "projectName" to project.projectName,
        "notificationType" to notifType,
        "title" to title.take(120),
        "message" to msg.take(500),
        "readFlag" to false,
        "createdAtEpochMs" to now,
        "createdAt" to FieldValue.serverTimestamp(),
      )
    return notifId to payload
  }

  suspend fun deleteProject(userId: String, projectId: String, projectName: String): Result<Unit> {
    val path = "users/$userId/projects/$projectId"
    return try {
      val uid = requireMatchingUserId(userId)
      userDoc(uid).collection("projects").document(projectId).delete().await()
      writeAuditLog(
        uid = uid,
        action = "project_deleted",
        resourceType = "project",
        resourceId = projectId,
        summary = "Deleted project $projectName and removed encrypted credentials",
      )
      Result.success(Unit)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.DELETE, path)
      Result.failure(e)
    }
  }

  suspend fun markNotificationRead(userId: String, notificationId: String): Result<Unit> {
    val path = "users/$userId/notifications/$notificationId"
    return try {
      val uid = requireMatchingUserId(userId)
      userDoc(uid).collection("notifications").document(notificationId)
        .update(mapOf("readFlag" to true)).await()
      Result.success(Unit)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.UPDATE, path)
      Result.failure(e)
    }
  }

  suspend fun markAllNotificationsRead(userId: String, unreadIds: List<String>): Result<Unit> {
    val path = "users/$userId/notifications"
    return try {
      val uid = requireMatchingUserId(userId)
      if (unreadIds.isEmpty()) return Result.success(Unit)
      val batch = db.batch()
      unreadIds.forEach { id ->
        val ref = userDoc(uid).collection("notifications").document(id)
        batch.update(ref, mapOf("readFlag" to true))
      }
      batch.commit().await()
      Result.success(Unit)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      handleFirestoreError(e, OperationType.UPDATE, path)
      Result.failure(e)
    }
  }

  suspend fun runAllDueScheduledChecks(userId: String): Int {
    val uid = requireMatchingUserId(userId)
    val now = System.currentTimeMillis()
    val snapshot = userDoc(uid).collection("projects").get().await()
    val projects = snapshot.toObjects(SupabaseProject::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
    var executed = 0
    for (project in projects) {
      if (project.monitoringEnabled && project.nextCheckEpochMs <= now) {
        runHealthCheck(uid, project, checkType = "scheduled")
        executed++
      }
    }
    return executed
  }

  private suspend fun writeAuditLog(
    uid: String,
    action: String,
    resourceType: String,
    resourceId: String,
    summary: String,
  ) {
    val auditId = "audit_${UUID.randomUUID().toString().replace("-", "").take(18)}"
    userDoc(uid).collection("audit_logs").document(auditId).set(
      mapOf(
        "userId" to uid,
        "action" to action.take(64),
        "resourceType" to resourceType,
        "resourceId" to resourceId.take(128),
        "summary" to LogSanitizer.sanitize(summary, 240),
        "createdAtEpochMs" to System.currentTimeMillis(),
        "createdAt" to FieldValue.serverTimestamp(),
      )
    ).await()
  }

  private fun logFlowError(error: Throwable, op: OperationType, path: String, expectedUid: String) {
    val firestoreError =
      generateSequence(error) { it.cause }
        .filterIsInstance<FirebaseFirestoreException>()
        .firstOrNull()
    if (firestoreError == null && error is CancellationException) return
    val ex = firestoreError ?: (error as? Exception)
    if (ex != null && auth.currentUser?.uid == expectedUid) {
      handleFirestoreError(ex, op, path)
    }
  }
}
