package com.zerotoship.foldex.core.data.repo

import com.zerotoship.foldex.core.data.db.SyncJobDao
import com.zerotoship.foldex.core.data.db.SyncStateDao
import com.zerotoship.foldex.core.data.db.toEntity
import com.zerotoship.foldex.core.data.db.toModel
import com.zerotoship.foldex.core.model.SyncJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SyncJob (定期同期ジョブ) の CRUD と最終実行結果の更新。
 * 削除時は紐づく sync_states も合わせて消す (孤児を残さない)。
 */
@Singleton
class SyncJobRepository @Inject constructor(
    private val jobDao: SyncJobDao,
    private val stateDao: SyncStateDao,
) {

    fun observeAll(): Flow<List<SyncJob>> =
        jobDao.observeAll().map { list -> list.map { it.toModel() } }

    suspend fun findById(id: String): SyncJob? = jobDao.findById(id)?.toModel()

    suspend fun upsert(job: SyncJob) {
        val now = System.currentTimeMillis()
        val merged = job.copy(
            createdAt = if (job.createdAt > 0) job.createdAt else now,
            updatedAt = now,
        )
        jobDao.upsert(merged.toEntity())
    }

    suspend fun delete(id: String) {
        stateDao.deleteByJob(id)
        jobDao.deleteById(id)
    }

    suspend fun updateLastRun(
        id: String,
        result: String,
        timestamp: Long = System.currentTimeMillis(),
    ) {
        jobDao.updateLastRun(id, timestamp, result)
    }
}
