// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.settings

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/**
 * ライセンス画面 (設定 → 「ライセンス」をタップで開く)。
 *
 * 本アプリ本体 (MIT) と、同梱・依存している OSS の一覧を表示する。
 * ライセンス全文は `assets/licenses/<spdxId>.txt` から読み込み、タップでダイアログ表示する。
 * 全文 txt を持たないもの (public domain 等) は補足テキストだけ表示する。
 */
private data class LicenseEntry(
    val name: String,
    /** SPDX ID。null なら「タップで全文」を出さず [note] のみ表示する。 */
    val spdxId: String?,
    val note: String,
)

// 本アプリ本体。依存ライブラリはすべて Apache-2.0 (MIT 互換) / xz は public domain。
private val OWN = LicenseEntry(
    name = "Foldex (本アプリ本体)",
    spdxId = "MIT",
    note = "MIT License — Copyright (c) 2026 Zero to Ship",
)

private val DEPENDENCIES = listOf(
    LicenseEntry("smbj", "Apache-2.0", "SMB クライアント — Apache License 2.0"),
    LicenseEntry("Apache Commons Net", "Apache-2.0", "FTP クライアント — Apache License 2.0"),
    LicenseEntry("Apache MINA SSHD", "Apache-2.0", "SFTP / SSH — Apache License 2.0"),
    LicenseEntry("Apache FtpServer", "Apache-2.0", "FTP サーバ — Apache License 2.0"),
    LicenseEntry("XZ for Java", null, "xz 解凍 — public domain"),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicensesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var openSpdxId by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("ライセンス") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            SectionHeader("このアプリ")
            LicenseRow(OWN, onClick = { openSpdxId = OWN.spdxId })

            HorizontalDivider()
            SectionHeader("依存ライブラリ")
            DEPENDENCIES.forEach { entry ->
                LicenseRow(entry, onClick = entry.spdxId?.let { id -> { openSpdxId = id } })
            }
        }
    }

    openSpdxId?.let { id ->
        LicenseFullTextDialog(
            spdxId = id,
            text = remember(id) { readLicenseText(context, id) },
            onDismiss = { openSpdxId = null },
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun LicenseRow(entry: LicenseEntry, onClick: (() -> Unit)?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(entry.name, style = MaterialTheme.typography.bodyLarge)
        Text(
            entry.note,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LicenseFullTextDialog(spdxId: String, text: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(spdxId) },
        text = {
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("閉じる") }
        },
    )
}

/** `assets/licenses/<spdxId>.txt` を読む。読めなければ簡単なフォールバック文字列を返す。 */
private fun readLicenseText(context: Context, spdxId: String): String =
    runCatching {
        context.assets.open("licenses/$spdxId.txt").bufferedReader().use { it.readText() }
    }.getOrElse { "$spdxId ライセンスの全文を読み込めませんでした。" }
