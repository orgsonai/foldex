package com.zerotoship.foldex.core.data.db

import com.zerotoship.foldex.core.model.ConflictPolicy
import com.zerotoship.foldex.core.model.ScheduleType
import com.zerotoship.foldex.core.model.SyncDirection
import com.zerotoship.foldex.core.model.SyncFilter
import com.zerotoship.foldex.core.model.SyncJob
import com.zerotoship.foldex.core.model.SyncSchedule
import com.zerotoship.foldex.core.model.SyncState
import org.json.JSONArray

internal fun SyncJobEntity.toModel(): SyncJob = SyncJob(
    id = id,
    name = name,
    enabled = enabled,
    localUri = localUri,
    remoteUri = remoteUri,
    direction = SyncDirection.fromWireName(direction),
    conflictPolicy = ConflictPolicy.fromWireName(conflictPolicy),
    filter = SyncFilter(
        includePatterns = decodeJsonStringArray(includePatterns),
        excludePatterns = decodeJsonStringArray(excludePatterns),
        maxFileSize = maxFileSize,
    ),
    schedule = SyncSchedule(
        type = ScheduleType.fromWireName(scheduleType),
        intervalMinutes = intervalMinutes,
        timeOfDayMinutes = scheduleTimeOfDay,
        daysOfWeek = scheduleDaysOfWeek,
        dayOfMonth = scheduleDayOfMonth,
        dateTimeMillis = scheduleDateTime,
    ),
    requiresWifi = requiresWifi,
    requiresCharging = requiresCharging,
    requiresBatteryNotLow = requiresBatteryNotLow,
    deleteEnabled = deleteEnabled,
    parallelism = parallelism,
    retryCount = retryCount,
    createdAt = createdAt,
    updatedAt = updatedAt,
    lastRunAt = lastRunAt,
    lastRunResult = lastRunResult,
)

internal fun SyncJob.toEntity(): SyncJobEntity = SyncJobEntity(
    id = id,
    name = name,
    enabled = enabled,
    localUri = localUri,
    remoteUri = remoteUri,
    direction = direction.wireName,
    conflictPolicy = conflictPolicy.wireName,
    includePatterns = encodeJsonStringArray(filter.includePatterns),
    excludePatterns = encodeJsonStringArray(filter.excludePatterns),
    maxFileSize = filter.maxFileSize,
    intervalMinutes = schedule.intervalMinutes,
    scheduleType = schedule.type.wireName,
    scheduleTimeOfDay = schedule.timeOfDayMinutes,
    scheduleDaysOfWeek = schedule.daysOfWeek,
    scheduleDayOfMonth = schedule.dayOfMonth,
    scheduleDateTime = schedule.dateTimeMillis,
    requiresWifi = requiresWifi,
    requiresCharging = requiresCharging,
    requiresBatteryNotLow = requiresBatteryNotLow,
    deleteEnabled = deleteEnabled,
    parallelism = parallelism,
    retryCount = retryCount,
    createdAt = createdAt,
    updatedAt = updatedAt,
    lastRunAt = lastRunAt,
    lastRunResult = lastRunResult,
)

internal fun SyncStateEntity.toModel(): SyncState = SyncState(
    jobId = jobId,
    path = path,
    localSize = localSize,
    localMtime = localMtime,
    remoteSize = remoteSize,
    remoteMtime = remoteMtime,
    lastSyncedAt = lastSyncedAt,
)

internal fun SyncState.toEntity(): SyncStateEntity = SyncStateEntity(
    jobId = jobId,
    path = path,
    localSize = localSize,
    localMtime = localMtime,
    remoteSize = remoteSize,
    remoteMtime = remoteMtime,
    lastSyncedAt = lastSyncedAt,
)

private fun decodeJsonStringArray(text: String): List<String> {
    if (text.isBlank()) return emptyList()
    return runCatching {
        val arr = JSONArray(text)
        List(arr.length()) { arr.optString(it, "") }.filter { it.isNotEmpty() }
    }.getOrDefault(emptyList())
}

private fun encodeJsonStringArray(items: List<String>): String =
    JSONArray(items).toString()
