package com.zerotoship.foldex.server

import android.content.Context
import androidx.core.content.ContextCompat
import com.zerotoship.foldex.server.ftp.FtpServerManager
import com.zerotoship.foldex.server.sftp.SftpServerManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
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

    fun isRunning(configId: String): Boolean =
        sftpManager.isRunning(configId) || ftpManager.isRunning(configId)

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
