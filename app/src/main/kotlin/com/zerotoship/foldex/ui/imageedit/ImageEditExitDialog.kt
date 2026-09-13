// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.imageedit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.zerotoship.foldex.R

/** 長い選択肢を同じ幅で並べ、狭い画面や大きな文字でも全操作へ到達できるようにする。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ImageEditExitDialog(
    onKeepDraft: () -> Unit,
    onSaveImage: () -> Unit,
    onDiscardDraft: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // AlertDialog の二つのボタン枠に分けると、残り幅で長いラベルが折り返されて配置が崩れる。
    BasicAlertDialog(onDismissRequest = onDismiss, modifier = modifier) {
        Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    stringResource(R.string.image_draft_close_title),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(bottom = 8.dp).semantics { heading() },
                )
                Text(
                    stringResource(R.string.image_draft_close_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                val actionModifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                Button(onClick = onKeepDraft, modifier = actionModifier, shape = MaterialTheme.shapes.small) {
                    Text(stringResource(R.string.image_draft_keep), Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                }
                OutlinedButton(onClick = onSaveImage, modifier = actionModifier, shape = MaterialTheme.shapes.small) {
                    Text(stringResource(R.string.image_save_action), Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                }
                TextButton(
                    onClick = onDiscardDraft,
                    modifier = actionModifier,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Text(stringResource(R.string.image_draft_discard), Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                }
                HorizontalDivider()
                TextButton(onClick = onDismiss, modifier = actionModifier) {
                    Text(stringResource(R.string.image_export_back_to_edit), Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                }
            }
        }
    }
}

@Preview(name = "Japanese", showBackground = true, widthDp = 360, heightDp = 640, locale = "ja")
@Preview(name = "Japanese large text", showBackground = true, widthDp = 320, heightDp = 480, fontScale = 2f, locale = "ja")
@Preview(name = "English landscape", showBackground = true, widthDp = 640, heightDp = 320, fontScale = 2f, locale = "en")
@Composable
private fun ImageEditExitDialogPreview() {
    MaterialTheme { ImageEditExitDialog({}, {}, {}, {}) }
}
