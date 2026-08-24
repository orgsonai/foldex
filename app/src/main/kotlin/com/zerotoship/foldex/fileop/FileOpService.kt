// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

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
import com.zerotoship.foldex.R

/**
 * フォルダコピー/移動/解凍など長時間のファイル操作中に、画面OFF/Doze でも
 * 処理が止まらないようにするための前景サービス。
 *
 * - `PARTIAL_WAKE_LOCK` で CPU を起こし続ける (画面OFFでも処理継続)。
 * - `WifiLock` でリモート転送中の Wi-Fi スリープ/切断を防ぐ。
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
                val label = intent?.getStringExtra(EXTRA_LABEL) ?: getString(R.string.file_op_in_progress)
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
        stopForeground(STOP_FOREGROUND_REMOVE)
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
            wifiLock = wm?.createWifiLock(wifiLockMode(), WIFI_TAG)
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
            getString(R.string.file_op_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.file_op_channel_desc)
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
        private const val WAKE_TAG: String = "foldex:fileop"
        private const val WIFI_TAG: String = "foldex:fileop-wifi"
        private const val MAX_HOLD_MS: Long = 2L * 60 * 60 * 1000 // 安全弁: 最長2時間

        /**
         * Android 14+ は HIGH_PERF を内部で LOW_LATENCY に置き換えるため後継を明示する。
         * 13 以前は画面 OFF・バックグラウンドでも有効な従来モードを保ち、転送の退行を防ぐ。
         */
        @Suppress("DEPRECATION")
        private fun wifiLockMode(): Int =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }

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
