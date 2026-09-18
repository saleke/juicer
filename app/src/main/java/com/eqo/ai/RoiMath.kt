package com.eqo.ai

/**
 * Pure ROI math for the Component 2 hybrid engine (C2 design review, P1).
 *
 * Frame pixels are consumed as a top-down, 224×224, packed-RGB byte snapshot
 * produced by the GL downscale blit. This object holds only the math — no
 * Android dependency — so it is unit-testable on the JVM.
 *
 * Pipeline per frame:
 *  1. [varianceWeights]: 16×16 grid of luma-variance saliency.
 *  2. [smooth]: one 3×3 box pass to suppress QP thrash (P8).
 *  3. [dilateCushion]: 1-cell halo around detail-rich cells (spatial memory).
 *  4. [temporalBlendAsymmetric]: fast-attack / slow-release EMA — the grid
 *     "remembers" recently detailed regions while subjects are still nearby.
 *  5. [modulateForHuman]: classifier-driven boost scaled by a soft human score.
 *  6. [isMostlyStatic]: mean |Δluma| test for the spec's skip-frame logic (P9).
 */
object RoiMath {

    /** Grid side — matches the encoder's macroblock addressing (spec). */
    const val GRID = 16

    /** Downscaled frame side the GL leg produces. */
    const val SNAPSHOT_SIZE = 224

    /** Bytes per pixel in the RGB snapshot. */
    const val BYTES_PER_PIXEL = 3

    /** Side of one grid cell in snapshot pixels (224 / 16). */
    const val CELL = SNAPSHOT_SIZE / GRID

    /** Temporal EMA factor: `w = (1-α)·prev + α·new`. */
    const val EMA_ALPHA = 0.3f

    /**
     * Asymmetric temporal blur: fast attack when detail rises, slow release
     * when it falls. Release ≈ 7%/frame keeps ~50% of a region's weight for
     * ~10 frames after the detail leaves — a smoothing "memory" so a subject
     * that just exited a scene doesn't instantly plumb the budget (the binary
     *-switch blur the user reported).
     */
    const val ASYMMETRIC_ATTACK_ALPHA = 0.5f
    const val ASYMMETRIC_RELEASE_ALPHA = 0.07f

    /** Peak weight retained by [dilateCushion] for a strong neighbor cell. */
    const val DILATE_FALLOFF = 0.65f

    /** Strong weight above which a cell seeds a dilation halo (edge proxy). */
    const val DILATE_THRESHOLD = 0.55f

    /** Soft human-score smoothing: rise fast on evidence, decay slowly off. */
    const val HUMAN_SCORE_ATTACK_ALPHA = 0.4f
    const val HUMAN_SCORE_RELEASE_ALPHA = 0.08f

    /** Mean |Δluma| below this (0–255 scale) counts as a static scene (P9). */
    const val STATIC_THRESHOLD = 2.0f

    /**
     * ImageNet-1k indices of human-adjacent classes (F7: ImageNet-1k has no
     * "person" class). Kept beside [loadLabels] so a label-file change is caught
     * by the unit test asserting these resolve to garment labels.
     */
    val HUMAN_ADJACENT_INDICES = intArrayOf(
        399, // abaya
        400, // academic gown
        445, // bikini
        474, // cardigan
        515, // cowboy hat
        570, // gasmask
        578, // gown
        610, // jersey
        617, // lab coat
        638, // maillot (swimsuit)
        639, // maillot (swimsuit variant)
        652, // military uniform
        655, // miniskirt
        658, // mitten
        735, // poncho
    )

    /** Weight applied to subject-region cells when a human is likely present. */
    const val HUMAN_BOOST = 0.35f

    /** Weight floor per the spec (0.2 = maximum compression background). */
    const val WEIGHT_FLOOR = 0.2f

    // ---------------------------------------------------------------- grid

