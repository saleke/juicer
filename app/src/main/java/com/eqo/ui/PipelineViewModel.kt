package com.eqo.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.eqo.pipeline.PipelineException
import com.eqo.pipeline.PipelineMetrics
import com.eqo.pipeline.TranscodePipeline
import com.eqo.pipeline.TranscodeResult
import com.eqo.pipeline.VideoMetadata
import com.eqo.pipeline.VideoPipelineStatus
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Harness view-model: owns one [ZeroCopyVideoPipeline] per run, mirrors its flows
 * into a single UI state, and releases it on [reset]/[onCleared].
 */
class PipelineViewModel(app: Application) : AndroidViewModel(app) {

    data class UiState(
        val uri: Uri? = null,
        val status: VideoPipelineStatus = VideoPipelineStatus.IDLE,
        val metadata: VideoMetadata? = null,
        val metrics: PipelineMetrics = PipelineMetrics(),
        val error: String? = null,
        val preparing: Boolean = false,
        /** Set when a run finishes: output file, sizes, saved % (Component 3). */
        val result: TranscodeResult? = null,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var pipeline: TranscodePipeline? = null
    private var observer: Job? = null

    fun onVideoPicked(uri: Uri) {
        disposePipeline()
        // Persist the SAF grant so the file survives process death (pitfall P10).
        runCatching {
            getApplication<Application>().contentResolver
                .takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
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
        if (_uiState.value.preparing) return
        val p = pipeline ?: createPipeline()
        viewModelScope.launch {
            _uiState.update { it.copy(preparing = true, error = null) }
            try {
                p.prepare(uri)
                p.start()
            } catch (e: PipelineException) {
                // The pipeline already set its own error flow; keep local state in sync.
                _uiState.update { it.copy(error = e.message) }
            } finally {
                _uiState.update { it.copy(preparing = false) }
            }
        }
    }

    fun pause() = pipeline?.pause()

    fun resume() = pipeline?.resume()

    /** Stops the run, frees all pipeline resources; a new run can then be started. */
    fun reset() {
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

    private fun createPipeline(): TranscodePipeline =
        TranscodePipeline(getApplication()).also { p ->
            pipeline = p
            observer?.cancel()
            observer = viewModelScope.launch {
                launch { p.status.collect { s -> _uiState.update { it.copy(status = s) } } }
                launch { p.metadata.collect { m -> _uiState.update { it.copy(metadata = m) } } }
                launch { p.metrics.collect { m -> _uiState.update { it.copy(metrics = m) } } }
                launch { p.error.collect { e -> if (e != null) _uiState.update { it.copy(error = e) } } }
                launch { p.result.collect { r -> if (r != null) _uiState.update { it.copy(result = r) } } }
            }
        }

    private fun disposePipeline() {
        observer?.cancel()
        observer = null
        pipeline?.release()
        pipeline = null
    }

    override fun onCleared() = disposePipeline()
}
