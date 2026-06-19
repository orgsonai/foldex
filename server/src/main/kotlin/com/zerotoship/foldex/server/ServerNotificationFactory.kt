// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.zerotoship.foldex.core.model.ServerConfig
import com.zerotoship.foldex.core.model.ServerType
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 自機サーバーの ForegroundService 用通知を組み立てる。
 *
 * 設計方針:
 * - IMPORTANCE_LOW (音/バイブ無し)、setShowBadge(false)。
 * - タイトル「Foldex サーバー稼働中」、本文に各サーバーの IP:port を一覧で表示。
 * - 「すべて停止」アクションで一括停止可能。
 *
 * クリック時の遷移先 (サーバー画面) は app モジュールが解決すべきだが、
 * server モジュールから app の Activity を直接参照できないため、
 * launch intent は呼び出し側で渡す前提とする。本クラスでは action ボタンのみ提供。
 */
@Singleton
class ServerNotificationFactory @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    fun ensureChannel() {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "サーバー稼働中",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "SFTP / FTP サーバーの稼働状態を表示します"
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    fun build(running: List<RunningServer>, contentIntent: PendingIntent? = null): Notification {
        ensureChannel()
        val title = "Foldex サーバー稼働中"
        val text = if (running.isEmpty()) {
            "稼働中のサーバーはありません"
        } else {
            running.joinToString("\n") { server ->
                val typeLabel = when (server.type) {
                    ServerType.SFTP -> "SFTP"
                    ServerType.FTP -> "FTP"
                }
                "$typeLabel: ${server.host}:${server.port} (${server.name})"
            }
        }
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(if (running.isEmpty()) text else running.first().let {
                "${it.host}:${it.port} ${if (running.size > 1) "他 ${running.size - 1} 件" else ""}"
            })
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        if (contentIntent != null) builder.setContentIntent(contentIntent)
        if (running.isNotEmpty()) {
            val stopAllIntent = Intent(context, ServerService::class.java)
                .setAction(ServerService.ACTION_STOP_ALL)
            val pi = PendingIntent.getService(
                context,
                0,
                stopAllIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            builder.addAction(0, "すべて停止", pi)
        }
        return builder.build()
    }

    data class RunningServer(
        val configId: String,
        val name: String,
        val type: ServerType,
        val host: String,
        val port: Int,
    ) {
        companion object {
            fun from(config: ServerConfig, host: String): RunningServer = RunningServer(
                configId = config.id,
                name = config.name,
                type = config.type,
                host = host,
                port = config.port,
            )
        }
    }

    companion object {
        const val CHANNEL_ID: String = "foldex_servers"
        const val NOTIFICATION_ID: Int = 4242
    }
}
