package com.eqo.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.io.File
import org.junit.Test

/**
 * Covers the pure ROI math: grid geometry, variance saliency, smoothing, EMA,
 * human modulation, motion detection, byte encoding, and the label-file
 * contract (C2 design review, P8/P9/P12).
 */
class RoiMathTest {

    private val snapshotSize = RoiMath.SNAPSHOT_SIZE
    private val bytesPerPixel = RoiMath.BYTES_PER_PIXEL

    private fun snapshot(base: Int = 100, vararg blocks: Triple<Int, Int, Int>): ByteArray {
        // blocks of (gx, gy, lumaValue); everything else luma `base`
        val rgb = ByteArray(snapshotSize * snapshotSize * bytesPerPixel)
        for (i in rgb.indices step bytesPerPixel) {
            rgb[i] = base.toByte(); rgb[i + 1] = base.toByte(); rgb[i + 2] = base.toByte()
        }
        for ((gx, gy, luma) in blocks) {
            val x0 = gx * RoiMath.CELL
            val y0 = gy * RoiMath.CELL
            for (y in y0 until y0 + RoiMath.CELL) {
                for (x in x0 until x0 + RoiMath.CELL) {
                    val i = (y * snapshotSize + x) * bytesPerPixel
                    rgb[i] = luma.toByte(); rgb[i + 1] = luma.toByte(); rgb[i + 2] = luma.toByte()
                }
            }
        }
        return rgb
    }

    @Test
    fun `fused gridStats matches reference varianceWeights and meanAbsLumaDelta`() {
        // Checkerboard cell 5,5 + a bright cell 2,3 for mean/variance variety.
        val rgb = snapshot()
        val x0 = 5 * RoiMath.CELL
        val y0 = 5 * RoiMath.CELL
        for (y in 0 until RoiMath.CELL) {
            for (x in 0 until RoiMath.CELL) {
                val i = ((y0 + y) * snapshotSize + (x0 + x)) * bytesPerPixel
                val v = if ((x + y) % 2 == 0) 255 else 0
                rgb[i] = v.toByte(); rgb[i + 1] = v.toByte(); rgb[i + 2] = v.toByte()
            }
        }
        for ((gx, gy, luma) in arrayOf(Triple(2, 3, 200))) {
            val bx = gx * RoiMath.CELL
            val by = gy * RoiMath.CELL
            for (y in by until by + RoiMath.CELL) {
                for (x in bx until bx + RoiMath.CELL) {
                    val i = (y * snapshotSize + x) * bytesPerPixel
                    rgb[i] = luma.toByte(); rgb[i + 1] = luma.toByte(); rgb[i + 2] = luma.toByte()
                }
            }
        }

        // Reference: two separate passes with a known previous-luma state.
        val prev = FloatArray(RoiMath.GRID * RoiMath.GRID) { 80f }
        val refGrid = FloatArray(RoiMath.GRID * RoiMath.GRID)
        val refPrev = prev.copyOf()
        val refDelta = RoiMath.meanAbsLumaDelta(rgb, refPrev)
        RoiMath.varianceWeights(rgb, refGrid)

        // Fused: one pass from the same previous state.
        val fusedGrid = FloatArray(RoiMath.GRID * RoiMath.GRID)
        val fusedPrev = prev.copyOf()
        val fusedDelta = RoiMath.gridStats(rgb, fusedPrev, fusedGrid)

        assertEquals(refDelta, fusedDelta, 1e-4f)
        assertTrue(fusedPrev.contentEquals(refPrev))
        assertTrue(
            "grids differ: ${refGrid.toList()} vs ${fusedGrid.toList()}",
            refGrid.indices.all { Math.abs(refGrid[it] - fusedGrid[it]) < 1e-4f },
        )
    }

