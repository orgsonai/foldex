// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.videoedit.panel

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.zerotoship.foldex.R
import com.zerotoship.foldex.ui.imageedit.formatBytes
import com.zerotoship.foldex.ui.videoedit.engine.ExportProgress
import com.zerotoship.foldex.ui.videoedit.engine.ExportStage
import com.zerotoship.foldex.ui.videoedit.engine.VideoExportOutcome
import com.zerotoship.foldex.ui.videoedit.model.US_PER_SECOND
import com.zerotoship.foldex.ui.videoedit.model.VideoExportError
import com.zerotoship.foldex.ui.videoedit.model.VideoExportFailure
import com.zerotoship.foldex.ui.videoedit.plan.ExportCheck
import com.zerotoship.foldex.ui.videoedit.plan.PlanNotice
import com.zerotoship.foldex.ui.videoedit.plan.UnmetCondition
import java.util.Locale

/** 書き出し中。戻る操作や外側のタップでは閉じず、取消はボタンだけで行う (押し間違いで数分の処理を失わないため)。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportProgressDialog(progress: ExportProgress, onCancel: () -> Unit) {
    BasicAlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
    ) {
        DialogSurface {
            Title(stringResource(R.string.video_edit_export_running))
            Text(
                stageText(progress),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            val percent = progress.percent
            if (progress.stage == ExportStage.ENCODING && percent != null) {
                LinearProgressIndicator(progress = { percent / 100f }, modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Text(
                stringResource(R.string.video_edit_export_background_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onCancel, modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp)) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    }
}

/** 書き出しの結果。保存したが指定どおりにならなかった点は、成功と同じ見た目にせず並べて見せる。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportResultDialog(outcome: VideoExportOutcome, onDismiss: () -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    BasicAlertDialog(onDismissRequest = onDismiss) {
        DialogSurface {
            when (outcome) {
                is VideoExportOutcome.Saved -> {
                    Title(stringResource(R.string.video_edit_export_saved))
                    Text(
                        stringResource(R.string.video_edit_export_saved_detail, outcome.displayName, formatBytes(outcome.sizeBytes)),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    outcome.path?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = muted) }
                    if (outcome.unmet.isNotEmpty()) {
                        Text(
                            stringResource(R.string.video_edit_export_unmet_title),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                        outcome.unmet.forEach { Text(unmetText(it), style = MaterialTheme.typography.bodyMedium) }
                    }
                    outcome.notices.forEach { Text(noticeText(it), style = MaterialTheme.typography.bodySmall, color = muted) }
                }
                is VideoExportOutcome.Failed -> {
                    Title(stringResource(R.string.video_edit_export_failed))
                    Text(exportErrorMessage(outcome.error), style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.video_edit_export_failed_safe), style = MaterialTheme.typography.bodySmall, color = muted)
                    outcome.error.code?.let {
                        Text(stringResource(R.string.video_edit_export_error_code, it), style = MaterialTheme.typography.bodySmall, color = muted)
                    }
                }
            }
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp)) {
                Text(stringResource(R.string.action_close))
            }
        }
    }
}

@Composable
internal fun exportErrorMessage(error: VideoExportError): String {
    val detail = error.detail
    return when (error.reason) {
        VideoExportFailure.NOTHING_TO_EXPORT -> stringResource(R.string.video_edit_fail_nothing)
        VideoExportFailure.NO_VIDEO -> stringResource(R.string.video_edit_fail_no_video)
        VideoExportFailure.NO_AUDIO -> stringResource(R.string.video_edit_fail_no_audio)
        VideoExportFailure.TARGET_TOO_SMALL -> stringResource(R.string.video_edit_fail_target_too_small)
        VideoExportFailure.UNSUPPORTED_PRESET -> stringResource(R.string.video_edit_fail_unsupported_preset)
        VideoExportFailure.SOURCE_MISSING ->
            if (detail != null) stringResource(R.string.video_edit_fail_source_missing, detail) else stringResource(R.string.video_edit_fail_read)
        VideoExportFailure.SOURCE_CHANGED ->
            if (detail != null) stringResource(R.string.video_edit_fail_source_changed, detail) else stringResource(R.string.video_edit_fail_read)
        VideoExportFailure.NOT_ENOUGH_SPACE -> stringResource(R.string.video_edit_fail_space, detail.orEmpty())
        VideoExportFailure.UNSUPPORTED_FORMAT -> stringResource(R.string.video_edit_fail_unsupported_format)
        VideoExportFailure.DECODER_FAILED -> stringResource(R.string.video_edit_fail_decoder)
        VideoExportFailure.ENCODER_UNSUPPORTED -> stringResource(R.string.video_edit_fail_encoder_unsupported)
        VideoExportFailure.ENCODER_FAILED -> stringResource(R.string.video_edit_fail_encoder)
        VideoExportFailure.VIDEO_PROCESSING_FAILED -> stringResource(R.string.video_edit_fail_video_processing)
        VideoExportFailure.AUDIO_PROCESSING_FAILED -> stringResource(R.string.video_edit_fail_audio_processing)
        VideoExportFailure.READ_FAILED -> stringResource(R.string.video_edit_fail_read)
        VideoExportFailure.WRITE_FAILED -> stringResource(R.string.video_edit_fail_write)
        VideoExportFailure.INVALID_OUTPUT -> stringResource(R.string.video_edit_fail_invalid_output)
        VideoExportFailure.DESTINATION_EXISTS -> stringResource(R.string.video_edit_fail_destination_exists)
        VideoExportFailure.TIME_LIMIT -> stringResource(R.string.video_edit_fail_time_limit)
        VideoExportFailure.BUSY -> stringResource(R.string.video_edit_fail_busy)
        VideoExportFailure.UNKNOWN -> stringResource(R.string.video_edit_fail_unknown)
    }
}

@Composable
internal fun noticeText(notice: PlanNotice): String = when (notice) {
    PlanNotice.BACKGROUND_BLACK -> stringResource(R.string.video_edit_export_notice_background)
    PlanNotice.TRIMMED_AFTER_VIDEO -> stringResource(R.string.video_edit_export_notice_trimmed)
}

@Composable
private fun stageText(progress: ExportProgress): String {
    val percent = progress.percent
    return when (progress.stage) {
        ExportStage.CHECKING -> stringResource(R.string.video_edit_export_stage_checking)
        ExportStage.ENCODING -> when {
            progress.attempt > 1 -> stringResource(R.string.video_edit_export_stage_retry, progress.attempt)
            percent != null -> stringResource(R.string.video_edit_export_stage_encoding, percent)
            else -> stringResource(R.string.video_edit_export_stage_encoding_unknown)
        }
        ExportStage.VERIFYING -> stringResource(R.string.video_edit_export_stage_verifying)
        ExportStage.SAVING -> stringResource(R.string.video_edit_export_stage_saving)
    }
}

@Composable
private fun unmetText(condition: UnmetCondition): String = when (condition) {
    is UnmetCondition.Size -> stringResource(
        R.string.video_edit_export_unmet_size,
        "${condition.requestedWidth}×${condition.requestedHeight}",
        "${condition.actualWidth}×${condition.actualHeight}",
    )
    is UnmetCondition.Codec -> stringResource(
        R.string.video_edit_export_unmet_codec,
        codecName(condition.requestedMimeType),
        codecName(condition.actualMimeType),
    )
    is UnmetCondition.Duration -> stringResource(
        R.string.video_edit_export_unmet_duration,
        seconds(condition.expectedUs),
        seconds(condition.actualUs),
    )
    is UnmetCondition.TargetSize -> stringResource(
        R.string.video_edit_export_unmet_target,
        formatBytes(condition.targetBytes),
        formatBytes(condition.actualBytes),
    )
}

private fun codecName(mimeType: String): String = when (mimeType) {
    ExportCheck.MIME_H264 -> "H.264"
    ExportCheck.MIME_HEVC -> "HEVC"
    else -> mimeType
}

/** 長さのずれは 1 コマ程度でも見えるよう、小数 2 桁で出す。 */
private fun seconds(timeUs: Long): String = String.format(Locale.US, "%.2f", timeUs.toDouble() / US_PER_SECOND)

@Composable
private fun DialogSurface(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
    }
}

@Composable
private fun Title(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.headlineSmall,
        modifier = Modifier.padding(bottom = 8.dp).semantics { heading() },
    )
}

@Preview(name = "Export progress", showBackground = true, widthDp = 360, locale = "ja")
@Composable
private fun ExportProgressDialogPreview() {
    MaterialTheme { ExportProgressDialog(ExportProgress(ExportStage.ENCODING, percent = 42), onCancel = {}) }
}

@Preview(name = "Export result unmet", showBackground = true, widthDp = 360, heightDp = 640, fontScale = 2f, locale = "ja")
@Composable
private fun ExportResultDialogPreview() {
    MaterialTheme {
        ExportResultDialog(
            VideoExportOutcome.Saved(
                displayName = "trip_edited.mp4",
                path = "/storage/emulated/0/DCIM/Camera/trip_edited.mp4",
                sizeBytes = 52_000_000,
                unmet = listOf(UnmetCondition.Codec(ExportCheck.MIME_HEVC, ExportCheck.MIME_H264)),
                notices = setOf(PlanNotice.BACKGROUND_BLACK),
            ),
            onDismiss = {},
        )
    }
}
