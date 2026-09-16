package com.eqo.ui

import android.app.Application
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.eqo.feedback.CompressionSessionMetrics
import com.eqo.feedback.JuicerFeedbackLoop
import com.eqo.feedback.UserAction
import com.eqo.pipeline.PipelineException
import com.eqo.pipeline.PipelineMetrics
import com.eqo.pipeline.TranscodePipeline
import com.eqo.pipeline.TranscodeResult
import com.eqo.pipeline.VideoMetadata
import com.eqo.pipeline.VideoPipelineStatus
import com.eqo.reclaim.ReclaimMath
import com.eqo.reclaim.RollbackCache
import com.eqo.reclaim.VideoCandidate
import com.eqo.reclaim.VideoGalleryScanner
import com.eqo.service.TranscodeService
import com.eqo.thermal.JuicerThermalGovernor
import com.eqo.thermal.ThermalMitigation
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Production ViewModel: manages transcode pipeline, Component 4 feedback loop,
 * thermal governor telemetry, and background foreground service lifecycle.
 */
class PipelineViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        private const val TAG = "eqo.Reclaim"
    }

    data class UiState(
        val uri: Uri? = null,
        val status: VideoPipelineStatus = VideoPipelineStatus.IDLE,
        val metadata: VideoMetadata? = null,
        val metrics: PipelineMetrics = PipelineMetrics(),
        val thermalMitigation: ThermalMitigation? = null,
        val error: String? = null,
        val preparing: Boolean = false,
        /** Set when a run finishes: output file, sizes, saved % (Component 3). */
        val result: TranscodeResult? = null,
        /** Video category determined by AI during run. */
        val category: String = "unknown",
        /** Last recorded thermal delta in degrees Celsius. */
        val thermalDelta: Float = 0.0f,
        /** Feature 2 — batch compression & gallery scanner state. */
        val scanGranted: Boolean = false,
        val scanning: Boolean = false,
        val scanError: String? = null,
        val scanCandidates: List<VideoCandidate> = emptyList(),
        val selectedCandidateIds: Set<Long> = emptySet(),
        val batchRunning: Boolean = false,
        val batchIndex: Int = 0,
        val batchTotal: Int = 0,
        val batchActiveVideo: String? = null,
        val batchResults: List<BatchItemResult> = emptyList(),
        /** Fraction used by the last scan (keeps list math == scan math). */
        val targetFraction: Float = ReclaimMath.DEFAULT_TARGET_FRACTION,
        /** Feature 3 — atomic storage reclamation & rollback. */
        val reclaimNote: String? = null,
        val rollbackBytes: Long = 0,
        val rollbackEntries: List<RollbackCache.Entry> = emptyList(),
    )

    /** Outcome of one batch item (single-run UI fields are not reused). */
    data class BatchItemResult(
        val candidate: VideoCandidate,
        val result: TranscodeResult?,
        val error: String?,
    )

    private data class BatchOutcome(
        val result: TranscodeResult?,
        val error: String?,
        val category: String?,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    val feedbackLoop = JuicerFeedbackLoop(app, viewModelScope)
    val thermalGovernor = JuicerThermalGovernor(app)

    /** Feature 3 — app-private rollback cache (root injected for tests). */
    private val rollbackCache = RollbackCache(File(app.filesDir, "reclaim"))
    private var pipeline: TranscodePipeline? = null
    private var observer: Job? = null
    private var batchJob: Job? = null
    private var initialTemp: Float = 35.0f

    init {
        viewModelScope.launch {
            thermalGovernor.mitigation.collect { m ->
                _uiState.update { it.copy(thermalMitigation = m) }
            }
        }
        refreshScanPermission()
        viewModelScope.launch(Dispatchers.Default) {
            purgeExpiredRollbacks()
            refreshRollbackCache()
        }
    }

    // =========================================================================
    // Feature 2 — gallery scanner + batch compression
    // =========================================================================

    fun refreshScanPermission() {
        _uiState.update {
            it.copy(scanGranted = VideoGalleryScanner.hasPermission(getApplication()))
        }
    }

    /** Called from the permission launcher; kicks off the scan when granted. */
    fun onScanPermissionResult(granted: Boolean) {
        _uiState.update { it.copy(scanGranted = granted) }
        if (granted) scanVideos()
    }

    /** Runs the MediaStore discovery on an I/O dispatcher. */
    fun scanVideos() {
        val s = _uiState.value
        if (s.scanning || !s.scanGranted) return
        _uiState.update { it.copy(scanning = true, scanError = null) }
        viewModelScope.launch(Dispatchers.Default) {
            val app = getApplication<Application>()
            val fraction = feedbackLoop.getDynamicConstraintsForCategory(_uiState.value.category)
            val candidates = withContext(Dispatchers.IO) {
                VideoGalleryScanner.scan(app.contentResolver, targetFraction = fraction)
            }
            _uiState.update { it.copy(scanning = false, scanCandidates = candidates, targetFraction = fraction) }
        }
    }

    fun toggleCandidate(id: Long) {
        _uiState.update { s ->
            val sel = s.selectedCandidateIds
            s.copy(selectedCandidateIds = if (id in sel) sel - id else sel + id)
        }
    }

    fun clearSelection() = _uiState.update { it.copy(selectedCandidateIds = emptySet()) }

    /**
     * Drives the batch queue: each item gets a fresh [TranscodePipeline],
     * serially (one at a time — the thermal governor and hardware codec are
     * shared). Every successful item is recorded into the C4 feedback loop as
     * [UserAction.KEPT_OFFLINE] (outputs stay in the gallery).
     */
    fun startBatch() {
        val s = _uiState.value
        if (s.batchRunning || s.status == VideoPipelineStatus.DECODING) return
        val candidates = s.scanCandidates.filter { it.id in s.selectedCandidateIds }
        if (candidates.isEmpty()) return
        disposePipeline()
        _uiState.update {
            it.copy(
                batchRunning = true, batchIndex = 0, batchTotal = candidates.size,
                batchActiveVideo = null, batchResults = emptyList(), error = null,
            )
        }
        batchJob?.cancel()
        batchJob = viewModelScope.launch(Dispatchers.Default) {
            val results = mutableListOf<BatchItemResult>()
            try {
                for ((i, c) in candidates.withIndex()) {
                    _uiState.update { it.copy(batchIndex = i, batchActiveVideo = c.displayName) }
                    val outcome = runBatchItem(c)
                    val item = BatchItemResult(c, outcome.result, outcome.error)
                    results += item
                    if (outcome.result != null) {
                        feedbackLoop.recordSession(
                            CompressionSessionMetrics(
                                videoCategory = outcome.category ?: _uiState.value.category,
                                inputBytes = outcome.result.inputBytes,
                                outputBytes = outcome.result.outputBytes,
                                savedPercent = outcome.result.savedPercent,
                                thermalDeltaCelsius = 0f,
                                visualIntegrityPercent = outcome.result.visualIntegrityPercent,
                                userAction = UserAction.KEPT_OFFLINE,
                            ),
                        )
                    }
                    _uiState.update { it.copy(batchResults = results.toList()) }
                    if (!isActive) break
                }
            } finally {
                _uiState.update {
                    it.copy(
                        batchRunning = false, batchIndex = 0, batchTotal = 0,
                        batchActiveVideo = null, selectedCandidateIds = emptySet(),
                        scanCandidates = emptyList(),
                    )
                }
            }
        }
    }

    /** Runs one candidate end-to-end and returns its outcome (never throws). */
    private suspend fun runBatchItem(c: VideoCandidate): BatchOutcome {
        val p = TranscodePipeline(
            context = getApplication(),
            thermalGovernor = thermalGovernor,
            targetFraction = feedbackLoop.getDynamicConstraintsForCategory(_uiState.value.category),
        )
        try {
            p.prepare(c.uri)
            p.start()
            val terminal = p.status.first {
                it == VideoPipelineStatus.COMPLETED || it == VideoPipelineStatus.ERROR
            }
            return if (terminal == VideoPipelineStatus.COMPLETED) {
                val r = p.result.value
                if (r == null) BatchOutcome(null, "completed without a result", null)
                else BatchOutcome(r, null, p.aiEngine.sceneCategory)
            } else {
                BatchOutcome(null, p.error.value ?: "pipeline failed", null)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return BatchOutcome(null, e.message ?: e.javaClass.simpleName, null)
        } finally {
            runCatching { p.release() }
        }
    }

    // =========================================================================
    // Feature 3 — atomic storage reclamation & rollback
    // =========================================================================

    /**
     * Reclaims the current run's original file: backs it into the private
     * rollback cache, retires the gallery row (tiered, best effort), then
     * points the published output at the original's name.
     */
    fun reclaimSpace() {
        val s = _uiState.value
        val src = s.uri ?: return
        val res = s.result ?: return
        if (s.batchRunning) return
        _uiState.update { it.copy(reclaimNote = null) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val resolver = getApplication<Application>().contentResolver
                val originalName = queryDisplayName(resolver, src) ?: "video.mp4"
                val entry = resolver.openInputStream(src)?.let { stream ->
                    rollbackCache.create(
                        displayName = originalName,
                        originalUri = src.toString(),
                        sourceMime = res.mime,
                        copyFrom = stream,
                    )
                } ?: throw PipelineException("could not open the original for backup")

                val removed = retireOriginal(src)
                if (removed) {
                    renameOwnedOutput(res, originalName)
                    _uiState.update {
                        it.copy(reclaimNote =
                            "Original moved to the private rollback cache — " +
                                "freed ~${formatBytes(res.savedBytes)} and the compressed copy sits in its place."
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(reclaimNote =
                            "Original is backed up in the rollback cache, but the gallery row " +
                                "returned read-only, so the file is retained in place."
                        )
                    }
                }
                refreshRollbackCache()
            } catch (e: Exception) {
                _uiState.update { it.copy(reclaimNote = "Reclaim failed: ${e.message}") }
            }
        }
    }

    fun refreshRollbackCache() {
        viewModelScope.launch(Dispatchers.IO) {
            val entries = rollbackCache.entries()
            _uiState.update {
                it.copy(rollbackEntries = entries, rollbackBytes = rollbackCache.totalBytes())
            }
        }
    }

    fun purgeRollback(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            rollbackCache.purge(id)
            refreshRollbackCache()
        }
    }

    fun purgeAllRollback() {
        viewModelScope.launch(Dispatchers.IO) {
            rollbackCache.purgeAll()
            refreshRollbackCache()
        }
    }

    /** Retention policy: entries older than the window are purged on boot. */
    private suspend fun purgeExpiredRollbacks() = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        rollbackCache.entries()
            .filter { ReclaimMath.shouldAutoPurge(it.backedUpAtMs, now) }
            .forEach { rollbackCache.purge(it.id) }
    }

        private fun queryDisplayName(resolver: android.content.ContentResolver, uri: Uri): String? =
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()

    /**
     * Tiered, best-effort removal of the original's gallery row.
     * `[verify-on-device]`: MediaStore rows owned by this app delete without a
     * dialog on API 31; rows owned by other apps may require the system
     * confirmation flow, in which case this returns false and the file stays.
     */
    private fun retireOriginal(src: Uri): Boolean {
        val resolver = getApplication<Application>().contentResolver
        return try {
            if (src.scheme == "content" && src.authority == MediaStore.AUTHORITY) {
                resolver.delete(src, null, null) > 0
            } else {
                DocumentsContract.deleteDocument(resolver, src)
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "original removal blocked: ${e.message}")
            false
        } catch (e: Exception) {
            Log.w(TAG, "original removal failed: ${e.message}")
            false
        }
    }

    /** Renames our published output to the original's name so it replaces it. */
    private fun renameOwnedOutput(res: TranscodeResult, originalName: String) {
        val base = originalName.substringBeforeLast('.')
        runCatching {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, "$base.mp4")
            }
            getApplication<Application>().contentResolver.update(res.outputUri, values, null, null)
        }.onFailure { Log.w(TAG, "output rename skipped: ${it.message}") }
    }

    private fun formatBytes(bytes: Long): String {
        val kb = bytes / 1024.0
        return if (kb >= 1024) "%.1f MB".format(kb / 1024) else "%.0f KB".format(kb)
    }

    fun onVideoPicked(uri: Uri) {
        disposePipeline()
        // Feature 3 — ask for a persistable write grant so the original can be
        // retired via DocumentsContract if it is a SAF document. Providers may
        // still return read-only; the reclaim flow degrades gracefully.
        runCatching {
            getApplication<Application>().contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        _uiState.update {
            it.copy(
                uri = uri,
                status = VideoPipelineStatus.IDLE,
                metadata = null,
                metrics = PipelineMetrics(),
                error = null,
                result = null,
            )
        }
    }

    fun start() {
        val uri = _uiState.value.uri ?: return
        if (_uiState.value.preparing || _uiState.value.batchRunning) return

        initialTemp = thermalGovernor.getBatteryTemperature() ?: 35.0f
        TranscodeService.start(getApplication())

        val p = pipeline ?: createPipeline()
        viewModelScope.launch {
            _uiState.update { it.copy(preparing = true, error = null) }
            try {
                p.prepare(uri)
                p.start()
            } catch (e: PipelineException) {
                TranscodeService.stop(getApplication())
                _uiState.update { it.copy(error = e.message) }
            } finally {
                _uiState.update { it.copy(preparing = false) }
            }
        }
    }

    fun pause() = pipeline?.pause()

    fun resume() = pipeline?.resume()

    /** Stops the run, frees pipeline and service resources. */
    fun reset() {
        TranscodeService.stop(getApplication())
        disposePipeline()
        _uiState.update {
            it.copy(
                status = VideoPipelineStatus.IDLE,
                metrics = PipelineMetrics(),
                error = null,
                result = null,
            )
        }
    }

    /**
     * Handles post-transcode user actions, recording session metrics into the
     * Component 4 feedback loop and triggering platform share or delete flows.
     */
    fun onUserAction(action: UserAction, onShareIntent: ((Intent) -> Unit)? = null) {
        val res = _uiState.value.result ?: return
        val cat = _uiState.value.category
        val metrics = CompressionSessionMetrics(
            videoCategory = cat,
            inputBytes = res.inputBytes,
            outputBytes = res.outputBytes,
            savedPercent = res.savedPercent,
            thermalDeltaCelsius = _uiState.value.thermalDelta,
            visualIntegrityPercent = res.visualIntegrityPercent,
            userAction = action,
        )
        feedbackLoop.recordSession(metrics)

        when (action) {
            UserAction.SAVED_AND_SHARED -> {
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "video/mp4"
                    putExtra(Intent.EXTRA_STREAM, res.outputUri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                onShareIntent?.invoke(Intent.createChooser(shareIntent, "Share Compressed Video"))
            }
            UserAction.PROMPTLY_DELETED -> {
                runCatching {
                    getApplication<Application>().contentResolver.delete(res.outputUri, null, null)
                }
                reset()
            }
            UserAction.KEPT_OFFLINE -> {
                // File is already saved and published in MediaStore Movies/eqo
            }
            UserAction.CANCELLED -> {
                reset()
            }
        }
    }

    private fun createPipeline(): TranscodePipeline {
        // Query learned target fraction for the category from Component 4
        val learnedTarget = feedbackLoop.getDynamicConstraintsForCategory(_uiState.value.category)

        return TranscodePipeline(
            context = getApplication(),
            thermalGovernor = thermalGovernor,
            targetFraction = learnedTarget,
        ).also { p ->
            pipeline = p
            observer?.cancel()
            observer = viewModelScope.launch {
                launch {
                    p.status.collect { s ->
                        _uiState.update { it.copy(status = s) }
                        if (s == VideoPipelineStatus.COMPLETED || s == VideoPipelineStatus.ERROR) {
                            TranscodeService.stop(getApplication())
                            val endTemp = thermalGovernor.getBatteryTemperature() ?: initialTemp
                            val delta = (endTemp - initialTemp).coerceAtLeast(0f)
                            val cat = p.aiEngine.sceneCategory
                            _uiState.update { it.copy(thermalDelta = delta, category = cat) }
                        }
                    }
                }
                launch { p.metadata.collect { m -> _uiState.update { it.copy(metadata = m) } } }
                launch { p.metrics.collect { m -> _uiState.update { it.copy(metrics = m) } } }
                launch { p.error.collect { e -> if (e != null) _uiState.update { it.copy(error = e) } } }
                launch { p.result.collect { r -> if (r != null) _uiState.update { it.copy(result = r) } } }
            }
        }
    }

    private fun disposePipeline() {
        observer?.cancel()
        observer = null
        pipeline?.release()
        pipeline = null
    }

    override fun onCleared() {
        TranscodeService.stop(getApplication())
        thermalGovernor.close()
        feedbackLoop.close()
        disposePipeline()
    }
}
