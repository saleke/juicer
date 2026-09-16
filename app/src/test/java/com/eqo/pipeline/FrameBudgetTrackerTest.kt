package com.eqo.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the drop-frame / degraded-encoding backpressure policy (spec §3;
 * design review, pitfall P5).
 */
class FrameBudgetTrackerTest {

    private val budget = 16_600_000L

    @Test
    fun `every frame is encoded while processing stays within budget`() {
        val tracker = FrameBudgetTracker(budgetNanos = budget)
        repeat(50) { tracker.recordProcessingTime(budget / 2) }
        assertFalse(tracker.isDegraded)
        repeat(20) { assertTrue(tracker.shouldEncodeFrame()) }
    }

    @Test
    fun `isolated over-budget frames do not degrade the pipeline`() {
        val tracker = FrameBudgetTracker(
            budgetNanos = budget,
            overBudgetStreakThreshold = 3,
            underBudgetStreakThreshold = 5,
        )
        tracker.recordProcessingTime(budget * 2)
        tracker.recordProcessingTime(budget / 2) // breaks the streak
        tracker.recordProcessingTime(budget * 2)
        assertFalse(tracker.isDegraded)
        assertTrue(tracker.shouldEncodeFrame())
    }

    @Test
    fun `sustained over-budget frames degrade encoding to every other frame`() {
        val tracker = FrameBudgetTracker(
            budgetNanos = budget,
            overBudgetStreakThreshold = 3,
            underBudgetStreakThreshold = 5,
        )
        repeat(3) { tracker.recordProcessingTime(budget * 2) }
        assertTrue(tracker.isDegraded)

        val decisions = (0 until 6).map { tracker.shouldEncodeFrame() }
        assertEquals(listOf(true, false, true, false, true, false), decisions)
    }

    @Test
    fun `pipeline recovers after sustained under-budget frames`() {
        val tracker = FrameBudgetTracker(
            budgetNanos = budget,
            overBudgetStreakThreshold = 3,
            underBudgetStreakThreshold = 5,
        )
        repeat(3) { tracker.recordProcessingTime(budget * 2) }
        assertTrue(tracker.isDegraded)

        repeat(5) {
            tracker.recordProcessingTime(budget / 2)
            tracker.shouldEncodeFrame()
        }
        assertFalse(tracker.isDegraded)
        repeat(10) { assertTrue(tracker.shouldEncodeFrame()) }
    }
}
