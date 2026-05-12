package com.zerotoship.foldex.ui.sync

import com.zerotoship.foldex.core.model.ConflictPolicy
import com.zerotoship.foldex.core.model.ScheduleType
import com.zerotoship.foldex.core.model.SyncDirection
import com.zerotoship.foldex.core.model.SyncSchedule
import java.text.SimpleDateFormat
import java.util.Locale

internal fun directionLabel(direction: SyncDirection): String = when (direction) {
    SyncDirection.TO_REMOTE -> "ローカル → リモート (アップロード)"
    SyncDirection.TO_LOCAL -> "リモート → ローカル (ダウンロード)"
    SyncDirection.BIDIRECTIONAL -> "ローカル ⇄ リモート (双方向)"
}

internal fun directionShortLabel(direction: SyncDirection): String = when (direction) {
    SyncDirection.TO_REMOTE -> "↑ アップロード"
    SyncDirection.TO_LOCAL -> "↓ ダウンロード"
    SyncDirection.BIDIRECTIONAL -> "⇄ 双方向"
}

internal fun conflictPolicyLabel(policy: ConflictPolicy): String = when (policy) {
    ConflictPolicy.NEWER_WINS -> "新しい方を採用 (推奨)"
    ConflictPolicy.LOCAL_WINS -> "ローカル優先"
    ConflictPolicy.REMOTE_WINS -> "リモート優先"
    ConflictPolicy.KEEP_BOTH -> "両方残す (片方をリネーム)"
    ConflictPolicy.SKIP -> "スキップして通知"
}

internal fun intervalLabel(minutes: Int): String = when {
    minutes <= 0 -> "手動のみ"
    minutes < 60 -> "${minutes}分ごと"
    minutes % 60 == 0 -> "${minutes / 60}時間ごと"
    else -> "${minutes}分ごと"
}

private fun timeLabel(minutesOfDay: Int): String =
    "%02d:%02d".format((minutesOfDay / 60).coerceIn(0, 23), (minutesOfDay % 60).coerceIn(0, 59))

private val WEEKDAY_LABELS = listOf("月", "火", "水", "木", "金", "土", "日")

internal fun scheduleLabel(s: SyncSchedule): String = when (s.type) {
    ScheduleType.INTERVAL -> intervalLabel(s.intervalMinutes)
    ScheduleType.DAILY -> "毎日 ${timeLabel(s.timeOfDayMinutes)}"
    ScheduleType.WEEKLY -> {
        val days = (0..6).filter { (s.daysOfWeek shr it) and 1 == 1 }.joinToString("") { WEEKDAY_LABELS[it] }
        "毎週 ${days.ifEmpty { "?" }} ${timeLabel(s.timeOfDayMinutes)}"
    }
    ScheduleType.MONTHLY -> {
        val d = if (s.dayOfMonth <= 0) "月末" else "${s.dayOfMonth}日"
        "毎月 $d ${timeLabel(s.timeOfDayMinutes)}"
    }
    ScheduleType.DATETIME -> {
        val fmt = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault())
        "日時指定 ${if (s.dateTimeMillis > 0) fmt.format(java.util.Date(s.dateTimeMillis)) else "未設定"}"
    }
}
