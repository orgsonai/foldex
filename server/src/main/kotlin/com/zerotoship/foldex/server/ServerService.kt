package com.zerotoship.foldex.server

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.zerotoship.foldex.core.common.Result
import com.zerotoship.foldex.core.data.repo.ServerConfigRepository
import com.zerotoship.foldex.server.sftp.SftpServerManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 自機サーバーを保持する ForegroundService。設定 ID をインテントで受け取り
 * SftpServerManager を起動・停止する。Wi-Fi 切断 → 自動停止 や FTP 対応は
 * 後続コミットで増強する。
 */
@AndroidEntryPoint
class ServerService : Service() {

    @Inject lateinit var sftpManager: SftpServerManager
    @Inject lateinit var notificationFactory: ServerNotificationFactory
    @Inject lateinit var networkResolver: NetworkBindingResolver
    @Inject lateinit var repository: ServerConfigRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notificationFactory.ensureChannel()
        // 即座に foreground 化しないと ANR / Android 12+ では BackgroundServiceStartNotAllowed 例外
        startForegroundIfNeeded()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundIfNeeded()
        when (intent?.action) {
            ACTION_START -> {
                val configId = intent.getStringExtra(EXTRA_CONFIG_ID)
                if (configId != null) startServer(configId)
            }
            ACTION_STOP -> {
                val configId = intent.getStringExtra(EXTRA_CONFIG_ID)
                if (configId != null) stopServer(configId)
            }
            ACTION_STOP_ALL -> stopAll()
        }
        return START_STICKY
    }

    private fun startForegroundIfNeeded() {
        val notif = notificationFactory.build(emptyList())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                ServerNotificationFactory.NOTIFICATION_ID,
                notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(ServerNotificationFactory.NOTIFICATION_ID, notif)
        }
    }

    private fun startServer(configId: String) {
        scope.launch {
            val result = sftpManager.start(configId)
            if (result is Result.Success) {
                refreshNotification()
            } else {
                refreshNotification()
            }
        }
    }

    private fun stopServer(configId: String) {
        scope.launch {
            sftpManager.stop(configId)
            if (sftpManager.runningIds().isEmpty()) stopSelf() else refreshNotification()
        }
    }

    private fun stopAll() {
        scope.launch {
            sftpManager.stopAll()
            stopSelf()
        }
    }

    private suspend fun refreshNotification() {
        val ids = sftpManager.runningIds()
        val running = ids.mapNotNull { id ->
            val cfg = repository.findById(id) ?: return@mapNotNull null
            val host = networkResolver.resolve(cfg.bindAddress) ?: cfg.bindAddress
            ServerNotificationFactory.RunningServer.from(cfg, host)
        }
        val notif = notificationFactory.build(running)
        getSystemService(android.app.NotificationManager::class.java)
            ?.notify(ServerNotificationFactory.NOTIFICATION_ID, notif)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START: String = "com.zerotoship.foldex.server.action.START"
        const val ACTION_STOP: String = "com.zerotoship.foldex.server.action.STOP"
        const val ACTION_STOP_ALL: String = "com.zerotoship.foldex.server.action.STOP_ALL"
        const val EXTRA_CONFIG_ID: String = "configId"

        fun startIntent(context: android.content.Context, configId: String): Intent =
            Intent(context, ServerService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_CONFIG_ID, configId)

        fun stopIntent(context: android.content.Context, configId: String): Intent =
            Intent(context, ServerService::class.java)
                .setAction(ACTION_STOP)
                .putExtra(EXTRA_CONFIG_ID, configId)

        fun stopAllIntent(context: android.content.Context): Intent =
            Intent(context, ServerService::class.java).setAction(ACTION_STOP_ALL)
    }
}
