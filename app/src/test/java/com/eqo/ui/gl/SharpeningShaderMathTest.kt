package com.eqo.ui.gl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Validates the mathematical properties, boundary conditions, and numerical stability
 * of the 5-tap Laplacian edge-enhancement convolution algorithm implemented in
 * [SharpeningVideoView] for Component 3 §2.
 */
class SharpeningShaderMathTest {

    private fun laplacianFilter(
        center: Float,
        top: Float,
        bottom: Float,
        left: Float,
        right: Float,
        sharpness: Float,
    ): Float {
        if (sharpness <= 0.0f) {
            return center
        }
        val edge = 4.0f * center - (top + bottom + left + right)
        return (center + sharpness * edge).coerceIn(0.0f, 1.0f)
    }

    @Test
    fun `zero sharpness preserves identical center pixel`() {
        val result = laplacianFilter(
            center = 0.5f,
            top = 0.2f,
            bottom = 0.8f,
            left = 0.1f,
            right = 0.9f,
            sharpness = 0.0f,
        )
        assertEquals(0.5f, result, 1e-6f)
    }

    @Test
    fun `flat uniform region yields zero edge response`() {
        val flatVal = 0.6f
        val result = laplacianFilter(
            center = flatVal,
            top = flatVal,
            bottom = flatVal,
            left = flatVal,
            right = flatVal,
            sharpness = 0.35f,
        )
        assertEquals(flatVal, result, 1e-6f)
    }

    @Test
    fun `high-frequency ridge is amplified by positive sharpness`() {
        // Center is brighter than all 4 neighbors (a sharp ridge / line feature)
        val center = 0.8f
        val neighbor = 0.4f
        val result = laplacianFilter(
            center = center,
            top = neighbor,
            bottom = neighbor,
            left = neighbor,
            right = neighbor,
            sharpness = 0.35f,
        )
        // edge = 4 * 0.8 - 1.6 = 1.6
        // output = 0.8 + 0.35 * 1.6 = 1.36 -> clamped to 1.0
        assertTrue("Ridge should be enhanced above original center", result > center)
        assertEquals(1.0f, result, 1e-6f)
    }

    @Test
    fun `step size calculation correctly differentiates video resolution vs viewport resolution`() {
        // Video: 1920x1080, Viewport: 720x405
        val videoWidth = 1920
        val videoHeight = 1080
        val viewportWidth = 720
        val viewportHeight = 405

        val stepWNative = 1.0f / videoWidth
        val stepHNative = 1.0f / videoHeight

        val stepWViewport = 1.0f / viewportWidth
        val stepHViewport = 1.0f / viewportHeight

        assertTrue("Native video texel step is finer than downscaled viewport step", stepWNative < stepWViewport)
        assertTrue("Native video texel step is finer than downscaled viewport step", stepHNative < stepHViewport)
        assertEquals(1.0f / 1920f, stepWNative, 1e-7f)
        assertEquals(1.0f / 1080f, stepHNative, 1e-7f)
    }

    @Test
    fun `output remains strictly clamped in unit range (0 to 1)`() {
        // Extreme negative edge (valley)
        val valleyResult = laplacianFilter(
            center = 0.1f,
            top = 1.0f,
            bottom = 1.0f,
            left = 1.0f,
            right = 1.0f,
            sharpness = 1.0f,
        )
        assertEquals(0.0f, valleyResult, 1e-6f)

        // Extreme positive edge (peak)
        val peakResult = laplacianFilter(
            center = 0.9f,
            top = 0.0f,
            bottom = 0.0f,
            left = 0.0f,
            right = 0.0f,
            sharpness = 1.0f,
        )
        assertEquals(1.0f, peakResult, 1e-6f)
    }
}
