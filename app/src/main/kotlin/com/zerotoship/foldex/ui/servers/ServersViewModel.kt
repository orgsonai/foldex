// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.servers

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zerotoship.foldex.R
import com.zerotoship.foldex.core.data.repo.ServerConfigRepository
import com.zerotoship.foldex.core.model.ServerConfig
import com.zerotoship.foldex.server.ServerController
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 自機サーバー一覧画面の状態と操作。
 * 起動状態は [ServerController.runningIds] を、設定は [ServerConfigRepository] を
 * combine して 1 つの State として UI に流す。start/stop は ForegroundService 経由。
 */
@HiltViewModel
class ServersViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: ServerConfigRepository,
    private val controller: ServerController,
) : ViewModel() {
    private fun text(id: Int, vararg args: Any): String = context.getString(id, *args)

    val state: StateFlow<ServersUiState> = combine(
        repository.observeAll(),
        controller.runningIds,
    ) { configs, runningIds ->
        ServersUiState(
            servers = configs.map { ServerRowState(config = it, isRunning = it.id in runningIds) },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ServersUiState())

    private val _events = Channel<ServerEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    init {
        // 起動失敗 (Wi-Fi 未接続 / ポート使用中 / 設定不備など) を UI に通知する。
        viewModelScope.launch {
            controller.startErrors.collect { msg -> _events.trySend(ServerEvent.Message(msg)) }
        }
    }

    fun start(config: ServerConfig) {
        controller.start(config.id)
        _events.trySend(ServerEvent.Message(text(R.string.server_starting, config.name)))
    }

    fun stop(config: ServerConfig) {
        controller.stop(config.id)
        _events.trySend(ServerEvent.Message(text(R.string.server_stopping, config.name)))
    }

    fun stopAll() {
        controller.stopAll()
        _events.trySend(ServerEvent.Message(text(R.string.servers_stopping_all)))
    }

    fun delete(config: ServerConfig) {
        viewModelScope.launch {
            try {
                if (controller.isRunning(config.id)) controller.stop(config.id)
                repository.delete(config.id)
                _events.trySend(ServerEvent.Message(text(R.string.server_deleted, config.name)))
            } catch (t: Throwable) {
                _events.trySend(ServerEvent.Message(text(R.string.connection_delete_error, t.message.orEmpty())))
            }
        }
    }

    data class ServersUiState(
        val servers: List<ServerRowState> = emptyList(),
    )

    data class ServerRowState(
        val config: ServerConfig,
        val isRunning: Boolean,
    )

    sealed class ServerEvent {
        data class Message(val text: String) : ServerEvent()
    }
}
