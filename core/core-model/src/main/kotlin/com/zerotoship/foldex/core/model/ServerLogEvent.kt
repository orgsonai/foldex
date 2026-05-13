package com.zerotoship.foldex.core.model

/**
 * サーバー接続ログのイベント種別。
 * 永続化キーは [storageKey] で、Room には文字列で書き込まれる。
 */
enum class ServerLogEvent(val storageKey: String) {
    SERVER_STARTED("server_started"),
    SERVER_STOPPED("server_stopped"),
    SERVER_START_FAILED("server_start_failed"),
    CLIENT_CONNECTED("client_connected"),
    CLIENT_DISCONNECTED("client_disconnected"),
    AUTH_SUCCESS("auth_success"),
    AUTH_FAILED("auth_failed"),
    FILE_OP_FAILED("file_op_failed");

    companion object {
        fun fromStorageKey(key: String): ServerLogEvent? =
            values().firstOrNull { it.storageKey == key }
    }
}
