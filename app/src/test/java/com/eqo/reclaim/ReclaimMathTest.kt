package com.eqo.reclaim

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReclaimMathTest {

    // The on-device 5g clip from the C3 verification (1,253,359 B, 13.12 s).
    private val fiveGSize = 1_253_359L
    private val fiveGDurationUs = 13_120_000L

    @Test
    fun `actual bitrate from size and duration`() {
        val bps = ReclaimMath.actualBitrateBps(fiveGSize, fiveGDurationUs)
        // 1,253,359 * 8 / 13.12 s ≈ 764 kbps
        assertTrue("expected ~764k, got $bps", bps in 700_000..840_000)
    }

    @Test
    fun `estimate for the 5g clip is about half the source`() {
        val est = ReclaimMath.estimatedOutputBytes(fiveGSize, fiveGDurationUs)
        // base = 764k * 0.5 ≈ 382k bps → 382k * 13.12 / 8 ≈ 627 KB vs 1,253 KB source
        assertTrue("est=$est", est in 500_000..780_000)
    }

    @Test
    fun `reclaimable is positive and below the source size`() {
        val reclaimable = ReclaimMath.reclaimableBytes(fiveGSize, fiveGDurationUs)
        assertTrue("reclaimable=$reclaimable", reclaimable in 500_000..800_000L)
        assertTrue(reclaimable < fiveGSize)
    }

    @Test
    fun `zero duration or zero size yields no reclaimable`() {
        assertEquals(0L, ReclaimMath.reclaimableBytes(fiveGSize, 0L))
        assertEquals(0L, ReclaimMath.reclaimableBytes(0L, fiveGDurationUs))
        assertEquals(fiveGSize, ReclaimMath.estimatedOutputBytes(fiveGSize, 0L))
    }

    @Test
    fun `reclaimable is monotone non-decreasing in size`() {
        var prev = -1L
        for (mb in 1..50) {
            val size = mb * 1_000_000L
            val r = ReclaimMath.reclaimableBytes(size, 60_000_000L)
            assertTrue("r($mb)=$r < prev=$prev", r >= prev)
            prev = r
        }
    }

    @Test
    fun `large high-bitrate clip reclaims roughly half`() {
        // 200 MB / 120 s ≈ 13.9 Mbps source; base ≈ 6.97 Mbps → est ≈ 104 MB
        val size = 200L * 1_000_000
        val durUs = 120_000_000L
        val r = ReclaimMath.reclaimableBytes(size, durUs)
        assertTrue("r=$r", r in 80_000_000L..110_000_000L)
    }

    @Test
    fun `saved percent is bounded and sane`() {
        val r = ReclaimMath.reclaimableBytes(fiveGSize, fiveGDurationUs)
        val pct = ReclaimMath.savedPercent(fiveGSize, r)
        assertTrue("pct=$pct", pct in 40f..65f)
        assertEquals(0f, ReclaimMath.savedPercent(0L, 0L))
    }

    @Test
    fun `fraction adjusts the estimate`() {
        val standard = ReclaimMath.estimatedOutputBytes(fiveGSize, fiveGDurationUs)
        val aggressive = ReclaimMath.estimatedOutputBytes(fiveGSize, fiveGDurationUs, 0.3f)
        assertTrue("aggressive=$aggressive should be < standard=$standard", aggressive <= standard)
    }

    @Test
    fun `eqo output prefix detection`() {
        assertTrue(ReclaimMath.isEqoOutput("eqo_v1_12345.mp4"))
        assertTrue(ReclaimMath.isEqoOutput("eqo_.mp4"))
        assertFalse(ReclaimMath.isEqoOutput("IMG_0001.MP4"))
        assertFalse(ReclaimMath.isEqoOutput("movie.mp4"))
        assertFalse(ReclaimMath.isEqoOutput("eqo video.mp4"))
    }

    @Test
    fun `auto purge boundary`() {
        val now = 1_000_000_000L
        assertTrue(ReclaimMath.shouldAutoPurge(now - ReclaimMath.DEFAULT_ROLLBACK_RETENTION_MS, now))
        assertFalse(ReclaimMath.shouldAutoPurge(now - 1, now))
        assertTrue(ReclaimMath.shouldAutoPurge(now - 1, now, retentionMs = 0L))
        assertFalse(ReclaimMath.shouldAutoPurge(now, now))
    }
}