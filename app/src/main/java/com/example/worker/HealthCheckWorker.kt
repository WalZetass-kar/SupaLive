package com.example.worker

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.R
import com.example.data.model.SupabaseProject
import com.example.data.repository.SupaliveRepository
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

/**
 * Background WorkManager worker executing 12-hour scheduled health checks
 * for all active monitoring-enabled projects of the signed-in user.
 */
class HealthCheckWorker(
  appContext: Context,
  workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

  override suspend fun doWork(): Result {
    val currentUser = Firebase.auth.currentUser ?: return Result.success()
    val uid = currentUser.uid
    val databaseId = applicationContext.getString(R.string.firestore_database_id)
    val db = FirebaseFirestore.getInstance(databaseId)
    val repository = SupaliveRepository(db = db)

    return try {
      val now = System.currentTimeMillis()
      val snapshot = db.collection("users").document(uid).collection("projects").get().await()
      val projects =
        snapshot.toObjects(
          SupabaseProject::class.java,
          DocumentSnapshot.ServerTimestampBehavior.ESTIMATE,
        )

      for (project in projects) {
        if (!project.monitoringEnabled) continue
        if (project.nextCheckEpochMs <= now) {
          val outcomeResult = repository.runHealthCheck(uid, project, checkType = "scheduled")
          outcomeResult.getOrNull()?.let { (_, outcome) ->
            if (!outcome.success) {
              postFailureNotification(project.projectName, outcome.resultingStatus.displayLabel)
            }
          }
        }
      }
      Result.success()
    } catch (_: Exception) {
      Result.retry()
    }
  }

  private fun postFailureNotification(projectName: String, statusLabel: String) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      val granted =
        ContextCompat.checkSelfPermission(
          applicationContext,
          Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
      if (!granted) return
    }

    ensureNotificationChannel()
    val notification =
      NotificationCompat.Builder(applicationContext, CHANNEL_ID)
        .setSmallIcon(R.mipmap.ic_launcher)
        .setContentTitle("Supalive Alert: $projectName")
        .setContentText("Scheduled health check reported $statusLabel.")
        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        .setAutoCancel(true)
        .build()

    NotificationManagerCompat.from(applicationContext)
      .notify(projectName.hashCode(), notification)
  }

  private fun ensureNotificationChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val manager =
        applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
      val channel =
        NotificationChannel(
          CHANNEL_ID,
          "Database Health Alerts",
          NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
          description = "Alerts when a scheduled 12-hour Supabase health check fails."
        }
      manager.createNotificationChannel(channel)
    }
  }

  private companion object {
    const val CHANNEL_ID = "supalive_health_alerts"
  }
}