    /**
     * Computes the 16×16 saliency grid from an RGB snapshot. Cell weight is the
     * normalized luma variance of its 14×14 pixel block: busy/detail-rich cells
     * (faces, text, edges) score high; flat cells (sky, walls) score low.
     *
     * `out` may be a reused array (16×16 floats, row-major) — no allocation here.
     */
    fun varianceWeights(rgb: ByteArray, out: FloatArray) {
        require(out.size == GRID * GRID) { "out must be ${GRID * GRID} floats" }
        require(rgb.size >= SNAPSHOT_SIZE * SNAPSHOT_SIZE * BYTES_PER_PIXEL) { "snapshot too small" }

        var maxVariance = 1e-6f
        for (gy in 0 until GRID) {
            for (gx in 0 until GRID) {
                val v = cellVariance(rgb, gx, gy)
                out[gy * GRID + gx] = v
                if (v > maxVariance) maxVariance = v
            }
        }
        // Normalize to 0..1 against the frame's busiest cell (P8's stability is
        // handled by smooth+temporalBlend; this keeps relative ordering only).
        val inv = 1f / maxVariance
        for (i in out.indices) out[i] *= inv
    }

    /**
     * Fused hot-path version of [meanAbsLumaDelta] + [varianceWeights]: ONE
     * pass over the snapshot produces the per-cell variance grid AND the
     * motion delta against [prevCellLuma] (updated in place). Halves the pixel
     * work vs. the two standalone functions, which remain as the tested
     * reference semantics.
     */
    fun gridStats(rgb: ByteArray, prevCellLuma: FloatArray, out: FloatArray): Float {
        require(out.size == GRID * GRID && prevCellLuma.size == GRID * GRID)
        require(rgb.size >= SNAPSHOT_SIZE * SNAPSHOT_SIZE * BYTES_PER_PIXEL) { "snapshot too small" }

        var maxVariance = 1e-6f
        var deltaTotal = 0f
        val n = CELL * CELL
        var cell = 0
        for (gy in 0 until GRID) {
            for (gx in 0 until GRID) {
                var rowIdx = (gy * CELL * SNAPSHOT_SIZE + gx * CELL) * BYTES_PER_PIXEL
                var sum = 0L
                var sumSq = 0L
                // luma() inlined: on the frame path this is ~50k calls/frame.
                for (y in 0 until CELL) {
                    var i = rowIdx
                    for (x in 0 until CELL) {
                        val l = ((rgb[i].toInt() and 0xFF) * 66 +
                            (rgb[i + 1].toInt() and 0xFF) * 129 +
                            (rgb[i + 2].toInt() and 0xFF) * 25 + 128) shr 8
                        sum += l
                        sumSq += l.toLong() * l
                        i += BYTES_PER_PIXEL
                    }
                    rowIdx += SNAPSHOT_SIZE * BYTES_PER_PIXEL
                }
                val mean = sum.toFloat() / n
                val v = (sumSq.toFloat() / n) - mean * mean
                out[cell] = v
                if (v > maxVariance) maxVariance = v
                deltaTotal += kotlin.math.abs(mean - prevCellLuma[cell])
                prevCellLuma[cell] = mean
                cell++
            }
        }
        val inv = 1f / maxVariance
        for (i in out.indices) out[i] *= inv
        return deltaTotal / (GRID * GRID)
    }

    /**
     * Rec.601 luma of the pixel at byte offset [i], fixed-point friendly.
     * Pixel bytes are unsigned — mask before widening or values ≥ 128 wrap
     * negative and create phantom edges at the 127/128 boundary.
     */
    private fun luma(rgb: ByteArray, i: Int): Int =
        ((rgb[i].toInt() and 0xFF) * 66 +
            (rgb[i + 1].toInt() and 0xFF) * 129 +
            (rgb[i + 2].toInt() and 0xFF) * 25 + 128) shr 8

