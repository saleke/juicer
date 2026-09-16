package com.eqo

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import android.view.WindowManager
import com.eqo.pipeline.TranscodePipeline
import com.eqo.pipeline.TranscodeResult
import com.eqo.pipeline.VideoPipelineStatus
import com.eqo.reclaim.RollbackCache
import com.eqo.reclaim.VideoGalleryScanner
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * adb-driven harness that runs the zero-copy pipeline against a URI and logs
 * the outcome — the automation-friendly path for the design review's on-device
 * verification step 4 (smoke test). Not part of the product UI.
 *
 * Usage:
 * ```
 * adb shell am start -n com.eqo/.MainActivity \
 *   -a com.eqo.action.SMOKE_TEST --es com.eqo.extra.URI <content-or-file-uri>
 *
 * NOTE: the app is headless once the trampoline activity finishes, so Android's
 * cached-app freezer will suspend it mid-run. For adb-driven smoke tests either
 * disable the freezer first (`adb shell settings put global cached_apps_freezer
 * disabled`) or keep the process foregrounded another way.
 * ```
 */
object SmokeTestLauncher {

    private const val TAG = "eqo.SmokeTest"

    /** Fixed allowance (covers the SSIM post-pass) plus a per-second budget. */
    private const val COMPLETION_FIXED_MS = 120_000L
    private const val COMPLETION_MS_PER_SOURCE_SEC = 2_000L

    // --- Feature 2/3 extras ---
    private const val EXTRA_SCAN = "com.eqo.extra.SCAN"
    private const val EXTRA_RECLAIM = "com.eqo.extra.RECLAIM"
    private const val EXTRA_RECLAIM_URI = "com.eqo.extra.RECLAIM_URI"
    private const val EXTRA_RECLAIM_MIME = "com.eqo.extra.RECLAIM_MIME"
    private const val EXTRA_BATCH = "com.eqo.extra.BATCH"
    private const val EXTRA_BATCH_MAX = "com.eqo.extra.BATCH_MAX"

