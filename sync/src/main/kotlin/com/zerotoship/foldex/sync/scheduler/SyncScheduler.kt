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
 * SyncJob を WorkManager の定期/単発実行へ橋渡しする。WorkManager の最小実行間隔は 15 分
 * (Android 標準制約) なので、それ未満や `intervalMinutes == 0` (手動のみ) のジョブは定期登録せず、
 * 明示的な [runNow] でのみ実行する — 仕様書 §8-K。
 */
@Singleton
class SyncScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val workManager: WorkManager get() = WorkManager.getInstance(context)

    /**
     * ジョブ設定を WorkManager に反映する。`enabled` かつ間隔が 15 分以上なら定期登録 (既存は更新)、
     * それ以外は定期登録を解除する。SyncJob を保存/更新した直後に呼ぶ想定。
     */
    fun apply(job: SyncJob) {
        if (job.enabled && job.intervalMinutes >= MIN_PERIODIC_MINUTES) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(job.intervalMinutes.toLong(), TimeUnit.MINUTES)
                .setConstraints(syncConstraints(job))
                .setInputData(workDataOf(SyncWorker.KEY_JOB_ID to job.id))
                .addTag(tagFor(job.id))
                .build()
            workManager.enqueueUniquePeriodicWork(periodicName(job.id), ExistingPeriodicWorkPolicy.UPDATE, request)
        } else {
            cancelPeriodic(job.id)
        }
    }

    /** いますぐ 1 回だけ同期する (手動同期)。既に走っていれば置き換える。 */
    fun runNow(job: SyncJob) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(syncConstraints(job))
            .setInputData(workDataOf(SyncWorker.KEY_JOB_ID to job.id))
            .addTag(tagFor(job.id))
            .build()
        workManager.enqueueUniqueWork(oneShotName(job.id), ExistingWorkPolicy.REPLACE, request)
    }

    /** ジョブに紐づく定期/単発の作業をすべて取り消す (ジョブ削除時など)。 */
    fun cancel(jobId: String) {
        workManager.cancelUniqueWork(periodicName(jobId))
        workManager.cancelUniqueWork(oneShotName(jobId))
    }

    private fun cancelPeriodic(jobId: String) = workManager.cancelUniqueWork(periodicName(jobId))

    private fun periodicName(jobId: String) = "sync-periodic-$jobId"
    private fun oneShotName(jobId: String) = "sync-now-$jobId"

    companion object {
        /** WorkManager の最小定期実行間隔 (分)。 */
        const val MIN_PERIODIC_MINUTES = 15
        private const val TAG_PREFIX = "sync-job:"
        fun tagFor(jobId: String) = "$TAG_PREFIX$jobId"
    }
}
