// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.server

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.zerotoship.foldex.server.ftp.FtpServerManager
import com.zerotoship.foldex.server.sftp.SftpServerManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * UI 層 (ViewModel) から自機サーバーを操作・観測するためのファサード。
 *
 * 起動・停止は ForegroundService [ServerService] を経由して行うので、
 * バックグラウンド制限を踏まずに済む。状態観測は SFTP / FTP 両マネージャの
 * StateFlow を combine して 1 つの集合として公開する。
 */
@Singleton
class ServerController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sftpManager: SftpServerManager,
    private val ftpManager: FtpServerManager,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 起動中の設定 ID 集合 (SFTP + FTP)。 */
    val runningIds: StateFlow<Set<String>> = combine(
        sftpManager.runningIds,
        ftpManager.runningIds,
    ) { a, b -> a + b }.stateIn(scope, SharingStarted.Eagerly, emptySet())

    /** 起動失敗メッセージ (SFTP / FTP)。UI で snackbar 表示する用。 */
    val startErrors: Flow<String> = merge(sftpManager.startErrors, ftpManager.startErrors)

    fun isRunning(configId: String): Boolean =
        sftpManager.isRunning(configId) || ftpManager.isRunning(configId)

    fun start(configId: String) {
        deliver(ServerService.startIntent(context, configId))
    }

    fun stop(configId: String) {
        if (deliver(ServerService.stopIntent(context, configId))) return
        // サービスへ要求を届けられなかった場合 (バックグラウンド起動制限など) でも、
        // 停止だけは必ず通す。マネージャは Singleton なので、プロセス内から直接
        // 止めても UI が見ている状態と食い違わない。
        scope.launch {
            sftpManager.stop(configId)
            ftpManager.stop(configId)
        }
    }

    fun stopAll() {
        if (deliver(ServerService.stopAllIntent(context))) return
        scope.launch {
            sftpManager.stopAll()
            ftpManager.stopAll()
        }
    }

    /** [ServerService] へ要求を送る。送れたら true。 */
    private fun deliver(intent: Intent): Boolean = runCatching {
        ContextCompat.startForegroundService(context, intent)
        true
    }.getOrDefault(false)
}
