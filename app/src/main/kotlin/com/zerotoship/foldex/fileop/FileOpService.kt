package com.zerotoship.foldex.fileop

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * フォルダコピー/移動/解凍など長時間のファイル操作中に、画面OFF/Doze でも
 * 処理が止まらないようにするための前景サービス。
 *
 * - `PARTIAL_WAKE_LOCK` で CPU を起こし続ける (画面OFFでも処理継続)。
 * - `WifiLock(FULL_HIGH_PERF)` でリモート転送中の Wi-Fi スリープ/切断を防ぐ。
 * - `dataSync` 前景通知でプロセスがバックグラウンド kill されにくくする。
 *
 * 実際のコピー処理は ViewModel 側 (`viewModelScope`) が行い、本サービスは
 * 「処理中、端末を起こし続けプロセスを保護する」役割のみを持つ。
 * 操作開始時に [start]、完了時 (finally) に [stop] を呼ぶ前提。
 */
class FileOpService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                releaseLocks()
                stopForegroundCompat()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                val label = intent?.getStringExtra(EXTRA_LABEL) ?: DEFAULT_LABEL
                startForegroundCompat(label)
                acquireLocks()
            }
        }
        // 一度きりの操作なので、kill されても自動再起動しない。
        return START_NOT_STICKY
    }

    private fun startForegroundCompat(label: String) {
        ensureChannel()
        val notif = buildNotification(label)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notif)
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun acquireLocks() {
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_TAG)
                .apply { setReferenceCounted(false) }
        }
        // タイムアウト付き (安全弁): 万一 stop が来なくても最長 MAX_HOLD_MS で自動解放。
        wakeLock?.let { if (!it.isHeld) it.acquire(MAX_HOLD_MS) }
        if (wifiLock == null) {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            wifiLock = wm?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, WIFI_TAG)
                ?.apply { setReferenceCounted(false) }
        }
        wifiLock?.let { if (!it.isHeld) it.acquire() }
    }

    private fun releaseLocks() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wifiLock?.let { if (it.isHeld) it.release() }
    }

    private fun buildNotification(label: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Foldex")
            .setContentText(label)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .build()

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "ファイル操作",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "コピー・移動・解凍など進行中の操作を表示します"
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    override fun onDestroy() {
        releaseLocks()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP: String = "com.zerotoship.foldex.fileop.action.STOP"
        const val EXTRA_LABEL: String = "label"
        const val CHANNEL_ID: String = "foldex_file_ops"
        const val NOTIFICATION_ID: Int = 4343
        private const val DEFAULT_LABEL: String = "ファイル操作中…"
        private const val WAKE_TAG: String = "foldex:fileop"
        private const val WIFI_TAG: String = "foldex:fileop-wifi"
        private const val MAX_HOLD_MS: Long = 2L * 60 * 60 * 1000 // 安全弁: 最長2時間

        /** 長時間ファイル操作の開始時に呼ぶ。前景化 + WakeLock 取得。 */
        fun start(context: Context, label: String) {
            val intent = Intent(context, FileOpService::class.java)
                .putExtra(EXTRA_LABEL, label)
            ContextCompat.startForegroundService(context, intent)
        }

        /** 操作完了時に呼ぶ。WakeLock 解放 + 前景解除 + サービス停止。 */
        fun stop(context: Context) {
            val intent = Intent(context, FileOpService::class.java)
                .setAction(ACTION_STOP)
            // 既に前景化済みの前提なので通常の startService で良い。
            runCatching { context.startService(intent) }
        }
    }
}
