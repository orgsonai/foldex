// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.sync

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import com.zerotoship.foldex.R
import com.zerotoship.foldex.core.model.ConflictPolicy
import com.zerotoship.foldex.core.model.ScheduleType
import com.zerotoship.foldex.core.model.SyncDirection
import com.zerotoship.foldex.core.model.SyncJob
import com.zerotoship.foldex.core.model.SyncSchedule
import java.text.SimpleDateFormat
import java.util.Date

@Composable internal fun directionLabel(direction: SyncDirection): String = when (direction) {
    SyncDirection.TO_REMOTE -> stringResource(R.string.sync_direction_to_remote)
    SyncDirection.TO_LOCAL -> stringResource(R.string.sync_direction_to_local)
    SyncDirection.BIDIRECTIONAL -> stringResource(R.string.sync_direction_bidirectional)
}

@Composable internal fun directionShortLabel(direction: SyncDirection): String = when (direction) {
    SyncDirection.TO_REMOTE -> stringResource(R.string.sync_direction_to_remote_short)
    SyncDirection.TO_LOCAL -> stringResource(R.string.sync_direction_to_local_short)
    SyncDirection.BIDIRECTIONAL -> stringResource(R.string.sync_direction_bidirectional_short)
}

@Composable internal fun conflictPolicyLabel(policy: ConflictPolicy): String = when (policy) {
    ConflictPolicy.NEWER_WINS -> stringResource(R.string.sync_conflict_newer)
    ConflictPolicy.LOCAL_WINS -> stringResource(R.string.sync_conflict_local)
    ConflictPolicy.REMOTE_WINS -> stringResource(R.string.sync_conflict_remote)
    ConflictPolicy.KEEP_BOTH -> stringResource(R.string.sync_conflict_both)
    ConflictPolicy.SKIP -> stringResource(R.string.sync_conflict_skip)
}

@Composable internal fun intervalLabel(minutes: Int): String = when {
    minutes <= 0 -> stringResource(R.string.sync_manual_only)
    minutes < 60 -> pluralStringResource(R.plurals.sync_every_minutes, minutes, minutes)
    minutes % 60 == 0 -> pluralStringResource(R.plurals.sync_every_hours, minutes / 60, minutes / 60)
    else -> pluralStringResource(R.plurals.sync_every_minutes, minutes, minutes)
}

private fun timeLabel(minutesOfDay: Int): String =
    "%02d:%02d".format((minutesOfDay / 60).coerceIn(0, 23), (minutesOfDay % 60).coerceIn(0, 59))

/**
 * 次回実行予定の表示文字列 (なければ null)。
 * - 時刻指定 (毎日/毎週/毎月/日時): [SyncSchedule.nextFireTimeMillis] の結果。
 * - INTERVAL: 前回実行 + 間隔の目安 (WorkManager 制御なので「目安」表記)。
 * - 手動のみ / 無効: null。
 */
@Composable internal fun nextRunLabel(job: SyncJob): String? {
    if (!job.enabled) return null
    val s = job.schedule
    val fmt = SimpleDateFormat("MM/dd HH:mm", LocalResources.current.configuration.locales[0])
    return when {
        s.isManualOnly -> null
        s.type == ScheduleType.INTERVAL -> {
            val base = job.lastRunAt ?: System.currentTimeMillis()
            val next = base + s.intervalMinutes.toLong() * 60_000L
            stringResource(R.string.sync_estimate, fmt.format(Date(next)))
        }
        else -> s.nextFireTimeMillis()?.let { fmt.format(Date(it)) }
    }
}

/**
 * 前回実行の表示文字列。
 * - 未実行 (lastRunAt == null): "未実行"。
 * - 実行済み: 実行時刻 (MM/dd HH:mm) + 結果サマリ。時刻は [nextRunLabel] と同じ書式に揃える。
 */
@Composable internal fun lastRunLabel(job: SyncJob): String {
    val at = job.lastRunAt ?: return stringResource(R.string.sync_never_run)
    val time = SimpleDateFormat(
        "MM/dd HH:mm",
        LocalResources.current.configuration.locales[0],
    ).format(Date(at))
    return job.lastRunResult?.let { "$time ・ $it" } ?: time
}

@Composable internal fun scheduleLabel(s: SyncSchedule): String = when (s.type) {
    ScheduleType.INTERVAL -> intervalLabel(s.intervalMinutes)
    ScheduleType.DAILY -> stringResource(R.string.sync_schedule_daily, timeLabel(s.timeOfDayMinutes))
    ScheduleType.WEEKLY -> {
        val labels = stringArrayResource(R.array.sync_weekday_short)
        val days = (0..6).filter { (s.daysOfWeek shr it) and 1 == 1 }.joinToString(" ") { labels[it] }
        stringResource(R.string.sync_schedule_weekly, days.ifEmpty { "?" }, timeLabel(s.timeOfDayMinutes))
    }
    ScheduleType.MONTHLY -> {
        val d = if (s.dayOfMonth <= 0) stringResource(R.string.sync_month_end) else stringResource(R.string.sync_month_day, s.dayOfMonth)
        stringResource(R.string.sync_schedule_monthly, d, timeLabel(s.timeOfDayMinutes))
    }
    ScheduleType.DATETIME -> {
        val fmt = SimpleDateFormat(
            "yyyy/MM/dd HH:mm",
            LocalResources.current.configuration.locales[0],
        )
        stringResource(R.string.sync_schedule_datetime, if (s.dateTimeMillis > 0) fmt.format(java.util.Date(s.dateTimeMillis)) else stringResource(R.string.sync_not_set))
    }
}