    /** Luma variance of grid cell (gx, gy) in the RGB snapshot. */
    private fun cellVariance(rgb: ByteArray, gx: Int, gy: Int): Float {
        val x0 = gx * CELL
        val y0 = gy * CELL
        var sum = 0L
        var sumSq = 0L
        val n = CELL * CELL
        var idx = (y0 * SNAPSHOT_SIZE + x0) * BYTES_PER_PIXEL
        for (y in 0 until CELL) {
            for (x in 0 until CELL) {
                val l = luma(rgb, idx + x * BYTES_PER_PIXEL)
                sum += l
                sumSq += l.toLong() * l
            }
            idx += SNAPSHOT_SIZE * BYTES_PER_PIXEL
        }
        val mean = sum.toFloat() / n
        return (sumSq.toFloat() / n) - mean * mean
    }

    // ---------------------------------------------------------------- filters

    /**
     * One 3×3 box smoothing pass over the grid (edge-replicated). Suppresses
     * single-cell flicker that would thrash per-block QP decisions (P8).
     */
    fun smooth(src: FloatArray, dst: FloatArray) {
        require(src.size == GRID * GRID && dst.size == GRID * GRID)
        for (y in 0 until GRID) {
            for (x in 0 until GRID) {
                var acc = 0f
                for (dy in -1..1) {
                    val yy = (y + dy).coerceIn(0, GRID - 1)
                    for (dx in -1..1) {
                        val xx = (x + dx).coerceIn(0, GRID - 1)
                        acc += src[yy * GRID + xx]
                    }
                }
                dst[y * GRID + x] = acc / 9f
            }
        }
    }

    /**
     * Contrast-adaptive edge dilation: every cell in `src` with weight ≥
     * [DILATE_THRESHOLD] seeds a 1-cell halo into its 8 neighbors at
     * [DILATE_FALLOFF] strength (a Chebyshev radius-1, classical morphological
     * dilation). The halo raises the haloed cells above the [WEIGHT_FLOOR] so
     * a thin textured region (a face-sized patch, an area of sharp edges)
     * cannot collapse the global ROI mean on its own, and pushes
     * detail-boundary cells up so the encoder keeps texture around, not just
     * inside, the hot region. No allocation.
     *
     * `src` and `dst` MUST NOT alias: strong-cell detection reads `src only`
     * (the halo must never re-seed itself on the same pass); `dst` is
     * initialized to a copy of `src` and then raised. The caller owns both
     * buffers (the engine dilates `gridB → gridA` per frame).
     */
    fun dilateCushion(src: FloatArray, dst: FloatArray) {
        require(src.size == GRID * GRID && dst.size == GRID * GRID)
        require(src !== dst) { "src and dst must not alias" }
        System.arraycopy(src, 0, dst, 0, src.size)
        for (y in 0 until GRID) {
            for (x in 0 until GRID) {
                val i = y * GRID + x
                if (src[i] < DILATE_THRESHOLD) continue
                val raised = src[i] * DILATE_FALLOFF
                for (dy in -1..1) {
                    val yy = y + dy
                    if (yy !in 0 until GRID) continue
                    for (dx in -1..1) {
                        if (dy == 0 && dx == 0) continue
                        val xx = x + dx
                        if (xx !in 0 until GRID) continue
                        val j = yy * GRID + xx
                        if (raised > dst[j]) dst[j] = raised
                    }
                }
            }
        }
    }

    /**
     * Asymmetric temporal EMA: fast inward (attack) when new detail appears,
     * slow decay (release) when it disappears. `out = (1-α_i)·prev + α_i·new`
     * with `α_i = attack` when `new ≥ prev` else `release`. First call (all-zero
     * prev) copies `new`. Arrays may be reused in place (`out === prev` allowed).
     */
    fun temporalBlendAsymmetric(
        prev: FloatArray,
        new: FloatArray,
        out: FloatArray,
        attackAlpha: Float = ASYMMETRIC_ATTACK_ALPHA,
        releaseAlpha: Float = ASYMMETRIC_RELEASE_ALPHA,
    ) {
        require(prev.size == new.size && new.size == out.size)
        val first = prev.all { it == 0f }
        if (first) {
            for (i in out.indices) out[i] = new[i]
            return
        }
        for (i in out.indices) {
            val alpha = if (new[i] >= prev[i]) attackAlpha else releaseAlpha
            out[i] = (1f - alpha) * prev[i] + alpha * new[i]
        }
    }

