package com.eqo.pipeline

import android.content.Context
import android.util.Log
import com.eqo.ai.JuicerAIEngine
import com.eqo.ai.SsimMath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger

/**
 * Components 1+2+3 wired together: the analysis pipeline with the hardware
 * re-encoder attached. The encoder is created lazily on the frame thread at
 * the first [passFrameToEncoder] call (the decoder backpressures through the
 * SurfaceTexture queue during that one-time init — no frames are lost, C3
 * pitfall P9), and finalized in [onDecodeComplete] so that COMPLETED
 * guarantees a complete output file.
 *
 * "Trust Verification" (component_three.md NEW §3): every [SSIM_INTERVAL]th
 * processed frame's 224² snapshot is copied out during the run; after the
 * muxer is finalized the output is decoded back in a lightweight post-pass
 * ([OutputFrameSampler]) and compared via SSIM — a real quality score for
 * the result card, best effort (never fails a successful transcode).
 */
class TranscodePipeline(
    context: Context,
    aiEngine: JuicerAIEngine = JuicerAIEngine(context),
    thermalGovernor: com.eqo.thermal.JuicerThermalGovernor = com.eqo.thermal.JuicerThermalGovernor(context),
    /** Target fraction of the source bitrate (user-facing quality knob). */
    private val targetFraction: Float = 0.5f,
) : JuicerPipeline(context, aiEngine, thermalGovernor) {

    companion object {
        private const val TAG = "eqo.Transcode"

        /**
         * Default SSIM sampling cadence (one source snapshot every 60 frames,
         * NEW §3), raised for long clips so the [SSIM_MAX_SAMPLES] budget
         * spreads across the whole duration instead of only the first minutes.
         */
        private const val SSIM_INTERVAL = 60

        /** Cap on stored snapshots (224²×3 ≈ 150 KB each). */
        private const val SSIM_MAX_SAMPLES = 32

        private val SNAPSHOT_BYTES =
            com.eqo.ai.RoiMath.SNAPSHOT_SIZE * com.eqo.ai.RoiMath.SNAPSHOT_SIZE *
                com.eqo.ai.RoiMath.BYTES_PER_PIXEL
    }

    /**
     * The muxer finalize waits for the encoder drain backlog plus the audio
     * passthrough. A fixed 30 s cap failed on-device for a 10-minute clip, so
     * the budget scales with clip duration (real-time worst case) on top of
     * the fixed allowance. It is a ceiling, not a delay.
     */
    private fun encoderFinishTimeoutMs(): Long {
        val durationSec = (metadata.value?.durationUs ?: 0L) / 1_000_000L
        return 30_000L + durationSec * 1_000L
    }

    /** SSIM cadence spread across the clip: ~[SSIM_MAX_SAMPLES] samples max. */
    private val ssimIntervalFrames: Int by lazy {
        val md = metadata.value
        val durationSec = (md?.durationUs ?: 0L) / 1_000_000L
        val fps = if ((md?.frameRate ?: -1) > 0) md!!.frameRate else 30
        maxOf(SSIM_INTERVAL, ((durationSec * fps) / SSIM_MAX_SAMPLES).toInt())
    }

    private val _result = MutableStateFlow<TranscodeResult?>(null)

    /** Set once the muxer is finalized; null until then / on failure. */
    val result: StateFlow<TranscodeResult?> = _result.asStateFlow()

    private var encoder: RoiVideoEncoder? = null

    /** Encoded-frame counter published into the metrics on the frame thread. */
    private val framesEncoded = AtomicInteger(0)

    /** SSIM source samples: timestamp (nanos) → packed-RGB snapshot copy. */
    private val ssimSamples = LinkedHashMap<Long, ByteArray>()
    private var framesSinceSample = 0

    override fun processFrameWithAI(rgb: ByteBuffer, timestampNanos: Long) {
        super.processFrameWithAI(rgb, timestampNanos)
        // Trust Verification sampling (NEW §3): copy out the snapshot on the
        // frame thread — the buffer is reused by the next frame.
        if (ssimSamples.size < SSIM_MAX_SAMPLES && ++framesSinceSample >= ssimIntervalFrames) {
            framesSinceSample = 0
            ssimSamples[timestampNanos] = ByteArray(SNAPSHOT_BYTES).also { arr ->
                rgb.duplicate().get(arr)
            }
        }
    }

    override fun passFrameToEncoder(roiMap: ByteArray?, timestampNanos: Long) {
        val uri = sourceUri ?: return
        val md = metadata.value ?: return

        // First frame: create the encoder (MediaStore entry, muxer, codec,
        // EGL window surface) here on the frame thread — the GL context
        // owner. Any throw propagates to the frame-path guard → run ERROR.
        val enc = encoder ?: RoiVideoEncoder(context, uri, md, targetFraction).also {
            it.start(renderer)
            encoder = it
            Log.i(TAG, "encoder attached (lazy, first frame)")
        }

        enc.onFrame(roiMap, timestampNanos, renderer)
        mutateMetrics { m ->
            m.copy(
                framesEncoded = framesEncoded.incrementAndGet(),
                encodedBytes = enc.bytesWritten(),
                appliedBitrate = enc.currentBitrate,
            )
        }
    }

    override suspend fun onDecodeComplete() {
        val enc = encoder ?: throw PipelineException("no frames were encoded")
        enc.signalEndOfStream()
        val timeoutMs = encoderFinishTimeoutMs()
        // The latch wait can now span minutes on long clips — block an IO
        // thread, not the pipeline dispatcher.
        val finished = withContext(Dispatchers.IO) { enc.awaitCompletion(timeoutMs) }
        if (!finished) {
            throw PipelineException("encoder did not finish within ${timeoutMs / 1000}s")
        }
        enc.error?.let { throw PipelineException(it) }
        if (enc.state != RoiVideoEncoder.State.DONE) {
            throw PipelineException("encoder ended in state ${enc.state}")
        }
        _result.value = enc.buildResult(framesEncoded.get()).let { base ->
            base.copy(visualIntegrityPercent = computeVisualIntegrity(base))
        }
        val r = _result.value!!
        val ssimStr = r.visualIntegrityPercent?.let { "%.1f%%".format(it) } ?: "n/a"
        val savedPercentStr = "%.1f%%".format(r.savedPercent)
        Log.i(
            TAG,
            "transcode complete: ${r.codecName} ${r.mime} in=${r.inputBytes} out=${r.outputBytes} " +
                "saved=${r.savedBytes} ($savedPercentStr) frames=${r.framesEncoded} audio=${r.audioPassthrough} " +
                "ssim=$ssimStr",
        )
    }

    /**
     * Best-effort SSIM pass over the sampled frames (never returns an
     * exception — a failed verification just yields null).
     */
    private fun computeVisualIntegrity(r: TranscodeResult): Float? {
        if (ssimSamples.isEmpty()) return null
        return runCatching {
            val matched = OutputFrameSampler(context).sample(r.outputUri, ssimSamples.keys.toList())
            if (matched.isEmpty()) return@runCatching null
            var total = 0f
            var count = 0
            for ((ts, source) in ssimSamples) {
                val output = matched[ts] ?: continue
                total += SsimMath.ssim(source, output)
                count++
            }
            if (count == 0) null else total / count * 100f
        }.onFailure {
            Log.w(TAG, "visual-integrity pass failed: ${it.message}")
        }.getOrNull()
    }

    override fun release() {
        // Two-phase (C3 P6): the cancel flag makes the frame thread stop
        // touching the codec; super.release() tears the renderer down ON the
        // frame thread (destroying the encoder EGL surface with the correct
        // ordering); only then is the codec/muxer released.
        encoder?.requestCancel()
        super.release()
        encoder?.completeCancel()
        encoder = null
    }
}
