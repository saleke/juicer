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
 *   a screen recording), the base itself is cut — but *gradually and
 *   reversibly*: after the window the base eases toward the complexity-derived
 *   target at a per-frame step, and can rise again if the content gets busy.
 *   This replaces an earlier one-shot, permanent cut (a calm 10 s intro used
 *   to halve the base for the whole run — the "light-switch" blur the user
 *   reported). The [floorBitrate] (a perceptual BPP floor, the public-API
 *   stand-in for a QP cap, C3 F1) stops the base, and the emitted target,
 *   from collapsing below what reads as "sharp enough".
 *
 * Deliberately free of any Android dependency so it is unit-testable on the
 * JVM, following the [FrameBudgetTracker] pattern.
 *
 * @param baseBitrate        Center of the adjustment range, bits per second.
 * @param floorBitrate       Absolute minimum for the base and emitted targets
 *                           (perceptual floor; 0 disables). Never overrides
 *                           the budget ceiling — the caller derives it to be
 *                           ≤ the source budget.
 * @param minAdjustIntervalMs Minimum wall time between emitted adjustments.
 * @param minRelativeDelta   Minimum |target − applied| / applied to emit.
 * @param emaAlpha           Smoothing factor for the ROI mean (per frame).
 * @param complexityWindowMs How long to accumulate complexity before base
 *                           adaptation begins.
 * @param baseAdaptAlpha     Per-frame easing of the base toward its target.
 */