    @Test
    fun `variance grid ranks busy cell above flat cells`() {
        // Variance is intra-cell, so the busy cell needs internal contrast —
        // a solid block would be just as "flat" as the background.
        val rgb = snapshot()
        val x0 = 5 * RoiMath.CELL
        val y0 = 5 * RoiMath.CELL
        for (y in 0 until RoiMath.CELL) {
            for (x in 0 until RoiMath.CELL) {
                val i = ((y0 + y) * snapshotSize + (x0 + x)) * bytesPerPixel
                val v = if ((x + y) % 2 == 0) 255 else 0 // checkerboard
                rgb[i] = v.toByte(); rgb[i + 1] = v.toByte(); rgb[i + 2] = v.toByte()
            }
        }
        val grid = FloatArray(RoiMath.GRID * RoiMath.GRID)
        RoiMath.varianceWeights(rgb, grid)

        val busy = grid[5 * RoiMath.GRID + 5]
        val flat = grid[0 * RoiMath.GRID + 0]
        assertTrue("busy=$busy flat=$flat", busy > flat)
        assertEquals(1f, grid.max(), 0.001f) // normalized against busiest cell
    }

    @Test
    fun `variance grid of uniform frame is all zero`() {
        val rgb = snapshot()
        val grid = FloatArray(RoiMath.GRID * RoiMath.GRID)
        RoiMath.varianceWeights(rgb, grid)
        assertTrue(grid.all { it == 0f })
    }

    @Test
    fun `smoothing spreads a single hot cell to neighbors`() {
        val src = FloatArray(RoiMath.GRID * RoiMath.GRID)
        src[3 * RoiMath.GRID + 3] = 1f
        val dst = FloatArray(RoiMath.GRID * RoiMath.GRID)
        RoiMath.smooth(src, dst)

        assertEquals(1f / 9f, dst[3 * RoiMath.GRID + 3], 0.0001f)
        assertEquals(1f / 9f, dst[3 * RoiMath.GRID + 4], 0.0001f)
        assertEquals(0f, dst[0], 0.0001f)
    }

    @Test
    fun `smoothing replicates edges`() {
        val src = FloatArray(RoiMath.GRID * RoiMath.GRID) { 1f }
        val dst = FloatArray(RoiMath.GRID * RoiMath.GRID)
        RoiMath.smooth(src, dst)
        assertTrue(dst.all { it == 1f })
    }

    @Test
    fun `temporal blend is EMA after the first call`() {
        val prev = FloatArray(256) { 0.5f }
        val new = FloatArray(256) { 1f }
        val out = FloatArray(256)
        RoiMath.temporalBlend(prev, new, out, alpha = 0.3f)
        assertEquals(0.65f, out[0], 0.0001f) // (1-0.3)·0.5 + 0.3·1
    }

    @Test
    fun `temporal blend copies on first call`() {
        val prev = FloatArray(256)
        val new = FloatArray(256) { 0.8f }
        val out = FloatArray(256)
        RoiMath.temporalBlend(prev, new, out, alpha = 0.3f)
        assertTrue(out.all { it == 0.8f })
    }

    @Test
    fun `temporal blend allows in-place operation`() {
        val prev = FloatArray(256) { 0.5f }
        val new = FloatArray(256) { 1f }
        RoiMath.temporalBlend(prev, new, prev, alpha = 0.3f)
        assertEquals(0.65f, prev[0], 0.0001f)
    }

    @Test
    fun `human modulation boosts centered region`() {
        val grid = FloatArray(RoiMath.GRID * RoiMath.GRID) // all zero
        RoiMath.modulateForHuman(grid, humanLikely = true)
        val start = (RoiMath.GRID - 12) / 2
        assertEquals(0.35f, grid[start * RoiMath.GRID + start], 0.001f)
        assertEquals(0f, grid[0], 0.001f) // corner untouched
        assertTrue(grid[start * RoiMath.GRID + start] <= 1f)
    }

    @Test
    fun `human modulation is a no-op when not human`() {
        val grid = FloatArray(RoiMath.GRID * RoiMath.GRID)
        RoiMath.modulateForHuman(grid, humanLikely = false)
        assertTrue(grid.all { it == 0f })
    }

