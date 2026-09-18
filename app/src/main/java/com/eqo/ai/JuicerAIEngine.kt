package com.eqo.ai

import android.content.Context
import android.hardware.HardwareBuffer
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.Closeable
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ## eqo Component 2 — the AI ROI engine (hybrid tier)
 *
 * Per the C2 design review: the shipped model is an ImageNet classifier, not a
 * spatial saliency model, so the engine is **hybrid**:
 *
 *  - **Per frame** (on the pipeline's frame thread, ~≤1 ms): luma-variance
 *    16×16 ROI grid from the 224×224 GPU-downscaled snapshot → 3×3 smoothing →
 *    temporal EMA → human modulation → byte encoding.
 *  - **At scene cadence** (own single-thread executor, latest-wins): the
 *    MobileNetV3 classifier runs on a snapshot copy to detect human-adjacent
 *    content and produce [sceneCategory] for Component 4.
 *
 * Execution tiers (design review P6): GPU delegate → CPU XNNPACK (1 thread) →
 * mock map. The frame path never blocks on the classifier (P5).
 *
 * The model asset must be uncompressed for mapping — enforced by the Gradle
 * `noCompress += "tflite"` rule (P11).
 */
class JuicerAIEngine(
    context: Context,
    private val classifyEveryNFrames: Int = DEFAULT_CLASSIFY_INTERVAL,
) : Closeable {

    /** Dynamically adjustable cadence modulated by thermal governor (0 = disabled/paused). */
    @Volatile
    var activeClassifyCadence: Int = classifyEveryNFrames

    companion object {
        private const val TAG = "eqo.AI"
        const val MODEL_ASSET = "juicer_saliency_quant.tflite"
        const val LABELS_ASSET = "imagenet_labels.txt"
        const val DEFAULT_CLASSIFY_INTERVAL = 15

        /** Mock-tier weights (user-directed interim): centered subject, flat background. */
        const val MOCK_FOCUSED: Byte = 0xFF.toByte()   // 1.0
        const val MOCK_BACKGROUND: Byte = 0x4D.toByte() // 0.3

        /** Any human-adjacent class in the top-K with this probability counts. */
        private const val HUMAN_MIN_PROB = 0.02f

        /** How many top logits are inspected for the human hint / scene label. */
        private const val TOP_K = 5

        /** torchvision ImageNet normalization (F9; verify-on-device). */
        private val NORM_MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
        private val NORM_STD = floatArrayOf(0.229f, 0.224f, 0.225f)

        private const val SNAPSHOT_SIZE = RoiMath.SNAPSHOT_SIZE
        private const val SNAPSHOT_BYTES = SNAPSHOT_SIZE * SNAPSHOT_SIZE * RoiMath.BYTES_PER_PIXEL
    }

    enum class Tier { GPU, CPU, MOCK }

    /** Callback fired per generated ROI map (spec, Component 2 Step C). */
    @Volatile
    var onROIMapGenerated: ((roiMatrix: ByteArray) -> Unit)? = null

    /** Latest classifier verdict; written by the classifier worker, read on the frame thread. */
    @Volatile
    var humanLikely: Boolean = false
        private set

    /**
     * Soft, continuous human evidence (0..1): the max human-adjacent class
     * probability in the top-K, written by the classifier worker. The frame
     * path smooths this with [RoiMath.asymmetricScoreStep] before boosting, so
     * a flickering verdict feathers the grid up/down instead of snapping it
     * (kills the "light swititch" blur).
     */
    @Volatile
    var humanScoreTarget: Float = 0f
        private set

    /** Frame-thread-smoothed human score; the actual boost driver. */
    var humanScore: Float = 0f
        private set

    /** Latest scene category label; feeds Component 4 (videoCategory). */
    @Volatile
    var sceneCategory: String = "unknown"
        private set

    /** Execution tier actually in use (P6 fallback result). */
    var tier: Tier = Tier.MOCK
        private set

    private val appContext = context.applicationContext
    private val closed = AtomicBoolean(false)

    private var interpreter: Interpreter? = null
    private var gpuDelegate: GpuDelegate? = null
    private var labels: List<String> = emptyList()

    // classifier worker (P5: never on the frame thread)
    private val classifyExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "eqo-classify").apply { priority = Thread.NORM_PRIORITY - 1 }
    }
    private val classifyBusy = AtomicBoolean(false)

    /** Reused NCHW float input [1,3,224,224]; direct for zero-copy into LiteRT. */
    private val classifyInput: ByteBuffer =
        ByteBuffer.allocateDirect(SNAPSHOT_BYTES * 4).order(ByteOrder.nativeOrder())
    private val classifyOutput = arrayOf(FloatArray(1000))

    // reused per-frame state — no allocation in the hot path
    private val gridA = FloatArray(RoiMath.GRID * RoiMath.GRID)
    private val gridB = FloatArray(RoiMath.GRID * RoiMath.GRID)
    private val prevGrid = FloatArray(RoiMath.GRID * RoiMath.GRID)
    private val cellLuma = FloatArray(RoiMath.GRID * RoiMath.GRID)
    private val roiBytes = ByteArray(RoiMath.GRID * RoiMath.GRID)
    private val classifierSnapshot = ByteArray(SNAPSHOT_BYTES)
    /** Worker-owned copy — the frame path reuses [classifierSnapshot] immediately. */
    private val classifyPending = ByteArray(SNAPSHOT_BYTES)
    /** Softmax of the latest logits; reused, classifier runs at scene cadence. */
    private val classifyProbs = FloatArray(1000)
    private var frameCounter = 0L

    // ---------------------------------------------------------------- init

    init {
        var loaded: Interpreter? = null
        var delegate: GpuDelegate? = null

        // Tier 1: GPU delegate.
        runCatching {
            delegate = GpuDelegate()
            loaded = loadInterpreter(delegate!!)
        }.onFailure {
            runCatching { delegate?.close() }
            delegate = null
            loaded = null
        }

        // Tier 2: CPU XNNPACK, single thread (spec: don't compete with encoder).
        if (loaded == null) {
            runCatching { loaded = loadInterpreter(null) }
                .onFailure { loaded = null }
        }

        if (loaded != null) {
            interpreter = loaded
            gpuDelegate = delegate
            tier = if (delegate != null) Tier.GPU else Tier.CPU
            Log.i(TAG, "tier=$tier (GPU delegate ${if (delegate != null) "active" else "unavailable"})")
            labels = runCatching {
                appContext.assets.open(LABELS_ASSET).bufferedReader().readLines()
            }.map { RoiMath.loadLabels(it) }
                .getOrDefault(emptyList())
            warmUp()
        }
        // else: stays MOCK — generateROIMap serves the centered mock map (P6).
    }

    /**
     * Memory-maps the model asset and builds the interpreter. The asset is
     * stored uncompressed (P11), so the channel map is valid for LiteRT.
     */
    private fun loadInterpreter(delegate: GpuDelegate?): Interpreter {
        val fd = appContext.assets.openFd(MODEL_ASSET)
        val model: MappedByteBuffer = FileInputStream(fd.fileDescriptor).use { fis ->
            fis.channel.map(
                FileChannel.MapMode.READ_ONLY,
                fd.startOffset,
                fd.declaredLength,
            )
        }
        val options = Interpreter.Options().apply {
            setNumThreads(1) // spec: don't compete with encoder threads
            if (delegate != null) addDelegate(delegate) else setUseXNNPACK(true)
        }
        return Interpreter(model, options)
    }

    /** First inference off the frame path — absorbs shader/kernel init (P5). */
    private fun warmUp() {
        val interp = interpreter ?: return
        runCatching {
            classifyInput.clear()
            classifyInput.put(ByteArray(classifyInput.capacity()))
            classifyInput.rewind()
            interp.runSignature(
                mapOf("x" to classifyInput),
                mapOf("linear_1" to classifyOutput),
                "main",
            )
        }
    }

    // ---------------------------------------------------------------- frame path

    /**
     * Component 1's Path A entry point. Computes the per-frame ROI map from the
     * 224×224 top-down packed-RGB snapshot in [rgb] (positioned at 0).
     *
     * Synchronous and allocation-free, per the Component 1 listener contract.
     * Returns the 16×16 byte map (also delivered to [onROIMapGenerated]).
     */
    fun generateROIMap(rgb: ByteBuffer): ByteArray {
        if (closed.get()) return roiBytes
        if (tier == Tier.MOCK) return generateMockROIMap()
        rgb.duplicate().get(classifierSnapshot, 0, SNAPSHOT_BYTES)
        return generateROIMap(classifierSnapshot)
    }

    /** JVM-testable core: byte-array snapshot in, ROI map out. */
    fun generateROIMap(rgb: ByteArray): ByteArray {
        if (closed.get()) return roiBytes
        if (tier == Tier.MOCK) return generateMockROIMap()

        // 1+2. fused single pass: motion delta + variance grid (hot-path cost
        // is one walk over the 224×224 snapshot, not two).
        val delta = RoiMath.gridStats(rgb, cellLuma, gridA)
        val static = frameCounter > 0 && RoiMath.isMostlyStatic(delta)
        if (!static) {
            // 3. smoothing → dilation cushion → asymmetric temporal blend
            //    (fast attack, slow release — the "memory") → human modulation
            //    scaled by the smoothed score → encode. The dilation writes
            //    into the free gridA scratch (src/dst must not alias: the halo
            //    must never re-seed itself on the same pass).
            RoiMath.smooth(gridA, gridB)
            RoiMath.dilateCushion(gridB, gridA)
            // The smoothed score rides its own asymmetric line so the boost
            // feathers out over dozens of frames after the last human verdict.
            humanScore = RoiMath.asymmetricScoreStep(humanScore, humanScoreTarget)
            RoiMath.temporalBlendAsymmetric(prevGrid, gridA, prevGrid)
            RoiMath.modulateForHuman(prevGrid, humanScore)
            RoiMath.encodeToBytes(prevGrid, roiBytes)
            onROIMapGenerated?.invoke(roiBytes)
            // P4 orientation check: dump the first frame's grid, row-major,
            // top-down. A known-layout clip must show its hot cells in the
            // matching grid rows here.
            if (frameCounter == 0L) {
                Log.i(TAG, "roi[0]: " + roiBytes.joinToString(" ") { (it.toInt() and 0xFF).toString() })
            }
        }

        frameCounter++
        maybeSubmitClassifier(rgb)
        return roiBytes
    }

    /**
     * Mock tier (user-directed interim): centered 4×4 high-priority region
     * simulating a centered subject, everything else low priority. Serves as
     * the runtime fallback when model/interpreter init failed (P6).
     */
    fun generateMockROIMap(hardwareBuffer: HardwareBuffer? = null): ByteArray {
        val matrix = ByteArray(RoiMath.GRID * RoiMath.GRID) { MOCK_BACKGROUND }
        val span = 4
        val start = (RoiMath.GRID - span) / 2
        for (row in start until start + span) {
            for (col in start until start + span) {
                matrix[row * RoiMath.GRID + col] = MOCK_FOCUSED
            }
        }
        onROIMapGenerated?.invoke(matrix)
        return matrix
    }

    // ---------------------------------------------------------------- classifier

    /**
     * Latest-wins classifier dispatch: if the worker is idle and the frame
     * counter hits the cadence, copy the snapshot and submit. A busy worker
     * means the previous inference is still running — the snapshot is skipped
     * (never queued) so the frame path never blocks (P5).
     */
    private fun maybeSubmitClassifier(rgb: ByteArray) {
        val cadence = activeClassifyCadence
        if (cadence <= 0 || frameCounter % cadence != 0L) return
        if (!classifyBusy.compareAndSet(false, true)) return
        // Copy into the worker-owned buffer: the frame thread rewrites
        // classifierSnapshot (and rgb) as soon as this call returns.
        System.arraycopy(rgb, 0, classifyPending, 0, SNAPSHOT_BYTES)
        val snapshot = classifyPending
        classifyExecutor.execute {
            try {
                runClassifier(snapshot)
            } finally {
                classifyBusy.set(false)
            }
        }
    }

    private fun runClassifier(rgb: ByteArray) {
        val interp = interpreter ?: return
        val t0 = System.nanoTime()

        // HWC packed RGB → CHW normalized floats (F9 NCHW input).
        classifyInput.clear()
        val px = SNAPSHOT_SIZE * SNAPSHOT_SIZE
        val planes = arrayOf(FloatArray(px), FloatArray(px), FloatArray(px))
        var i = 0
        for (p in 0 until px) {
            planes[0][p] = ((rgb[i].toInt() and 0xFF) / 255f - NORM_MEAN[0]) / NORM_STD[0]
            planes[1][p] = ((rgb[i + 1].toInt() and 0xFF) / 255f - NORM_MEAN[1]) / NORM_STD[1]
            planes[2][p] = ((rgb[i + 2].toInt() and 0xFF) / 255f - NORM_MEAN[2]) / NORM_STD[2]
            i += 3
        }
        classifyInput.asFloatBuffer().apply {
            put(planes[0]); put(planes[1]); put(planes[2])
        }
        val t1 = System.nanoTime()

        runCatching {
            interp.runSignature(
                mapOf("x" to classifyInput),
                mapOf("linear_1" to classifyOutput),
                "main",
            )
        }.onSuccess {
            val t2 = System.nanoTime()
            if (t2 - t1 > 20_000_000L) {
                Log.i(TAG, "classify timing: convert=%.1fms inference=%.1fms".format((t1 - t0) / 1e6, (t2 - t1) / 1e6))
            }
            val logits = classifyOutput[0]
            // Softmax: raw logits have no probability scale, so the 0.02
            // human threshold is only meaningful post-normalization.
            var maxLogit = Float.NEGATIVE_INFINITY
            for (v in logits) if (v > maxLogit) maxLogit = v
            var z = 0f
            for (i in logits.indices) {
                val e = kotlin.math.exp(logits[i] - maxLogit)
                classifyProbs[i] = e
                z += e
            }
            val invZ = 1f / z
            for (i in classifyProbs.indices) classifyProbs[i] *= invZ

            val probs = classifyProbs
            val top = probs.indices.sortedByDescending { probs[it] }.take(TOP_K)

            var human = false
            var bestIdx = top.firstOrNull() ?: -1
            var maxHumanProb = 0f
            for (idx in top) {
                if (RoiMath.HUMAN_ADJACENT_INDICES.contains(idx) && probs[idx] > HUMAN_MIN_PROB) {
                    human = true
                    if (probs[idx] > maxHumanProb) {
                        maxHumanProb = probs[idx]
                        // Prefer the strongest human-adjacent label for the
                        // scene category too.
                        bestIdx = idx
                    }
                }
            }
            // Soft evidence (0..1) in place of the boolean alone — the frame
            // path smooths this so the boost ramps, never switches (F7).
            humanScoreTarget = maxHumanProb.coerceAtMost(1f)
            humanLikely = human
            if (bestIdx in labels.indices) sceneCategory = labels[bestIdx]
            // F9/P4 diagnostics: verdict + top-1 confidence, per classification.
            val topIdx = top.firstOrNull() ?: -1
            Log.i(
                TAG,
                "classify: scene=${labels.getOrNull(topIdx) ?: "?"} " +
                    "human=$human humanScore=%.3f top1=%.3f frame=$frameCounter".format(maxHumanProb, probs[topIdx]),
            )
        }
    }

    // ---------------------------------------------------------------- teardown

    /** Thread-safe shutdown (spec, Component 2 lifecycle). Idempotent. */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        classifyExecutor.execute {
            runCatching {
                interpreter?.close()
                gpuDelegate?.close()
            }
        }
        classifyExecutor.shutdown()
        interpreter = null
        gpuDelegate = null
    }
}
