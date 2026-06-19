// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

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

    /** ScheduleType=INTERVAL のときの間隔(分)。0 = 手動のみ。 */
    val intervalMinutes: Int,
    /** ScheduleType の wireName。 */
    val scheduleType: String = "interval",
    /** DAILY/WEEKLY/MONTHLY: 時刻 (0..1439 分)。 */
    val scheduleTimeOfDay: Int = 0,
    /** WEEKLY: 曜日ビットマスク (bit0=月 ... bit6=日)。 */
    val scheduleDaysOfWeek: Int = 0,
    /** MONTHLY: 日 (1..31, 0=月末)。 */
    val scheduleDayOfMonth: Int = 1,
    /** DATETIME: 実行 epoch ms。 */
    val scheduleDateTime: Long = 0L,
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

    /** ドラッグ並び替え順。小さいほど上。同値は updatedAt の新しい順。Room v5→v6 は destructive で吸収。 */
    val sortOrder: Int = 0,
)
