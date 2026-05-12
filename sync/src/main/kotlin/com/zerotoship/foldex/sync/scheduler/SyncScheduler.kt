package com.zerotoship.foldex.sync.scheduler

import android.content.Context
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

    /** ジョブ設定を WorkManager に反映する (保存/更新の直後に呼ぶ)。 */
    fun apply(job: SyncJob) {
        // 種別が変わった可能性があるので、まず両方の登録を解除してから貼り直す。
        cancelPeriodic(job.id)
        cancelScheduled(job.id)
        if (!job.enabled) return
        if (job.schedule.isSimpleInterval) enqueuePeriodic(job) else scheduleNext(job)
    }

    /** 次回実行を予約する (DAILY/WEEKLY/MONTHLY は実行完了後の再予約にも使う)。 */
    fun scheduleNext(job: SyncJob) {
        if (!job.enabled) return
        val next = job.schedule.nextFireTimeMillis() ?: return
        val delayMs = (next - System.currentTimeMillis()).coerceAtLeast(0L)
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
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

    /** いますぐ 1 回だけ同期する (手動同期)。 */
    fun runNow(job: SyncJob) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(syncConstraints(job))
            .setInputData(workDataOf(SyncWorker.KEY_JOB_ID to job.id))
            .addTag(tagFor(job.id))
            .build()
        workManager.enqueueUniqueWork(oneShotName(job.id), ExistingWorkPolicy.REPLACE, request)
    }

    /** ジョブに紐づく定期/予約/単発の作業をすべて取り消す (ジョブ削除時など)。 */
    fun cancel(jobId: String) {
        cancelPeriodic(jobId)
        cancelScheduled(jobId)
        workManager.cancelUniqueWork(oneShotName(jobId))
    }

    private fun cancelPeriodic(jobId: String) = workManager.cancelUniqueWork(periodicName(jobId))
    private fun cancelScheduled(jobId: String) = workManager.cancelUniqueWork(scheduledName(jobId))

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
