// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zerotoship.foldex.core.data.log.AppLogger
import com.zerotoship.foldex.core.data.repo.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

data class AppLogUiState(
    val lines: List<String> = emptyList(),
    val filter: Filter = Filter.ALL,
    val loading: Boolean = true,
) {
    enum class Filter { ALL, INFO, WARN, ERROR }

    val filteredLines: List<String> get() = when (filter) {
        Filter.ALL -> lines
        Filter.INFO -> lines.filter { it.contains("[INFO]") }
        Filter.WARN -> lines.filter { it.contains("[WARN]") || it.contains("[ERROR]") }
        Filter.ERROR -> lines.filter { it.contains("[ERROR]") }
    }
}

@HiltViewModel
class AppLogViewModel @Inject constructor(
    private val logger: AppLogger,
    private val settings: SettingsRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(AppLogUiState())
    val state: StateFlow<AppLogUiState> = _state.asStateFlow()

    /** 永久ログの保存先 URI (null = オフ)。設定画面の表示用。 */
    val permanentLogUri: StateFlow<String?> = settings.settings
        .map { it.permanentLogUri }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** 永久ログの保存先を設定 / 解除する (null で解除)。 */
    fun setPermanentLog(uri: String?) {
        viewModelScope.launch { settings.setPermanentLogUri(uri) }
    }

    init {
        viewModelScope.launch {
            // 新しい書き込みがあれば再読み込み (画面を開いている間は live tail)。
            logger.lastWriteAt.collectLatest { reload() }
        }
    }

    fun setFilter(f: AppLogUiState.Filter) {
        _state.value = _state.value.copy(filter = f)
    }

    fun reload() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true)
            val lines = logger.tail()
            _state.value = _state.value.copy(lines = lines, loading = false)
        }
    }

    fun clear() {
        logger.clear()
        _state.value = _state.value.copy(lines = emptyList())
    }

    fun logFile(): File = logger.logFile()
}
