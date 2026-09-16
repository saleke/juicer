package com.eqo.feedback

/**
 * On-device learned compression profile for a specific video category (Component 4).
 *
 * Dynamically tuned over time based on user feedback (retention vs deletion)
 * and hardware telemetry.
 */
data class TargetCompressionProfile(
    /** The category this profile applies to (e.g. "clothing/human", "unknown"). */
    val category: String,

    /**
     * Learned target fraction of the source bitrate (default 0.50f = 50%).
     * Clamped to [MIN_TARGET_FRACTION, MAX_TARGET_FRACTION].
     */
    val targetFraction: Float = DEFAULT_TARGET_FRACTION,

    /** Number of completed sessions recorded for this category. */
    val sessionCount: Int = 0,

    /** Epoch timestamp of the last calibration update. */
    val lastUpdatedMs: Long = System.currentTimeMillis(),
) {
    companion object {
        const val DEFAULT_TARGET_FRACTION = 0.50f
        const val MIN_TARGET_FRACTION = 0.30f
        const val MAX_TARGET_FRACTION = 0.80f
    }
}
