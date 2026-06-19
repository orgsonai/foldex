package com.zerotoship.foldex.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ConnectionDao {

    @Query("SELECT * FROM connections ORDER BY sortOrder ASC, name ASC")
    fun observeAll(): Flow<List<ConnectionEntity>>

    @Query("SELECT * FROM connections WHERE id = :id")
    suspend fun findById(id: String): ConnectionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ConnectionEntity)

    @Update
    suspend fun update(entity: ConnectionEntity)

    @Query("DELETE FROM connections WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE connections SET lastConnectedAt = :timestamp WHERE id = :id")
    suspend fun updateLastConnectedAt(id: String, timestamp: Long)

    @Query("UPDATE connections SET sortOrder = :order WHERE id = :id")
    suspend fun updateSortOrder(id: String, order: Int)
}
