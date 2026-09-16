package com.eqo.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.eqo.feedback.UserAction
import com.eqo.pipeline.PipelineMetrics
import com.eqo.pipeline.TranscodeResult
import com.eqo.pipeline.VideoMetadata
import com.eqo.pipeline.VideoPipelineStatus
import com.eqo.reclaim.VideoCandidate
import com.eqo.thermal.ThermalMitigation

/**
 * ## eqo Product Interface
 *
 * Provides end-to-end video transcoding controls, live telemetry, Media3 playback,
 * A/B visual comparison against the original, and direct integration with Component 4
 * feedback actions.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PipelineScreen(vm: PipelineViewModel = viewModel()) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(vm::onVideoPicked)
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> vm.onScanPermissionResult(granted) }

    var selectedComparison by remember { mutableStateOf(ComparisonMode.COMPRESSED) }
    var sharpnessBoost by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("eqo", fontWeight = FontWeight.Bold)
                        Spacer(Modifier.padding(horizontal = 4.dp))
                        Text(
                            "Intelligent Video Compressor",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Video Picker Button
            Button(
                onClick = { picker.launch(arrayOf("video/*")) },
                enabled = !state.preparing && state.status != VideoPipelineStatus.DECODING,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (state.uri == null) "Select Video to Compress" else "Pick Another Video")
            }

            // Status & Thermal Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusChip(state.status)
                state.thermalMitigation?.let { ThermalChip(it) }
            }

            if (state.preparing) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }

            // Source Metadata Card
            state.metadata?.let { MetadataCard(it) }

            // Live Transcoding Metrics
            if (state.status == VideoPipelineStatus.DECODING || state.status == VideoPipelineStatus.PAUSED) {
                MetricsCard(state.metrics)
            }

            // Completed Result Card & Visual Verification Player
            state.result?.let { result ->
                ResultHeroCard(result = result, category = state.category)

                // Visual Verification Player & A/B Inspection Toggle
                val activePlayUri = if (selectedComparison == ComparisonMode.ORIGINAL) {
                    state.uri ?: result.outputUri
                } else {
                    result.outputUri
                }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "Visual Integrity Inspection",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )

                        // A/B Selector Chips & Dynamic Sharpness Boost
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            FilterChip(
                                selected = selectedComparison == ComparisonMode.COMPRESSED,
                                onClick = { selectedComparison = ComparisonMode.COMPRESSED },
                                label = { Text("eqo (${formatBytes(result.outputBytes)})") },
                            )
                            FilterChip(
                                selected = selectedComparison == ComparisonMode.ORIGINAL,
                                onClick = { selectedComparison = ComparisonMode.ORIGINAL },
                                label = { Text("Original (${formatBytes(result.inputBytes)})") },
                            )
                            FilterChip(
                                selected = sharpnessBoost,
                                onClick = { sharpnessBoost = !sharpnessBoost },
                                label = { Text(if (sharpnessBoost) "✨ Sharp" else "✨ Normal") },
                            )
                        }

                        // Media3 ExoPlayer Surface with real-time GL Sharpening Shader
                        val aspect = if (state.metadata != null && state.metadata!!.width > 0 && state.metadata!!.height > 0) {
                            state.metadata!!.width.toFloat() / state.metadata!!.height.toFloat()
                        } else {
                            16f / 9f
                        }
                        VideoPlayerView(
                            uri = activePlayUri,
                            aspectRatio = aspect.coerceIn(0.5f, 2.2f),
                            sharpness = if (sharpnessBoost) 0.35f else 0.0f,
                        )

                        // Component 4 Feedback Action Bar
                        Text(
                            "Feedback & Actions (Component 4)",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Button(
                                onClick = {
                                    vm.onUserAction(UserAction.SAVED_AND_SHARED) { shareIntent ->
                                        context.startActivity(shareIntent)
                                    }
                                },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text("Save & Share")
                            }

                            OutlinedButton(
                                onClick = {
                                    vm.onUserAction(UserAction.KEPT_OFFLINE)
                                },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text("Keep Offline")
                            }

                            Button(
                                onClick = {
                                    vm.onUserAction(UserAction.PROMPTLY_DELETED)
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                            ) {
                                Text("Delete")
                            }
                        }

                        // Feature 3 — atomic storage reclamation. Only meaningful
                        // once a result exists; the original is moved to the
                        // private rollback cache first (reversible), then the
                        // gallery row is retired best-effort.
                        OutlinedButton(
                            onClick = vm::reclaimSpace,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Reclaim Space (move original to rollback)")
                        }
                        state.reclaimNote?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            // Primary Pipeline Action Controls
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.status == VideoPipelineStatus.IDLE && state.uri != null) {
                    Button(
                        onClick = vm::start,
                        enabled = !state.preparing,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Start Intelligent Compression")
                    }
                }

                when (state.status) {
                    VideoPipelineStatus.DECODING -> {
                        Button(onClick = vm::pause, modifier = Modifier.weight(1f)) { Text("Pause") }
                        OutlinedButton(onClick = vm::reset, modifier = Modifier.weight(1f)) { Text("Cancel") }
                    }
                    VideoPipelineStatus.PAUSED -> {
                        Button(onClick = vm::resume, modifier = Modifier.weight(1f)) { Text("Resume") }
                        OutlinedButton(onClick = vm::reset, modifier = Modifier.weight(1f)) { Text("Cancel") }
                    }
                    VideoPipelineStatus.COMPLETED, VideoPipelineStatus.ERROR -> {
                        OutlinedButton(onClick = vm::reset, modifier = Modifier.fillMaxWidth()) {
                            Text("Compress Another Video")
                        }
                    }
                    else -> Unit
                }
            }

            BatchAndReclaimSection(vm, state)

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

private enum class ComparisonMode {
    ORIGINAL, COMPRESSED
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
                VideoPipelineStatus.COMPLETED -> Color(0xFF4CAF50)
                VideoPipelineStatus.ERROR -> MaterialTheme.colorScheme.error
            }
            Text("●", color = color)
        },
    )
}

@Composable
private fun ThermalChip(m: ThermalMitigation) {
    val color = when (m.statusName) {
        "NORMAL" -> Color(0xFF4CAF50)
        "LIGHT" -> Color(0xFFFFB300)
        "MODERATE" -> Color(0xFFFF9800)
        else -> Color(0xFFF44336)
    }
    AssistChip(
        onClick = {},
        label = { Text("Thermal: ${m.statusName}") },
        leadingIcon = { Text("●", color = color) },
    )
}

@Composable
private fun ResultHeroCard(result: TranscodeResult, category: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "-%.1f%%".format(result.savedPercent),
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF2E7D32))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = result.visualIntegrityPercent?.let { "%.1f%% SSIM Identical".format(it) } ?: "Verified Decoded",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                    )
                }
            }

            Text(
                text = "${formatBytes(result.inputBytes)}  ➔  ${formatBytes(result.outputBytes)} (${formatBytes(result.savedBytes)} saved)",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Codec: ${result.codecName.substringAfterLast('.')}", style = MaterialTheme.typography.labelSmall)
                Text("Category: $category", style = MaterialTheme.typography.labelSmall)
                Text("Frames: ${result.framesEncoded}", style = MaterialTheme.typography.labelSmall)
                Text(
                    if (result.audioPassthrough) "Audio: Preserved" else "Audio: None",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

@Composable
private fun MetadataCard(md: VideoMetadata) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Source Video", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            LabeledValue("Resolution", md.resolution + rotationNote(md.rotationDegrees))
            LabeledValue("Frame rate", if (md.frameRate > 0) "${md.frameRate} fps" else "unknown")
            LabeledValue(
                "Bitrate",
                if (md.bitrate > 0) "%.2f Mbps".format(md.bitrate / 1_000_000.0) else "unknown",
            )
            LabeledValue("Codec", md.mime)
            LabeledValue(
                "Audio",
                if (md.hasAudio) {
                    val rate = if (md.audioSampleRate > 0) " @ ${md.audioSampleRate / 1000}kHz" else ""
                    val ch = if (md.audioChannels > 0) " (${md.audioChannels} ch)" else ""
                    "${md.audioMime?.substringAfterLast('/') ?: "audio"}$ch$rate"
                } else {
                    "None"
                },
            )
        }
    }
}

private fun rotationNote(degrees: Int): String =
    if (degrees % 360 != 0) " (rotate $degrees° for display)" else ""

@Composable
private fun MetricsCard(metrics: PipelineMetrics) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Pipeline Performance", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            LabeledValue("Frames rendered / processed", "${metrics.framesRendered} / ${metrics.framesProcessed}")
            LabeledValue("Frames dropped (pacing)", metrics.framesDropped.toString())
            LabeledValue(
                "Processing speed",
                "%.1f ms/frame (avg)".format(metrics.processNanosAvg / 1_000_000.0),
            )
            LabeledValue("Frames encoded", metrics.framesEncoded.toString())
            LabeledValue("Output written", formatBytes(metrics.encodedBytes))
            if (metrics.appliedBitrate > 0) {
                LabeledValue("Dynamic bitrate", "%.2f Mbps".format(metrics.appliedBitrate / 1_000_000.0))
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
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
    }
}

// =========================================================================
// Feature 2 & 3 — Batch Compression & Storage Reclaim UI
// =========================================================================

@Composable
private fun BatchAndReclaimSection(vm: PipelineViewModel, state: PipelineViewModel.UiState) {
    Spacer(
        Modifier
            .padding(vertical = 8.dp)
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant),
    )

    Text(
        "Batch Compression & Storage Reclaim",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
    )

    // --- Permission row ---
    if (!state.scanGranted) {
        Text(
            "eqo needs access to the device gallery to scan for compressible videos.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            onClick = { vm.refreshScanPermission(); /* first touch forces re-check; then launch */ },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Allow Device Video Access")
        }
    }

    // --- Scan + list ---
    if (state.scanGranted) {
        Button(
            onClick = vm::scanVideos,
            enabled = !state.scanning && !state.batchRunning,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (state.scanCandidates.isEmpty() && !state.scanning) "Scan Device Videos" else "Rescan")
        }

        if (state.scanning) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(
                "Scanning MediaStore…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (state.scanCandidates.isNotEmpty()) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(6.dp),
            ) {
                items(state.scanCandidates, key = { it.id }) { c ->
                    BatchCandidateRow(
                        candidate = c,
                        selected = c.id in state.selectedCandidateIds,
                        targetFraction = state.targetFraction,
                        onToggle = { vm.toggleCandidate(c.id) },
                    )
                }
            }

            val totalReclaimable = state.scanCandidates
                .filter { it.id in state.selectedCandidateIds }
                .sumOf { it.reclaimableBytes(state.targetFraction) }

            // --- Batch action footer ---
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${state.selectedCandidateIds.size} selected — ~${formatBytes(totalReclaimable)} reclaimable",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = vm::clearSelection) { Text("Clear") }
            }

            Button(
                onClick = vm::startBatch,
                enabled = state.selectedCandidateIds.isNotEmpty() && !state.batchRunning,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Compress ${state.selectedCandidateIds.size} Selected Videos")
            }
        }
    }

    // --- Active batch progress ---
    if (state.batchRunning) {
        LinearProgressIndicator(
            progress = { if (state.batchTotal > 0) state.batchIndex / state.batchTotal.toFloat() else 0f },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "Compressing ${state.batchIndex + 1} of ${state.batchTotal}: ${state.batchActiveVideo ?: "…"}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (state.batchResults.isNotEmpty()) {
        Text("Batch Results", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            state.batchResults.forEach { item ->
                val outcome = if (item.result != null) {
                    "${item.candidate.displayName} — saved %.1f%%".format(item.result.savedPercent)
                } else {
                    "${item.candidate.displayName} — FAILED: ${item.error}"
                }
                Text(
                    outcome,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (item.result != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }

    // --- Rollback cache status ---
    if (state.rollbackEntries.isNotEmpty() || state.rollbackBytes > 0) {
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Rollback cache: ${formatBytes(state.rollbackBytes)} (${state.rollbackEntries.size})",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = vm::purgeAllRollback) { Text("Purge All") }
        }
    }
}

@Composable
private fun BatchCandidateRow(
    candidate: VideoCandidate,
    selected: Boolean,
    targetFraction: Float,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = selected, onCheckedChange = { onToggle() })
        Column(Modifier.weight(1f)) {
            Text(
                candidate.displayName,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(candidate.resolution, "${candidate.durationSeconds}s", candidate.mimeType?.substringAfterLast('/'))
                    .joinToString(" • "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        Text(
            "~${formatBytes(candidate.reclaimableBytes(targetFraction))}",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.tertiary,
        )
    }
}
