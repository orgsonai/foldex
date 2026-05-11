package com.zerotoship.foldex.sync.scheduler

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.zerotoship.foldex.core.data.repo.SyncJobRepository
import com.zerotoship.foldex.core.model.StorageProvider
import com.zerotoship.foldex.sync.engine.SyncEngine
import com.zerotoship.foldex.sync.model.SyncProgress
import com.zerotoship.foldex.sync.model.SyncResult
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * 1 件の SyncJob を実行する CoroutineWorker。SyncScheduler が定期/単発でエンキューする。
 *
 * 依存 ([SyncEngine] / [SyncJobRepository] / [StorageProvider]) は Hilt が注入する
 * (`@HiltWorker` — app 側で HiltWorkerFactory を設定済み)。[StorageProvider] は app が
 * StorageProviderRouter にバインドしているもの。仕様書 §8-A / §8-K。
 */
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val syncEngine: SyncEngine,
    private val jobRepository: SyncJobRepository,
    private val storage: StorageProvider,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val jobId = inputData.getString(KEY_JOB_ID) ?: return Result.failure()
        val job = jobRepository.findById(jobId) ?: return Result.failure()
        if (!job.enabled) return Result.success()

        val result = syncEngine.run(job, storage) { progress ->
            setProgressAsync(progress.toData())
        }

        return when (result.outcome) {
            SyncResult.Outcome.SUCCESS -> Result.success(result.toData())
            SyncResult.Outcome.PARTIAL, SyncResult.Outcome.FAILED ->
                if (runAttemptCount < job.retryCount) Result.retry() else Result.failure(result.toData())
        }
    }

    private fun SyncProgress.toData(): Data = workDataOf(
        KEY_PROGRESS_PHASE to phase.name,
        KEY_PROGRESS_TOTAL to totalActions,
        KEY_PROGRESS_DONE to completedActions,
        KEY_PROGRESS_PATH to (currentPath ?: ""),
        KEY_PROGRESS_BYTES to transferredBytes,
        KEY_PROGRESS_TOTAL_BYTES to totalBytes,
    )

    private fun SyncResult.toData(): Data = workDataOf(
        KEY_RESULT_OUTCOME to outcome.name,
        KEY_RESULT_SUMMARY to toSummaryLine(),
        KEY_RESULT_TRANSFERRED to transferredCount,
        KEY_RESULT_DELETED to deleted,
        KEY_RESULT_CONFLICTS to conflicts,
        KEY_RESULT_FAILED to failed,
    )

    companion object {
        const val KEY_JOB_ID = "jobId"

        const val KEY_PROGRESS_PHASE = "progress.phase"
        const val KEY_PROGRESS_TOTAL = "progress.totalActions"
        const val KEY_PROGRESS_DONE = "progress.completedActions"
        const val KEY_PROGRESS_PATH = "progress.currentPath"
        const val KEY_PROGRESS_BYTES = "progress.transferredBytes"
        const val KEY_PROGRESS_TOTAL_BYTES = "progress.totalBytes"

        const val KEY_RESULT_OUTCOME = "result.outcome"
        const val KEY_RESULT_SUMMARY = "result.summary"
        const val KEY_RESULT_TRANSFERRED = "result.transferred"
        const val KEY_RESULT_DELETED = "result.deleted"
        const val KEY_RESULT_CONFLICTS = "result.conflicts"
        const val KEY_RESULT_FAILED = "result.failed"
    }
}
