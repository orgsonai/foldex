// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.filebrowser

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.zerotoship.foldex.R
import com.zerotoship.foldex.core.model.FileNode

/**
 * ZIP 圧縮ダイアログ。出力名 (拡張子省略可) + 任意のパスワード (空 = 無し / AES-256 で暗号化)。
 */
@Composable
fun ZipCompressDialog(
    targets: List<FileNode>,
    onDismiss: () -> Unit,
    onConfirm: (zipName: String, password: String?) -> Unit,
) {
    var zipName by remember {
        mutableStateOf(
            (targets.singleOrNull()?.name?.removeSuffix(".zip") ?: "archive") + ".zip",
        )
    }
    var encrypt by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(pluralStringResource(R.plurals.zip_compress_title, targets.size, targets.size)) },
        text = {
            Column {
                OutlinedTextField(
                    value = zipName,
                    onValueChange = { zipName = it },
                    label = { Text(stringResource(R.string.zip_file_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Row {
                    FilterChip(
                        selected = !encrypt,
                        onClick = { encrypt = false; password = "" },
                        label = { Text(stringResource(R.string.zip_no_encryption)) },
                    )
                    Spacer(Modifier.height(0.dp))
                    Spacer(Modifier.fillMaxWidth(0f))
                    FilterChip(
                        selected = encrypt,
                        onClick = { encrypt = true },
                        label = { Text(stringResource(R.string.zip_aes_encryption)) },
                    )
                }
                if (encrypt) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text(stringResource(R.string.zip_password)) },
                        singleLine = true,
                        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            IconButton(onClick = { showPassword = !showPassword }) {
                                Icon(
                                    if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = stringResource(if (showPassword) R.string.action_hide else R.string.action_show),
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        stringResource(R.string.zip_password_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(zipName.trim(), if (encrypt) password else null) },
                enabled = zipName.isNotBlank() && (!encrypt || password.isNotEmpty()),
            ) { Text(stringResource(R.string.action_compress)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/**
 * ZIP 解凍ダイアログ。最初は password なしで試して needsPassword=true になったら入力を求める。
 */
@Composable
fun ZipExtractDialog(
    request: ZipExtractRequest,
    onDismiss: () -> Unit,
    onConfirm: (password: String?) -> Unit,
) {
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.zip_extract_title)) },
        text = {
            Column {
                Text(stringResource(R.string.zip_extract_body, request.node.name))
                if (request.needsPassword) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        request.initialError ?: stringResource(R.string.zip_password_protected),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text(stringResource(R.string.zip_password)) },
                        singleLine = true,
                        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            IconButton(onClick = { showPassword = !showPassword }) {
                                Icon(
                                    if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = stringResource(if (showPassword) R.string.action_hide else R.string.action_show),
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(password.takeIf { it.isNotEmpty() }) },
                enabled = !request.needsPassword || password.isNotEmpty(),
            ) { Text(stringResource(R.string.action_extract)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
