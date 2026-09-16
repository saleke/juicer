package com.eqo.pipeline

/**
 * Encapsulates track negotiation and timestamp sanitization rules for zero-copy
 * audio transmuxing into Android [android.media.MediaMuxer] containers.
 */
object AudioTransmuxPolicy {

    /** MIME types natively supported by standard Android MPEG-4 muxers across all target API levels. */
    val PREFERRED_AUDIO_MIMES = listOf(
        "audio/mp4a-latm",
        "audio/opus",
        "audio/3gpp",
        "audio/amr-wb",
    )

    data class TrackInfo(
        val index: Int,
        val mime: String,
        val channelCount: Int = 2,
        val sampleRate: Int = 44100,
    )

    /**
     * Selects the best audio track index from available container tracks.
     * Prioritizes MPEG-4 natively supported formats (AAC, Opus) over exotic formats
     * (e.g. PCM / AC3) that would cause MediaMuxer to reject the track.
     */
    fun selectBestAudioTrack(tracks: List<TrackInfo>): Int? {
        if (tracks.isEmpty()) return null

        // 1. First priority: AAC (standard universal MP4 audio)
        val aac = tracks.firstOrNull { it.mime.equals("audio/mp4a-latm", ignoreCase = true) || it.mime.contains("mp4a", ignoreCase = true) }
        if (aac != null) return aac.index

        // 2. Second priority: Opus (modern efficient audio on API 29+)
        val opus = tracks.firstOrNull { it.mime.equals("audio/opus", ignoreCase = true) }
        if (opus != null) return opus.index

        // 3. Third priority: Any other preferred MPEG-4 mime
        val otherPreferred = tracks.firstOrNull { t ->
            PREFERRED_AUDIO_MIMES.any { pref -> t.mime.equals(pref, ignoreCase = true) }
        }
        if (otherPreferred != null) return otherPreferred.index

        // 4. Fallback: First available audio track
        return tracks.first().index
    }

    /**
     * Sanitizes presentation timestamps for [android.media.MediaMuxer.writeSampleData].
     *
     * In Android MediaMuxer:
     *  1. `bufferInfo.presentationTimeUs < 0` throws `IllegalArgumentException: bufferInfo is invalid`.
     *  2. Regressing timestamps (pts < lastPts) causes MediaMuxer write failures.
     *
     * This function normalizes negative priming delay timestamps to 0L and strictly enforces
     * non-decreasing monotonicity.
     */
    fun sanitizePresentationTimestamp(sampleTimeUs: Long, lastPtsUs: Long): Long {
        val nonNegative = sampleTimeUs.coerceAtLeast(0L)
        return if (nonNegative >= lastPtsUs) nonNegative else lastPtsUs
    }

    /**
     * Determines whether [MediaExtractor] has reached EOF.
     * A negative sample time (`-1L`) is the canonical end-of-stream indicator.
     */
    fun isEof(sampleTimeUs: Long): Boolean = sampleTimeUs < 0L
}
