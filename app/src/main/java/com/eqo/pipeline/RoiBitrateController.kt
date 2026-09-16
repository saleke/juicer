package com.eqo.pipeline

/**
 * Pure ROI→bitrate control policy (Component 3, C3 design review F1/F6/P5).
 *
 * `MediaCodec.PARAMETER_KEY_QP_OFFSET_MAP` does not exist in the public API
 * (C3 F1), so the ROI map's aggregate complexity drives the *global* encoder
 * bitrate at runtime instead — the spec's own fallback strategy, promoted to
 * the primary mechanism:
 *
 * - the 16×16 ROI map's mean (0..1) is EMA-smoothed to absorb per-frame
 *   flicker,
 * - the smoothed value maps linearly to a multiplier around the base bitrate
 *   (dull scene → fewer bits, busy/human scene → more),
 * - a new bitrate is only emitted when at least [minAdjustIntervalMs] passed
 *   since the last applied change AND the target moved at least
 *   [minRelativeDelta] — thrashing `setParameters` per frame is useless and
 *   trips hardware rate controllers into overshoot (F6),
 * - **content-aware base budgeting** (component_three.md NEW §1): during the
 *   first [complexityWindowMs] of the run the smoothed ROI mean doubles as a
 *   complexity score; if the content is genuinely low-complexity (a lecture,
 *   a screen recording), the base itself is cut by up to [maxBaseReduction]
 *   once, so the whole run budgets like an 800 kbps encode instead of a
 *   4 Mbps one. The window can't literally pre-scan before encoding (frames
 *   are GPU-resident and unbufferable), so the first window runs at the
 *   source-derived base — a ~2 s prefix at the higher rate, negligible in
 *   size (design decision, C3 §3b).
 *
 * Deliberately free of any Android dependency so it is unit-testable on the
 * JVM, following the [FrameBudgetTracker] pattern.
 *
 * @param baseBitrate        Center of the adjustment range, bits per second.
 * @param minAdjustIntervalMs Minimum wall time between emitted adjustments.
 * @param minRelativeDelta   Minimum |target − applied| / applied to emit.
 * @param emaAlpha           Smoothing factor for the ROI mean (per frame).
 * @param complexityWindowMs How long to accumulate complexity before the
 *                           one-time base-budgeting decision.
 */
