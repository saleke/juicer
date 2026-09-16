package com.eqo.feedback

/**
 * Tracked user action indicating behavioral satisfaction with the compressed output
 * (Component 4, component_four.md §1).
 */
enum class UserAction {
    /** User saved the video and shared it to an external app (highest satisfaction). */
    SAVED_AND_SHARED,

    /** User retained the compressed video in the local gallery. */
    KEPT_OFFLINE,

    /** User discarded/deleted the compressed video shortly after inspection. */
    PROMPTLY_DELETED,

    /** User aborted or cancelled the transcode in flight. */
    CANCELLED,
}

/**
 * Telemetry and behavioral metrics recorded per compression session (Component 4).
 */
data class CompressionSessionMetrics(
    /** Video category determined by Component 2 classifier (e.g. "clothing/human", "nature"). */
    val videoCategory: String,

    /** Size of source file in bytes. */
    val inputBytes: Long,

    /** Size of compressed output file in bytes. */
    val outputBytes: Long,

    /** Percentage of space saved: `(1 - out/in) * 100`. */
    val savedPercent: Float,

    /** Temperature change of device during compression in degrees Celsius. */
    val thermalDeltaCelsius: Float,

    /**
     * Local SSIM Structural Similarity score (0% to 100%).
     * Null if verification was skipped.
     */
    val visualIntegrityPercent: Float?,

    /** User behavioral signal. */
    val userAction: UserAction,

    /** Epoch timestamp in milliseconds. */
    val timestampMs: Long = System.currentTimeMillis(),
)
