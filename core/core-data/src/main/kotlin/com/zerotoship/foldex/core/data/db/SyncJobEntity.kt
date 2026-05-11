package com.zerotoship.foldex.core.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 仕様書 §8-J の SyncJobEntity。glob パターンは JSON 配列文字列で保持し、
 * Mapper で List<String> に変換する。
 */
@Entity(tableName = "sync_jobs")
data class SyncJobEntity(
    @PrimaryKey val id: String,
    val name: String,
    val enabled: Boolean,

    val localUri: String,
    val remoteUri: String,
    val direction: String,

    val conflictPolicy: String,
    val includePatterns: String,
    val excludePatterns: String,
    val maxFileSize: Long?,

    val intervalMinutes: Int,
    val requiresWifi: Boolean,
    val requiresCharging: Boolean,
    val requiresBatteryNotLow: Boolean,

    val deleteEnabled: Boolean,
    val parallelism: Int,
    val retryCount: Int,

    val createdAt: Long,
    val updatedAt: Long,
    val lastRunAt: Long?,
    val lastRunResult: String?,
)
