package com.zerotoship.foldex.ui.filebrowser

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 他アプリから ACTION_SEND で渡されたファイルを「現在のフォルダに保存」できる旨を案内するバナー。
 * 受信時は [FileBrowserViewModel.receiveSharedFiles] が積み、ユーザーが保存先フォルダを開いた状態で
 * 「ここに保存」を押すと [FileBrowserViewModel.saveSharedFilesHere] が実行される。
 */
@Composable
fun ShareReceiveBanner(
    count: Int,
    onSaveHere: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.tertiaryContainer)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.Share,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onTertiaryContainer,
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "$count 件の共有を受信中",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Text(
                "保存先フォルダを開いて「ここに保存」",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = onCancel) { Text("キャンセル") }
            Button(onClick = onSaveHere) { Text("ここに保存") }
        }
    }
}
