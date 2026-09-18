package com.eqo.pipeline

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import com.eqo.ai.RoiMath
import com.eqo.ai.gl.SnapshotRenderer
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * ## eqo Component 3 — the hardware re-encoder
 *
 * Owns everything downstream of the frame thread's OES texture:
 *
 * ```
 * OES (frame thread) ─▶ encoder input Surface (EGL window surface on the
 *                       renderer's context — see SnapshotRenderer)
 *                  ─▶ hw MediaCodec encoder (HEVC → AVC fallback, VBR → CBR)
 *                  ─▶ drain thread ─▶ MediaMuxer(MediaStore fd)
 *                                        ├─ encoded video samples
 *                                        └─ passthrough audio samples
 * ```
 *
 * Quality control (C3 design review F1): the public API has no per-region QP
 * map, so the ROI map's aggregate complexity modulates the *global* bitrate
 * at runtime (`PARAMETER_KEY_VIDEO_BITRATE`), gated by [RoiBitrateController].
 *
 * Threading:
 *  - [start]/[onFrame] run on the pipeline's frame thread (GL context owner);
 *  - the drain thread ("eqo-encode-drain") is the single muxer writer and
 *    performs the audio passthrough after video EOS;
 *  - [signalEndOfStream]/[awaitCompletion] are called from the pipeline
 *    dispatcher once the decode loop settles.
 *
 * Lifecycle state machine: RUNNING → DONE | FAILED (terminal), CANCELED from
 * any pre-terminal state via [requestCancel]/[completeCancel].
 */
