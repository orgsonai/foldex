// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zerotoship.foldex.core.data.repo.TrashRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class TrashUiState(
    val entries: List<TrashRepository.Entry> = emptyList(),
    val totalBytes: Long = 0L,
    val loading: Boolean = true,
)

@HiltViewModel
class TrashViewModel @Inject constructor(
    private val repo: TrashRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(TrashUiState())
    val state: StateFlow<TrashUiState> = _state.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true)
            val entries = repo.list()
            _state.value = TrashUiState(entries = entries, totalBytes = repo.currentSizeBytes(), loading = false)
        }
    }

    fun restore(id: String) {
        viewModelScope.launch {
            val ok = repo.restore(id)
            _messages.send(if (ok) "復元しました" else "復元に失敗しました (同名のファイルが既に存在する可能性があります)")
            refresh()
        }
    }

    fun deletePermanently(id: String) {
        viewModelScope.launch { repo.deletePermanently(id); refresh() }
    }

    fun emptyTrash() {
        viewModelScope.launch { repo.empty(); _messages.send("ゴミ箱を空にしました"); refresh() }
    }
}
