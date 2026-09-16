package com.eqo.pipeline

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaCodecList
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.eqo.ai.RoiMath
import com.eqo.ai.gl.SnapshotRenderer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Component 3 "Trust Verification" post-pass: decodes the transcoded output
 * and returns 224×224 packed-RGB snapshots at (approximately) the requested
 * presentation timestamps, for SSIM comparison against the source samples
 * captured during the run (component_three.md NEW §3).
 *
 * One dedicated thread owns its own EGL context (a second [SnapshotRenderer]
 * instance — the class is self-contained); the caller blocks until sampling
 * finishes or times out. Seeks per target: `SEEK_PREVIOUS_SYNC` +
 * `codec.flush()`, then decode forward to the first frame at/after the
 * target. A target matches when its output frame is within one frame
 * interval (33 ms) of the source sample's timestamp — VFR drops make exact
 * matches unreliable.
 */
class OutputFrameSampler(private val context: Context) {

    companion object {
        private const val TAG = "eqo.SSIM"

        /** Nanoseconds of tolerance for matching an output frame to a target. */
        private const val MATCH_TOLERANCE_NS = 33_000_000L

        /** Nanoseconds at/after which a target is considered reached. */
        private const val REACHED_EPSILON_NS = 1_000_000L

        private const val DEQUEUE_TIMEOUT_US = 10_000L
        private const val FRAME_WAIT_MS = 100L
        private const val TOTAL_TIMEOUT_MS = 60_000L
        private const val SNAPSHOT_BYTES =
            RoiMath.SNAPSHOT_SIZE * RoiMath.SNAPSHOT_SIZE * RoiMath.BYTES_PER_PIXEL
    }

    /**
     * @param targetsNanos source-sample timestamps (nanoseconds) to capture.
     * @return matched timestamp → packed-RGB snapshot; unmatched targets are
     *   simply absent. Empty on any setup failure (verification is best
     *   effort — it must never fail a successful transcode).
     */
    fun sample(uri: Uri, targetsNanos: List<Long>): Map<Long, ByteArray> {
        if (targetsNanos.isEmpty()) return emptyMap()
        val result = HashMap<Long, ByteArray>()
        val done = CountDownLatch(1)

        val thread = HandlerThread("eqo-ssim").also { it.start() }
        try {
            Handler(thread.looper).post {
                try {
                    result.putAll(runSampling(uri, targetsNanos))
                } catch (t: Throwable) {
                    // Best effort: log and return whatever was captured.
                    Log.w(TAG, "output sampling failed: ${t.message}")
                } finally {
                    done.countDown()
                }
            }
            done.await(TOTAL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } finally {
            thread.quitSafely()
        }
        return result
    }

    /** Runs entirely on the sampler thread (owns the EGL context). */
    private fun runSampling(uri: Uri, targetsNanos: List<Long>): Map<Long, ByteArray> {
        val renderer = SnapshotRenderer()
        var extractor: MediaExtractor? = null
        var codec: MediaCodec? = null
        val captured = HashMap<Long, ByteArray>()
        try {
            renderer.setup()
            val frameLatch = AtomicReference(CountDownLatch(1))
            // The callback handler must NOT be this thread's looper: the
            // sampling loop below blocks this thread in latch.await, so a
            // callback posted here could never run — every frame would time
            // out and every target would silently miss (observed on-device:
            // "ssim=n/a" with no sampler logs). The callback only counts the
            // latch down, which is safe from the main looper.
            renderer.setOnFrameAvailableListener(
                { frameLatch.get().countDown() },
                Handler(context.mainLooper),
            )

            val ex = MediaExtractor()
            extractor = ex
            MediaExtractorCompat.setDataSource(ex, context, uri)
            val trackIndex = (0 until ex.trackCount).firstOrNull { i ->
                ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: return emptyMap()
            ex.selectTrack(trackIndex)
            val format = ex.getTrackFormat(trackIndex)
            val mime = requireNotNull(format.getString(MediaFormat.KEY_MIME))

            // Same rule as the transcode decoder: never hand KEY_ROTATION to the
            // codec — some codecs bake it into the render transform, which would
            // rotate this leg's snapshots relative to the encoder-leg pixels.
            val decodeFormat = MediaFormat(format).apply { removeKey("rotation-degrees") }

            val selected = HardwareCodecSelector.selectDecoder(mime, MediaCodecList(MediaCodecList.ALL_CODECS))
            val mc = if (selected != null) {
                try {
                    MediaCodec.createByCodecName(selected.name).also { it.configure(decodeFormat, renderer.surface!!, null, 0) }
                } catch (_: Exception) {
                    null
                }
            } else null
            val decoder = mc ?: MediaCodec.createDecoderByType(mime).also {
                it.configure(decodeFormat, renderer.surface!!, null, 0)
            }
            codec = decoder
            decoder.start()

            for (target in targetsNanos) {
                val snap = sampleOne(renderer, frameLatch, ex, decoder, target) ?: continue
                captured[target] = snap
                Log.i(TAG, "captured output frame at ${target}ns")
            }
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor?.release() }
            runCatching { renderer.release() }
        }
        return captured
    }

    /**
     * Seeks to just before [targetNanos], decodes forward, and returns the
     * snapshot of the first frame at/after the target. Null when no frame
     * matches within tolerance.
     */
    private fun sampleOne(
        renderer: SnapshotRenderer,
        frameLatch: AtomicReference<CountDownLatch>,
        ex: MediaExtractor,
        decoder: MediaCodec,
        targetNanos: Long,
    ): ByteArray? {
        ex.seekTo(targetNanos / 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        decoder.flush()

        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        while (!outputDone) {
            // ---- feed ----
            if (!inputDone) {
                val inIndex = decoder.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                if (inIndex >= 0) {
                    val buffer = requireNotNull(decoder.getInputBuffer(inIndex))
                    val size = ex.readSampleData(buffer, 0)
                    if (size < 0) {
                        decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        decoder.queueInputBuffer(inIndex, 0, size, ex.sampleTime, 0)
                        ex.advance()
                    }
                }
            }

            // ---- drain ----
            when (val outIndex = decoder.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)) {
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED, MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                else -> if (outIndex >= 0) {
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    val latch = CountDownLatch(1)
                    frameLatch.set(latch)
                    decoder.releaseOutputBuffer(outIndex, true)
                    if (latch.await(FRAME_WAIT_MS, TimeUnit.MILLISECONDS)) {
                        renderer.updateAndDraw()
                        val ts = renderer.latestFrameTimestampNanos
                        if (ts >= targetNanos - REACHED_EPSILON_NS) {
                            outputDone = true
                            if (kotlin.math.abs(ts - targetNanos) <= MATCH_TOLERANCE_NS) {
                                // Copy out: the renderer's snapshot buffer is
                                // reused for the next frame.
                                val arr = ByteArray(SNAPSHOT_BYTES)
                                renderer.readSnapshot().duplicate().get(arr)
                                return arr
                            }
                            // Past the target but too far (frame dropped): no match.
                        }
                        // Before the target: keep decoding forward.
                    }
                    // Latch timeout: the frame never arrived; keep draining.
                }
            }
        }
        return null
    }
}
