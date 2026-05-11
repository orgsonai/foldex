package com.zerotoship.foldex.core.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 自機サーバーへの接続イベントログ。デフォルトオンで不正アクセス検知に使う。
 * レート制限・自動ブロックは P7 以降で扱うのでここでは書き込み専用構造に留める。
 */
@Entity(
    tableName = "server_logs",
    indices = [Index("configId"), Index("timestamp")],
)
data class ServerLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val configId: String,
    /** "client_connected" | "auth_success" | "auth_failed" | "client_disconnected" | "server_started" | "server_stopped" */
    val event: String,
    val clientAddress: String,
    val username: String?,
    val timestamp: Long,
    val details: String?,
)
