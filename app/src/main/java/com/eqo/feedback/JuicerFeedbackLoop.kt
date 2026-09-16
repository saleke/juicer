package com.eqo.feedback

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap

/**
 * ## eqo Component 4 — The Local Feedback Loop (The Adaptation Engine)
 *
 * Tracks post-compression user actions and hardware telemetry to dynamically tune
 * compression targets for each video category over time.
 *
 * Fully privacy-preserving: 100% isolated to on-device private storage without any
 * network egress.
 */
class JuicerFeedbackLoop(
    context: Context,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : Closeable {

    companion object {
        private const val TAG = "eqo.Feedback"
        private const val PREFS_NAME = "eqo_feedback_profiles"
        private const val KEY_FRACTION_PREFIX = "target_fraction_"
        private const val KEY_COUNT_PREFIX = "session_count_"
        private const val KEY_UPDATED_PREFIX = "last_updated_"
    }

    private val appContext = context.applicationContext
    private val prefs: SharedPreferences = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // Cache in-memory for instant lookups on the pipeline thread
    private val profileCache = ConcurrentHashMap<String, TargetCompressionProfile>()

    private val _recentSessions = MutableStateFlow<List<CompressionSessionMetrics>>(emptyList())
    val recentSessions: StateFlow<List<CompressionSessionMetrics>> = _recentSessions.asStateFlow()

    /**
     * Look up the learned target fraction for [category] (spec, Component 4 Step C).
     * Synchronous and fast — uses in-memory cache populated from SharedPreferences.
     */
    fun getDynamicConstraintsForCategory(category: String): Float {
        return getProfile(category).targetFraction
    }

    /**
     * Returns the cached or stored [TargetCompressionProfile] for [category].
     */
    fun getProfile(category: String): TargetCompressionProfile {
        return profileCache.computeIfAbsent(category) { loadProfileFromPrefs(category) }
    }

    /**
     * Records a completed compression session and updates the calibration profile
     * asynchronously on the background I/O dispatcher (spec, §3 Execution Constraints).
     */
    fun recordSession(metrics: CompressionSessionMetrics) {
        scope.launch {
            val current = getProfile(metrics.videoCategory)
            val updated = CalibrationPolicy.calibrate(current, metrics)

            profileCache[metrics.videoCategory] = updated
            saveProfileToPrefs(updated)

            _recentSessions.value = (_recentSessions.value + metrics).takeLast(20)

            Log.i(
                TAG,
                "Calibrated [${metrics.videoCategory}]: " +
                    "action=${metrics.userAction} ssim=${metrics.visualIntegrityPercent?.let { "%.1f%%".format(it) } ?: "n/a"} " +
                    "fraction=%.2f → %.2f (sessions=${updated.sessionCount})".format(
                        current.targetFraction,
                        updated.targetFraction,
                    ),
            )
        }
    }

    private fun loadProfileFromPrefs(category: String): TargetCompressionProfile {
        val fraction = prefs.getFloat(
            KEY_FRACTION_PREFIX + category,
            TargetCompressionProfile.DEFAULT_TARGET_FRACTION,
        )
        val count = prefs.getInt(KEY_COUNT_PREFIX + category, 0)
        val updated = prefs.getLong(KEY_UPDATED_PREFIX + category, 0L)

        return TargetCompressionProfile(
            category = category,
            targetFraction = fraction,
            sessionCount = count,
            lastUpdatedMs = updated,
        )
    }

    private fun saveProfileToPrefs(profile: TargetCompressionProfile) {
        prefs.edit()
            .putFloat(KEY_FRACTION_PREFIX + profile.category, profile.targetFraction)
            .putInt(KEY_COUNT_PREFIX + profile.category, profile.sessionCount)
            .putLong(KEY_UPDATED_PREFIX + profile.category, profile.lastUpdatedMs)
            .apply()
    }

    /** Clears all learned profiles (testing / debug). */
    fun resetProfiles() {
        profileCache.clear()
        prefs.edit().clear().apply()
        _recentSessions.value = emptyList()
    }

    override fun close() {
        // No persistent resources to close
    }
}
