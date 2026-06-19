// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.core.data.repo

import com.zerotoship.foldex.core.data.db.ServerLogDao
import com.zerotoship.foldex.core.data.db.ServerLogEntity
import com.zerotoship.foldex.core.data.db.toModel
import com.zerotoship.foldex.core.model.ServerLog
import com.zerotoship.foldex.core.model.ServerLogEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 自機サーバーの接続ログ書き込み・参照を集約する。
 * 認証ハンドラから高頻度で呼ばれる経路にあるので、書き込みは Insert のみ・
 * 例外は呼び出し側で握りつぶせるよう [Result] 等は介さず Long を返す。
 */
@Singleton
class ServerLogRepository @Inject constructor(
    private val dao: ServerLogDao,
) {

    fun observeRecent(configId: String, limit: Int = 200): Flow<List<ServerLog>> =
        dao.observeRecent(configId, limit).map { list -> list.map { it.toModel() } }

    suspend fun append(
        configId: String,
        event: ServerLogEvent,
        clientAddress: String,
        username: String? = null,
        details: String? = null,
        timestamp: Long = System.currentTimeMillis(),
    ): Long = dao.insert(
        ServerLogEntity(
            configId = configId,
            event = event.storageKey,
            clientAddress = clientAddress,
            username = username,
            timestamp = timestamp,
            details = details,
        ),
    )

    suspend fun deleteByConfigId(configId: String) = dao.deleteByConfigId(configId)

    suspend fun deleteOlderThan(cutoff: Long): Int = dao.deleteOlderThan(cutoff)
}
