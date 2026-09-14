// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.videoedit.panel

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.zerotoship.foldex.R
import com.zerotoship.foldex.ui.imageedit.formatBytes
import com.zerotoship.foldex.ui.videoedit.model.ExportEstimate
import com.zerotoship.foldex.ui.videoedit.model.ExportEstimateResult
import com.zerotoship.foldex.ui.videoedit.model.ExportPreset
import com.zerotoship.foldex.ui.videoedit.model.ExportResolution
import com.zerotoship.foldex.ui.videoedit.model.ExportSettings
import com.zerotoship.foldex.ui.videoedit.model.VideoCodecChoice
import com.zerotoship.foldex.ui.videoedit.model.VideoProject
import com.zerotoship.foldex.ui.videoedit.model.formatClock
import java.util.Locale

/**
 * 書き出しの設定。解像度・fps・コーデック・容量を選ぶと、その場で映像のビットレートと容量の見積もりを出す。
 * 試作版は設定の画面だけで、書き出しの処理はまだ行わない。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportSheet(
    settings: ExportSettings,
    project: VideoProject,
    onChange: (ExportSettings) -> Unit,
    onExport: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        ExportSettingsContent(settings = settings, project = project, onChange = onChange, onExport = onExport)
    }
}

@Composable
private fun ExportSettingsContent(
    settings: ExportSettings,
    project: VideoProject,
    onChange: (ExportSettings) -> Unit,
    onExport: () -> Unit,
) {
    val durationUs = project.durationUs
    val losslessAvailable = remember(project) { ExportEstimate.isLosslessCandidate(project) }
    val estimate = remember(settings, durationUs) { ExportEstimate.estimate(settings, durationUs) }
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            stringResource(R.string.video_edit_export),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.semantics { heading() },
        )
        ChipRow {
            ExportPreset.entries.forEach { preset ->
                FilterChip(
                    selected = settings.preset == preset,
                    onClick = { onChange(settings.copy(preset = preset)) },
                    enabled = preset != ExportPreset.LOSSLESS_CUT || losslessAvailable,
                    label = { Text(presetLabel(preset)) },
                )
            }
        }
        val encodes = settings.preset == ExportPreset.STANDARD ||
            settings.preset == ExportPreset.HIGH_QUALITY ||
            settings.preset == ExportPreset.TARGET_SIZE
        if (encodes) {
            SectionLabel(stringResource(R.string.video_edit_export_resolution))
            ChipRow {
                ExportResolution.entries.forEach { resolution ->
                    FilterChip(
                        selected = settings.resolution == resolution,
                        onClick = { onChange(settings.copy(resolution = resolution)) },
                        label = { Text(resolutionLabel(resolution)) },
                    )
                }
            }
            SectionLabel(stringResource(R.string.video_edit_export_fps))
            ChipRow {
                FPS_CHOICES.forEach { fps ->
                    FilterChip(
                        selected = settings.fps == fps,
                        onClick = { onChange(settings.copy(fps = fps)) },
                        label = { Text("$fps fps") },
                    )
                }
            }
            SectionLabel(stringResource(R.string.video_edit_export_codec))
            ChipRow {
                VideoCodecChoice.entries.forEach { codec ->
                    FilterChip(
                        selected = settings.codec == codec,
                        onClick = { onChange(settings.copy(codec = codec)) },
                        label = { Text(codecLabel(codec)) },
                    )
                }
            }
        }
        if (settings.preset == ExportPreset.TARGET_SIZE) {
            var input by remember { mutableStateOf(settings.targetMiB.toString()) }
            OutlinedTextField(
                value = input,
                onValueChange = { value ->
                    input = value.filter { it.isDigit() }.take(5)
                    input.toIntOrNull()?.takeIf { it > 0 }?.let { onChange(settings.copy(targetMiB = it)) }
                },
                label = { Text(stringResource(R.string.video_edit_export_target_mb)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        EstimateText(settings = settings, estimate = estimate, durationUs = durationUs, losslessAvailable = losslessAvailable, onChange = onChange)
        if (settings.preset != ExportPreset.AUDIO_ONLY) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.video_edit_export_location),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Switch(checked = settings.keepLocation, onCheckedChange = { onChange(settings.copy(keepLocation = it)) })
            }
        }
        Button(
            onClick = onExport,
            enabled = durationUs > 0L,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) { Text(stringResource(R.string.video_edit_export)) }
        Text(
            stringResource(R.string.video_edit_export_not_ready),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EstimateText(
    settings: ExportSettings,
    estimate: ExportEstimateResult?,
    durationUs: Long,
    losslessAvailable: Boolean,
    onChange: (ExportSettings) -> Unit,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val error = MaterialTheme.colorScheme.error
    when {
        settings.preset == ExportPreset.LOSSLESS_CUT -> Text(
            stringResource(if (losslessAvailable) R.string.video_edit_export_lossless_note else R.string.video_edit_export_lossless_unavailable),
            style = MaterialTheme.typography.bodyMedium,
            color = if (losslessAvailable) muted else error,
        )
        estimate == null -> Unit
        settings.preset == ExportPreset.AUDIO_ONLY -> Text(
            stringResource(R.string.video_edit_export_audio_estimate, formatNumber(estimate.audioKbps), formatBytes(estimate.estimatedBytes)),
            style = MaterialTheme.typography.bodyMedium,
            color = muted,
        )
        else -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(
                    R.string.video_edit_export_estimate,
                    formatClock(durationUs),
                    formatNumber(estimate.videoKbps),
                    formatNumber(estimate.audioKbps),
                    formatBytes(estimate.estimatedBytes),
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = muted,
            )
            if (estimate.belowMinimum) {
                val suggestion = estimate.suggestedResolution
                if (suggestion == null) {
                    Text(stringResource(R.string.video_edit_export_too_small), style = MaterialTheme.typography.bodyMedium, color = error)
                } else {
                    Text(
                        stringResource(R.string.video_edit_export_below_minimum, resolutionLabel(settings.resolution)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = error,
                    )
                    TextButton(onClick = { onChange(settings.copy(resolution = suggestion)) }) {
                        Text(stringResource(R.string.video_edit_export_use_resolution, resolutionLabel(suggestion)))
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun ChipRow(content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        content = content,
    )
}

@Composable
private fun presetLabel(preset: ExportPreset): String = stringResource(
    when (preset) {
        ExportPreset.STANDARD -> R.string.video_edit_preset_standard
        ExportPreset.HIGH_QUALITY -> R.string.video_edit_preset_high
        ExportPreset.TARGET_SIZE -> R.string.video_edit_preset_target
        ExportPreset.AUDIO_ONLY -> R.string.video_edit_preset_audio
        ExportPreset.LOSSLESS_CUT -> R.string.video_edit_preset_lossless
    },
)

private fun resolutionLabel(resolution: ExportResolution): String = when (resolution) {
    ExportResolution.P720 -> "720p"
    ExportResolution.P1080 -> "1080p"
    ExportResolution.P2160 -> "4K"
}

private fun codecLabel(codec: VideoCodecChoice): String = when (codec) {
    VideoCodecChoice.H264 -> "H.264"
    VideoCodecChoice.HEVC -> "HEVC"
}

private fun formatNumber(value: Int): String = String.format(Locale.getDefault(), "%,d", value)

private val FPS_CHOICES = listOf(24, 30, 60)

@Preview(name = "Target size", showBackground = true, widthDp = 360, heightDp = 720, locale = "ja")
@Composable
private fun ExportSettingsPreview() {
    MaterialTheme {
        ExportSettingsContent(
            settings = ExportSettings(preset = ExportPreset.TARGET_SIZE, targetMiB = 50, audioKbps = 128),
            project = VideoProject(),
            onChange = {},
            onExport = {},
        )
    }
}
