package com.zerotoship.foldex.core.data.db

import androidx.room.Entity
import androidx.room.Index

/**
 * 仕様書 §8-E の SyncStateEntity。jobId + path で複合主キー。
 * jobId 単位で観測したサイズ / mtime を保存し、次回 DiffEngine が前回状態として
 * 利用する。
 */
@Entity(
    tableName = "sync_states",
    primaryKeys = ["jobId", "path"],
    indices = [Index("jobId")],
)
data class SyncStateEntity(
    val jobId: String,
    val path: String,
    val localSize: Long?,
    val localMtime: Long?,
    val remoteSize: Long?,
    val remoteMtime: Long?,
    val lastSyncedAt: Long,
)
