package com.example.network

import java.security.SecureRandom
import kotlin.math.abs

/**
 * Maintains a stable 12-hour monitoring cadence per project with a randomized
 * initial phase offset (0..719 minutes) so load is spread without cumulative drift.
 */
object SchedulerCalculator {
  const val DEFAULT_INTERVAL_HOURS = 12L
  const val TWELVE_HOURS_MS = 12L * 60L * 60L * 1000L
  private const val MAX_PHASE_MINUTES = 720L
  private val random = SecureRandom()

  fun generateInitialPhaseOffsetMinutes(seed: String? = null): Long {
    if (!seed.isNullOrBlank()) {
      return abs(seed.hashCode().toLong()) % MAX_PHASE_MINUTES
    }
    return random.nextInt(MAX_PHASE_MINUTES.toInt()).toLong()
  }

  /**
   * Computes the initial next check timestamp from now + phase offset (within the 12h window).
   */
  fun computeInitialNextCheckEpochMs(
    nowEpochMs: Long,
    phaseOffsetMinutes: Long,
  ): Long {
    val boundedOffset = phaseOffsetMinutes.coerceIn(0L, MAX_PHASE_MINUTES - 1L)
    return nowEpochMs + (boundedOffset * 60_000L)
  }

  /**
   * Advances the scheduled check timestamp by exact multiples of `intervalHours`
   * from the previous schedule anchor so retries or execution delays never cause phase drift.
   */
  fun advanceScheduleWithoutDrift(
    previousScheduledEpochMs: Long,
    nowEpochMs: Long,
    intervalHours: Long = DEFAULT_INTERVAL_HOURS,
  ): Long {
    val stepMs = intervalHours.coerceAtLeast(1L) * 3_600_000L
    if (previousScheduledEpochMs <= 0L) {
      return nowEpochMs + stepMs
    }
    var next = previousScheduledEpochMs
    if (next > nowEpochMs) {
      return next
    }
    val elapsed = nowEpochMs - previousScheduledEpochMs
    val steps = (elapsed / stepMs) + 1L
    next = previousScheduledEpochMs + (steps * stepMs)
    return next
  }
}
