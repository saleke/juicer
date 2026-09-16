package com.eqo.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

/**
 * Covers the SSIM validator math (component_three.md NEW §3, C3 §5):
 * identity scores 1.0, degradation lowers the score monotonically, and the
 * score is stable against benign transforms that preserve structure.
 */
class SsimMathTest {

    private val size = SsimMath.SNAPSHOT_SIZE
    private val bpp = SsimMath.BYTES_PER_PIXEL

    private fun grayFrame(value: Int, seed: Long = 0): ByteArray {
        val rgb = ByteArray(size * size * bpp)
        val rnd = if (seed == 0L) null else Random(seed)
        for (i in rgb.indices step bpp) {
            // Deterministic texture so variance is non-zero (SSIM on flat
            // images is degenerate — the structural term vanishes).
            val v = value + (rnd?.nextInt(-20, 20) ?: 0)
            rgb[i] = v.toByte(); rgb[i + 1] = v.toByte(); rgb[i + 2] = v.toByte()
        }
        return rgb
    }

    @Test
    fun `identical frames score exactly one`() {
        val a = grayFrame(128, seed = 42)
        assertEquals(1.0f, SsimMath.ssim(a, a.copyOf()), 1e-6f)
    }

    @Test
    fun `noisy copy scores below one but stays high`() {
        val a = grayFrame(128, seed = 42)
        val b = a.copyOf()
        val rnd = Random(7)
        for (i in b.indices step bpp) {
            val d = rnd.nextInt(-4, 4) // light sensor-like noise
            b[i] = (b[i] + d).toByte()
            b[i + 1] = (b[i + 1] + d).toByte()
            b[i + 2] = (b[i + 2] + d).toByte()
        }
        val s = SsimMath.ssim(a, b)
        assertTrue("ssim=$s", s in 0.5f..1f)
    }

    @Test
    fun `heavier degradation scores lower than light degradation`() {
        val a = grayFrame(128, seed = 42)
        val light = a.copyOf()
        val heavy = a.copyOf()
        val rnd = Random(7)
        for (i in a.indices step bpp) {
            light[i] = (light[i] + rnd.nextInt(-4, 4)).toByte()
            heavy[i] = (heavy[i] + rnd.nextInt(-30, 30)).toByte()
        }
        // Copy the noise across channels so both stay gray.
        for (i in a.indices step bpp) {
            light[i + 1] = light[i]; light[i + 2] = light[i]
            heavy[i + 1] = heavy[i]; heavy[i + 2] = heavy[i]
        }
        val sLight = SsimMath.ssim(a, light)
        val sHeavy = SsimMath.ssim(a, heavy)
        assertTrue("light=$sLight heavy=$sHeavy", sHeavy < sLight)
    }

    @Test
    fun `different content scores well below one`() {
        val a = grayFrame(128, seed = 42)
        val b = grayFrame(128, seed = 99)
        val s = SsimMath.ssim(a, b)
        assertTrue("ssim=$s", s < 0.5f)
    }

    @Test
    fun `small brightness shift keeps high similarity`() {
        // A uniform +5 lift is structure-preserving: SSIM should barely move.
        val a = grayFrame(128, seed = 42)
        val b = a.copyOf()
        for (i in b.indices step bpp) {
            b[i] = (b[i] + 5).toByte(); b[i + 1] = (b[i + 1] + 5).toByte(); b[i + 2] = (b[i + 2] + 5).toByte()
        }
        val s = SsimMath.ssim(a, b)
        assertTrue("ssim=$s", s > 0.9f)
    }

    @Test
    fun `score is symmetric`() {
        val a = grayFrame(100, seed = 1)
        val b = grayFrame(110, seed = 2)
        val ab = SsimMath.ssim(a, b)
        val ba = SsimMath.ssim(b, a)
        assertTrue(abs(ab - ba) < 1e-5f)
    }
}
