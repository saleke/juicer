package com.eqo.pipeline

/**
 * Pure backpressure policy (design review, pitfall P5 / spec §3).
 *
 * The ImageReader's `acquireLatestImage()` already guarantees stale frames are
 * dropped rather than accumulated in RAM. This tracker adds the spec's second
 * lever: when the dual-read path runs over budget for several consecutive frames,
 * the encoder leg is gracefully thinned to every other frame (halving the encoder
 * frame rate) until processing is comfortably back under budget.
 *
 * Deliberately free of any Android dependency so it is unit-testable on the JVM.
 *
 * @param budgetNanos             Per-frame listener budget; defaults to 60 FPS (16.6 ms).
 * @param overBudgetStreakThreshold  Consecutive over-budget frames before degrading.
 * @param underBudgetStreakThreshold Consecutive under-budget frames before recovering.
 */
class FrameBudgetTracker(
    private val budgetNanos: Long = PipelineMetrics.FRAME_BUDGET_NANOS,
    private val overBudgetStreakThreshold: Int = 3,
    private val underBudgetStreakThreshold: Int = 10,
) {
    private var overStreak = 0
    private var underStreak = 0
    private var degraded = false
    private var framesSinceDegraded = 0

    /** True while the encoder leg is being thinned to every other frame. */
    val isDegraded: Boolean get() = degraded

    /** Records the processing time of the latest processed frame; updates degradation state. */
    fun recordProcessingTime(nanos: Long) {
        if (nanos > budgetNanos) {
            overStreak++
            underStreak = 0
            if (!degraded && overStreak >= overBudgetStreakThreshold) {
                degraded = true
                framesSinceDegraded = 0
            }
        } else {
            underStreak++
            overStreak = 0
            if (degraded && underStreak >= underBudgetStreakThreshold) {
                degraded = false
                framesSinceDegraded = 0
            }
        }
    }

    /**
     * Whether the encoder leg should receive the current frame.
     * While degraded, alternates true/false — i.e. every other frame.
     * Must be called exactly once per frame.
     */
    fun shouldEncodeFrame(): Boolean {
        if (!degraded) return true
        val encode = framesSinceDegraded % 2 == 0
        framesSinceDegraded++
        return encode
    }
}