    fun runFromIntent(activity: Activity, intent: Intent) {
        val appContext = activity.applicationContext
        // Keep the trampoline activity alive for the duration of the run: a
        // finished activity leaves the process in the cached state, where
        // Android schedules it in the background cpuset (~3–5× CPU starvation
        // on the test phone, violating the 8 ms frame budget). A production
        // transcode session runs as a foreground service for the same reason.
        // The screen flag keeps the process in a foreground scheduling state.
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // --- Feature 2: MediaStore scan smoke test (no pipeline) ---
        if (intent.getBooleanExtra(EXTRA_SCAN, false)) {
            runScanOnly(appContext)
            activity.finish()
            return
        }

        val uriString = intent.getStringExtra(MainActivity.EXTRA_URI)

        // --- Feature 3: standalone rollback-backup smoke test (no pipeline) ---
        if (intent.getBooleanExtra(EXTRA_RECLAIM, false) && uriString.isNullOrBlank()) {
            runReclaimOnly(appContext, intent)
            activity.finish()
            return
        }

        // --- Feature 2: serial batch smoke test (no explicit uri; scans) ---
        if (intent.getBooleanExtra(EXTRA_BATCH, false)) {
            runBatchOnly(activity, appContext, intent)
            return
        }

        if (uriString.isNullOrBlank()) {
            Log.w(TAG, "smoke test requested but no 'uri' extra present; ignoring")
            activity.finish()
            return
        }

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val pipeline = TranscodePipeline(appContext)

        scope.launch {
            try {
                Log.i(TAG, "smoke test: prepare+start for $uriString")
                pipeline.prepare(Uri.parse(uriString)).also { md ->
                    Log.i(
                        TAG,
                        "prepared: ${md.width}x${md.height} ${md.mime} @${md.frameRate}fps " +
                            "rot=${md.rotationDegrees} dur=${md.durationUs / 1000}ms",
                    )
                }
                pipeline.start()

                // Decode+encode+finalize roughly tracks clip duration on the
                // test phone; a fixed cap timed out on a 10-minute clip.
                val timeoutMs =
                    COMPLETION_FIXED_MS + (pipeline.metadata.value?.durationUs ?: 0L) /
                        1_000_000L * COMPLETION_MS_PER_SOURCE_SEC
                val terminal = withTimeoutOrNull(timeoutMs) {
                    pipeline.status.first { it == VideoPipelineStatus.COMPLETED || it == VideoPipelineStatus.ERROR }
                }
                when (terminal) {
                    VideoPipelineStatus.COMPLETED -> Log.i(TAG, "SMOKE OK")
                    VideoPipelineStatus.ERROR -> Log.i(TAG, "SMOKE ERROR: ${pipeline.error.value}")
                    null -> Log.w(TAG, "SMOKE TIMEOUT after ${timeoutMs / 1000}s")
                    else -> Unit // first{...} only yields COMPLETED/ERROR, but stay exhaustive
                }
                val m = pipeline.metrics.value
                Log.i(
                    TAG,
                    "metrics: rendered=${m.framesRendered} processed=${m.framesProcessed} " +
                        "dropped=${m.framesDropped} overBudget=${m.framesOverBudget} " +
                        "encoded=${m.framesEncoded} encodedBytes=${m.encodedBytes} " +
                        "processAvgMs=%.2f decodeAvgMs=%.2f".format(
                            m.processNanosAvg / 1_000_000.0,
                            m.decodeNanosAvg / 1_000_000.0,
                        ),
                )
                // The Component 3 outcome: output file + space saved. The result
                // flow is set inside onDecodeComplete, before COMPLETED.
                pipeline.result.value?.let { r ->
                    val ssimStr = r.visualIntegrityPercent?.let { "%.1f%%".format(it) } ?: "n/a"
                    val savedPercentStr = "%.1f%%".format(r.savedPercent)
                    Log.i(
                        TAG,
                        "result: out=${r.outputUri} ${r.codecName} ${r.mime} " +
                            "inBytes=${r.inputBytes} outBytes=${r.outputBytes} " +
                            "savedBytes=${r.savedBytes} savedPercent=$savedPercentStr frames=${r.framesEncoded} " +
                            "audio=${r.audioPassthrough} ssim=$ssimStr",
                    )
                } ?: Log.w(TAG, "result: none (encoder did not produce output)")

                // --- Feature 3: full reclaim on this run's original (backup +
                // retire + rename). Only meaningful when the source is a
                // MediaStore row owned by this app (e.g. re-running on an
                // earlier thumbnailed output) — otherwise the delete is
                // read-only and just logged.
                if (intent.getBooleanExtra(EXTRA_RECLAIM, false)) {
                    val res = pipeline.result.value
                    if (res == null) {
                        Log.w(TAG, "reclaim-after: no result; skipping")
                    } else {
                        runReclaimAfter(appContext, uriString, res)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "smoke test crashed", e)
            } finally {
                pipeline.release()
                scope.cancel()
                activity.runOnUiThread { activity.finish() }
            }
        }
    }

    private fun runScanOnly(appContext: Context) {
        val hasPerm = VideoGalleryScanner.hasPermission(appContext)
        Log.i(TAG, "scan: hasPermission=$hasPerm")
        if (!hasPerm) {
            Log.w(
                TAG,
                "scan: permission not granted; grant with: adb shell pm grant com.eqo android.permission.READ_EXTERNAL_STORAGE",
            )
        } else {
            val candidates = VideoGalleryScanner.scan(appContext.contentResolver, limit = 50)
            // Cross-check: how many eqo_* rows exist in MediaStore at all.
            val eqoRows = appContext.contentResolver.query(
                android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                arrayOf(android.provider.MediaStore.Video.Media._ID),
                "${android.provider.MediaStore.Video.Media.DISPLAY_NAME} LIKE 'eqo\\_%' ESCAPE '\\'",
                null,
                null,
            )?.use { it.count } ?: 0
            Log.i(TAG, "scan: ${candidates.size} candidates; eqo rows excluded in MediaStore=$eqoRows")
            candidates.take(5).forEachIndexed { i, c ->
                Log.i(
                    TAG,
                    "scan[$i]: ${c.displayName} size=${c.sizeBytes} reclaim=${c.reclaimableBytes()} " +
                        "res=${c.resolution} dur=${c.durationSeconds}s",
                )
            }
        }
    }

    private fun runReclaimOnly(appContext: Context, intent: Intent) {
        val reclaimUri = intent.getStringExtra(EXTRA_RECLAIM_URI)
        val reclaimMime = intent.getStringExtra(EXTRA_RECLAIM_MIME) ?: "video/mp4"
        if (reclaimUri.isNullOrBlank()) {
            Log.w(TAG, "reclaim: no uri extra; pass --es com.eqo.extra.RECLAIM_URI <uri>")
        } else {
            val cache = RollbackCache(File(appContext.filesDir, "reclaim"))
            runCatching {
                appContext.contentResolver.openInputStream(Uri.parse(reclaimUri))?.use { stream ->
                    val entry = cache.create(
                        displayName = "smoke_test.mp4",
                        originalUri = reclaimUri,
                        sourceMime = reclaimMime,
                        copyFrom = stream,
                    )
                    Log.i(TAG, "reclaim: backup OK id=${entry.id} bytes=${entry.sizeBytes}")
                } ?: Log.w(TAG, "reclaim: openInputStream returned null")
            }.onFailure { Log.e(TAG, "reclaim: backup failed", it) }
            Log.i(
                TAG,
                "reclaim: totalRollbackBytes=${cache.totalBytes()} entries=${cache.entries().size}",
            )
        }
    }

    /**
     * Feature 2 serial-batch smoke test: scan, pick small quick candidates,
     * and run them one-at-a-time with a fresh pipeline each (mirrors the
     * ViewModel batch driver, which is not driveable headlessly).
     */
    private fun runBatchOnly(activity: Activity, appContext: Context, intent: Intent) {
        val maxItems = intent.getIntExtra(EXTRA_BATCH_MAX, 3).coerceIn(1, 10)
        val candidates = VideoGalleryScanner.scan(appContext.contentResolver, limit = 100)
            .filter { it.sizeBytes in 1..30_000_000L && it.durationUs in 1..30_000_000L }
            .take(maxItems)
        if (candidates.isEmpty()) {
            Log.i(TAG, "batch: no candidates matching the quick-batch filter")
            activity.finish()
            return
        }
        Log.i(TAG, "batch: ${candidates.size} items -> ${candidates.joinToString { it.displayName }}")
        // Production batch runs from the ViewModel, which holds a foreground
        // dataSync service to keep the process un-cached (the adb harness has
        // no visible window, so LMK would otherwise reap us under pressure).
        com.eqo.service.TranscodeService.start(appContext)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope.launch {
            var okCount = 0
            candidates.forEachIndexed { i, c ->
                Log.i(TAG, "batch[$i/${candidates.size}]: start ${c.displayName}")
                val p = TranscodePipeline(appContext)
                try {
                    p.prepare(c.uri)
                    p.start()
                    val timeoutMs = COMPLETION_FIXED_MS + c.durationUs / 1_000_000L * COMPLETION_MS_PER_SOURCE_SEC
                    val terminal = withTimeoutOrNull(timeoutMs) {
                        p.status.first {
                            it == VideoPipelineStatus.COMPLETED || it == VideoPipelineStatus.ERROR
                        }
                    }
                    when (terminal) {
                        VideoPipelineStatus.COMPLETED -> {
                            okCount++
                            val r = p.result.value
                            val ssim = r?.visualIntegrityPercent?.let { "%.1f%%".format(it) } ?: "n/a"
                            Log.i(
                                TAG,
                                "batch[$i]: OK saved=${r?.savedPercent?.let { "%.1f%%".format(it) } ?: "?"} " +
                                    "ssim=$ssim out=${if (r == null) c.displayName else r.outputUri}",
                            )
                        }
                        VideoPipelineStatus.ERROR ->
                            Log.e(TAG, "batch[$i]: ERROR ${p.error.value}")
                        null ->
                            Log.w(TAG, "batch[$i]: TIMEOUT")
                        else -> Unit
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "batch[$i]: crashed", e)
                } finally {
                    runCatching { p.release() }
                }
            }
            Log.i(TAG, "batch: done $okCount/${candidates.size} succeeded")
            com.eqo.service.TranscodeService.stop(appContext)
            activity.runOnUiThread { activity.finish() }
        }
    }

    /**
     * Full reclaim (Feature 3) on an [[originalUri]] whose pipeline run just
     * finished: backup the original into the rollback cache, retire its
     * gallery row, then point the fresh output at the original's name.
     */
    private fun runReclaimAfter(appContext: Context, originalUri: String, result: TranscodeResult) {
        val cache = RollbackCache(File(appContext.filesDir, "reclaim"))
        val resolver = appContext.contentResolver
        val src = Uri.parse(originalUri)
        try {
            val name = resolver.query(src, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }
                ?: "video.mp4"
            val entry = resolver.openInputStream(src)?.use { streams ->
                cache.create(name, originalUri, result.mime, streams)
            } ?: run {
                Log.w(TAG, "reclaim-after: openInputStream returned null (read-only?)")
                return
            }
            Log.i(TAG, "reclaim-after: backed up ${entry.displayName} bytes=${entry.sizeBytes}")
            val rows = if (src.scheme == "content" && src.authority == MediaStore.AUTHORITY) {
                resolver.delete(src, null, null)
            } else {
                if (android.provider.DocumentsContract.deleteDocument(resolver, src)) 1 else 0
            }
            Log.i(TAG, "reclaim-after: retired original rows=$rows")
            if (rows > 0) {
                val base = name.substringBeforeLast('.')
                resolver.update(
                    result.outputUri,
                    ContentValues().apply {
                        put(MediaStore.Video.Media.DISPLAY_NAME, "$base.mp4")
                    },
                    null,
                    null,
                )
                Log.i(TAG, "reclaim-after: output renamed to ${base}.mp4")
            }
        } catch (e: Exception) {
            Log.e(TAG, "reclaim-after failed", e)
        }
        Log.i(TAG, "reclaim-after: totalRollbackBytes=${cache.totalBytes()} entries=${cache.entries().size}")
    }
}
