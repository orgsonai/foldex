package com.zerotoship.foldex.ui.media

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** HOME の「画像」/「動画」タイルから開かれる横断ビューア用 ViewModel。 */
@HiltViewModel
class MediaCollectionViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    val kind: MediaKind = (savedStateHandle.get<String>(ARG_KIND) ?: "image").let {
        if (it == "video") MediaKind.VIDEO else MediaKind.IMAGE
    }

    private val _state = MutableStateFlow(MediaCollectionState(kind = kind))
    val state: StateFlow<MediaCollectionState> = _state.asStateFlow()

    /** 権限の有無を反映 (画面側で `RequestPermission` の結果を受けて呼ぶ)。 */
    fun setPermissionGranted(granted: Boolean) {
        _state.value = _state.value.copy(hasPermission = granted)
        if (granted) reload()
    }

    fun reload() {
        _state.value = _state.value.copy(isLoading = true, error = null)
        viewModelScope.launch {
            val items = withContext(Dispatchers.IO) { runCatching { query() }.getOrElse { emptyList() } }
            _state.value = _state.value.copy(items = items, isLoading = false)
        }
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
    val filePath: String?, // _DATA, MANAGE_EXTERNAL_STORAGE 下で取れる。null/非アクセスのことあり。
    val durationMs: Long,
)

data class MediaCollectionState(
    val kind: MediaKind,
    val items: List<MediaItem> = emptyList(),
    val isLoading: Boolean = false,
    val hasPermission: Boolean = false,
    val error: String? = null,
)