class RoiBitrateController(
    baseBitrate: Int,
    private val minAdjustIntervalMs: Long = DEFAULT_ADJUST_INTERVAL_MS,
    private val minRelativeDelta: Float = DEFAULT_MIN_RELATIVE_DELTA,
    private val emaAlpha: Float = DEFAULT_EMA_ALPHA,
    private val complexityWindowMs: Long = DEFAULT_COMPLEXITY_WINDOW_MS,
) {
    companion object {
        const val DEFAULT_ADJUST_INTERVAL_MS = 500L
        const val DEFAULT_MIN_RELATIVE_DELTA = 0.10f
        const val DEFAULT_EMA_ALPHA = 0.1f

        /** Target = effectiveBase × ([MIN_GAIN] + [GAIN_SPAN] × smoothedRoi), clamped. */
        const val MIN_GAIN = 0.7f
        const val GAIN_SPAN = 0.8f
        const val MIN_MULTIPLIER = 0.5f
        const val MAX_MULTIPLIER = 1.5f

        /** Below this smoothed ROI mean the content counts as low-complexity. */
        const val LOW_COMPLEXITY_THRESHOLD = 0.25f

        /** One-time base cut for zero-complexity content (NEW §1: up to −50%). */
        const val MAX_BASE_REDUCTION = 0.5f

        const val DEFAULT_COMPLEXITY_WINDOW_MS = 2_000L
    }

    /** The base after the complexity-window decision (starts at the input base). */
    var baseBitrate: Int = baseBitrate
        private set

    private var smoothedRoi = Float.NaN
    private var appliedBitrate: Int = baseBitrate
    private var lastAdjustMs = Long.MIN_VALUE
    private var startedMs = Long.MIN_VALUE
    private var baseAdjusted = false

    /** Bitrate currently applied to the encoder (the base until first emit). */
    val currentBitrate: Int get() = appliedBitrate

    /**
     * Feed one frame's ROI mean (0..1). Returns the new bitrate in bits per
     * second to apply via `MediaCodec.setParameters`, or null when no change
     * should be emitted (cadence or delta gate). Must be called once per
     * encoded frame, in order.
     */
    fun onFrame(roiMean: Float, nowMs: Long): Int? {
        if (startedMs == Long.MIN_VALUE) startedMs = nowMs
        smoothedRoi =
            if (smoothedRoi.isNaN()) roiMean
            else smoothedRoi + emaAlpha * (roiMean - smoothedRoi)

        maybeApplyComplexityVerdict(nowMs)

        val target = targetFor(smoothedRoi)

        if (lastAdjustMs != Long.MIN_VALUE && nowMs - lastAdjustMs < minAdjustIntervalMs) return null
        if (kotlin.math.abs(target - appliedBitrate).toFloat() / appliedBitrate < minRelativeDelta) return null

        lastAdjustMs = nowMs
        appliedBitrate = target
        return target
    }

    /**
     * Content-aware base budgeting: once, at the end of the complexity
     * window, low-complexity content gets the base cut (up to
     * [MAX_BASE_REDUCTION], linear in how far below the threshold it sits).
     * The verdict re-bases [appliedBitrate] too, so the next emitted target
     * is computed against the reduced base.
     */
    private fun maybeApplyComplexityVerdict(nowMs: Long) {
        if (baseAdjusted || nowMs - startedMs < complexityWindowMs) return
        baseAdjusted = true
        val complexity = smoothedRoi.coerceIn(0f, 1f)
        if (complexity >= LOW_COMPLEXITY_THRESHOLD) return
        val severity = 1f - complexity / LOW_COMPLEXITY_THRESHOLD // 0..1
        val scale = 1f - MAX_BASE_REDUCTION * severity
        val newBase = (baseBitrate * scale).toInt()
        if (newBase == baseBitrate) return
        baseBitrate = newBase
        appliedBitrate = newBase
        lastAdjustMs = nowMs
    }

    /** Maps a smoothed ROI mean to the clamped bitrate target. Pure. */
    fun targetFor(smoothedRoi: Float): Int {
        val gain = MIN_GAIN + GAIN_SPAN * smoothedRoi.coerceIn(0f, 1f)
        val mult = gain.coerceIn(MIN_MULTIPLIER, MAX_MULTIPLIER)
        return (baseBitrate * mult).toInt()
    }
}

/**
 * Base-bitrate derivation (C3 design review P11, revised after on-device
 * findings): the budget source is the *minimum* of the declared track bitrate
 * and the actual average bitrate (file size × 8 / duration) — a low-bitrate
 * source must never be re-encoded at a higher rate (observed on-device:
 * declared 2.8 Mbps vs actual 124 kbps produced a 13× LARGER output, because
 * MTK's VBR treats the target as an average and pads static content).
 *
 * The target is a fraction of that budget, clamped to [floor, ceiling] where
 * the ceiling is the budget itself (never re-encode above source rate) and
 * the floor is a quarter of the budget (allow real savings on already
 * heavily-compressed sources) with a small absolute minimum.
 */
object BitrateMath {

    /** HEVC bits per pixel for a "visually decent" fallback target. */
    const val FALLBACK_BPP = 0.07f

    /** Absolute bounds for the derived base, bits per second. */
    const val MIN_BITRATE = 60_000
    const val MAX_BITRATE = 20_000_000

    /**
     * @param sourceBitrate Declared track bitrate in bps; 0 when absent.
     * @param actualBitrate File-size × 8 / duration in bps; 0 when unknown.
     * @param fraction      Target fraction of the budget (default 50%).
     */
    fun baseBitrate(
        sourceBitrate: Int,
        width: Int,
        height: Int,
        frameRate: Int,
        actualBitrate: Int = 0,
        fraction: Float = 0.5f,
    ): Int {
        var budget = 0
        if (sourceBitrate > 0) budget = sourceBitrate
        if (actualBitrate > 0) budget = if (budget > 0) minOf(budget, actualBitrate) else actualBitrate

        // No bitrate information at all: the bpp fallback already encodes a
        // decent *target* (not a budget), so no fraction is applied to it.
        if (budget == 0) {
            return (width * height * maxOf(frameRate, 24) * FALLBACK_BPP).toInt()
                .coerceIn(MIN_BITRATE, MAX_BITRATE)
        }

        val ceiling = minOf(budget, MAX_BITRATE)
        val floor = maxOf(MIN_BITRATE, budget / 4).coerceAtMost(ceiling)
        return (budget * fraction).toInt().coerceIn(floor, ceiling)
    }
}