class RoiVideoEncoder(
    private val context: Context,
    private val sourceUri: Uri,
    private val metadata: VideoMetadata,
    /** Target fraction of the source bitrate (user-facing quality knob). */
    private val targetFraction: Float = 0.5f,
) {
    companion object {
        private const val TAG = "eqo.Encoder"

        /** AV1 (hardware-only), HEVC primary, AVC compatibility fallback (C3 F3). */
        private val HARDWARE_TIER_MIMES = listOf(
            MediaFormat.MIMETYPE_VIDEO_AV1,
            MediaFormat.MIMETYPE_VIDEO_HEVC,
            MediaFormat.MIMETYPE_VIDEO_AVC,
        )

        private const val I_FRAME_INTERVAL_S = 2.0f
        private const val DEFAULT_FRAME_RATE = 30
        private const val DRAIN_TIMEOUT_US = 10_000L
        private const val CANCEL_JOIN_TIMEOUT_MS = 2_000L
    }

    enum class State { RUNNING, DONE, FAILED, CANCELED }

    @Volatile
    var state: State = State.RUNNING
        private set

    /** Non-null after a drain-thread failure; surfaced by the pipeline. */
    val error: String? get() = failure.get()

    private val failure = AtomicReference<String?>(null)
    private val doneLatch = CountDownLatch(1)

    private val resolver = context.contentResolver

    // ---- set by start() on the frame thread ------------------------------
    private var codec: MediaCodec? = null
    private var codecName: String = ""
    private var outMime: String = ""
    private var muxer: MediaMuxer? = null
    private var outputUri: Uri? = null
    private var outputDisplayName: String = ""
    private var outputPfd: android.os.ParcelFileDescriptor? = null
    private var audioExtractor: MediaExtractor? = null
    private var audioFormat: MediaFormat? = null
    private var audioBuf: ByteBuffer? = null
    private val audioInfo = MediaCodec.BufferInfo()
    private var inputBytes: Long = 0
    private var drainThread: Thread? = null

    // ---- drain-thread owned ------------------------------------------------
    private var muxerStarted = false
    private var videoTrack = -1
    private var audioTrack = -1
    private var lastVideoPtsUs = -1L
    private var lastAudioPtsUs = -1L
    private val encodedBytes = AtomicLong(0)
    private val framesEncoded = AtomicLong(0)

    private val bitrateController: RoiBitrateController

    init {
        val fps = if (metadata.frameRate > 0) metadata.frameRate else DEFAULT_FRAME_RATE
        val base = BitrateMath.baseBitrate(
            metadata.bitrate, metadata.width, metadata.height, fps,
            actualBitrate = actualSourceBitrate(),
            fraction = targetFraction,
        )
        bitrateController = RoiBitrateController(
            baseBitrate = base,
            floorBitrate = BitrateMath.perceptualFloor(
                metadata.width, metadata.height, fps,
                budgetCeiling = base,
            ),
        )
    }

    /**
     * Actual average source bitrate: file size × 8 / track duration. The
     * declared track bitrate can be wildly off (on-device: 2.8 Mbps declared
     * vs 124 kbps actual), and budgeting from the declared value made the
     * output 13× LARGER than the input — MTK's VBR pads static content up to
     * the target. Returns 0 when either input is unknown.
     */
    private fun actualSourceBitrate(): Int {
        if (metadata.durationUs <= 0) return 0
        val bytes = querySize(sourceUri)
        if (bytes <= 0) return 0
        return (bytes * 8 / (metadata.durationUs / 1_000_000.0)).toInt()
    }

    /** The bitrate the encoder was configured/last adjusted to, bps. */
    val currentBitrate: Int get() = bitrateController.currentBitrate

    // ------------------------------------------------------------------ start

    /**
     * Creates the output file, muxer, and encoder, and binds the encoder's
     * input surface to [renderer]. Must be called on the frame thread (the
     * GL context owner), before the first [onFrame]. Throws on any failure —
     * the pipeline's frame-path guard turns it into a run failure.
     */
    fun start(renderer: SnapshotRenderer) {
        try {
            startInternal(renderer)
        } catch (t: Throwable) {
            // A partial start must not leak the MediaStore row / fd / muxer.
            releaseResources(deleteOutput = true)
            throw t
        }
    }

    private fun startInternal(renderer: SnapshotRenderer) {
        inputBytes = querySize(sourceUri)
        val sourceName = queryDisplayName(sourceUri) ?: "video"
        outputDisplayName = "eqo_" + sourceName.substringBeforeLast('.') +
            "_" + System.currentTimeMillis() + ".mp4"

        // --- output entry + muxer (MediaStore Movies/eqo, no permission) ---
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, outputDisplayName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/eqo")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = requireNotNull(
            resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values),
        ) { "MediaStore insert failed" }
        outputUri = uri
        outputPfd = resolver.openFileDescriptor(uri, "rw")
            ?: throw PipelineException("could not open output file for writing")
        // Coded (unrotated) dimensions go to the encoder; the muxer hint
        // restores display orientation (C3 F5).
        muxer = MediaMuxer(outputPfd!!.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            .also { it.setOrientationHint(metadata.rotationDegrees) }

        // --- audio passthrough track (F9): format known up-front -----------
        audioExtractor = MediaExtractor().also { ex ->
            MediaExtractorCompat.setDataSource(ex, context, sourceUri)
            val audioIdx = selectBestAudioTrack(ex)
            if (audioIdx != null) {
                ex.selectTrack(audioIdx)
                audioFormat = ex.getTrackFormat(audioIdx)
                audioBuf = ByteBuffer.allocateDirect(1 shl 20)
                Log.i(TAG, "selected audio track index=$audioIdx mime=${audioFormat?.getString(MediaFormat.KEY_MIME)}")
            } else {
                Log.i(TAG, "no audio track found in source")
            }
        }

        // --- encoder codec: HEVC → AVC, hardware preferred (F3) ------------
        val frameRate = if (metadata.frameRate > 0) metadata.frameRate else DEFAULT_FRAME_RATE
        val (info, mime) = selectEncoderCodec(metadata.width, metadata.height)
            ?: throw PipelineException("no hardware encoder found for ${HARDWARE_TIER_MIMES}")
        outMime = mime

        val format = MediaFormat.createVideoFormat(mime, metadata.width, metadata.height).apply {
            setInteger(
                MediaFormat.KEY_BITRATE_MODE,
                preferredBitrateMode(info, mime),
            )
            setInteger(MediaFormat.KEY_BIT_RATE, bitrateController.currentBitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setFloat(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL_S)
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
            )
        }

        val mc = MediaCodec.createByCodecName(info.name)
        try {
            mc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val inputSurface = mc.createInputSurface()
            mc.start()
            renderer.attachEncoderSurface(inputSurface, metadata.width, metadata.height)
        } catch (e: Exception) {
            runCatching { mc.release() }
            throw e
        }
        codec = mc
        codecName = info.name
        if (HardwareCodecSelector.isSoftwareName(info.name)) {
            Log.w(TAG, "selected SOFTWARE encoder $codecName — encode will be slow")
        }
        Log.i(
            TAG,
            "encoder=$codecName mime=$mime ${metadata.width}x${metadata.height} " +
                "rot=${metadata.rotationDegrees} declared=${metadata.bitrate} " +
                "actual=${actualSourceBitrate()} baseBitrate=${bitrateController.currentBitrate} " +
                "audio=${audioFormat != null}",
        )

        drainThread = Thread(::drainLoop, "eqo-encode-drain").apply {
            priority = Thread.NORM_PRIORITY + 1
            start()
        }
    }

    // ------------------------------------------------------------- frame path

    /**
     * Submits the current OES frame to the encoder. Frame thread only; called
     * once per encoded frame. Applies the ROI-driven bitrate adjustment (if
     * the controller emits one) before drawing.
     */
    fun onFrame(roiMap: ByteArray?, timestampNanos: Long, renderer: SnapshotRenderer) {
        val mc = codec ?: return
        if (state != State.RUNNING) return

        val roiMean = roiMean(roiMap)
        val newBitrate = bitrateController.onFrame(
            roiMean,
            SystemClock.elapsedRealtime(),
            roiMeanScratch[0],
        )
        if (newBitrate != null && state == State.RUNNING) {
            runCatching {
                mc.setParameters(bundleOf(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, newBitrate))
            }.onSuccess {
                Log.d(TAG, "bitrate → $newBitrate (roiMean=%.2f overall=%.2f)".format(roiMean, roiMeanScratch[0]))
            }
        }

        renderer.drawToEncoder(timestampNanos)
        framesEncoded.incrementAndGet()
    }

    // ------------------------------------------------------------- completion

    /** Signals that no more frames will be submitted. Pipeline dispatcher. */
    fun signalEndOfStream() {
        val mc = codec ?: return
        runCatching { mc.signalEndOfInputStream() }
            .onFailure { fail("signalEndOfInputStream failed: ${it.message}") }
    }

    /**
     * Waits for the drain thread to finish (video EOS + audio passthrough +
     * muxer stop). Returns false on timeout; check [error] afterwards.
     */
    fun awaitCompletion(timeoutMs: Long): Boolean =
        doneLatch.await(timeoutMs, TimeUnit.MILLISECONDS)

    /**
     * Builds the run's result. Valid only when [state] is [State.DONE].
     */
    fun buildResult(framesEncodedTotal: Int): TranscodeResult = TranscodeResult(
        outputUri = requireNotNull(outputUri) { "no output" },
        displayName = outputDisplayName,
        inputBytes = inputBytes,
        outputBytes = querySize(requireNotNull(outputUri)),
        codecName = codecName,
        mime = outMime,
        framesEncoded = framesEncodedTotal,
        audioPassthrough = audioTrack >= 0,
    )

    /** Encoded bytes so far (video + passthrough audio), for live metrics. */
    fun bytesWritten(): Long = encodedBytes.get()

    // ---------------------------------------------------------------- cancel

    /**
     * Asks the encoder to stop without finalizing the output. Sets the
     * cancel flag (frame path and drain loop observe it); call
     * [completeCancel] afterwards to free everything and delete the partial
     * output. Safe from any thread; a no-op once terminal.
     */
    fun requestCancel() {
        if (state != State.RUNNING) return
        state = State.CANCELED
    }

    /**
     * Joins the drain thread and releases codec/muxer/extractor. The output
     * is kept only for a fully finalized run ([State.DONE]); a canceled or
     * failed run deletes the partial MediaStore entry. Safe from any thread;
     * idempotent. Call [requestCancel] first to stop an in-flight run.
     */
    fun completeCancel() {
        when (state) {
            State.RUNNING -> state = State.CANCELED
            else -> Unit
        }
        drainThread?.let { t -> runCatching { t.join(CANCEL_JOIN_TIMEOUT_MS) } }
        releaseResources(deleteOutput = state != State.DONE)
    }

    // -------------------------------------------------------------- internals

    /** Reused scratch for [RoiMath.detailWeightedMean] — no hot-path allocation. */
    private val weightedMeanScratch = IntArray(256)

    /** Reused scratch receiving the overall (plain) ROI mean. */
    private val roiMeanScratch = FloatArray(1)

    /**
     * Aggregated 16×16 ROI map → 0..1 mean, detail-weighted: the average is
     * biased toward the busiest cells (the top quartile), so a small face in a
     * big frame still lifts the bitrate instead of being diluted to the flat
     * background mean. [roiMeanScratch] is filled with the *overall* mean, so
     * the bitrate controller can budget savings on total complexity while
     * steering bits toward detail. A null map counts as neutral.
     *
     * @return the detail-weighted mean (0..1) and, via [roiMeanScratch], the
     *   plain mean — the latter is what the low-complexity budget cut is based
     *   on, so a sparse slide stays cheap even though its text is sharp.
     */
    private fun roiMean(roiMap: ByteArray?): Float {
        if (roiMap == null || roiMap.isEmpty()) {
            roiMeanScratch[0] = 0.5f
            return 0.5f
        }
        return RoiMath.detailWeightedMeanWithOverall(roiMap, weightedMeanScratch, roiMeanScratch)
    }

    private fun drainLoop() {
        val mc = codec ?: return
        try {
            val info = MediaCodec.BufferInfo()
            var videoEos = false
            while (!videoEos && state == State.RUNNING) {
                when (val idx = mc.dequeueOutputBuffer(info, DRAIN_TIMEOUT_US)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> startMuxer(mc.outputFormat)
                    else -> if (idx >= 0) {
                        val buf = mc.getOutputBuffer(idx)
                        if (buf != null && info.size > 0 && muxerStarted) {
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            // Defensive: a regressing PTS would make MediaMuxer
                            // throw (C3 P7).
                            if (info.presentationTimeUs >= lastVideoPtsUs) {
                                // Interleave: audio up to the video head must
                                // land in the file BEFORE this video sample —
                                // appending the whole audio track after the
                                // video (a 217 MB run) breaks streaming-style
                                // players: no audio, playback gives up early.
                                writeAudioUpTo(info.presentationTimeUs)
                                muxer!!.writeSampleData(videoTrack, buf, info)
                                encodedBytes.addAndGet(info.size.toLong())
                                lastVideoPtsUs = info.presentationTimeUs
                            } else {
                                Log.w(TAG, "dropped non-monotonic video sample ts=${info.presentationTimeUs}")
                            }
                        }
                        mc.releaseOutputBuffer(idx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) videoEos = true
                    }
                }
            }

            if (videoEos && state == State.RUNNING) {
                writeAudioUpTo(Long.MAX_VALUE / 2) // flush the audio tail
                muxer?.stop()
                clearPending()
                state = State.DONE
                Log.i(
                    TAG,
                    "muxer stopped: videoBytes+audioBytes=${encodedBytes.get()} " +
                        "frames=$framesEncoded lastPtsUs=$lastVideoPtsUs",
                )
            }
        } catch (e: Exception) {
            fail("encoder drain failed: ${e.message}")
        } finally {
            doneLatch.countDown()
        }
    }

    /**
     * Selects the best audio track from the container, prioritizing MPEG-4
     * natively supported codecs (AAC, Opus) over exotic formats.
     */
    private fun selectBestAudioTrack(ex: MediaExtractor): Int? {
        val tracks = (0 until ex.trackCount).mapNotNull { i ->
            val mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME)
            if (mime?.startsWith("audio/") == true) {
                AudioTransmuxPolicy.TrackInfo(index = i, mime = mime)
            } else null
        }
        return AudioTransmuxPolicy.selectBestAudioTrack(tracks)
    }

    /** Adds both tracks and starts the muxer — exactly once. */
    private fun startMuxer(videoFormat: MediaFormat) {
        if (muxerStarted) return
        val m = muxer ?: return
        videoTrack = m.addTrack(videoFormat)
        audioFormat?.let { af ->
            runCatching {
                audioTrack = m.addTrack(af)
            }.onFailure { e ->
                Log.w(TAG, "MediaMuxer rejected audio track (${af.getString(MediaFormat.KEY_MIME)}): ${e.message}; continuing video-only")
                audioTrack = -1
            }
        }
        m.start()
        muxerStarted = true
        Log.i(TAG, "muxer started: videoTrack=$videoTrack audioTrack=$audioTrack")
    }

    /**
     * Writes every audio sample with `sampleTime <= ptsUs` into the muxer,
     * unmodified (F9). Called as the video head advances so audio and video
     * samples interleave by timestamp (standard MP4 layout) instead of the
     * whole audio track being appended after the video.
     *
     * Sanitizes presentation timestamps against negative values (which cause
     * MediaMuxer to throw IllegalArgumentException: bufferInfo is invalid)
     * and strictly enforces non-decreasing timestamp ordering.
     */
    private fun writeAudioUpTo(ptsUs: Long) {
        if (audioTrack < 0 || !muxerStarted) return
        val ex = audioExtractor ?: return
        val m = muxer ?: return
        val buf = audioBuf ?: return
        val info = audioInfo ?: return

        while (true) {
            val sampleTime = ex.sampleTime
            if (AudioTransmuxPolicy.isEof(sampleTime) || sampleTime > ptsUs) {
                break
            }

            buf.clear()
            val size = ex.readSampleData(buf, 0)
            if (size < 0) break

            val effectivePts = AudioTransmuxPolicy.sanitizePresentationTimestamp(sampleTime, lastAudioPtsUs)

            info.set(
                0,
                size,
                effectivePts,
                if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0)
                    MediaCodec.BUFFER_FLAG_KEY_FRAME else 0,
            )

            try {
                m.writeSampleData(audioTrack, buf, info)
                encodedBytes.addAndGet(size.toLong())
                lastAudioPtsUs = effectivePts
            } catch (e: Exception) {
                Log.w(TAG, "writeSampleData audio failed: ${e.message}")
                break
            }

            if (!ex.advance()) break
        }
    }

    /**
     * Publishes the finalized output: drops the IS_PENDING flag on the
     * MediaStore row so the file becomes visible to the gallery and other
     * apps. Without this the row lingers as a hidden `.pending-*` file even
     * though the muxer wrote a complete movie (C3 on-device bug).
     */
    private fun clearPending() {
        val uri = outputUri ?: return
        runCatching {
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) },
                null,
                null,
            )
        }.onFailure { Log.w(TAG, "could not publish output: ${it.message}") }
    }

    private fun fail(message: String) {
        failure.compareAndSet(null, message)
        state = State.FAILED
        Log.e(TAG, message)
    }

    /** Frees every resource; deletes the output row when [deleteOutput]. */
    private fun releaseResources(deleteOutput: Boolean) {
        val uri = outputUri
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
        runCatching { muxer?.release() } // no stop() — the file is not finalized
        muxer = null
        runCatching { audioExtractor?.release() }
        audioExtractor = null
        runCatching { outputPfd?.close() }
        outputPfd = null
        if (deleteOutput && uri != null) {
            runCatching { resolver.delete(uri, null, null) }
                .onFailure { Log.w(TAG, "could not delete partial output: ${it.message}") }
        }
        if (deleteOutput) outputUri = null // the row is gone; nothing to reference
    }

    private fun selectEncoderCodec(width: Int, height: Int): Pair<MediaCodecInfo, String>? {
        val list = MediaCodecList(MediaCodecList.ALL_CODECS)
        // 1. Try hardware-accelerated encoders in tier order: AV1 -> HEVC -> AVC
        for (mime in HARDWARE_TIER_MIMES) {
            val info = HardwareCodecSelector.selectHardwareOnlyEncoder(mime, list) ?: continue
            val videoCaps = try {
                info.getCapabilitiesForType(mime).videoCapabilities
            } catch (_: IllegalArgumentException) {
                null
            }
            if (videoCaps?.isSizeSupported(width, height) != false) {
                Log.i(TAG, "Selected hardware encoder: ${info.name} for $mime (${width}x${height})")
                return info to mime
            }
        }

        // 2. Absolute last resort fallback: standard encoder selector for AVC
        val fallbackMime = MediaFormat.MIMETYPE_VIDEO_AVC
        val fallbackInfo = HardwareCodecSelector.selectEncoder(fallbackMime, list)
        if (fallbackInfo != null) {
            Log.w(TAG, "Falling back to generic encoder: ${fallbackInfo.name} for $fallbackMime")
            return fallbackInfo to fallbackMime
        }

        return null
    }

    private fun preferredBitrateMode(info: MediaCodecInfo, mime: String): Int {
        val encCaps = try {
            info.getCapabilitiesForType(mime).encoderCapabilities
        } catch (_: IllegalArgumentException) {
            null
        }
        return try {
            if (encCaps?.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR) == true) {
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
            } else {
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
            }
        } catch (_: Exception) {
            MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
        }
    }

    /**
     * Size of [uri] in bytes. `OpenableColumns.SIZE` resolves content://
     * URIs (the picker path) but returns nothing for file:// ones (the
     * smoke-test path — observed as inBytes=0 on-device), so fall back to
     * an fd stat. 0 when both fail.
     */
    private fun querySize(uri: Uri): Long {
        if (uri.scheme == "file" && uri.path != null) {
            val len = java.io.File(uri.path!!).length()
            if (len > 0L) return len
        }
        try {
            resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) return c.getLong(0)
            }
        } catch (_: Exception) {
        }
        return try {
            resolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L
        } catch (_: Exception) {
            0L
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        if (uri.scheme == "file" && uri.path != null) {
            return java.io.File(uri.path!!).name
        }
        return try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun bundleOf(key: String, value: Int): android.os.Bundle =
        android.os.Bundle().apply { putInt(key, value) }
}
