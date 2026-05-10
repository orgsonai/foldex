package com.zerotoship.foldex.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ServerLogDao {

    @Query("SELECT * FROM server_logs WHERE configId = :configId ORDER BY timestamp DESC LIMIT :limit")
    fun observeRecent(configId: String, limit: Int = 200): Flow<List<ServerLogEntity>>

    @Insert
    suspend fun insert(entity: ServerLogEntity): Long

    @Query("DELETE FROM server_logs WHERE configId = :configId")
    suspend fun deleteByConfigId(configId: String)

    @Query("DELETE FROM server_logs WHERE timestamp < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long): Int
}
