package com.zerotoship.foldex.core.model

import java.util.Calendar

/** 定期同期のスケジュール種別。 */
enum class ScheduleType(val wireName: String) {
    /** n 分ごと (WorkManager の制約により最短 15 分)。0 = 手動のみ。 */
    INTERVAL("interval"),

    /** 毎日、指定時刻に。 */
    DAILY("daily"),

    /** 指定の曜日 (複数可)、指定時刻に。 */
    WEEKLY("weekly"),

    /** 毎月、指定日 (0 = 月末)、指定時刻に。 */
    MONTHLY("monthly"),

    /** 指定の日時に 1 回だけ。 */
    DATETIME("datetime"),
    ;

    companion object {
        fun fromWireName(value: String): ScheduleType = entries.firstOrNull { it.wireName == value } ?: INTERVAL
    }
}

/**
 * 同期ジョブのスケジュール設定。`ScheduleType` に応じて使うフィールドが変わる。
 *
 * 時刻はすべて端末のローカルタイムゾーン基準。次回実行時刻の算出は [nextFireTimeMillis]。
 */
data class SyncSchedule(
    val type: ScheduleType = ScheduleType.INTERVAL,
    /** INTERVAL: 実行間隔(分)。0 = 手動のみ。 */
    val intervalMinutes: Int = 0,
    /** DAILY/WEEKLY/MONTHLY: 0..1439 分 (= 時刻)。 */
    val timeOfDayMinutes: Int = 0,
    /** WEEKLY: ビットマスク (bit0=月, bit1=火, ... bit6=日)。 */
    val daysOfWeek: Int = 0,
    /** MONTHLY: 1..31。0 = 月末。月にその日がなければ月末扱い。 */
    val dayOfMonth: Int = 1,
    /** DATETIME: 実行 epoch ミリ秒 (1 回限り)。 */
    val dateTimeMillis: Long = 0L,
) {
    /** 定期実行されない (= 手動のみ) か。 */
    val isManualOnly: Boolean
        get() = type == ScheduleType.INTERVAL && intervalMinutes < MIN_INTERVAL_MINUTES

    /** WorkManager の PeriodicWorkRequest をそのまま使えるか (= 単純な n 分間隔)。 */
    val isSimpleInterval: Boolean
        get() = type == ScheduleType.INTERVAL && intervalMinutes >= MIN_INTERVAL_MINUTES

    /**
     * 実行後にまた次回をスケジュールし直す必要がある種別か (DATETIME は 1 回限りなので不要)。
     * INTERVAL も AlarmManager 方式で「次回時刻」を都度貼り直すため対象に含める。
     */
    val isRecurringOneShot: Boolean
        get() = type == ScheduleType.DAILY || type == ScheduleType.WEEKLY ||
            type == ScheduleType.MONTHLY || isSimpleInterval

    /**
     * [from] (epoch ms) より後で最初に実行すべき時刻 (epoch ms)。実行予定がなければ null。
     * INTERVAL は [from] + 間隔。手動のみ (間隔 < 最小値) は null。
     */
    fun nextFireTimeMillis(from: Long = System.currentTimeMillis()): Long? = when (type) {
        ScheduleType.INTERVAL ->
            if (intervalMinutes >= MIN_INTERVAL_MINUTES) from + intervalMinutes * 60_000L else null
        ScheduleType.DATETIME -> dateTimeMillis.takeIf { it > from }
        ScheduleType.DAILY -> nextWithTime(from) { true }
        ScheduleType.WEEKLY -> {
            if (daysOfWeek == 0) null
            else nextWithTime(from) { cal -> (daysOfWeek shr mondayIndex(cal)) and 1 == 1 }
        }
        ScheduleType.MONTHLY -> nextMonthly(from)
    }

    private fun nextWithTime(from: Long, dayMatches: (Calendar) -> Boolean): Long {
        val cal = Calendar.getInstance().apply {
            timeInMillis = from
            applyTimeOfDay(timeOfDayMinutes)
        }
        if (cal.timeInMillis <= from) cal.add(Calendar.DAY_OF_MONTH, 1)
        repeat(8) {
            if (cal.timeInMillis > from && dayMatches(cal)) return cal.timeInMillis
            cal.add(Calendar.DAY_OF_MONTH, 1)
        }
        return cal.timeInMillis
    }

    private fun nextMonthly(from: Long): Long {
        val cal = Calendar.getInstance().apply { timeInMillis = from }
        repeat(13) {
            val maxDay = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
            val targetDay = if (dayOfMonth <= 0 || dayOfMonth > maxDay) maxDay else dayOfMonth
            val candidate = (cal.clone() as Calendar).apply {
                set(Calendar.DAY_OF_MONTH, targetDay)
                applyTimeOfDay(timeOfDayMinutes)
            }
            if (candidate.timeInMillis > from) return candidate.timeInMillis
            cal.set(Calendar.DAY_OF_MONTH, 1)
            cal.add(Calendar.MONTH, 1)
        }
        return cal.timeInMillis
    }

    companion object {
        const val MIN_INTERVAL_MINUTES = 15

        /** 月曜=0 ... 日曜=6 のインデックス。 */
        private fun mondayIndex(cal: Calendar): Int = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7

        private fun Calendar.applyTimeOfDay(minutesOfDay: Int) {
            set(Calendar.HOUR_OF_DAY, (minutesOfDay / 60).coerceIn(0, 23))
            set(Calendar.MINUTE, (minutesOfDay % 60).coerceIn(0, 59))
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
    }
}
