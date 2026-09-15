// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.videoedit

import android.content.Context
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zerotoship.foldex.R
import com.zerotoship.foldex.ui.videoedit.project.VideoDraftEntry
import com.zerotoship.foldex.ui.videoedit.project.VideoProjectStore
import com.zerotoship.foldex.ui.videoedit.project.videoDraftRoot
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** 動画編集の下書きの一覧。一覧のストアは下書きを開かないので、使用中の印を持たない。 */
@HiltViewModel
class VideoDraftsViewModel @Inject constructor(@ApplicationContext context: Context) : ViewModel() {
    private val store = VideoProjectStore(videoDraftRoot(context.filesDir))
    private val _state = MutableStateFlow(VideoDraftsState())
    internal val state = _state.asStateFlow()

    fun refresh() {
        if (_state.value.busy) return
        _state.value = _state.value.copy(busy = true)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { store.list() }
            _state.value = VideoDraftsState(
                entries = result.getOrNull().orEmpty(),
                error = if (result.isFailure) R.string.video_draft_list_failed else null,
            )
        }
    }

    fun delete(id: String) {
        if (_state.value.busy) return
        _state.value = _state.value.copy(busy = true)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { store.delete(id) }
            val entries = withContext(Dispatchers.IO) { store.list() }
            _state.value = VideoDraftsState(
                entries = entries.getOrNull() ?: _state.value.entries,
                error = if (result.isFailure) R.string.video_draft_delete_failed else null,
            )
        }
    }
}

internal data class VideoDraftsState(
    val entries: List<VideoDraftEntry> = emptyList(),
    val busy: Boolean = false,
    @StringRes val error: Int? = null,
)