class RoiBitrateController(
    baseBitrate: Int,
    private val floorBitrate: Int = 0,
    private val minAdjustIntervalMs: Long = DEFAULT_ADJUST_INTERVAL_MS,
    private val minRelativeDelta: Float = DEFAULT_MIN_RELATIVE_DELTA,
    private val emaAlpha: Float = DEFAULT_EMA_ALPHA,
    private val complexityWindowMs: Long = DEFAULT_COMPLEXITY_WINDOW_MS,
    private val baseAdaptAlpha: Float = DEFAULT_BASE_ADAPT_ALPHA,
) {
    companion object {
        const val DEFAULT_ADJUST_INTERVAL_MS = 500L
        const val DEFAULT_MIN_RELATIVE_DELTA = 0.10f
        const val DEFAULT_EMA_ALPHA = 0.1f

        /** Per-frame easing of the base toward its complexity target. */
        const val DEFAULT_BASE_ADAPT_ALPHA = 0.02f

        /** Faster easing when complexity returns (busy scene recovery). */
        const val BASE_RISE_ALPHA = 0.08f

        /** Target = effectiveBase × ([MIN_GAIN] + [GAIN_SPAN] × smoothedRoi), clamped. */
        const val MIN_GAIN = 0.7f
        const val GAIN_SPAN = 0.8f
        const val MIN_MULTIPLIER = 0.5f
        const val MAX_MULTIPLIER = 1.5f

        /** Below this smoothed ROI mean the content counts as low-complexity. */
        const val LOW_COMPLEXITY_THRESHOLD = 0.25f

        /** Base cut for zero-complexity content (NEW §1: up to −50%). */
        const val MAX_BASE_REDUCTION = 0.5f

        const val DEFAULT_COMPLEXITY_WINDOW_MS = 2_000L
    }

    /** The base at run start — the reference for every later complexity target. */
    private val initialBase: Int = baseBitrate

    /** The base after complexity adaptation (starts at the input base). */
    var baseBitrate: Int = baseBitrate
        private set

    private var smoothedRoi = Float.NaN
    private var smoothedComplexity = Float.NaN
    private var appliedBitrate: Int = baseBitrate
    private var lastAdjustMs = Long.MIN_VALUE
    private var startedMs = Long.MIN_VALUE

    /** Bitrate currently applied to the encoder (the base until first emit). */
    val currentBitrate: Int get() = appliedBitrate

    /**
     * Feed one frame's ROI mean (0..1). Returns the new bitrate in bits per
     * second to apply via `MediaCodec.setParameters`, or null when no change
     * should be emitted (cadence or delta gate). Must be called once per
     * encoded frame, in order.
     */
    /**
     * Feed one frame's ROI means (0..1). Returns the new bitrate in bits per
     * second to apply via `MediaCodec.setParameters`, or null when no change
     * should be emitted (cadence or delta gate). Must be called once per
     * encoded frame, in order.
     *
     * Two separate aggregates:
     * - [roiMean] is the *detail-weighted* mean — drives the live bitrate
     *   target (bits go where the detail is);
     * - [overallComplexity] is the *plain* mean of the whole map — drives the
     *   low-complexity base cut, so a sparse slide is judged by total content
     *   (cheap) not by its one sharp corner. Defaults to [roiMean] for
     *   callers/tests that only feed one signal.
     */
    fun onFrame(roiMean: Float, nowMs: Long, overallComplexity: Float = roiMean): Int? {
        if (startedMs == Long.MIN_VALUE) startedMs = nowMs
        smoothedRoi =
            if (smoothedRoi.isNaN()) roiMean
            else smoothedRoi + emaAlpha * (roiMean - smoothedRoi)
        smoothedComplexity =
            if (smoothedComplexity.isNaN()) overallComplexity
            else smoothedComplexity + emaAlpha * (overallComplexity - smoothedComplexity)

        maybeAdaptBase(nowMs)

        val target = targetFor(smoothedRoi)

        if (lastAdjustMs != Long.MIN_VALUE && nowMs - lastAdjustMs < minAdjustIntervalMs) return null
        if (kotlin.math.abs(target - appliedBitrate).toFloat() / appliedBitrate < minRelativeDelta) return null

        lastAdjustMs = nowMs
        appliedBitrate = target
        return target
    }

    /**
     * Content-aware base budgeting (NEW §1), *continuous and reversible*:
     * after the complexity window the base eases toward a complexity-derived
     * target at [baseAdaptAlpha] per frame, so a calm intro drifts the budget
     * down gracefully and a busy scene later pulls it back up — no permanent
     * one-shot cut. If content is at or above [LOW_COMPLEXITY_THRESHOLD] the
     * target is the original base (scale 1.0). The perceptual [floorBitrate]
     * binds the base (and, via [targetFor], every emitted target).
     *
     * The complexity score is the *overall* (plain) ROI mean — the same
     * measure the old code used — deliberately NOT the detail-weighted one:
     * a sparse slide scores low here even though its text is sharp, so the
     * whole file still gets the budget cut; the detail weighting only steers
     * *where* those bits go (see [onFrame]).
     */
    private fun maybeAdaptBase(nowMs: Long) {
        if (nowMs - startedMs < complexityWindowMs) return
        val complexity = smoothedComplexity.coerceIn(0f, 1f)
        val scale = if (complexity >= LOW_COMPLEXITY_THRESHOLD) 1f
        else 1f - MAX_BASE_REDUCTION * (1f - complexity / LOW_COMPLEXITY_THRESHOLD)
        val target = (initialBase * scale).toInt().coerceAtLeast(floorBitrate)
        if (target == baseBitrate) return
        // Asymmetric easing: a busy scene that returns pulls the base back up
        // promptly (fast rise), while genuinely calm content descends gently —
        // no cliff, no permanent cut.
        val alpha = if (target > baseBitrate) BASE_RISE_ALPHA else baseAdaptAlpha
        val step = ((target - baseBitrate) * alpha).toInt()
        if (step != 0) baseBitrate = (baseBitrate + step).coerceAtLeast(floorBitrate)
    }

    /**
     * Maps a smoothed ROI mean to the clamped bitrate target. Pure. The target
     * never falls below the perceptual [floorBitrate] (the public-API stand-in
     * for a per-block QP cap, C3 F1).
     */
    fun targetFor(smoothedRoi: Float): Int {
        val gain = MIN_GAIN + GAIN_SPAN * smoothedRoi.coerceIn(0f, 1f)
        val mult = gain.coerceIn(MIN_MULTIPLIER, MAX_MULTIPLIER)
        return (baseBitrate * mult).toInt().coerceAtLeast(floorBitrate)
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

    /**
     * Bits per pixel that still reads as "sharp enough" on flat content
     * (the perceptual floor; C3 F1's stand-in for a QP cap — quantizer 32
     * on clean 720p/1080p content typically lands ~0.03–0.05 bpp for AVC).
     * Used only as a *floor*; the budget ceiling still binds above it, so a
     * low-bitrate source is never lifted to the floor.
     */
    const val QUALITY_FLOOR_BPP = 0.035f

    /** Absolute bounds for the derived base, bits per second. */
    const val MIN_BITRATE = 60_000
    const val MAX_BITRATE = 20_000_000

    /**
     * Perceptual floor for the run, bits per second: resolution × fps ×
     * [QUALITY_FLOOR_BPP], clamped into absolute bounds and never above the
     * [budgetCeiling] (so it cannot fight the savings goal on tiny sources).
     * Feed this into [RoiBitrateController] as `floorBitrate`.
     */
    fun perceptualFloor(width: Int, height: Int, frameRate: Int, budgetCeiling: Int): Int {
        val bpp = (width * height * maxOf(frameRate, 24) * QUALITY_FLOOR_BPP).toInt()
            .coerceIn(MIN_BITRATE, MAX_BITRATE)
        return bpp.coerceAtMost(budgetCeiling)
    }

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
