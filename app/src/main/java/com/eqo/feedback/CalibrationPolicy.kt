package com.eqo.feedback

/**
 * Pure policy for Component 4 adaptive calibration (Component 4, component_four.md §2).
 *
 * Translates post-compression telemetry and user behavioral signals into updated
 * [TargetCompressionProfile] weights for subsequent compression runs.
 *
 * Implements mathematical safeguards to prevent false penalties:
 * Deletions of high-integrity footage (SSIM >= 95%) are recognized as content-driven
 * rather than compression failures, protecting the engine from skewed feedback.
 */
object CalibrationPolicy {

    /** SSIM threshold above which visual quality is deemed near-flawless. */
    const val HIGH_QUALITY_SSIM_THRESHOLD = 95.0f

    /** SSIM threshold below which visual degradation is noticeable. */
    const val LOW_QUALITY_SSIM_THRESHOLD = 92.0f

    /** Maximum thermal rise allowable to qualify for an efficiency reward. */
    const val MAX_THERMAL_DELTA_FOR_REWARD = 4.0f

    /** Aggressiveness reward step: reduces bitrate target by 2% on satisfied runs. */
    const val REWARD_STEP = 0.02f

    /** Correction penalty step: increases bitrate target by 4% on quality failures. */
    const val PENALTY_STEP = 0.04f

    /**
     * Computes the updated compression profile for the given session.
     * Pure function: does not mutate [current].
     */
    fun calibrate(
        current: TargetCompressionProfile,
        metrics: CompressionSessionMetrics,
    ): TargetCompressionProfile {
        if (metrics.userAction == UserAction.CANCELLED) {
            return current
        }

        val ssim = metrics.visualIntegrityPercent
        val currentFraction = current.targetFraction

        val newFraction = when (metrics.userAction) {
            UserAction.SAVED_AND_SHARED, UserAction.KEPT_OFFLINE -> {
                // Reward condition: user was satisfied, visual integrity was high, and device stayed cool
                val isHighQuality = ssim != null && ssim >= HIGH_QUALITY_SSIM_THRESHOLD
                val isCool = metrics.thermalDeltaCelsius < MAX_THERMAL_DELTA_FOR_REWARD

                if (isHighQuality && isCool) {
                    (currentFraction - REWARD_STEP)
                        .coerceIn(TargetCompressionProfile.MIN_TARGET_FRACTION, TargetCompressionProfile.MAX_TARGET_FRACTION)
                } else {
                    currentFraction
                }
            }

            UserAction.PROMPTLY_DELETED -> {
                // False-penalty guard: if SSIM was excellent, deletion was content-driven, not a quality defect
                val isHighQuality = ssim != null && ssim >= HIGH_QUALITY_SSIM_THRESHOLD
                if (isHighQuality) {
                    currentFraction // No penalty
                } else {
                    // Penalty condition: video was deleted and had borderline/low quality or unverified SSIM
                    (currentFraction + PENALTY_STEP)
                        .coerceIn(TargetCompressionProfile.MIN_TARGET_FRACTION, TargetCompressionProfile.MAX_TARGET_FRACTION)
                }
            }

            UserAction.CANCELLED -> currentFraction
        }

        return current.copy(
            targetFraction = newFraction,
            sessionCount = current.sessionCount + 1,
            lastUpdatedMs = metrics.timestampMs,
        )
    }
}
