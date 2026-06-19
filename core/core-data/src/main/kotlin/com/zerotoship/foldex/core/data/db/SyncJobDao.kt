// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.core.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncJobDao {

    @Query("SELECT * FROM sync_jobs ORDER BY sortOrder ASC, updatedAt DESC")
    fun observeAll(): Flow<List<SyncJobEntity>>

    @Query("SELECT * FROM sync_jobs WHERE id = :id")
    suspend fun findById(id: String): SyncJobEntity?

    @Upsert
    suspend fun upsert(entity: SyncJobEntity)

    @Query("DELETE FROM sync_jobs WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query(
        "UPDATE sync_jobs SET lastRunAt = :timestamp, lastRunResult = :result, updatedAt = :timestamp WHERE id = :id",
    )
    suspend fun updateLastRun(id: String, timestamp: Long, result: String)

    @Query("UPDATE sync_jobs SET sortOrder = :order WHERE id = :id")
    suspend fun updateSortOrder(id: String, order: Int)
}
