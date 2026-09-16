package com.eqo.pipeline

import android.media.MediaCodecInfo
import android.media.MediaCodecList

/**
 * Chooses a decoder by explicitly scanning [MediaCodecList] and preferring hardware
 * implementations (spec, Step B).
 *
 * Note on the spec's codec prefixes: `c2.android.*` (and the legacy `OMX.google.*`)
 * are Google's *software* codec families. The spec's intent — "explicitly look for
 * hardware decoders using MediaCodecList" — is honored by preferring codecs flagged
 * [MediaCodecInfo.isHardwareAccelerated], with the name heuristic as a fallback and
 * any available decoder as a last resort (design review, fact R4 / pitfall P8).
 */
object HardwareCodecSelector {

    /** Codec simple-name prefixes of Google's software decoder families. */
    private val SOFTWARE_NAME_PREFIXES = listOf("OMX.google.", "c2.android.", "c2.google.")

    /** Heuristic: is this codec name from a software implementation? */
    fun isSoftwareName(codecName: String): Boolean =
        SOFTWARE_NAME_PREFIXES.any { codecName.startsWith(it, ignoreCase = true) }

    /** Heuristic: is this codec name plausibly a hardware implementation? */
    fun isHardwareName(codecName: String): Boolean = !isSoftwareName(codecName)

    /**
     * Picks the best decoder for [mime] from [codecList], preferring:
     * 1. codecs flagged hardware-accelerated,
     * 2. codecs whose name suggests a hardware implementation,
     * 3. any decoder for the mime (software fallback).
     *
     * Returns null when no decoder exists for the mime.
     */
    fun selectDecoder(mime: String, codecList: MediaCodecList): MediaCodecInfo? =
        select(codecList, mime) { info -> !info.isEncoder }

    /**
     * Picks the best encoder for [mime] from [codecList] with the same
     * hardware preference as [selectDecoder] (spec, Component 3 Step A:
     * hardware encoders only — the software `c2.android.*` families are a
     * loud last resort, not a preference).
     *
     * Returns null when no encoder exists for the mime.
     */
    fun selectEncoder(mime: String, codecList: MediaCodecList): MediaCodecInfo? =
        select(codecList, mime) { info -> info.isEncoder }

    /**
     * Picks a strictly hardware-accelerated encoder for [mime].
     * Returns null if only software encoders exist for this mime (e.g. c2.android.av1.encoder),
     * preventing severe frame rate drops when evaluating high-tier codecs.
     */
    fun selectHardwareOnlyEncoder(mime: String, codecList: MediaCodecList): MediaCodecInfo? {
        val candidates = codecList.codecInfos.filter { info ->
            info.isEncoder && supportsType(info, mime)
        }
        return candidates.firstOrNull { it.isHardwareAccelerated }
            ?: candidates.firstOrNull { isHardwareName(it.name) && !isSoftwareName(it.name) }
    }

    private fun select(
        codecList: MediaCodecList,
        mime: String,
        filter: (MediaCodecInfo) -> Boolean,
    ): MediaCodecInfo? {
        val candidates = codecList.codecInfos.filter { info ->
            filter(info) && supportsType(info, mime)
        }
        return candidates.firstOrNull { it.isHardwareAccelerated }
            ?: candidates.firstOrNull { isHardwareName(it.name) }
            ?: candidates.firstOrNull()
    }

    private fun supportsType(info: MediaCodecInfo, mime: String): Boolean = try {
        // getCapabilitiesForType throws IllegalArgumentException for unsupported types,
        // so this doubles as an availability check.
        info.getCapabilitiesForType(mime) != null
    } catch (_: IllegalArgumentException) {
        false
    }
}
