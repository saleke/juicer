package com.eqo.pipeline

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the codec-name heuristics (design review, fact R4): Google's software
 * families (`OMX.google.*`, `c2.android.*`) must not be preferred over vendor
 * hardware codecs.
 */
class HardwareCodecSelectorTest {

    @Test
    fun `google software codec families are detected as software`() {
        assertTrue(HardwareCodecSelector.isSoftwareName("OMX.google.h264.decoder"))
        assertTrue(HardwareCodecSelector.isSoftwareName("c2.android.avc.decoder"))
        assertTrue(HardwareCodecSelector.isSoftwareName("c2.google.avc.decoder"))
    }

    @Test
    fun `vendor codecs are detected as hardware`() {
        assertTrue(HardwareCodecSelector.isHardwareName("OMX.qcom.video.decoder.avc"))
        assertTrue(HardwareCodecSelector.isHardwareName("OMX.MTK.VIDEO.DECODER.AVC"))
        assertTrue(HardwareCodecSelector.isHardwareName("c2.qti.avc.decoder"))
        assertTrue(HardwareCodecSelector.isHardwareName("c2.mtk.avc.decoder"))
        assertTrue(HardwareCodecSelector.isHardwareName("OMX.Exynos.AVC.Decoder"))
    }

    @Test
    fun `software detection is case-insensitive`() {
        assertTrue(HardwareCodecSelector.isSoftwareName("omx.google.h264.decoder"))
        assertTrue(HardwareCodecSelector.isSoftwareName("C2.ANDROID.AVC.DECODER"))
        assertTrue(HardwareCodecSelector.isSoftwareName("c2.android.av1.encoder"))
    }

    @Test
    fun `av1 hardware encoders are properly recognized`() {
        assertTrue(HardwareCodecSelector.isHardwareName("c2.qti.av1.encoder"))
        assertTrue(HardwareCodecSelector.isHardwareName("c2.mtk.av1.encoder"))
        assertFalse(HardwareCodecSelector.isHardwareName("c2.android.av1.encoder"))
    }
}
