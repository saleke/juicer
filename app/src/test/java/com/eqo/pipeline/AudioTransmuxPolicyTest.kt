package com.eqo.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests covering track selection, timestamp sanitization, and EOF handling
 * in [AudioTransmuxPolicy].
 */
class AudioTransmuxPolicyTest {

    @Test
    fun `selectBestAudioTrack returns null when no audio tracks present`() {
        assertNull(AudioTransmuxPolicy.selectBestAudioTrack(emptyList()))
    }

    @Test
    fun `selectBestAudioTrack prioritizes AAC over uncompressed PCM and AC3`() {
        val tracks = listOf(
            AudioTransmuxPolicy.TrackInfo(index = 0, mime = "audio/raw"),
            AudioTransmuxPolicy.TrackInfo(index = 1, mime = "audio/ac3"),
            AudioTransmuxPolicy.TrackInfo(index = 2, mime = "audio/mp4a-latm"),
        )
        val selected = AudioTransmuxPolicy.selectBestAudioTrack(tracks)
        assertEquals(2, selected)
    }

    @Test
    fun `selectBestAudioTrack prioritizes Opus when AAC is absent`() {
        val tracks = listOf(
            AudioTransmuxPolicy.TrackInfo(index = 0, mime = "audio/vorbis"),
            AudioTransmuxPolicy.TrackInfo(index = 1, mime = "audio/opus"),
            AudioTransmuxPolicy.TrackInfo(index = 2, mime = "audio/raw"),
        )
        val selected = AudioTransmuxPolicy.selectBestAudioTrack(tracks)
        assertEquals(1, selected)
    }

    @Test
    fun `selectBestAudioTrack falls back to first track when none are preferred`() {
        val tracks = listOf(
            AudioTransmuxPolicy.TrackInfo(index = 3, mime = "audio/unknown-custom"),
            AudioTransmuxPolicy.TrackInfo(index = 4, mime = "audio/x-unknown"),
        )
        val selected = AudioTransmuxPolicy.selectBestAudioTrack(tracks)
        assertEquals(3, selected)
    }

    @Test
    fun `sanitizePresentationTimestamp clamps negative priming delay to zero`() {
        // Encoders can output negative initial PTS (e.g. -1000us) which MediaMuxer rejects
        val sanitized = AudioTransmuxPolicy.sanitizePresentationTimestamp(
            sampleTimeUs = -1000L,
            lastPtsUs = -1L,
        )
        assertEquals(0L, sanitized)
    }

    @Test
    fun `sanitizePresentationTimestamp preserves strictly increasing timestamps`() {
        val lastPts = 100_000L
        val nextPts = 120_000L
        val sanitized = AudioTransmuxPolicy.sanitizePresentationTimestamp(
            sampleTimeUs = nextPts,
            lastPtsUs = lastPts,
        )
        assertEquals(120_000L, sanitized)
    }

    @Test
    fun `sanitizePresentationTimestamp enforces monotonicity on regressing timestamps`() {
        // Out-of-order or jittered timestamp from container
        val lastPts = 150_000L
        val jitteredPts = 140_000L
        val sanitized = AudioTransmuxPolicy.sanitizePresentationTimestamp(
            sampleTimeUs = jitteredPts,
            lastPtsUs = lastPts,
        )
        assertEquals("Should hold lastPts to satisfy MediaMuxer monotonicity contract", lastPts, sanitized)
    }

    @Test
    fun `isEof correctly identifies negative sample times as end of stream`() {
        assertTrue(AudioTransmuxPolicy.isEof(-1L))
        assertTrue(AudioTransmuxPolicy.isEof(-100L))
        assertFalse(AudioTransmuxPolicy.isEof(0L))
        assertFalse(AudioTransmuxPolicy.isEof(42_000L))
    }
}
