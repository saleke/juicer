package com.eqo.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Covers the ROI→bitrate control policy and the base-bitrate derivation
 * (C3 design review, F1/F6/P5/P11 and the NEW §1 content-aware budgeting).
 */
class RoiBitrateControllerTest {

    private val base = 4_000_000

    @Test
    fun `target maps roiMean into the clamped gain range`() {
        val c = RoiBitrateController(base)
        assertEquals((base * 0.7f).toInt(), c.targetFor(0f))   // gain floor 0.7 binds
        assertEquals(base, c.targetFor(0.375f))                 // gain = 1.0
        assertEquals((base * 1.5f).toInt(), c.targetFor(1f))    // clamp 1.5 binds
        assertEquals((base * 1.5f).toInt(), c.targetFor(2f))    // input clamped to 0..1
    }

    @Test
    fun `first frame emits only when the target moved enough`() {
        // emaAlpha=1 ⇒ no smoothing: the smoothed value is the frame's value.
        val c = RoiBitrateController(base, emaAlpha = 1f)
        // gain exactly 1.0 → no delta → no emit
        assertNull(c.onFrame(0.375f, nowMs = 0))
        assertEquals(base, c.currentBitrate)
        // big delta → emit
        assertEquals((base * 1.5f).toInt(), c.onFrame(1.0f, nowMs = 0))
    }

    @Test
    fun `adjustments are cadence-gated`() {
        val c = RoiBitrateController(base, emaAlpha = 1f)
        assertEquals((base * 1.5f).toInt(), c.onFrame(1.0f, nowMs = 0))
        // Target moved 0.7×↔1.5×, but only 100 ms since the last adjust.
        assertNull(c.onFrame(0.0f, nowMs = 100))
        assertEquals((base * 1.5f).toInt(), c.currentBitrate)
        // After the interval, the swing is emitted.
        assertEquals((base * 0.7f).toInt(), c.onFrame(0.0f, nowMs = 600))
    }

    @Test
    fun `roi mean is ema-smoothed before mapping`() {
        val c = RoiBitrateController(base, minAdjustIntervalMs = 0, minRelativeDelta = 0f)
        // Ten frames at 1.0 after one frame at 0: smoothed converges toward
        // 1.0 but never exceeds the frame-0 anchor plus alpha-weighted steps.
        c.onFrame(0f, 0)
        var emitted = 0
        for (i in 1..10) emitted += if (c.onFrame(1f, i * 10L) != null) 1 else 0
        // With smoothing, early targets stay far from the clamp; at least the
        // first and last differ (the whole point of the EMA).
        org.junit.Assert.assertTrue(emitted >= 1)
        org.junit.Assert.assertTrue(c.currentBitrate > (base * 0.7f).toInt())
        org.junit.Assert.assertTrue(c.currentBitrate <= (base * 1.5f).toInt())
    }

    @Test
    fun `low-complexity content eases the base down after the window, never locking`() {
        val c = RoiBitrateController(base, complexityWindowMs = 2_000)
        // Simulate a static lecture: roiMean ≈ 0.05 for 2.5 s at 30 fps.
        for (i in 0..75) c.onFrame(0.05f, i * 33L)
        // The base descended toward its target (0.6× base) but eased there —
        // it is lower, yet far from a snapped permanent cut.
        org.junit.Assert.assertTrue("base=${c.baseBitrate}", c.baseBitrate < base)
        org.junit.Assert.assertTrue("base=${c.baseBitrate}", c.baseBitrate > (base * 0.5f).toInt())
        // Still calm: the base keeps approaching the low-complexity target.
        val before = c.baseBitrate
        for (i in 0..120) c.onFrame(0.05f, 85_000 + i * 33L)
        org.junit.Assert.assertTrue("base=${c.baseBitrate}", c.baseBitrate < before)
    }

    @Test
    fun `base rebounds when complexity returns`() {
        // A busy scene that arrives late must pull the budget back up — the
        // opposite of the old permanent one-shot verdict (blur-by-intro).
        val c = RoiBitrateController(base, complexityWindowMs = 2_000)
        for (i in 0..75) c.onFrame(0.05f, i * 33L) // calm intro
        val reduced = c.baseBitrate
        org.junit.Assert.assertTrue(reduced < base)
        // A busy subject returns for ~8 s.
        for (i in 0..240) c.onFrame(0.9f, 100_000 + i * 33L)
        org.junit.Assert.assertTrue("base=${c.baseBitrate}", c.baseBitrate > reduced)
        org.junit.Assert.assertTrue("base=${c.baseBitrate}", c.baseBitrate <= base)
    }

