package com.zerotoship.foldex.ui.filebrowser

import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import com.zerotoship.foldex.core.model.FileNode

@Composable
fun RenameDialog(
    node: FileNode,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val baseName = node.name.substringBeforeLast(".")
    val ext = node.name.substringAfterLast(".", "")
    val hasExt = ext.isNotEmpty() && ext != node.name

    var text by remember {
        mutableStateOf(
            TextFieldValue(
                text = node.name,
                selection = TextRange(0, if (hasExt) baseName.length else node.name.length),
            )
        )
    }
    val focusRequester = remember { FocusRequester() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("名前を変更") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("名前") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onConfirm(text.text) }),
                modifier = androidx.compose.ui.Modifier.focusRequester(focusRequester),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(text.text) },
                enabled = text.text.isNotBlank() && text.text != node.name,
            ) { Text("変更") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("キャンセル") }
        },
    )

    LaunchedEffect(Unit) { focusRequester.requestFocus() }
}
