// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.core.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface SyncStateDao {

    @Query("SELECT * FROM sync_states WHERE jobId = :jobId")
    suspend fun findByJob(jobId: String): List<SyncStateEntity>

    @Upsert
    suspend fun upsert(entity: SyncStateEntity)

    @Upsert
    suspend fun upsertAll(entities: List<SyncStateEntity>)

    @Query("DELETE FROM sync_states WHERE jobId = :jobId")
    suspend fun deleteByJob(jobId: String)

    @Query("DELETE FROM sync_states WHERE jobId = :jobId AND path = :path")
    suspend fun deleteByPath(jobId: String, path: String)
}
