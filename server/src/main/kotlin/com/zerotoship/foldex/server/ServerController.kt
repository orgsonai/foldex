package com.zerotoship.foldex.server

import android.content.Context
import androidx.core.content.ContextCompat
import com.zerotoship.foldex.server.sftp.SftpServerManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * UI 層 (ViewModel) から自機サーバーを操作・観測するためのファサード。
 *
 * 起動・停止は ForegroundService [ServerService] を経由して行うので、
 * バックグラウンド制限を踏まずに済む。状態観測は [SftpServerManager] の
 * StateFlow をそのまま流す。
 */
@Singleton
class ServerController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sftpManager: SftpServerManager,
) {

    /** 起動中の設定 ID 集合 (SFTP)。FTP 対応時に統合する。 */
    val runningIds: StateFlow<Set<String>> = sftpManager.runningIds

    fun isRunning(configId: String): Boolean = sftpManager.isRunning(configId)

    fun start(configId: String) {
        ContextCompat.startForegroundService(
            context,
            ServerService.startIntent(context, configId),
        )
    }

    fun stop(configId: String) {
        ContextCompat.startForegroundService(
            context,
            ServerService.stopIntent(context, configId),
        )
    }

    fun stopAll() {
        ContextCompat.startForegroundService(
            context,
            ServerService.stopAllIntent(context),
        )
    }
}