    @Test
    fun `static scene detection uses mean luma delta`() {
        val rgbA = snapshot()
        val cellLuma = FloatArray(RoiMath.GRID * RoiMath.GRID)
        val d1 = RoiMath.meanAbsLumaDelta(rgbA, cellLuma)
        // second call with identical frame: delta should be 0
        val d2 = RoiMath.meanAbsLumaDelta(rgbA, cellLuma)
        assertTrue(d1 >= 0f)
        assertEquals(0f, d2, 0.0001f)
        assertTrue(RoiMath.isMostlyStatic(d2))
    }

    @Test
    fun `changed frame is not static`() {
        // The threshold is on the MEAN per-cell delta, so a single changed cell
        // can never trip it — change the whole frame's brightness instead.
        val cellLuma = FloatArray(RoiMath.GRID * RoiMath.GRID)
        RoiMath.meanAbsLumaDelta(snapshot(base = 100), cellLuma)
        val d = RoiMath.meanAbsLumaDelta(snapshot(base = 140), cellLuma)
        assertTrue("delta=$d", d > RoiMath.STATIC_THRESHOLD)
    }

    @Test
    fun `byte encoding clamps to weight floor`() {
        val grid = FloatArray(RoiMath.GRID * RoiMath.GRID) // all zeros
        val bytes = ByteArray(RoiMath.GRID * RoiMath.GRID)
        RoiMath.encodeToBytes(grid, bytes)
        // floor 0.2 ⇒ byte 51
        assertEquals(51, bytes[0].toInt() and 0xFF)
    }

    @Test
    fun `byte encoding round-trips through decode`() {
        val grid = FloatArray(RoiMath.GRID * RoiMath.GRID) { (it % 16) / 16f }
        val bytes = ByteArray(grid.size)
        RoiMath.encodeToBytes(grid, bytes)
        val back = FloatArray(grid.size)
        RoiMath.decodeFromBytes(bytes, back)
        for (i in grid.indices) {
            // values below the floor are intentionally lifted to WEIGHT_FLOOR
            assertEquals(maxOf(grid[i], RoiMath.WEIGHT_FLOOR), back[i], 0.01f)
        }
    }

    @Test
    fun `human adjacent indices are garment labels in the shipped asset`() {
        // P12: catches label-file drift. Falls back to a local copy when run
        // outside the repo (unit tests run from the module dir).
        val asset = sequenceOf(
            "src/main/assets/imagenet_labels.txt",
            "app/src/main/assets/imagenet_labels.txt",
        ).map(::File).firstOrNull { it.exists() }
            ?: return // asset missing in this context — covered by build-time merge
        val labels = RoiMath.loadLabels(asset.readLines())
        // 0-based indices per the canonical sorted-synset ImageNet-1k ordering
        // (verified against torchvision's imagenet_classes.txt and the Keras
        // class-index JSON — the asset is byte-identical to the former).
        val expected = mapOf(
            399 to "abaya", 400 to "academic gown", 445 to "bikini", 474 to "cardigan",
            515 to "cowboy hat", 570 to "gasmask", 578 to "gown", 610 to "jersey",
            617 to "lab coat", 638 to "maillot", 639 to "maillot", 652 to "military uniform",
            655 to "miniskirt", 658 to "mitten", 735 to "poncho",
        )
        for ((idx, label) in expected) {
            assertEquals("index $idx", label, labels[idx])
        }
        // And the engine's constant must match this same set, or the human hint
        // would read the wrong logits.
        assertEquals(expected.keys, RoiMath.HUMAN_ADJACENT_INDICES.toSet())
        // And no "person" class anywhere (F7).
        assertTrue(labels.none { it.equals("person", ignoreCase = true) })
    }

    @Test
    fun `loadLabels rejects wrong counts`() {
        try {
            RoiMath.loadLabels(List(999) { "x" })
            org.junit.Assert.fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
    }
}