    /**
     * Temporal EMA: `out = (1-α)·prev + α·new`. First call (all-zero prev) just
     * copies. Arrays may be reused in place (`out === prev` is allowed).
     */
    fun temporalBlend(prev: FloatArray, new: FloatArray, out: FloatArray, alpha: Float = EMA_ALPHA) {
        require(prev.size == new.size && new.size == out.size)
        val first = prev.all { it == 0f }
        if (first) {
            for (i in out.indices) out[i] = new[i]
            return
        }
        for (i in out.indices) {
            out[i] = (1f - alpha) * prev[i] + alpha * new[i]
        }
    }

    /**
     * Boosts weights in a centered subject region when the classifier says a
     * human is likely in frame (F7/F3). The region is deliberately generous
     * (center 12×12) because garment-only evidence localizes poorly.
     *
     * The boost is scaled by a *soft* human score in 0..1 (not a binary flag):
     * a flickering classifier verdict no longer snaps the whole grid up/down —
     * the caller smooths the score over time, so the boost feathers in and out
     * instead of switching like a light bulb.
     */
    fun modulateForHuman(grid: FloatArray, humanScore: Float) {
        if (humanScore <= 0f) return
        val span = 12
        val start = (GRID - span) / 2
        val boost = (HUMAN_BOOST * humanScore.coerceIn(0f, 1f)).coerceAtMost(HUMAN_BOOST)
        for (y in start until start + span) {
            for (x in start until start + span) {
                val i = y * GRID + x
                grid[i] = (grid[i] + boost).coerceAtMost(1f)
            }
        }
    }

    /** Boolean convenience overload (a scored 1.0 / 0.0 verdict). */
    fun modulateForHuman(grid: FloatArray, humanLikely: Boolean) =
        modulateForHuman(grid, if (humanLikely) 1f else 0f)

    /**
     * Asymmetric scalar step used to smooth the classifier's human score:
     * `current + α·(target − current)` with α chosen by direction — fast attack
     * when the target rises, slow release when it falls. Keeps the *field value*
     * here pure and JVM-testable; the engine owns the field itself.
     */
    fun asymmetricScoreStep(
        current: Float,
        target: Float,
        attackAlpha: Float = HUMAN_SCORE_ATTACK_ALPHA,
        releaseAlpha: Float = HUMAN_SCORE_RELEASE_ALPHA,
    ): Float {
        val alpha = if (target >= current) attackAlpha else releaseAlpha
        return current + alpha * (target - current)
    }

    // ---------------------------------------------------------------- motion

    /**
     * Mean absolute luma difference per cell between the current snapshot and
     * the previous frame's per-cell mean luma (caller-maintained). Returns the
     * delta and updates [prevCellLuma] in place.
     */
    fun meanAbsLumaDelta(rgb: ByteArray, prevCellLuma: FloatArray): Float {
        var total = 0f
        for (gy in 0 until GRID) {
            for (gx in 0 until GRID) {
                val cur = cellMeanLuma(rgb, gx, gy)
                val i = gy * GRID + gx
                total += kotlin.math.abs(cur - prevCellLuma[i])
                prevCellLuma[i] = cur
            }
        }
        return total / (GRID * GRID)
    }

    /** True when [delta] indicates a mostly static scene (spec skip-frame, P9). */
    fun isMostlyStatic(delta: Float): Boolean = delta < STATIC_THRESHOLD