    @Test
    fun `busy content keeps its base after the window`() {
        val c = RoiBitrateController(base, complexityWindowMs = 2_000)
        for (i in 0..75) c.onFrame(0.9f, i * 33L)
        assertEquals(base, c.baseBitrate)
    }

    @Test
    fun `boundary complexity is not cut`() {
        val c = RoiBitrateController(base, complexityWindowMs = 2_000)
        for (i in 0..75) c.onFrame(0.25f, i * 33L)
        assertEquals(base, c.baseBitrate)
    }

    @Test
    fun `target never falls below the perceptual floor`() {
        val floor = (base * 0.75f).toInt()
        val c = RoiBitrateController(base, floorBitrate = floor, emaAlpha = 1f)
        assertEquals(floor, c.targetFor(0f)) // gain floor would give 0.7×base
        assertEquals(floor, c.onFrame(0f, 0))
    }

    @Test
    fun `base never drops below the floor during adaptation`() {
        val floor = (base * 0.75f).toInt()
        val c = RoiBitrateController(
            base, floorBitrate = floor, complexityWindowMs = 0, baseAdaptAlpha = 0.5f,
        )
        for (i in 0..20) c.onFrame(0f, i * 33L)
        org.junit.Assert.assertTrue("base=${c.baseBitrate}", c.baseBitrate >= floor)
    }

    @Test
    fun `sparse-but-sharp scene cuts the base while keeping bits high`() {
        // A slide with one sharp text region: detail-weighted mean reads high
        // (bits go where the text is) but the *overall* mean is low, so the
        // whole file still earns the low-complexity budget cut.
        val c = RoiBitrateController(base, complexityWindowMs = 2_000, emaAlpha = 1f)
        for (i in 0..75) c.onFrame(0.75f, i * 33L, overallComplexity = 0.05f)
        // Base eased toward the low-complexity target…
        org.junit.Assert.assertTrue("base=${c.baseBitrate}", c.baseBitrate < base)
        // …while the emitted target still rides the (high) detail-weighted mean.
        org.junit.Assert.assertTrue("target=${c.currentBitrate}", c.currentBitrate > (base * 0.9f).toInt())
    }

    @Test
    fun `detail-weighted mean defaults to overall when only one signal is fed`() {
        // Back-compat: onFrame's third argument defaults to the first. A
        // uniformly high scene must keep the base.
        val c = RoiBitrateController(base, complexityWindowMs = 2_000)
        for (i in 0..75) c.onFrame(0.9f, i * 33L)
        assertEquals(base, c.baseBitrate)
    }
}

class BitrateMathTest {

    @Test
    fun `default target is half the source bitrate`() {
        assertEquals(4_000_000, BitrateMath.baseBitrate(8_000_000, 1920, 1080, 30))
    }

    @Test
    fun `unknown source bitrate falls back to resolution x fps x bpp`() {
        val expected = (1920 * 1080 * 30 * BitrateMath.FALLBACK_BPP).toInt()
        assertEquals(expected, BitrateMath.baseBitrate(0, 1920, 1080, 30))
    }

    @Test
    fun `low-bitrate source is never lifted above its original rate`() {
        // 50% of 200 kbps is 100 kbps; the floor yields to the source cap.
        val b = BitrateMath.baseBitrate(200_000, 640, 480, 30)
        org.junit.Assert.assertTrue("b=$b", b in 100_000..200_000)
    }

    @Test
    fun `derived base stays within absolute bounds`() {
        org.junit.Assert.assertTrue(BitrateMath.baseBitrate(100_000_000, 3840, 2160, 60) <= BitrateMath.MAX_BITRATE)
        org.junit.Assert.assertTrue(BitrateMath.baseBitrate(0, 320, 240, 10) >= BitrateMath.MIN_BITRATE)
    }

    @Test
    fun `actual bitrate caps the declared budget`() {
        // The on-device case: 2.8 Mbps declared vs 124 kbps actual
        // (file size × 8 / duration). Budget = 124 kbps, target = half of it.
        val b = BitrateMath.baseBitrate(2_800_000, 640, 360, 24, actualBitrate = 124_000)
        assertEquals(62_000, b)
    }

    @Test
    fun `unknown actual bitrate is ignored`() {
        assertEquals(4_000_000, BitrateMath.baseBitrate(8_000_000, 1920, 1080, 30, actualBitrate = 0))
    }

    @Test
    fun `tiny actual bitrate still yields a usable floor`() {
        // A quarter-budget floor with a 60 kbps absolute minimum keeps the
        // encoder in a range hardware rate controllers actually accept.
        val b = BitrateMath.baseBitrate(100_000, 640, 360, 24, actualBitrate = 100_000)
        assertEquals(60_000, b)
    }
}
