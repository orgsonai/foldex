package com.zerotoship.foldex.core.data.repo

import com.zerotoship.foldex.core.data.db.SyncStateDao
import com.zerotoship.foldex.core.data.db.toEntity
import com.zerotoship.foldex.core.data.db.toModel
import com.zerotoship.foldex.core.model.SyncState
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 1 ジョブ単位の前回同期状態 (サイズ + mtime) を扱う Repository。
 * DiffEngine が「前回状態 vs 現在のローカル/リモート状態」を比較するために
 * jobId スコープでまとめて読み込み、Executor が転送完了後に upsert する。
 */
@Singleton
class SyncStateRepository @Inject constructor(
    private val dao: SyncStateDao,
) {

    suspend fun snapshotForJob(jobId: String): Map<String, SyncState> =
        dao.findByJob(jobId).associate { it.path to it.toModel() }

    suspend fun upsert(state: SyncState) = dao.upsert(state.toEntity())

    suspend fun upsertAll(states: List<SyncState>) {
        if (states.isEmpty()) return
        dao.upsertAll(states.map { it.toEntity() })
    }

    suspend fun deleteByJob(jobId: String) = dao.deleteByJob(jobId)

    suspend fun deletePath(jobId: String, path: String) = dao.deleteByPath(jobId, path)
}
