package com.eqo.pipeline

import android.net.Uri

/**
 * Outcome of one successful transcode run (Component 3).
 *
 * @property outputUri    MediaStore Uri of the written .mp4 (Movies/eqo).
 * @property displayName  Output display name (Gallery / file managers).
 * @property inputBytes   Size of the whole source file (container + all tracks).
 * @property outputBytes  Size of the written output file.
 * @property codecName    Simple name of the encoder that was used.
 * @property mime         Output video MIME type (video/hevc or video/avc).
 * @property framesEncoded Frames submitted to the encoder (VFR drops included).
 * @property visualIntegrityPercent Optional mean SSIM (0–100) between sampled
 *   source and output frames — the "Trust Verification" badge
 *   (component_three.md NEW §3). Null when sampling didn't run or matched
 *   nothing (verification is best effort, never fatal).
 */
data class TranscodeResult(
    val outputUri: Uri,
    val displayName: String,
    val inputBytes: Long,
    val outputBytes: Long,
    val codecName: String,
    val mime: String,
    val framesEncoded: Int,
    val audioPassthrough: Boolean,
    val visualIntegrityPercent: Float? = null,
) {
    val savedBytes: Long get() = (inputBytes - outputBytes).coerceAtLeast(0)
    val savedPercent: Float
        get() = if (inputBytes > 0) savedBytes * 100f / inputBytes else 0f
}
