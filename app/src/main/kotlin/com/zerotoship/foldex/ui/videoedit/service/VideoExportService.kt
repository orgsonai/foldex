// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.videoedit.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.zerotoship.foldex.R
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 動画の書き出し中に、画面を消したり別のアプリへ切り替えたりしても処理が止まらないようにする前景サービス。
 *
 * 書き出しそのものは編集画面の ViewModel が行い、このサービスは端末を起こし続けて進み具合を通知に出すだけ
 * (FileOpService と同じ役割分担)。通知の「取消」と、Android 15 以降の実行時間の上限は [VideoExportSignals] で ViewModel へ伝える。
 */
class VideoExportService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PROGRESS -> notifyProgress(intent.getIntExtra(EXTRA_PERCENT, -1))
            ACTION_CANCEL -> VideoExportSignals.emit(VideoExportSignal.CANCEL_REQUESTED)
            ACTION_STOP -> stopNow()
            else -> try {
                startForegroundCompat()
                acquireWakeLock()
            } catch (_: IllegalStateException) {
                // Android 15 以降、mediaProcessing の 1 日の上限を使い切っていると開始できず例外になる。
                // アプリを落とさず、上限に達したときと同じく書き出しを止める。
                VideoExportSignals.emit(VideoExportSignal.TIME_LIMIT)
                stopSelf()
            }
        }
        // 一度きりの処理なので、終了させられても自動で再起動しない。
        return START_NOT_STICKY
    }

    /** Android 15 以降、mediaProcessing の前景サービスは 1 日の合計時間に上限がある。超えたら書き出しを止める。 */
    override fun onTimeout(startId: Int, fgsType: Int) {
        VideoExportSignals.emit(VideoExportSignal.TIME_LIMIT)
        stopNow()
    }

    override fun onDestroy() {
        releaseWakeLock()
        super.onDestroy()
    }

    private fun stopNow() {
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startForegroundCompat() {
        ensureChannel()
        val notification = buildNotification(-1)
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM ->
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else -> startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun notifyProgress(percent: Int) {
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, buildNotification(percent))
    }

    private fun buildNotification(percent: Int): Notification {
        val cancel = PendingIntent.getService(
            this,
            0,
            Intent(this, VideoExportService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.video_export_notification_title))
            .setContentText(if (percent >= 0) getString(R.string.video_export_notification_percent, percent) else null)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(100, percent.coerceIn(0, 100), percent < 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .addAction(0, getString(R.string.action_cancel), cancel)
            .build()
    }

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(CHANNEL_ID, getString(R.string.video_export_channel), NotificationManager.IMPORTANCE_LOW).apply {
            description = getString(R.string.video_export_channel_desc)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val power = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_TAG).apply { setReferenceCounted(false) }
        }
        // 安全弁: 停止の指示が来なくても、上限の時間で自動的に解放する。
        wakeLock?.let { if (!it.isHeld) it.acquire(MAX_HOLD_MS) }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
    }

    companion object {
        private const val ACTION_PROGRESS = "com.zerotoship.foldex.videoedit.action.PROGRESS"
        private const val ACTION_CANCEL = "com.zerotoship.foldex.videoedit.action.CANCEL"
        private const val ACTION_STOP = "com.zerotoship.foldex.videoedit.action.STOP"
        private const val EXTRA_PERCENT = "percent"
        private const val CHANNEL_ID = "foldex_video_export"
        private const val NOTIFICATION_ID = 4344
        private const val WAKE_TAG = "foldex:videoexport"
        private const val MAX_HOLD_MS = 6L * 60 * 60 * 1000

        /** 書き出しの開始時に呼ぶ (画面が前面にある間)。 */
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, VideoExportService::class.java))
        }

        /** 進み具合 (0〜100、不明なら -1) を通知に出す。開始後だけ呼ぶ。 */
        fun progress(context: Context, percent: Int) {
            runCatching {
                context.startService(Intent(context, VideoExportService::class.java).setAction(ACTION_PROGRESS).putExtra(EXTRA_PERCENT, percent))
            }
        }

        /** 書き出しの終了時 (成功・失敗・取消のすべて) に呼ぶ。 */
        fun stop(context: Context) {
            runCatching { context.startService(Intent(context, VideoExportService::class.java).setAction(ACTION_STOP)) }
        }
    }
}

enum class VideoExportSignal { CANCEL_REQUESTED, TIME_LIMIT }

/** サービスから編集画面への知らせ。書き出しは同時に 1 本なので、プロセス全体で 1 つの流れにする。 */
object VideoExportSignals {
    private val flow = MutableSharedFlow<VideoExportSignal>(extraBufferCapacity = 8)
    val events: SharedFlow<VideoExportSignal> = flow.asSharedFlow()

    fun emit(signal: VideoExportSignal) {
        flow.tryEmit(signal)
    }
}
