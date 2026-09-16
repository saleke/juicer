package com.eqo.ai

/**
 * Pure SSIM (Structural Similarity Index, Wang et al. 2004) over two
 * 224×224 packed-RGB snapshots — the "Trust Verification" metric
 * (component_three.md NEW §3).
 *
 * Luma is derived per pixel (Rec. 601), then SSIM is averaged over
 * non-overlapping 8×8 windows (the reference implementation's block size),
 * which is more sensitive to local structure loss than a single global
 * window. Identical inputs score exactly 1.0.
 *
 * Free of Android dependencies for JVM unit testing.
 */
object SsimMath {

    const val SNAPSHOT_SIZE = RoiMath.SNAPSHOT_SIZE
    const val BYTES_PER_PIXEL = RoiMath.BYTES_PER_PIXEL

    /** Non-overlapping window edge (reference implementation: 8). */
    private const val WINDOW = 8

    /** Stabilizer constants for the dynamic-range terms (L=255, K1=0.01, K2=0.03). */
    private const val C1 = (0.01 * 255 * 0.01 * 255).toFloat()
    private const val C2 = (0.03 * 255 * 0.03 * 255).toFloat()

    /**
     * Mean SSIM between two packed-RGB snapshots ([RoiMath.SNAPSHOT_SIZE]²,
     * 3 bytes per pixel, top-down — the pipeline snapshot format).
     * Returns a value in [-1, 1]; 1 = structurally identical.
     */
    fun ssim(a: ByteArray, b: ByteArray): Float {
        require(a.size >= SNAPSHOT_SIZE * SNAPSHOT_SIZE * BYTES_PER_PIXEL) { "a too small: ${a.size}" }
        require(b.size >= SNAPSHOT_SIZE * SNAPSHOT_SIZE * BYTES_PER_PIXEL) { "b too small: ${b.size}" }

        val la = lumaPlane(a)
        val lb = lumaPlane(b)
        val n = SNAPSHOT_SIZE / WINDOW
        var total = 0f
        for (wy in 0 until n) {
            for (wx in 0 until n) {
                total += windowSsim(la, lb, wx * WINDOW, wy * WINDOW)
            }
        }
        return total / (n * n)
    }

    /** One 8×8 window's SSIM. */
    private fun windowSsim(la: FloatArray, lb: FloatArray, x0: Int, y0: Int): Float {
        val count = WINDOW * WINDOW
        var ma = 0f
        var mb = 0f
        var i = 0
        for (y in 0 until WINDOW) {
            val row = (y0 + y) * SNAPSHOT_SIZE + x0
            for (x in 0 until WINDOW) {
                ma += la[row + x]
                mb += lb[row + x]
            }
        }
        ma /= count
        mb /= count

        var va = 0f
        var vb = 0f
        var cov = 0f
        for (y in 0 until WINDOW) {
            val row = (y0 + y) * SNAPSHOT_SIZE + x0
            for (x in 0 until WINDOW) {
                val da = la[row + x] - ma
                val db = lb[row + x] - mb
                va += da * da
                vb += db * db
                cov += da * db
            }
        }
        va /= count - 1
        vb /= count - 1
        cov /= count - 1

        val num = (2 * ma * mb + C1) * (2 * cov + C2)
        val den = (ma * ma + mb * mb + C1) * (va + vb + C2)
        return num / den
    }

    /** Rec. 601 luma of a packed-RGB snapshot, one float per pixel. */
    private fun lumaPlane(rgb: ByteArray): FloatArray {
        val out = FloatArray(SNAPSHOT_SIZE * SNAPSHOT_SIZE)
        for (p in out.indices) {
            val i = p * BYTES_PER_PIXEL
            out[p] =
                0.299f * (rgb[i].toInt() and 0xFF) +
                0.587f * (rgb[i + 1].toInt() and 0xFF) +
                0.114f * (rgb[i + 2].toInt() and 0xFF)
        }
        return out
    }
}
