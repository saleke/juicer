package com.eqo.reclaim

import com.eqo.pipeline.BitrateMath

/**
 * Pure math for the Batch Compression (Feature 2) and Atomic Storage
 * Reclamation (Feature 3) features. No Android dependencies — unit-testable.
 *
 * The reclaim estimate reuses the production budget math ([BitrateMath]):
 * for a scanned MediaStore item we know only size + duration (no declared
 * track bitrate and no frame-rate column), so we use the size/duration-only
 * branch of `baseBitrate` — budget = actual average bitrate, target = the
 * learned `targetFraction` of it. `estimatedOutputBytes` is then the base
 * bitrate × duration, and `reclaimableBytes` is `size − estimate`.
 *
 * Invariant: reclaimable is monotone non-decreasing in `sizeBytes` for a
 * fixed duration, so `ORDER BY SIZE DESC` in the scanner query equals
 * "sorted by reclaimable bytes" without a client-side re-sort.
 */
object ReclaimMath {

    /** Default target fraction assumed before any Component 4 learning. */
    const val DEFAULT_TARGET_FRACTION = 0.5f

    /** eqo's own outputs are prefixed this way and must never be scanned again. */
    const val EQO_OUTPUT_PREFIX = "eqo_"

    /** Default rollback retention before an entry is auto-purged. */
    const val DEFAULT_ROLLBACK_RETENTION_MS = 7L * 24 * 60 * 60 * 1000 // 7 days

    /** Actual average bitrate = file size × 8 / duration (the C3 "actual"). */
    fun actualBitrateBps(sizeBytes: Long, durationUs: Long): Int {
        if (sizeBytes <= 0 || durationUs <= 0) return 0
        val seconds = durationUs / 1_000_000.0
        return (sizeBytes * 8 / seconds).toInt().coerceIn(0, Int.MAX_VALUE)
    }

    /**
     * Estimated compressed output size. Historically returns the source size
     * when nothing can be derived (unreadable duration/size), i.e. assumes a
     * 100% budget — the honest "no savings shown" outcome.
     */
    fun estimatedOutputBytes(sizeBytes: Long, durationUs: Long, targetFraction: Float = DEFAULT_TARGET_FRACTION): Long {
        val actual = actualBitrateBps(sizeBytes, durationUs)
        if (actual <= 0) return sizeBytes
        val base = BitrateMath.baseBitrate(
            sourceBitrate = 0,
            width = 0,
            height = 0,
            frameRate = 24,
            actualBitrate = actual,
            fraction = targetFraction,
        )
        val seconds = durationUs / 1_000_000.0
        return (base * seconds / 8.0).toLong().coerceIn(0, sizeBytes)
    }

    /** Bytes of device storage the transcode would free on the original. */
    fun reclaimableBytes(sizeBytes: Long, durationUs: Long, targetFraction: Float = DEFAULT_TARGET_FRACTION): Long =
        (sizeBytes - estimatedOutputBytes(sizeBytes, durationUs, targetFraction)).coerceAtLeast(0)

    /** 0..100 fraction of the source freed. */
    fun savedPercent(sizeBytes: Long, reclaimableBytes: Long): Float =
        if (sizeBytes > 0) reclaimableBytes * 100f / sizeBytes else 0f

    /** The scanner's exclusion predicate; also protects re-scans of our outputs. */
    fun isEqoOutput(displayName: String): Boolean = displayName.startsWith(EQO_OUTPUT_PREFIX)

    /**
     * Rollback retention: an entry older than `retentionMs` is eligible for
     * automatic purge. Default [DEFAULT_ROLLBACK_RETENTION_MS].
     */
    fun shouldAutoPurge(
        backedUpAtMs: Long,
        nowMs: Long,
        retentionMs: Long = DEFAULT_ROLLBACK_RETENTION_MS,
    ): Boolean = nowMs - backedUpAtMs >= retentionMs
}