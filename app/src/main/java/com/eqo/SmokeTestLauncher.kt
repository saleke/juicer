package com.eqo

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import com.eqo.pipeline.TranscodePipeline
import com.eqo.pipeline.VideoPipelineStatus
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

    fun runFromIntent(activity: Activity, intent: Intent) {
        val uriString = intent.getStringExtra(MainActivity.EXTRA_URI)
        if (uriString.isNullOrBlank()) {
            Log.w(TAG, "smoke test requested but no 'uri' extra present; ignoring")
            activity.finish()
            return
        }

        val appContext = activity.applicationContext
        // Keep the trampoline activity alive for the duration of the run: a
        // finished activity leaves the process in the cached state, where
        // Android schedules it in the background cpuset (~3–5× CPU starvation
        // on the test phone, violating the 8 ms frame budget). A production
        // transcode session runs as a foreground service for the same reason.
        // The screen flag keeps the process in a foreground scheduling state.
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val pipeline = TranscodePipeline(appContext)

        scope.launch {
            try {
                Log.i(TAG, "smoke test: prepare+start for $uriString")
                pipeline.prepare(Uri.parse(uriString)).also { md ->
                    Log.i(TAG, "prepared: ${md.width}x${md.height} ${md.mime} @${md.frameRate}fps")
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
                    Log.i(
                        TAG,
                        ("result: out=${r.outputUri} ${r.codecName} ${r.mime} " +
                            "inBytes=${r.inputBytes} outBytes=${r.outputBytes} " +
                            "savedBytes=${r.savedBytes} savedPercent=%.1f frames=${r.framesEncoded} " +
                            "audio=${r.audioPassthrough} " +
                            "ssim=${r.visualIntegrityPercent?.let { "%.1f%%".format(it) } ?: "n/a"}")
                            .format(r.savedPercent),
                    )
                } ?: Log.w(TAG, "result: none (encoder did not produce output)")
            } catch (e: Exception) {
                Log.e(TAG, "smoke test crashed", e)
            } finally {
                pipeline.release()
                scope.cancel()
                activity.runOnUiThread { activity.finish() }
            }
        }
    }
}
