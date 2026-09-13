// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.imageedit

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.zerotoship.foldex.ui.imageedit.model.EditDocument
import com.zerotoship.foldex.ui.imageedit.model.EditSize
import com.zerotoship.foldex.ui.imageedit.model.ImageFormat
import com.zerotoship.foldex.ui.imageedit.model.MetadataPolicy
import com.zerotoship.foldex.ui.imageedit.model.SaveNaming
import com.zerotoship.foldex.ui.imageedit.model.Transparency
import com.zerotoship.foldex.R
import kotlin.math.roundToInt

/** 背景・撮影情報・容量上限はドキュメントへ保持し、保存の再試行でも同じ設定を使う。 */
@Composable
fun SaveDialog(
    state: ImageEditUiState,
    onFormatChange: (ImageFormat) -> Unit,
    onQualityChange: (Int) -> Unit,
    onBackgroundChange: (Int) -> Unit,
    onMetadataChange: (MetadataPolicy) -> Unit,
    onTargetChange: (Int?) -> Unit,
    onDismiss: () -> Unit,
    onSave: (SaveRequest) -> Unit,
) {
    val doc = state.document ?: return
    val format = doc.output.format
    var quality by remember(doc.output.quality) { mutableFloatStateOf(doc.output.quality.toFloat()) }
    var backgroundText by remember(doc.output.jpegBackground) {
        mutableStateOf("%06X".format(doc.output.jpegBackground and 0xffffff))
    }
    var targetText by remember { mutableStateOf((doc.output.targetBytes?.div(1024) ?: 500).toString()) }
    val useTarget = doc.output.targetBytes != null
    val target = targetText.toIntOrNull()?.takeIf { it in 1..999999 }?.times(1024)
    val background = backgroundText.takeIf { it.length == 6 }?.toIntOrNull(16)?.or(0xff shl 24)
    val showBackground = format == ImageFormat.JPEG && state.previewTransparency != Transparency.OPAQUE
    val canSave = !state.saving && (!useTarget || target != null) && (!showBackground || background != null)
    val canOverwrite = state.canOverwrite && SaveNaming.canOverwrite(state.originalName, format)
    val submit: (Boolean, Boolean) -> Unit = { overwrite, chooseLocation ->
        if (canSave) {
            if (format.hasQuality) onQualityChange(quality.roundToInt())
            if (showBackground && background != null) onBackgroundChange(background)
            onTargetChange(if (useTarget) target else null)
            onSave(SaveRequest(overwrite = overwrite, chooseLocation = chooseLocation))
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.action_save)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("${doc.outputSize.width} × ${doc.outputSize.height}", style = MaterialTheme.typography.bodyMedium)
                if (state.estimateMemoryLimited) {
                    Text(stringResource(R.string.image_memory_estimate_limited), style = MaterialTheme.typography.bodySmall)
                }
                if (state.estimatedBytes != null && !useTarget) {
                    Text(stringResource(R.string.image_current_size, formatBytes(state.estimatedBytes.toLong())),
                        style = MaterialTheme.typography.labelSmall)
                }
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.image_format), style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ImageFormat.entries.forEach { item ->
                        FilterChip(selected = format == item, onClick = { onFormatChange(item) }, label = { Text(item.displayName) })
                    }
                }
                if (showBackground) {
                    Text(stringResource(if (state.previewTransparency == Transparency.HAS_ALPHA)
                        R.string.image_jpeg_background_help else R.string.image_jpeg_background_unknown),
                        style = MaterialTheme.typography.bodySmall)
                    Row {
                        listOf(-1 to R.string.image_background_white, 0xff000000.toInt() to R.string.image_background_black).forEach { (color, label) ->
                            TextButton(onClick = {
                                backgroundText = "%06X".format(color and 0xffffff)
                                onBackgroundChange(color)
                            }) { Text(stringResource(label)) }
                        }
                    }
                    OutlinedTextField(
                        value = backgroundText,
                        onValueChange = { text ->
                            backgroundText = text.filter { it in "0123456789abcdefABCDEF" }.take(6)
                            if (backgroundText.length == 6) onBackgroundChange(backgroundText.toInt(16) or (0xff shl 24))
                        },
                        label = { Text(stringResource(R.string.image_background_hex)) },
                        singleLine = true, isError = background == null,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    val preview = remember(state.preview) { state.preview?.asImageBitmap() }
                    if (preview != null) {
                        Spacer(Modifier.height(8.dp))
                        Image(preview, contentDescription = stringResource(R.string.image_output_preview),
                            modifier = Modifier.fillMaxWidth().height(160.dp)
                                .background(Color(background ?: doc.output.jpegBackground)))
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.image_standard_color_output), style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = useTarget, onCheckedChange = { enabled ->
                        if (enabled && target == null) targetText = "500"
                        onTargetChange(if (enabled) target ?: 500 * 1024 else null)
                    })
                    Text(stringResource(R.string.image_fit_target_size), style = MaterialTheme.typography.bodyMedium)
                }
                if (useTarget) {
                    OutlinedTextField(
                        value = targetText,
                        onValueChange = { input ->
                            targetText = input.filter { it in '0'..'9' }.take(6)
                            targetText.toIntOrNull()?.takeIf { it > 0 }?.let { onTargetChange(it * 1024) }
                        },
                        label = { Text(stringResource(R.string.image_target_kb)) },
                        singleLine = true, isError = target == null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(stringResource(if (format.hasQuality) R.string.image_target_size_help else R.string.image_png_target_help),
                        style = MaterialTheme.typography.labelSmall)
                } else if (format.hasQuality) {
                    Text(stringResource(R.string.image_quality, quality.roundToInt()), style = MaterialTheme.typography.labelMedium)
                    Slider(value = quality, onValueChange = { quality = it },
                        onValueChangeFinished = { onQualityChange(quality.roundToInt()) }, valueRange = 30f..100f)
                }
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.image_metadata_title), style = MaterialTheme.typography.labelMedium)
                MetadataPolicy.entries.forEach { policy ->
                    val label = when (policy) {
                        MetadataPolicy.KEEP_CAPTURE -> R.string.image_metadata_keep
                        MetadataPolicy.REMOVE_LOCATION -> R.string.image_metadata_no_location
                        MetadataPolicy.SHARE -> R.string.image_metadata_share
                    }
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(
                        selected = doc.output.metadata == policy, role = Role.RadioButton,
                        onClick = { onMetadataChange(policy) },
                    ), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = doc.output.metadata == policy, onClick = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(label), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (!canOverwrite) Text(stringResource(R.string.image_export_keep_original), style = MaterialTheme.typography.bodySmall)
                TextButton(enabled = canSave, onClick = { submit(false, true) }) {
                    Text(stringResource(R.string.image_export_choose_location))
                }
            }
        },
        confirmButton = {
            TextButton(enabled = canSave, onClick = { submit(false, false) }) { Text(stringResource(R.string.image_save_as)) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
                if (canOverwrite) TextButton(enabled = canSave, onClick = { submit(true, false) }) {
                    Text(stringResource(R.string.image_overwrite_save))
                }
            }
        },
    )
}

@Preview(showBackground = true)
@Composable
private fun SaveDialogPreview() {
    MaterialTheme {
        SaveDialog(
            state = ImageEditUiState(document = EditDocument.ofSingleImage("photo", "photo.png", "photo", EditSize(800, 600), ImageFormat.PNG)),
            onFormatChange = {}, onQualityChange = {}, onBackgroundChange = {}, onMetadataChange = {},
            onTargetChange = {}, onDismiss = {}, onSave = {},
        )
    }
}
