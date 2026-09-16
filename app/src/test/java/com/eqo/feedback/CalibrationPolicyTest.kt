package com.eqo.feedback

import org.junit.Assert.assertEquals
import org.junit.Test

class CalibrationPolicyTest {

    private val defaultProfile = TargetCompressionProfile(category = "nature")

    @Test
    fun `saved and shared with high quality and low heat rewards compression aggressiveness`() {
        val metrics = CompressionSessionMetrics(
            videoCategory = "nature",
            inputBytes = 10_000_000,
            outputBytes = 3_000_000,
            savedPercent = 70.0f,
            thermalDeltaCelsius = 1.5f,
            visualIntegrityPercent = 97.5f,
            userAction = UserAction.SAVED_AND_SHARED,
        )

        val updated = CalibrationPolicy.calibrate(defaultProfile, metrics)
        // 0.50 - 0.02 = 0.48
        assertEquals(0.48f, updated.targetFraction, 0.001f)
        assertEquals(1, updated.sessionCount)
    }

    @Test
    fun `kept offline with high quality rewards compression aggressiveness`() {
        val metrics = CompressionSessionMetrics(
            videoCategory = "nature",
            inputBytes = 10_000_000,
            outputBytes = 3_000_000,
            savedPercent = 70.0f,
            thermalDeltaCelsius = 2.0f,
            visualIntegrityPercent = 96.0f,
            userAction = UserAction.KEPT_OFFLINE,
        )

        val updated = CalibrationPolicy.calibrate(defaultProfile, metrics)
        assertEquals(0.48f, updated.targetFraction, 0.001f)
    }

    @Test
    fun `high thermal delta denies reward even if user kept video`() {
        val metrics = CompressionSessionMetrics(
            videoCategory = "nature",
            inputBytes = 10_000_000,
            outputBytes = 3_000_000,
            savedPercent = 70.0f,
            thermalDeltaCelsius = 5.2f, // Above 4.0C threshold
            visualIntegrityPercent = 98.0f,
            userAction = UserAction.SAVED_AND_SHARED,
        )

        val updated = CalibrationPolicy.calibrate(defaultProfile, metrics)
        assertEquals(0.50f, updated.targetFraction, 0.001f)
    }

    @Test
    fun `borderline visual integrity denies reward`() {
        val metrics = CompressionSessionMetrics(
            videoCategory = "nature",
            inputBytes = 10_000_000,
            outputBytes = 2_000_000,
            savedPercent = 80.0f,
            thermalDeltaCelsius = 1.0f,
            visualIntegrityPercent = 91.0f, // Below 95.0% threshold
            userAction = UserAction.SAVED_AND_SHARED,
        )

        val updated = CalibrationPolicy.calibrate(defaultProfile, metrics)
        assertEquals(0.50f, updated.targetFraction, 0.001f)
    }

    @Test
    fun `promptly deleted with low visual integrity applies penalty`() {
        val metrics = CompressionSessionMetrics(
            videoCategory = "nature",
            inputBytes = 10_000_000,
            outputBytes = 2_000_000,
            savedPercent = 80.0f,
            thermalDeltaCelsius = 1.0f,
            visualIntegrityPercent = 88.0f, // Clear degradation
            userAction = UserAction.PROMPTLY_DELETED,
        )

        val updated = CalibrationPolicy.calibrate(defaultProfile, metrics)
        // 0.50 + 0.04 = 0.54
        assertEquals(0.54f, updated.targetFraction, 0.001f)
    }

    @Test
    fun `promptly deleted with null SSIM applies penalty`() {
        val metrics = CompressionSessionMetrics(
            videoCategory = "nature",
            inputBytes = 10_000_000,
            outputBytes = 2_000_000,
            savedPercent = 80.0f,
            thermalDeltaCelsius = 1.0f,
            visualIntegrityPercent = null,
            userAction = UserAction.PROMPTLY_DELETED,
        )

        val updated = CalibrationPolicy.calibrate(defaultProfile, metrics)
        assertEquals(0.54f, updated.targetFraction, 0.001f)
    }

    @Test
    fun `promptly deleted with high SSIM is protected from penalty`() {
        // High visual integrity means deletion was content-driven, not compression failure
        val metrics = CompressionSessionMetrics(
            videoCategory = "nature",
            inputBytes = 10_000_000,
            outputBytes = 4_000_000,
            savedPercent = 60.0f,
            thermalDeltaCelsius = 1.0f,
            visualIntegrityPercent = 97.8f, // Excellent fidelity
            userAction = UserAction.PROMPTLY_DELETED,
        )

        val updated = CalibrationPolicy.calibrate(defaultProfile, metrics)
        assertEquals(0.50f, updated.targetFraction, 0.001f) // Untouched!
    }

    @Test
    fun `calibration adheres to minimum and maximum bounds`() {
        var profile = defaultProfile
        val rewardMetrics = CompressionSessionMetrics(
            videoCategory = "nature",
            inputBytes = 10_000_000,
            outputBytes = 3_000_000,
            savedPercent = 70.0f,
            thermalDeltaCelsius = 1.0f,
            visualIntegrityPercent = 99.0f,
            userAction = UserAction.SAVED_AND_SHARED,
        )

        // Apply 20 consecutive rewards
        for (i in 0 until 20) {
            profile = CalibrationPolicy.calibrate(profile, rewardMetrics)
        }
        assertEquals(TargetCompressionProfile.MIN_TARGET_FRACTION, profile.targetFraction, 0.001f)

        val penaltyMetrics = CompressionSessionMetrics(
            videoCategory = "nature",
            inputBytes = 10_000_000,
            outputBytes = 2_000_000,
            savedPercent = 80.0f,
            thermalDeltaCelsius = 1.0f,
            visualIntegrityPercent = 80.0f,
            userAction = UserAction.PROMPTLY_DELETED,
        )

        // Apply 20 consecutive penalties
        for (i in 0 until 20) {
            profile = CalibrationPolicy.calibrate(profile, penaltyMetrics)
        }
        assertEquals(TargetCompressionProfile.MAX_TARGET_FRACTION, profile.targetFraction, 0.001f)
    }

    @Test
    fun `cancelled action produces zero changes`() {
        val metrics = CompressionSessionMetrics(
            videoCategory = "nature",
            inputBytes = 10_000_000,
            outputBytes = 0,
            savedPercent = 0f,
            thermalDeltaCelsius = 0f,
            visualIntegrityPercent = null,
            userAction = UserAction.CANCELLED,
        )

        val updated = CalibrationPolicy.calibrate(defaultProfile, metrics)
        assertEquals(defaultProfile, updated)
    }
}
