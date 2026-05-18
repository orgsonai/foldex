package com.zerotoship.foldex.ui.common

import com.zerotoship.foldex.ui.filebrowser.ClipboardOperation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 「コピー / 切り取り」のクリップボード状態を画面横断で共有する。
 *
 * ファイルブラウザだけでなく HOME の「画像」「動画」コレクションからも長押しで
 * Copy/Cut を積めるように、Hilt @Singleton で保持してどの ViewModel からでも
 * 同じインスタンスを観測/書き換えできるようにする。
 *
 * 中身は FileBrowser 側の [ClipboardOperation] (List<FileNode>) をそのまま使う。
 * メディア由来のアイテムは `FileUri.Local(filePath)` で FileNode を組み立てて
 * 投げ込めば、貼り付け先のストレージ実装が同一インターフェイスで処理してくれる。
 */
@Singleton
class SharedClipboard @Inject constructor() {
    private val _state = MutableStateFlow<ClipboardOperation?>(null)
    val state: StateFlow<ClipboardOperation?> = _state.asStateFlow()

    fun set(op: ClipboardOperation?) { _state.value = op }
    fun clear() { _state.value = null }
}
