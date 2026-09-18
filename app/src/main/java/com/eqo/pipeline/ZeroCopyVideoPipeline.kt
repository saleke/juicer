package com.eqo.pipeline

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Handler
import android.util.Log
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Surface
import com.eqo.ai.gl.SnapshotRenderer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

/** Typed failure surfaced by [ZeroCopyVideoPipeline.prepare]. */
class PipelineException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * ## eqo Component 1 — the zero-copy video pipeline
 *
 * Extracts and decodes a local video entirely into GPU-accessible memory:
 *
 * ```
 * MediaExtractor ─▶ MediaCodec (hardware) ─▶ Surface(SurfaceTexture)
 *                                            │ OES external texture
 *                     ┌──────────────────────┤ (drawn on the frame thread,
 *                     ▼                      ▼  GL context lives there)
 *          224×224 FBO snapshot        processFrameWithAI / passFrameToEncoder
 *          (AI leg, GPU downscale)     (Component 2 / Component 3 seams)
 * ```
 *
 * No `byte[]`, `ByteBuffer`, or `Bitmap` ever touches the JVM heap in the
 * frame path beyond the single bounded 224×224 RGB snapshot (~150 KB,
 * preallocated and reused — the C2 design review's accepted copy).
 *
 * Threading: one single-threaded executor dispatcher ("eqo-pipeline") runs
 * extraction + the synchronous decode loop; one HandlerThread ("eqo-frame")
 * owns the EGL/GL context and runs the dual-read frame path. Nothing runs on
 * the main thread.
 */
open class ZeroCopyVideoPipeline(protected val context: Context) {

    companion object {
        /** Per-frame processing budget for 60 FPS (16.6 ms). */
        const val FRAME_BUDGET_NANOS = PipelineMetrics.FRAME_BUDGET_NANOS

        /** Fixed SurfaceTexture buffer-queue depth (spec §3: at most 3 frames / <40 MB). */
        const val MAX_POOLED_FRAMES = 3

        private const val DEQUEUE_TIMEOUT_US = 10_000L // 10 ms — keeps pause/release responsive
        private const val EOS_SETTLE_TIMEOUT_MS = 2_000L
        private const val RELEASE_JOIN_TIMEOUT_MS = 2_000L
        private const val PAUSE_POLL_MS = 50L

        /** Max rendered-but-unprocessed frames before the decode loop waits. */
        private const val MAX_RENDER_BACKLOG = 2L

        private const val PACE_POLL_MS = 5L

        /** Headroom added to the largest scanned sample when sizing input buffers. */
        private const val MAX_INPUT_SLACK_BYTES = 1 shl 20

        /** Upper bound on the pre-scan sample walk (~2.8 h at 30 fps). */
        private const val MAX_SCAN_SAMPLES = 300_000
    }

    // ---------------------------------------------------------------- state

    private val _status = MutableStateFlow(VideoPipelineStatus.IDLE)
    /** Current lifecycle state; see [VideoPipelineStatus]. */
    val status: StateFlow<VideoPipelineStatus> = _status.asStateFlow()

    private val _metadata = MutableStateFlow<VideoMetadata?>(null)
    /** Metadata of the prepared video; null before [prepare] succeeds. */
    val metadata: StateFlow<VideoMetadata?> = _metadata.asStateFlow()

    private val _metrics = MutableStateFlow(PipelineMetrics())
    /** Live frame counters and timing. */
    val metrics: StateFlow<PipelineMetrics> = _metrics.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    /** Last fatal error message; null when healthy. */
    val error: StateFlow<String?> = _error.asStateFlow()

    // ----------------------------------------------------------- resources

    /** Dedicated single-threaded dispatcher for extraction + decoding (spec §1). */
    private val pipelineDispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "eqo-pipeline").apply { priority = Thread.MAX_PRIORITY - 1 }
    }.asCoroutineDispatcher()

    private val scope = CoroutineScope(SupervisorJob() + pipelineDispatcher)

    private var extractor: MediaExtractor? = null
    private var codec: MediaCodec? = null
    private var codecInfo: MediaCodecInfo? = null

    /** Total video sample count from the [prepare] pre-scan; 0 when unknown. */
    private var sampleCount = 0

    /** Largest video sample (bytes) from the [prepare] pre-scan; 0 when unknown. */
    private var scannedMaxSampleBytes = 0

    /** The URI passed to [prepare]; the encoder's audio-passthrough leg re-opens it. */
    protected var sourceUri: Uri? = null

    /**
     * Output stage. Started early (before codec configure) so the GL surface
     * exists when the decoder is bound to it; owns the SurfaceTexture the
     * decoder renders into (C2 design review, F6). Protected: the Component 3
     * encoder leg attaches its input surface to the renderer's EGL context.
     */
    protected lateinit var renderer: SnapshotRenderer
    private var frameThread: HandlerThread? = null
    private var decodeJob: Job? = null

    // -------------------------------------------------- cross-thread flags

    private val prepared = AtomicBoolean(false)
    private val released = AtomicBoolean(false)
    private val resourcesClosed = AtomicBoolean(false)

    @Volatile private var paused = false

    private val framesRendered = AtomicLong(0)
    private val framesProcessed = AtomicLong(0)

    /** Frames currently inside [onFrameAvailable]; lets settlement see in-flight work. */
    private val framesInFlight = AtomicLong(0)

    private val metricsLock = Any()
    private var processNanosTotal = 0L

    private val budgetTracker = FrameBudgetTracker()

    /** Thermal pacing delay injected per rendered frame to allow silicon cooling. */
    @Volatile var thermalPaceMs: Long = 0L

    // =========================================================================
    // Step A + B: initialization, extraction, surface-backed hardware decoding
    // =========================================================================

    /**
     * Opens [uri], extracts track metadata, selects a hardware decoder, and wires its
     * output to the GPU frame leg's SurfaceTexture. Runs on the pipeline dispatcher.
     *
     * @throws PipelineException on unreadable files, missing video tracks, codec
     *   allocation or configuration failures (wrapping `IOException`,
     *   `MediaCodec.CodecException`, and `IllegalArgumentException` per spec §4).
     */
    suspend fun prepare(uri: Uri): VideoMetadata = withContext(pipelineDispatcher) {
        check(!released.get()) { "pipeline already released" }
        if (prepared.get()) return@withContext requireNotNull(_metadata.value)

        try {
            // --- Step A: extraction -------------------------------------------
            sourceUri = uri
            val ex = MediaExtractor()
            extractor = ex
            MediaExtractorCompat.setDataSource(ex, context, uri)

            val trackIndex = (0 until ex.trackCount).firstOrNull { i ->
                ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: throw PipelineException("No video track found in the selected file")

            ex.selectTrack(trackIndex)
            val trackFormat = ex.getTrackFormat(trackIndex)
            val mime = requireNotNull(trackFormat.getString(MediaFormat.KEY_MIME))

            // MediaFormat values are not type-stable across devices (pitfall P7):
            // KEY_FRAME_RATE may be stored as Float and KEY_BIT_RATE may be absent.
            val width = trackFormat.intOr(MediaFormat.KEY_WIDTH, 0)
            val height = trackFormat.intOr(MediaFormat.KEY_HEIGHT, 0)
            if (width <= 0 || height <= 0) throw PipelineException("Invalid video dimensions")
            val bitrate = trackFormat.intOr(MediaFormat.KEY_BIT_RATE, 0)
            val frameRate = trackFormat.intOr(MediaFormat.KEY_FRAME_RATE, -1)
            val rotation = trackFormat.intOr("rotation-degrees", 0)
            val durationUs = trackFormat.longOr(MediaFormat.KEY_DURATION, 0L)

            // --- Step B: surface-backed hardware decoding ----------------------
            // The decoder's single output surface is the SnapshotRenderer's
            // SurfaceTexture (C2 design review, F6): decoded frames land in an
            // OES external texture, consumed zero-copy by both the AI leg
            // (224×224 downscale blit) and — later — the encoder leg.
            val thread = HandlerThread("eqo-frame").also { it.start() }
            frameThread = thread

            val r = SnapshotRenderer()
            renderer = r
            // Renderer setup runs on the frame thread — it owns the GL context.
            val ready = java.util.concurrent.CountDownLatch(1)
            var setupError: Throwable? = null
            Handler(thread.looper).post {
                try {
                    r.setup()
                    // Frame delivery callback runs on the same thread (P3).
                    r.setOnFrameAvailableListener(::onFrameAvailable, Handler(thread.looper))
                    // P4 diagnostic for real hardware — invoke manually to verify
                    // the SurfaceTexture→OES→FBO→readback chain end-to-end.
                    // Disabled by default: on the emulator, feeding a software
                    // Canvas frame into the Surface breaks the subsequent
                    // MediaCodec connection (2/2 codec-create failures after it).
                    // val selfOk = runCatching { r.selfTest() }.getOrDefault(false)
                    // android.util.Log.i("eqo.GL", "renderer self-test: " + if (selfOk) "PASS" else "FAIL")
                } catch (t: Throwable) {
                    setupError = t
                } finally {
                    ready.countDown()
                }
            }
            ready.await()
            setupError?.let { throw PipelineException("GPU frame leg failed to start: ${it.message}", it) }

            // The decoder format must NOT carry the container rotation hint
            // (KEY_ROTATION): some codecs fold it into the rendered output
            // (via the SurfaceTexture transform), which would double-rotate the
            // encoded copy — we re-add the rotation only as the muxer hint from
            // the source metadata (C3 F5), so the encoder always captures the
            // raw coded pixels (on-device bug: camera videos came out rotated).
            // The input buffers also need sizing to the largest sample: a sample
            // larger than the codec's default input buffer makes readSampleData
            // fail mid-stream, which previously ended the decode early (EOS) and
            // silently shipped an output containing only the first seconds.
            val (maxSampleBytes, videoSampleCount) = scanVideoSamples(ex)
            val decoderFormat = MediaFormat(trackFormat).apply {
                removeKey("rotation-degrees")
                if (maxSampleBytes > 0) {
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, maxSampleBytes + MAX_INPUT_SLACK_BYTES)
                }
            }
            sampleCount = videoSampleCount
            scannedMaxSampleBytes = maxSampleBytes

            val (mediaCodec, info) = createCodec(mime, decoderFormat, r.surface!!)
            codec = mediaCodec
            codecInfo = info

            // Color format comes from codec capabilities — track formats rarely carry it.
            // COLOR_FormatYUV420Flexible is the canonical "unspecified/flexible" value
            // for video codecs (the SDK has no generic COLOR_FormatFlexible constant).
            val colorFormat = try {
                info?.getCapabilitiesForType(mime)?.colorFormats
                    ?.firstOrNull { it != MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible }
                    ?: MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
            } catch (_: IllegalArgumentException) {
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
            }

            // Inspect any audio track present in the container
            val audioIdx = (0 until ex.trackCount).firstOrNull { i ->
                ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            }
            val audioFormat = audioIdx?.let { ex.getTrackFormat(it) }
            val audioMime = audioFormat?.getString(MediaFormat.KEY_MIME)
            val audioChannels = audioFormat?.intOr(MediaFormat.KEY_CHANNEL_COUNT, 0) ?: 0
            val audioSampleRate = audioFormat?.intOr(MediaFormat.KEY_SAMPLE_RATE, 0) ?: 0
            val audioBitrate = audioFormat?.intOr(MediaFormat.KEY_BIT_RATE, 0) ?: 0

            val md = VideoMetadata(
                width = width,
                height = height,
                bitrate = bitrate,
                frameRate = frameRate,
                colorFormat = colorFormat,
                mime = mime,
                rotationDegrees = rotation,
                durationUs = durationUs,
                audioMime = audioMime,
                audioChannels = audioChannels,
                audioSampleRate = audioSampleRate,
                audioBitrate = audioBitrate,
            )
            _metadata.value = md
            prepared.set(true)
            md
        } catch (e: IOException) {
            throw pipelineFailure("Could not read the video file: ${e.message}", e)
        } catch (e: MediaCodec.CodecException) {
            throw pipelineFailure("Codec error while configuring the decoder: ${e.message}", e)
        } catch (e: IllegalArgumentException) {
            throw pipelineFailure("Unsupported video format: ${e.message}", e)
        }
    }

    /**
     * Allocates a decoder for [mime], preferring hardware implementations found via
     * [MediaCodecList]. Falls back to the platform default decoder when the hardware
     * candidate cannot be created or configured (pitfall P8).
     */
    private fun createCodec(
        mime: String,
        format: MediaFormat,
        surface: Surface,
    ): Pair<MediaCodec, MediaCodecInfo?> {
        val selected = HardwareCodecSelector.selectDecoder(mime, MediaCodecList(MediaCodecList.ALL_CODECS))

        var hardwareCodec: MediaCodec? = null
        if (selected != null) {
            try {
                hardwareCodec = MediaCodec.createByCodecName(selected.name)
                hardwareCodec.configure(format, surface, null, 0)
                return hardwareCodec to selected
            } catch (e: MediaCodec.CodecException) {
                hardwareCodec?.release()
                hardwareCodec = null
            } catch (e: IllegalArgumentException) {
                hardwareCodec?.release()
                hardwareCodec = null
            } catch (e: IllegalStateException) {
                hardwareCodec?.release()
                hardwareCodec = null
            }
        }

        val fallback = MediaCodec.createDecoderByType(mime)
        fallback.configure(format, surface, null, 0)
        return fallback to null
    }

    /**
     * Walks the already-selected video track once, measuring the largest sample
     * (bytes) and the sample count without copying any data (`getSampleSize` is
     * metadata-only). Returns `0 to 0` when the walk fails or is bounded early.
     * Leaves the extractor positioned back at the first sample.
     */
    private fun scanVideoSamples(ex: MediaExtractor): Pair<Int, Int> {
        var max = 0
        var count = 0
        return try {
            ex.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            while (count < MAX_SCAN_SAMPLES && ex.sampleTime >= 0) {
                count++
                max = maxOf(max, ex.getSampleSize().toInt())
                if (!ex.advance()) break
            }
            ex.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            max to count
        } catch (_: Exception) {
            0 to 0
        }
    }

    // =========================================================================
    // Lifecycle controls
    // =========================================================================

    /** Starts decoding. Requires [prepare]; a paused pipeline resumes instead. */
    fun start() {
        if (released.get() || !prepared.get()) return
        when (_status.value) {
            VideoPipelineStatus.IDLE, VideoPipelineStatus.PAUSED -> Unit
            else -> return
        }
        paused = false
        _status.value = VideoPipelineStatus.DECODING
        if (decodeJob?.isActive == true) return // parked loop will notice `paused == false`
        decodeJob = scope.launch { runDecodeLoop() }
    }

    /** Parks the decode loop between frames without releasing the codec. */
    fun pause() {
        if (_status.value == VideoPipelineStatus.DECODING) {
            paused = true
            _status.value = VideoPipelineStatus.PAUSED
        }
    }

    /** Un-parks a paused loop. The loop itself flips the status back to DECODING. */
    fun resume() {
        if (_status.value == VideoPipelineStatus.PAUSED) {
            paused = false
            _status.value = VideoPipelineStatus.DECODING
        }
    }

    // =========================================================================
    // The decode loop (pipeline dispatcher thread)
    // =========================================================================

    /**
     * Synchronous feed/drain loop: fills input buffers from the extractor and renders
     * output buffers straight into the GPU frame leg's surface. Short dequeue timeouts keep
     * pause/release/cancellation checks frequent (spec §1; design review, pitfall P4).
     */
    private suspend fun runDecodeLoop() {
        val ex = extractor ?: return
        val mc = codec ?: return
        try {
            mc.start()
            // Diagnostic: the decoder's input buffer capacity vs the largest
            // source sample determines whether readSampleData can ever underflow.
            runCatching {
                val caps = buildList {
                    for (i in 0 until 8) {
                        try {
                            val b = mc.getInputBuffer(i) ?: break
                            add("$i=${b.capacity()}")
                        } catch (_: Exception) { break }
                    }
                }
                Log.i("eqo.Pipeline", "decoder inputCap=[${caps.joinToString(", ")}] maxSampleBytes=$scannedMaxSampleBytes count=$sampleCount")
            }
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var samplesFed = 0
            val loopStart = SystemClock.elapsedRealtimeNanos()

            while (!outputDone && !released.get() && _status.value != VideoPipelineStatus.ERROR) {
                // Park while paused (spec's PAUSED state — stream position is kept).
                if (paused) {
                    while (paused && !released.get()) delay(PAUSE_POLL_MS)
                    if (released.get()) break
                    _status.value = VideoPipelineStatus.DECODING
                }

                // ---- feed -----------------------------------------------------
                if (!inputDone) {
                    val inIndex = mc.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                    if (inIndex >= 0) {
                        val buffer = requireNotNull(mc.getInputBuffer(inIndex))
                        // A sample that overflows the codec's input buffer either
                        // throws or returns -1. Either way, treat it as a hard
                        // failure mid-stream — silently ending the input here made
                        // outputs stop after the first few seconds (on-device bug
                        // with big I-frames).
                        val size: Int = try {
                            ex.readSampleData(buffer, 0)
                        } catch (e: Exception) {
                            if (sampleCount > 0 && samplesFed < sampleCount) throw e
                            -1
                        }
                        if (size < 0) {
                            if (sampleCount > 0 && samplesFed < sampleCount) {
                                throw PipelineException(
                                    "input underflow at sample ${samplesFed + 1}/$sampleCount " +
                                        "(decoder buffer ${buffer.capacity()}B vs ${scannedMaxSampleBytes}B)",
                                )
                            }
                            mc.queueInputBuffer(
                                inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputDone = true
                        } else {
                            val flags = if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
                                MediaCodec.BUFFER_FLAG_KEY_FRAME
                            } else 0
                            mc.queueInputBuffer(inIndex, 0, size, ex.sampleTime, flags)
                            samplesFed++
                            ex.advance()
                        }
                    }
                }

                // ---- drain ----------------------------------------------------
                when (val outIndex = mc.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit // nothing to do for surface mode
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    else -> if (outIndex >= 0) {
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        // render=true ⇒ the frame goes straight into the SurfaceTexture
                        // surface's BufferQueue — zero JVM-side copies (fact R3).
                        mc.releaseOutputBuffer(outIndex, true)
                        val rendered = framesRendered.incrementAndGet()
                        publishMetrics(
                            processNanos = null,
                            decodeNanosAvg = (SystemClock.elapsedRealtimeNanos() - loopStart) /
                                max(1L, rendered),
                        )
                        if (thermalPaceMs > 0L) {
                            delay(thermalPaceMs)
                        }
                        // Component 3 pacing: the SurfaceTexture queue drops
                        // frames (latest-wins) when the consumer lags, which
                        // silently thins the encoded output (observed 36%
                        // loss on a 10-minute clip, C3 §5 addendum). Capping
                        // the backlog to a couple of frames keeps every
                        // rendered frame processed + encoded, at the cost of
                        // decoding no faster than the frame thread consumes.
                        while (!released.get() && !outputDone &&
                            framesRendered.get() - framesProcessed.get() > MAX_RENDER_BACKLOG
                        ) {
                            delay(PACE_POLL_MS)
                        }
                    }
                }
            }

            if (outputDone && !released.get() && _status.value != VideoPipelineStatus.ERROR) {
                awaitFrameSettlement()
                // Component 3 seam: the encoder's EOS + muxer finalization run
                // here, on the pipeline dispatcher, after every rendered frame
                // has been consumed — COMPLETED means the output file is done.
                onDecodeComplete()
                _status.value = VideoPipelineStatus.COMPLETED
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: PipelineException) {
            pipelineFailure(e.message ?: "pipeline failed", e)
        } catch (e: MediaCodec.CodecException) {
            pipelineFailure("Decoder failure: ${e.message}", e)
        } catch (e: IllegalStateException) {
            pipelineFailure("Decoder entered an invalid state: ${e.message}", e)
        } finally {
            // Free the codec promptly after completion; on error the whole pipeline
            // tears down. Both paths are idempotent and thread-safe (pitfall P11).
            closeResources()
        }
    }

    /**
     * After end-of-stream, waits until the listener stops consuming frames (frames
     * still queued in the SurfaceTexture at EOS — pitfall P6). Bounded by a timeout so
     * the pipeline can never hang in DECODING.
     */
    /**
     * After end-of-stream, waits until the listener stops consuming frames (frames
     * still queued in the SurfaceTexture at EOS — pitfall P6). Bounded by a timeout so
     * the pipeline can never hang in DECODING.
     *
     * A frame in flight keeps [framesProcessed] unchanged, so stability alone can
     * report "settled" mid-processing (observed on a slow GL path: settlement
     * fired with 0 of 97 frames consumed). The fast path is therefore a full
     * drain — every frame rendered to the surface has been consumed — with the
     * stability check only as a fallback for frames the queue never delivers.
     */
    private suspend fun awaitFrameSettlement() {
        val deadline = SystemClock.elapsedRealtime() + EOS_SETTLE_TIMEOUT_MS
        var lastProcessed = -1L
        var stablePolls = 0
        while (SystemClock.elapsedRealtime() < deadline && !released.get()) {
            if (framesProcessed.get() >= framesRendered.get()) return // drained
            val current = framesProcessed.get()
            val idle = framesInFlight.get() == 0L
            if (idle && current == lastProcessed) stablePolls++ else stablePolls = 0
            lastProcessed = current
            if (stablePolls >= 3) return
            delay(60)
        }
    }

    // =========================================================================
    // Step C: the dual-read listener (frame HandlerThread, owns the GL context)
    // =========================================================================

    /**
     * Per-frame dual-read path. The SurfaceTexture's frame-available callback
     * runs on the frame thread, which owns the GL context (C2 design review,
     * P3). Any throw in here would kill frame delivery silently and deadlock
     * the decoder via Surface backpressure (pitfall P2), so the body is fully
     * guarded.
     *
     * Frame-available callbacks are NOT guaranteed to be per-frame: when
     * buffers queue up faster than they are consumed, one callback can
     * represent many queued buffers (observed on API 31/PowerVR: 2 callbacks
     * for 97 rendered frames). So each callback drains the queue: keep
     * consuming while updateTexImage still acquires a newer buffer — on an
     * empty queue it is a no-op and the SurfaceTexture timestamp stops
     * advancing.
     */
    private fun onFrameAvailable(st: SurfaceTexture) {
        if (released.get()) return
        framesInFlight.incrementAndGet()
        try {
            var lastTs = Long.MIN_VALUE
            while (!released.get()) {
                val started = SystemClock.elapsedRealtimeNanos()
                // Consume the latest decoded frame into the OES texture and blit
                // it into the 224×224 snapshot FBO (zero-copy up to this point).
                renderer.updateAndDraw()

                val frameTimestampNanos = st.timestamp
                if (frameTimestampNanos == lastTs) break // queue drained
                lastTs = frameTimestampNanos

                // Read back the snapshot (reused direct buffer; the only CPU copy
                // in the AI leg — ~150 KB, bounded and preallocated).
                val snapshot = renderer.readSnapshot()

                // Path A — AI inference seam (Component 2).
                processFrameWithAI(snapshot, frameTimestampNanos)

                // Path B — hardware encoder seam (Component 3), possibly thinned by
                // the drop-frame strategy when processing runs over budget.
                if (budgetTracker.shouldEncodeFrame()) {
                    passFrameToEncoder(lastRoiMap, frameTimestampNanos)
                }

                val elapsed = SystemClock.elapsedRealtimeNanos() - started
                budgetTracker.recordProcessingTime(elapsed)
                framesProcessed.incrementAndGet()
                publishMetrics(processNanos = elapsed, decodeNanosAvg = null)
            }
        } catch (e: Exception) {
            pipelineFailure("Frame processing failed: ${e.message}", e)
        } finally {
            framesInFlight.decrementAndGet()
        }
    }

    /**
     * Latest ROI map produced by [processFrameWithAI]; consumed by the encoder
     * leg on the same thread (no synchronization needed).
     */
    protected var lastRoiMap: ByteArray? = null

    /**
     * **Path A — seam for Component 2 (the AI ROI engine).**
     *
     * Called on the frame thread with the current frame's 224×224 top-down
     * packed-RGB snapshot (a reused direct [java.nio.ByteBuffer]; copy it out
     * if retained — the buffer is overwritten by the next frame).
     *
     * Contract:
     * - Must return **synchronously** (sub-16.6 ms; the AI core is ~1 ms) —
     *   this function returning is what allows the loop to consume the next
     *   frame. Retaining the buffer after return risks reading the next
     *   frame's pixels.
     * - Must not call [release] or any lifecycle method.
     * - Implementations should store their result in [lastRoiMap] if the
     *   encoder leg should receive it.
     */
    protected open fun processFrameWithAI(rgb: ByteBuffer, timestampNanos: Long) {
        // Placeholder — wired to the AI engine by JuicerPipeline.
    }

    /**
     * **Path B — seam for Component 3 (the hardware re-encoder).**
     *
     * Same contract as [processFrameWithAI]: synchronous, no retained pixel
     * references. May be skipped for individual frames by the drop-frame
     * strategy (spec §3). The frame itself is available to the encoder leg as
     * the OES texture inside the renderer (draw it onto the encoder input
     * surface); [roiMap] carries the per-frame quality map.
     */
    protected open fun passFrameToEncoder(roiMap: ByteArray?, timestampNanos: Long) {
        // Placeholder — wired to the MediaCodec encoder in Component 3.
    }

    /**
     * **Component 3 completion seam.** Called on the pipeline dispatcher after
     * [awaitFrameSettlement] and before the status flips to COMPLETED — i.e.
     * every rendered frame has been consumed and its encoder submission
     * (including the blocking `eglSwapBuffers`) has returned.
     *
     * Implementations finish downstream work here (encoder end-of-stream,
     * muxer finalization) so that COMPLETED guarantees a complete output
     * file. Throwing marks the run ERROR.
     */
    protected open suspend fun onDecodeComplete() {
        // Placeholder — wired to the encoder's finalize path in Component 3.
    }

    // =========================================================================
    // Metrics plumbing
    // =========================================================================

    /**
     * Lets subclasses extend [PipelineMetrics] with their own counters
     * (Component 3: encoded frames, bytes, applied bitrate) while keeping the
     * base publish path the single writer.
     */
    protected fun mutateMetrics(block: (PipelineMetrics) -> PipelineMetrics) {
        synchronized(metricsLock) {
            _metrics.value = block(_metrics.value)
        }
    }

    private fun publishMetrics(processNanos: Long?, decodeNanosAvg: Long?) {
        synchronized(metricsLock) {
            val prev = _metrics.value
            if (processNanos != null) processNanosTotal += processNanos
            val rendered = framesRendered.get()
            val processed = framesProcessed.get()
            _metrics.value = prev.copy(
                framesRendered = rendered.toInt(),
                framesProcessed = processed.toInt(),
                framesDropped = (rendered - processed).coerceAtLeast(0).toInt(),
                framesOverBudget = prev.framesOverBudget +
                    (if (processNanos != null && processNanos > FRAME_BUDGET_NANOS) 1 else 0),
                processNanosLast = processNanos ?: prev.processNanosLast,
                processNanosAvg = if (processed > 0) processNanosTotal / processed else 0,
                decodeNanosAvg = decodeNanosAvg ?: prev.decodeNanosAvg,
            )
        }
    }

    // =========================================================================
    // Error handling & release
    // =========================================================================

    /** Marks the pipeline ERROR and returns the exception to (re)throw to the caller. */
    private fun pipelineFailure(message: String, cause: Throwable?): PipelineException {
        Log.e("eqo.Pipeline", message, cause)
        _error.value = message
        _status.value = VideoPipelineStatus.ERROR
        return PipelineException(message, cause)
    }

    /**
     * Flushes the codec, releases the extractor, tears down the GPU frame leg,
     * and shuts down the frame thread + dispatcher. Idempotent and safe to call
     * from any state (spec §4). After release the instance is single-use.
     *
     * Open so subclasses (e.g. the Component 2/3 wiring in [JuicerPipeline])
     * can release their own resources after the base teardown; always call
     * `super.release()`.
     */
    open fun release() {
        released.set(true)
        runBlocking {
            withTimeoutOrNull(RELEASE_JOIN_TIMEOUT_MS) { decodeJob?.cancelAndJoin() }
            closeResources()
        }
        scope.cancel()
        pipelineDispatcher.close()
    }

    /** Idempotent teardown of native/window resources (pitfall P11 ordering). */
    private fun closeResources() {
        if (!resourcesClosed.compareAndSet(false, true)) return
        released.set(true)
        runCatching { codec?.stop() }   // flushes; may throw if never started — fine
        runCatching { codec?.release() }
        codec = null
        codecInfo = null
        runCatching { extractor?.release() }
        extractor = null
        // Renderer teardown must run on the frame thread (GL context owner, P3):
        // post the release, then quit the looper after it completes.
        val thread = frameThread
        if (thread != null) {
            val done = java.util.concurrent.CountDownLatch(1)
            Handler(thread.looper).post {
                runCatching { if (::renderer.isInitialized) renderer.release() }
                done.countDown()
            }
            runCatching { done.await(2, java.util.concurrent.TimeUnit.SECONDS) }
        }
        thread?.quitSafely()
        frameThread = null
    }
}

/**
 * Type-tolerant MediaFormat int accessor: handles keys stored as Float and absent
 * keys (design review, fact R8 / pitfall P7).
 */
private fun MediaFormat.intOr(key: String, default: Int): Int {
    if (!containsKey(key)) return default
    return try {
        getInteger(key)
    } catch (_: ClassCastException) {
        try {
            getFloat(key).toInt()
        } catch (_: ClassCastException) {
            // Some extractors expose numeric keys (e.g. "rotation-degrees") as
            // Java Strings — dropping those silently turned camera rotation into 0.
            getString(key)?.trim()?.toIntOrNull() ?: default
        }
    }
}

/** Type-tolerant MediaFormat long accessor (KEY_DURATION is Long in practice). */
private fun MediaFormat.longOr(key: String, default: Long): Long {
    if (!containsKey(key)) return default
    return try {
        getLong(key)
    } catch (_: ClassCastException) {
        try {
            getInteger(key).toLong()
        } catch (_: ClassCastException) {
            getString(key)?.trim()?.toLongOrNull() ?: default
        }
    }
}
