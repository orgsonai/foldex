// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.imageedit

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zerotoship.foldex.R
import com.zerotoship.foldex.ui.imageedit.draft.ImageDraftEntry
import com.zerotoship.foldex.ui.imageedit.draft.ImageDraftStore
import com.zerotoship.foldex.ui.imageedit.draft.imageDraftRoot
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class ImageDraftsViewModel @Inject constructor(@ApplicationContext private val context: Context) : ViewModel() {
    private val store = ImageDraftStore(imageDraftRoot(context.filesDir))
    private val _state = MutableStateFlow(ImageDraftsState())
    internal val state = _state.asStateFlow()

    fun refresh() {
        if (_state.value.busy) return
        _state.value = _state.value.copy(busy = true)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { store.list() }
            _state.value = ImageDraftsState(result.getOrNull().orEmpty(),
                error = if (result.isFailure) context.getString(R.string.image_draft_list_failed) else null)
        }
    }

    fun delete(id: String) {
        if (_state.value.busy) return
        _state.value = _state.value.copy(busy = true)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { store.delete(id) }
            val entries = withContext(Dispatchers.IO) { store.list() }
            _state.value = ImageDraftsState(entries.getOrNull() ?: _state.value.entries,
                error = if (result.isFailure) context.getString(R.string.image_draft_delete_failed) else null)
        }
    }
}

internal data class ImageDraftsState(
    val entries: List<ImageDraftEntry> = emptyList(),
    val busy: Boolean = false,
    val error: String? = null,
)
