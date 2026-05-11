package com.zerotoship.foldex.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ServerConfigDao {

    @Query("SELECT * FROM server_configs ORDER BY type ASC, name ASC")
    fun observeAll(): Flow<List<ServerConfigEntity>>

    @Query("SELECT * FROM server_configs WHERE id = :id")
    suspend fun findById(id: String): ServerConfigEntity?

    @Query("SELECT * FROM server_configs WHERE autoStartOnAppLaunch = 1")
    suspend fun findAutoStartOnAppLaunch(): List<ServerConfigEntity>

    @Query("SELECT * FROM server_configs WHERE autoStartOnBoot = 1")
    suspend fun findAutoStartOnBoot(): List<ServerConfigEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ServerConfigEntity)

    @Query("DELETE FROM server_configs WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE server_configs SET lastStartedAt = :timestamp WHERE id = :id")
    suspend fun updateLastStartedAt(id: String, timestamp: Long)
}
