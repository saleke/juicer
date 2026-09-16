package com.eqo.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.eqo.pipeline.PipelineMetrics
import com.eqo.pipeline.TranscodeResult
import com.eqo.pipeline.VideoMetadata
import com.eqo.pipeline.VideoPipelineStatus

/**
 * Minimal harness screen for the zero-copy pipeline: pick a local video, run it,
 * and watch status / metadata / metrics. Not a product UI.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PipelineScreen(vm: PipelineViewModel = viewModel()) {
    val state by vm.uiState.collectAsStateWithLifecycle()

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(vm::onVideoPicked)
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("eqo") }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(
                onClick = { picker.launch(arrayOf("video/*")) },
                enabled = !state.preparing,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Pick a video") }

            state.uri?.let { uri ->
                Text(
                    text = uri.toString(),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (state.preparing) LinearProgressIndicator(Modifier.fillMaxWidth())

            StatusChip(state.status)
            state.metadata?.let { MetadataCard(it) }
            MetricsCard(state.metrics)
            state.result?.let { ResultCard(it) }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = vm::start,
                    enabled = state.uri != null &&
                        state.status == VideoPipelineStatus.IDLE &&
                        !state.preparing,
                ) { Text("Start") }

                when (state.status) {
                    VideoPipelineStatus.DECODING ->
                        Button(onClick = vm::pause) { Text("Pause") }
                    VideoPipelineStatus.PAUSED ->
                        Button(onClick = vm::resume) { Text("Resume") }
                    else -> Unit
                }

                OutlinedButton(
                    onClick = vm::reset,
                    enabled = state.status != VideoPipelineStatus.IDLE,
                ) { Text("Reset") }
            }

            state.error?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun StatusChip(status: VideoPipelineStatus) {
    AssistChip(
        onClick = {},
        label = { Text("Status: ${status.name}") },
        leadingIcon = {
            val color = when (status) {
                VideoPipelineStatus.IDLE -> MaterialTheme.colorScheme.outline
                VideoPipelineStatus.DECODING -> MaterialTheme.colorScheme.primary
                VideoPipelineStatus.PAUSED -> MaterialTheme.colorScheme.tertiary
                VideoPipelineStatus.COMPLETED -> MaterialTheme.colorScheme.secondary
                VideoPipelineStatus.ERROR -> MaterialTheme.colorScheme.error
            }
            Text("●", color = color)
        },
    )
}

@Composable
private fun MetadataCard(md: VideoMetadata) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Video", style = MaterialTheme.typography.titleMedium)
            LabeledValue("Resolution", md.resolution + rotationNote(md.rotationDegrees))
            LabeledValue("Frame rate", if (md.frameRate > 0) "${md.frameRate} fps" else "unknown")
            LabeledValue(
                "Bitrate",
                if (md.bitrate > 0) "%.2f Mbps".format(md.bitrate / 1_000_000.0) else "unknown",
            )
            LabeledValue("Codec", md.mime)
            LabeledValue("Color format", "0x%04X".format(md.colorFormat))
        }
    }
}

private fun rotationNote(degrees: Int): String =
    if (degrees % 360 != 0) " (rotate $degrees° for display)" else ""

@Composable
private fun MetricsCard(metrics: PipelineMetrics) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Pipeline", style = MaterialTheme.typography.titleMedium)
            LabeledValue("Frames rendered", metrics.framesRendered.toString())
            LabeledValue("Frames processed", metrics.framesProcessed.toString())
            LabeledValue("Frames dropped (backpressure)", metrics.framesDropped.toString())
            LabeledValue(
                "Frame processing",
                "%.2f ms avg / %.2f ms last".format(
                    metrics.processNanosAvg / 1_000_000.0,
                    metrics.processNanosLast / 1_000_000.0,
                ),
            )
            LabeledValue("Over budget (>16.6 ms)", metrics.framesOverBudget.toString())
            LabeledValue(
                "Decode wall time",
                "%.2f ms/frame".format(metrics.decodeNanosAvg / 1_000_000.0),
            )
            LabeledValue("Frames encoded", metrics.framesEncoded.toString())
            LabeledValue("Encoded bytes", formatBytes(metrics.encodedBytes))
            if (metrics.appliedBitrate > 0) {
                LabeledValue("Encoder bitrate", "%.2f Mbps".format(metrics.appliedBitrate / 1_000_000.0))
            }
        }
    }
}

/** The Component 3 payoff: what came out, how much space it saved. */
@Composable
private fun ResultCard(r: TranscodeResult) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Result", style = MaterialTheme.typography.titleMedium)
            LabeledValue("Saved", "${formatBytes(r.savedBytes)} (%.1f%%)".format(r.savedPercent))
            LabeledValue("Original size", formatBytes(r.inputBytes))
            LabeledValue("Compressed size", formatBytes(r.outputBytes))
            LabeledValue("Output codec", r.codecName.substringAfterLast('.'))
            LabeledValue("Audio", if (r.audioPassthrough) "copied from source" else "none")
            LabeledValue("Frames encoded", r.framesEncoded.toString())
            r.visualIntegrityPercent?.let {
                LabeledValue("Visual integrity (SSIM)", "%.1f%% identical".format(it))
            }
            Text(
                text = r.displayName,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        runCatching {
                            ctx.startActivity(
                                Intent(Intent.ACTION_VIEW).apply {
                                    setDataAndType(r.outputUri, "video/mp4")
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                },
                            )
                        }
                    },
                ) { Text("Open") }
                OutlinedButton(
                    onClick = {
                        runCatching {
                            ctx.startActivity(
                                Intent.createChooser(
                                    Intent(Intent.ACTION_SEND).apply {
                                        setType("video/mp4")
                                        putExtra(Intent.EXTRA_STREAM, r.outputUri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    },
                                    "Share compressed video",
                                ),
                            )
                        }
                    },
                ) { Text("Share") }
            }
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1 shl 20 -> "%.1f MB".format(bytes / 1024f / 1024f)
    bytes >= 1 shl 10 -> "%.0f KB".format(bytes / 1024f)
    else -> "$bytes B"
}

@Composable
private fun LabeledValue(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}
