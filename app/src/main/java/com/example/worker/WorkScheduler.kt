package com.example.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object WorkScheduler {
  private const val UNIQUE_WORK_NAME = "supalive_12h_health_monitor"

  fun ensure12HourSchedule(context: Context) {
    val constraints =
      Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    val request =
      PeriodicWorkRequestBuilder<HealthCheckWorker>(12L, TimeUnit.HOURS, 15L, TimeUnit.MINUTES)
        .setConstraints(constraints)
        .build()

    WorkManager.getInstance(context.applicationContext)
      .enqueueUniquePeriodicWork(
        UNIQUE_WORK_NAME,
        ExistingPeriodicWorkPolicy.KEEP,
        request,
      )
  }

  fun cancelSchedule(context: Context) {
    WorkManager.getInstance(context.applicationContext).cancelUniqueWork(UNIQUE_WORK_NAME)
  }
}
