package com.eqo.pipeline

/**
 * Essential video metadata extracted by [ZeroCopyVideoPipeline.prepare].
 *
 * @property width         Coded frame width in pixels (pre-rotation).
 * @property height        Coded frame height in pixels (pre-rotation).
 * @property bitrate       Track bitrate in bits per second; 0 when the container
 *                         does not advertise one (displayed as "unknown").
 * @property frameRate     Nominal frame rate in fps; -1 when absent.
 * @property colorFormat   Decoder color format constant from
 *                         [android.media.MediaCodecInfo.CodecCapabilities].
 * @property mime          Container/codec MIME type, e.g. `video/avc`.
 * @property rotationDegrees  Clockwise rotation the container requests for display.
 *                         Decoded GPU textures are always in coded (unrotated)
 *                         orientation — see the design review, pitfall P9.
 */
data class VideoMetadata(
    val width: Int,
    val height: Int,
    val bitrate: Int,
    val frameRate: Int,
    val colorFormat: Int,
    val mime: String,
    val rotationDegrees: Int,
    /** Track duration in microseconds; 0 when absent. With the file size this
     *  yields the actual average bitrate — the declared one can be wildly off. */
    val durationUs: Long = 0L,
) {
    val resolution: String get() = "${width}x${height}"
}
