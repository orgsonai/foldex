package com.zerotoship.foldex.server.log

import android.util.Log
import com.zerotoship.foldex.core.data.repo.ServerLogRepository
import com.zerotoship.foldex.core.model.ServerLogEvent
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 認証ハンドラやサーバーマネージャから呼び出される薄いログ集約点。
 *
 * 認証ハンドラは MINA SSHD の同期 API 上にいるので、書き込み失敗で
 * 認証フローを巻き込まないよう Throwable は握りつぶし、Logcat にだけ流す。
 * Repository への suspend 呼び出しは呼び出し側で扱う (パスワード認証側は
 * runBlocking、サービス側は Service スコープのコルーチン)。
 */
@Singleton
class ServerLogger @Inject constructor(
    private val repository: ServerLogRepository,
) {

    suspend fun record(
        configId: String,
        event: ServerLogEvent,
        clientAddress: String,
        username: String? = null,
        details: String? = null,
    ) {
        runCatching {
            repository.append(
                configId = configId,
                event = event,
                clientAddress = clientAddress,
                username = username,
                details = details,
            )
        }.onFailure { t ->
            Log.w(TAG, "Failed to append server log: ${event.storageKey}", t)
        }
    }

    companion object {
        private const val TAG = "ServerLogger"
    }
}
