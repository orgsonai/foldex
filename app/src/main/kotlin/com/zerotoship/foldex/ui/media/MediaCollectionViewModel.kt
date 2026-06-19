package com.zerotoship.foldex.ui.media

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zerotoship.foldex.core.model.FileNode
import com.zerotoship.foldex.core.model.FileUri
import com.zerotoship.foldex.core.model.NodeType
import com.zerotoship.foldex.core.model.Permissions
import com.zerotoship.foldex.ui.common.SharedClipboard
import com.zerotoship.foldex.ui.filebrowser.ClipboardOperation
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import kotlin.time.Instant

/** HOME の「画像」/「動画」タイルから開かれる横断ビューア用 ViewModel。 */
@HiltViewModel
class MediaCollectionViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    savedStateHandle: SavedStateHandle,
    private val sharedClipboard: SharedClipboard,
) : ViewModel() {

    val kind: MediaKind = (savedStateHandle.get<String>(ARG_KIND) ?: "image").let {
        if (it == "video") MediaKind.VIDEO else MediaKind.IMAGE
    }

    private val _state = MutableStateFlow(MediaCollectionState(kind = kind))
    val state: StateFlow<MediaCollectionState> = _state.asStateFlow()

    fun setPermissionGranted(granted: Boolean) {
        _state.value = _state.value.copy(hasPermission = granted)
        if (granted) reload()
    }

    fun reload() {
        _state.value = _state.value.copy(isLoading = true, error = null)
        viewModelScope.launch {
            val items = withContext(Dispatchers.IO) { runCatching { query() }.getOrElse { emptyList() } }
            val folders = computeFolders(items)
            _state.value = _state.value.copy(items = items, folders = folders, isLoading = false)
        }
    }

    /** 指定パス配下に「入る」(= フォルダごとビューから一覧へ)。`null` で全フォルダ一覧に戻る。 */
    fun openFolder(folderPath: String?) {
        _state.value = _state.value.copy(openedFolder = folderPath, selectedUris = emptySet())
    }

    /** 長押し選択モード: 単体トグル。 */
    fun toggleSelection(uri: Uri) {
        val cur = _state.value.selectedUris.toMutableSet()
        val key = uri.toString()
        if (!cur.add(key)) cur.remove(key)
        _state.value = _state.value.copy(selectedUris = cur)
    }

    fun clearSelection() {
        _state.value = _state.value.copy(selectedUris = emptySet())
    }

    /** 選択中のメディアを MediaStore から削除 (実体ファイルも削除される)。 */
    fun deleteSelected() {
        val keys = _state.value.selectedUris
        if (keys.isEmpty()) return
        viewModelScope.launch {
            val targets = _state.value.items.filter { it.contentUri.toString() in keys }
            val deleted = withContext(Dispatchers.IO) {
                var n = 0
                targets.forEach { item ->
                    runCatching {
                        // 1) MediaStore レコード削除を試みる。スコープドストレージで RecoverableSecurityException
                        //    が出ることがあるが、MANAGE_EXTERNAL_STORAGE があれば通る想定。
                        val rows = context.contentResolver.delete(item.contentUri, null, null)
                        // 2) ContentResolver が 0 だった場合は File でフォールバック (path が取れていれば)。
                        if (rows == 0 && item.filePath != null) {
                            val f = File(item.filePath)
                            if (f.exists()) f.delete()
                        }
                        n++
                    }
                }
                n
            }
            // 状態を再ロード (FolderCount などのために)。
            reload()
            _state.value = _state.value.copy(selectedUris = emptySet(), lastMessage = "${deleted} 件を削除しました")
        }
    }

    fun consumeMessage() {
        _state.value = _state.value.copy(lastMessage = null)
    }

    /** 選択中アイテムを「コピー」として SharedClipboard に積む。
     *  ファイルブラウザに戻って好きな場所で「貼り付け」できる。filePath を持たない
     *  (= 実体パス未取得) アイテムは現状の Storage 抽象では扱えないので除外する。 */
    fun copySelected() = stageSelectionToClipboard(asCut = false)
    fun cutSelected() = stageSelectionToClipboard(asCut = true)

    private fun stageSelectionToClipboard(asCut: Boolean) {
        val keys = _state.value.selectedUris
        if (keys.isEmpty()) return
        val nodes = _state.value.items
            .asSequence()
            .filter { it.contentUri.toString() in keys }
            .mapNotNull { item -> item.toLocalFileNode() }
            .toList()
        if (nodes.isEmpty()) {
            _state.value = _state.value.copy(
                lastMessage = "実体パスが取得できないため、この選択は積めません",
            )
            return
        }
        sharedClipboard.set(
            if (asCut) ClipboardOperation.Cut(nodes) else ClipboardOperation.Copy(nodes),
        )
        val verb = if (asCut) "切り取り" else "コピー"
        _state.value = _state.value.copy(
            selectedUris = emptySet(),
            lastMessage = "${nodes.size} 件を${verb}しました (ファイルブラウザで貼り付け)",
        )
    }

    /** MediaStore 由来の [MediaItem] を、Storage 抽象が扱える [FileNode] に変換する。
     *  filePath が無いものは null。MediaStore は permissions を返さないので、
     *  実体ファイルから rwx を読んで埋める。 */
    private fun MediaItem.toLocalFileNode(): FileNode? {
        val path = filePath ?: return null
        val f = File(path)
        if (!f.exists()) return null
        return FileNode(
            uri = FileUri.Local(path),
            name = displayName.ifEmpty { f.name },
            type = NodeType.FILE,
            size = if (size > 0) size else f.length(),
            lastModified = if (modifiedSec > 0) Instant.fromEpochSeconds(modifiedSec) else null,
            permissions = Permissions(readable = f.canRead(), writable = f.canWrite(), executable = f.canExecute()),
        )
    }

    private fun query(): List<MediaItem> {
        val (collectionUri, projection) = when (kind) {
            MediaKind.IMAGE -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI to arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.SIZE,
                MediaStore.Images.Media.DATE_MODIFIED,
                MediaStore.Images.Media.DATA,
            )
            MediaKind.VIDEO -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI to arrayOf(
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.SIZE,
                MediaStore.Video.Media.DATE_MODIFIED,
                MediaStore.Video.Media.DATA,
                MediaStore.Video.Media.DURATION,
            )
        }
        val sortOrder = "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
        val out = ArrayList<MediaItem>(256)
        context.contentResolver.query(collectionUri, projection, null, null, sortOrder)?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val sizeCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val mtimeCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
            val dataCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
            val durationCol = if (kind == MediaKind.VIDEO)
                c.getColumnIndex(MediaStore.Video.Media.DURATION) else -1
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                out.add(
                    MediaItem(
                        contentUri = ContentUris.withAppendedId(collectionUri, id),
                        displayName = c.getString(nameCol) ?: "",
                        size = c.getLong(sizeCol),
                        modifiedSec = c.getLong(mtimeCol),
                        filePath = c.getString(dataCol),
                        durationMs = if (durationCol >= 0) c.getLong(durationCol) else 0L,
                    ),
                )
            }
        }
        return out
    }

    /** items を直属フォルダごとに集計。filePath が null のものは「その他」にまとめる。 */
    private fun computeFolders(items: List<MediaItem>): List<MediaFolder> {
        val byParent = LinkedHashMap<String, MutableList<MediaItem>>()
        for (it in items) {
            val parent = it.filePath?.let { p -> File(p).parent } ?: "(その他)"
            byParent.getOrPut(parent) { mutableListOf() }.add(it)
        }
        return byParent.map { (path, group) ->
            MediaFolder(
                path = path,
                displayName = if (path == "(その他)") path else File(path).name,
                count = group.size,
                sampleUri = group.firstOrNull()?.contentUri,
                latestModifiedSec = group.maxOfOrNull { it.modifiedSec } ?: 0L,
            )
        }.sortedByDescending { it.latestModifiedSec }
    }

    companion object {
        const val ARG_KIND = "kind"
    }
}

enum class MediaKind { IMAGE, VIDEO }

data class MediaItem(
    val contentUri: Uri,
    val displayName: String,
    val size: Long,
    val modifiedSec: Long,
    val filePath: String?,
    val durationMs: Long,
)

data class MediaFolder(
    val path: String,
    val displayName: String,
    val count: Int,
    val sampleUri: Uri?,
    val latestModifiedSec: Long,
)

data class MediaCollectionState(
    val kind: MediaKind,
    val items: List<MediaItem> = emptyList(),
    val folders: List<MediaFolder> = emptyList(),
    val openedFolder: String? = null,
    val isLoading: Boolean = false,
    val hasPermission: Boolean = false,
    val error: String? = null,
    val selectedUris: Set<String> = emptySet(),
    val lastMessage: String? = null,
) {
    /** 現在の選択モード (1 件でも選択中か)。 */
    val isSelectionMode: Boolean get() = selectedUris.isNotEmpty()

    /** 現在の表示対象: openedFolder が指定されていればその配下のみ、null なら全件。 */
    val visibleItems: List<MediaItem>
        get() = if (openedFolder == null) items
                else items.filter { (it.filePath?.let { p -> File(p).parent } ?: "(その他)") == openedFolder }
}
