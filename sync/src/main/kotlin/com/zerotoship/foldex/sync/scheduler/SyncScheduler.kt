package com.zerotoship.foldex.sync.scheduler

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.zerotoship.foldex.core.model.SyncJob
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SyncJob のスケジュールを WorkManager へ橋渡しする — 仕様書 §8-K。
 *
 * - `INTERVAL` (>= 15 分): `PeriodicWorkRequest` で定期実行 (WorkManager 標準制約)。
 * - `DAILY` / `WEEKLY` / `MONTHLY` / `DATETIME`: 次回実行時刻を算出して `OneTimeWorkRequest` を
 *   `setInitialDelay` で予約する。実行後に [scheduleNext] を呼んで次回を再予約する
 *   (PeriodicWorkRequest では「毎日 2 時」のような時刻指定ができないため)。
 * - 手動のみ / 実行予定なし: 何も登録しない。[runNow] でのみ実行。
 */
@Singleton
class SyncScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val workManager: WorkManager get() = WorkManager.getInstance(context)

    /** ジョブ設定を WorkManager / AlarmManager に反映する (保存/更新の直後に呼ぶ)。 */
    fun apply(job: SyncJob) {
        // 種別が変わった可能性があるので、まず全ての登録を解除してから貼り直す。
        cancelPeriodic(job.id)
        cancelScheduled(job.id)
        cancelAlarm(job.id)
        if (!job.enabled) return
        if (job.schedule.isSimpleInterval) enqueuePeriodic(job) else scheduleNext(job)
    }

    /**
     * 次回実行を AlarmManager で予約する (DAILY/WEEKLY/MONTHLY/DATETIME)。
     *
     * 以前は WorkManager の `setInitialDelay` で予約していたが、遅延付き WorkManager は
     * Doze 中に次のメンテナンス枠まで延期され「定刻に動かない / アプリを開くまでキュー中」に
     * なるため、`setAndAllowWhileIdle` の標準アラームで起こす方式に変更した (特別権限なし)。
     * 発火は [SyncAlarmReceiver] が受け、そこで即時 WorkManager 実行をエンキューする。
     * DAILY/WEEKLY/MONTHLY の次回再予約は受信側 ([SyncAlarmReceiver]) と完了後の [SyncWorker] が行う。
     */
    fun scheduleNext(job: SyncJob) {
        if (!job.enabled) return
        val next = job.schedule.nextFireTimeMillis() ?: return
        val pi = alarmPendingIntent(job.id, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            ?: return
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        runCatching { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi) }
    }

    /** アラーム発火時の即時実行 (遅延なし)。制約はジョブ設定に従う。 */
    fun fireNow(job: SyncJob) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(syncConstraints(job))
            .setInputData(workDataOf(SyncWorker.KEY_JOB_ID to job.id))
            .addTag(tagFor(job.id))
            .build()
        workManager.enqueueUniqueWork(scheduledName(job.id), ExistingWorkPolicy.REPLACE, request)
    }

    private fun enqueuePeriodic(job: SyncJob) {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(
            job.schedule.intervalMinutes.toLong(), TimeUnit.MINUTES,
        )
            .setConstraints(syncConstraints(job))
            .setInputData(workDataOf(SyncWorker.KEY_JOB_ID to job.id))
            .addTag(tagFor(job.id))
            .build()
        workManager.enqueueUniquePeriodicWork(periodicName(job.id), ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    /**
     * 指定ジョブの「いまの実行状態」を観測する Flow。
     * WorkManager のタグ単位で各 WorkInfo を取り、優先度の高い RUNNING / ENQUEUED を抽出する。
     */
    fun observeStatus(jobId: String): kotlinx.coroutines.flow.Flow<JobRunStatus> {
        return workManager.getWorkInfosByTagFlow(tagFor(jobId)).let { src ->
            kotlinx.coroutines.flow.flow {
                src.collect { infos ->
                    val status = when {
                        infos.any { it.state == androidx.work.WorkInfo.State.RUNNING } -> JobRunStatus.RUNNING
                        infos.any { it.state == androidx.work.WorkInfo.State.ENQUEUED } -> JobRunStatus.ENQUEUED
                        else -> JobRunStatus.IDLE
                    }
                    emit(status)
                }
            }
        }
    }

    enum class JobRunStatus { IDLE, ENQUEUED, RUNNING }

    /** いますぐ 1 回だけ同期する (手動同期)。 */
    fun runNow(job: SyncJob) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(syncConstraints(job))
            .setInputData(workDataOf(SyncWorker.KEY_JOB_ID to job.id))
            .addTag(tagFor(job.id))
            .build()
        workManager.enqueueUniqueWork(oneShotName(job.id), ExistingWorkPolicy.REPLACE, request)
    }

    /** ジョブに紐づく定期/予約/単発/アラームをすべて取り消す (ジョブ削除時など)。 */
    fun cancel(jobId: String) {
        cancelPeriodic(jobId)
        cancelScheduled(jobId)
        cancelAlarm(jobId)
        workManager.cancelUniqueWork(oneShotName(jobId))
    }

    private fun cancelPeriodic(jobId: String) = workManager.cancelUniqueWork(periodicName(jobId))
    private fun cancelScheduled(jobId: String) = workManager.cancelUniqueWork(scheduledName(jobId))

    private fun cancelAlarm(jobId: String) {
        val pi = alarmPendingIntent(jobId, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE) ?: return
        context.getSystemService(AlarmManager::class.java)?.cancel(pi)
        pi.cancel()
    }

    /**
     * ジョブ固有のアラーム用 PendingIntent。data Uri をジョブごとに変えて識別を分ける
     * (extras は filterEquals の対象外のため)。FLAG_NO_CREATE 指定時は未登録なら null。
     */
    private fun alarmPendingIntent(jobId: String, flags: Int): PendingIntent? {
        val intent = Intent(context, SyncAlarmReceiver::class.java).apply {
            action = SyncAlarmReceiver.ACTION_FIRE
            data = Uri.parse("foldex://sync-alarm/$jobId")
            putExtra(SyncAlarmReceiver.EXTRA_JOB_ID, jobId)
        }
        return PendingIntent.getBroadcast(context, jobId.hashCode(), intent, flags)
    }

    private fun periodicName(jobId: String) = "sync-periodic-$jobId"
    private fun scheduledName(jobId: String) = "sync-scheduled-$jobId"
    private fun oneShotName(jobId: String) = "sync-now-$jobId"

    companion object {
        /** WorkManager の最小定期実行間隔 (分)。 */
        const val MIN_PERIODIC_MINUTES = 15
        private const val TAG_PREFIX = "sync-job:"
        fun tagFor(jobId: String) = "$TAG_PREFIX$jobId"
    }
}
