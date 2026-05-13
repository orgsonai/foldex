package com.zerotoship.foldex.ui.filebrowser

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

enum class CreateKind { FOLDER, FILE }

/**
 * フォルダ / ファイルの新規作成ダイアログ。タブで種別を切り替える。
 * 作成成功時は ViewModel が即ファイルを開く (FILE のとき) など。
 */
@Composable
fun CreateDialog(
    initialKind: CreateKind,
    onConfirm: (name: String, kind: CreateKind) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var kind by remember(initialKind) { mutableStateOf(initialKind) }
    val focusRequester = remember { FocusRequester() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (kind == CreateKind.FOLDER) "新しいフォルダ" else "新しいファイル") },
        text = {
            Column {
                SingleChoiceSegmentedButtonRow {
                    SegmentedButton(
                        selected = kind == CreateKind.FOLDER,
                        onClick = { kind = CreateKind.FOLDER },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    ) { Text("フォルダ") }
                    SegmentedButton(
                        selected = kind == CreateKind.FILE,
                        onClick = { kind = CreateKind.FILE },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    ) { Text("ファイル") }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(if (kind == CreateKind.FOLDER) "フォルダ名" else "ファイル名 (拡張子付き)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (name.isNotBlank()) onConfirm(name, kind) }),
                    modifier = Modifier.focusRequester(focusRequester),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name, kind) },
                enabled = name.isNotBlank(),
            ) { Text("作成") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("キャンセル") }
        },
    )

    LaunchedEffect(Unit) { focusRequester.requestFocus() }
}