    private fun cellMeanLuma(rgb: ByteArray, gx: Int, gy: Int): Float {
        val x0 = gx * CELL
        val y0 = gy * CELL
        var sum = 0L
        val n = CELL * CELL
        var idx = (y0 * SNAPSHOT_SIZE + x0) * BYTES_PER_PIXEL
        for (y in 0 until CELL) {
            for (x in 0 until CELL) {
                sum += luma(rgb, idx + x * BYTES_PER_PIXEL)
            }
            idx += SNAPSHOT_SIZE * BYTES_PER_PIXEL
        }
        return sum.toFloat() / n
    }

    // ---------------------------------------------------------------- encoding

    /**
     * Encodes the float grid into the Component 3 contract: one byte per cell,
     * `0x00..0xFF` mapping linearly to weight 0.0..1.0, clamped to the spec's
     * [WEIGHT_FLOOR] so no region is crushed below the 0.2 quality floor.
     */
    fun encodeToBytes(grid: FloatArray, out: ByteArray) {
        require(grid.size == GRID * GRID && out.size == GRID * GRID)
        for (i in grid.indices) {
            val w = grid[i].coerceIn(WEIGHT_FLOOR, 1f)
            out[i] = (w * 0xFF).toInt().coerceIn(0, 0xFF).toByte()
        }
    }

    /** Decodes a byte map back to float weights (diagnostics/tests). */
    fun decodeFromBytes(bytes: ByteArray, out: FloatArray) {
        require(bytes.size == GRID * GRID && out.size == GRID * GRID)
        for (i in bytes.indices) out[i] = (bytes[i].toInt() and 0xFF) / 255f
    }

    /**
     * Detail-weighted aggregate of a byte ROI map, in 0..1 (see [detailWeightedMean]).
     * Additionally writes the *overall* mean into [overallOut] (a length-1
     * scratch) so the caller can budget savings by total complexity while
     * steering bits toward detail — a slideshow is "low budget, spent where
     * the text is".
     */
    fun detailWeightedMeanWithOverall(map: ByteArray, histogram: IntArray, overallOut: FloatArray): Float {
        require(overallOut.size == 1)
        require(map.size == GRID * GRID)
        require(histogram.size == 256)
        java.util.Arrays.fill(histogram, 0)

        var sum = 0L
        for (b in map) {
            val w = b.toInt() and 0xFF
            histogram[w]++
            sum += w
        }
        val overall = sum / (GRID * GRID * 255f)
        overallOut[0] = overall

        // Mean of the busiest quartile by walking the byte histogram down from
        // 255 — no sort, one O(256) pass.
        val top = GRID * GRID / 4
        var remaining = top
        var topSum = 0L
        var level = 255
        while (remaining > 0 && level >= 0) {
            val take = minOf(histogram[level], remaining)
            topSum += take * level
            remaining -= take
            level--
        }
        val topQuartile = topSum / (top * 255f)
        return 0.5f * topQuartile + 0.5f * overall
    }

    /**
     * Detail-weighted aggregate of a byte ROI map, in 0..1. A plain mean
     * lets a lone subject drown in flat background — but since the ROI map
     * only ever drives the *global* bitrate (no public QP map, C3 F1), the
     * aggregate must still react to a small busy region. We blend the overall
     * mean with the *mean of the top quartile* (the busiest 64 cells):
     * `0.5·topQuartile + 0.5·overall`. A small face in a big flat scene lifts
     * the value enough to hold a decent budget; uniformly-flat content still
     * scores low.
     *
     * Hot path: prefer [detailWeightedMeanWithOverall] — it is allocation-free
     * (the histogram AND the length-1 result scratch are caller-owned). This
     * convenience allocates the one-element scratch per call and is for tests
     * and diagnostics.
     */
    fun detailWeightedMean(map: ByteArray, histogram: IntArray): Float {
        val scratch = FloatArray(1)
        return detailWeightedMeanWithOverall(map, histogram, scratch)
    }

    /** Loads the 1000-entry ImageNet label list, one label per line. */
    fun loadLabels(lines: List<String>): List<String> {
        require(lines.size == 1000) { "expected 1000 labels, got ${lines.size}" }
        return lines
    }
}
