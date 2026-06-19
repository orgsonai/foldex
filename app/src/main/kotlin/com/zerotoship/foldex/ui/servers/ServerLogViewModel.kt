// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.servers

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zerotoship.foldex.core.data.repo.ServerConfigRepository
import com.zerotoship.foldex.core.data.repo.ServerLogRepository
import com.zerotoship.foldex.core.model.ServerConfig
import com.zerotoship.foldex.core.model.ServerLog
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 1 つのサーバー設定に紐づく接続ログ閲覧画面。
 * 設定 ID は navigation 引数経由、ログは [ServerLogRepository.observeRecent] を
 * そのまま流す。クリア操作も提供する。
 */
@HiltViewModel
class ServerLogViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val configRepository: ServerConfigRepository,
    private val logRepository: ServerLogRepository,
) : ViewModel() {

    private val configId: String = savedStateHandle["id"]
        ?: error("ServerLogViewModel requires 'id' navArg")

    private val _config = MutableStateFlow<ServerConfig?>(null)
    val config: StateFlow<ServerConfig?> = _config.asStateFlow()

    val logs: StateFlow<List<ServerLog>> = logRepository.observeRecent(configId, LIMIT)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch {
            _config.value = configRepository.findById(configId)
        }
    }

    fun clear() {
        viewModelScope.launch {
            logRepository.deleteByConfigId(configId)
        }
    }

    companion object {
        private const val LIMIT = 200
    }
}
