// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.sync.scheduler

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.zerotoship.foldex.core.data.notify.OpCompletionNotifier
import com.zerotoship.foldex.core.data.repo.SettingsRepository
import com.zerotoship.foldex.core.data.repo.SyncJobRepository
import com.zerotoship.foldex.core.model.StorageProvider
import com.zerotoship.foldex.sync.engine.SyncEngine
import com.zerotoship.foldex.sync.model.SyncProgress
import com.zerotoship.foldex.sync.model.SyncResult
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first

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
    private val scheduler: SyncScheduler,
    private val settingsRepo: SettingsRepository,
    private val opNotifier: OpCompletionNotifier,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val jobId = inputData.getString(KEY_JOB_ID) ?: return Result.failure()
        val job = jobRepository.findById(jobId) ?: return Result.failure()
        if (!job.enabled) return Result.success()

        // 長時間ワーカーとして前景化する。これにより画面OFF/Doze 中でも実行枠 (約10分の
        // 既定上限) で打ち切られず、大きな同期を最後まで走らせられる。
        // 端末制限で前景化が拒否される場合があるので runCatching で握りつぶす (通常ワーカーで継続)。
        runCatching { setForeground(createForegroundInfo()) }

        // 毎日/毎週/毎月 は OneTimeWork で次回を予約しているので、実行のたびに次回を再予約する。
        if (job.schedule.isRecurringOneShot) scheduler.scheduleNext(job)

        val result = syncEngine.run(job, storage) { progress ->
            setProgressAsync(progress.toData())
        }

        // 完了をシステム通知する (設定が ON のときだけ)。リトライ予定のときは「まだ終わって
        // いない」ので通知しない。最終結果 (成功 / リトライ尽きた失敗) のときだけ知らせる。
        val notifyOnDone = runCatching { settingsRepo.settings.first().notifyOnSyncComplete }.getOrDefault(true)
        return when (result.outcome) {
            SyncResult.Outcome.SUCCESS -> {
                if (notifyOnDone) {
                    opNotifier.notify("同期が完了しました", "「${job.name}」: ${result.toSummaryLine()}")
                }
                Result.success(result.toData())
            }
            SyncResult.Outcome.PARTIAL, SyncResult.Outcome.FAILED ->
                if (runAttemptCount < job.retryCount) {
                    Result.retry()
                } else {
                    if (notifyOnDone) {
                        opNotifier.notify("同期が完了しました (一部失敗)", "「${job.name}」: ${result.toSummaryLine()}")
                    }
                    Result.failure(result.toData())
                }
        }
    }

    /**
     * expedited ワーカーの前景化情報。Android 12 未満では expedited 実行に前景サービスを使うため
     * WorkManager がこの値を要求する (12 以降は JobScheduler の expedited job として走る)。
     */
    override suspend fun getForegroundInfo(): ForegroundInfo = createForegroundInfo()

    /** 長時間ワーカー用の前景通知 (IMPORTANCE_LOW = 無音)。Android 14+ は dataSync 型を明示。 */
    private fun createForegroundInfo(): ForegroundInfo {
        ensureChannel()
        val notif = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("Foldex 同期中")
            .setContentText("バックグラウンドで同期を実行しています")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(FOREGROUND_NOTIFICATION_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(FOREGROUND_NOTIFICATION_ID, notif)
        }
    }

    private fun ensureChannel() {
        val nm = applicationContext.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "同期",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "バックグラウンド同期の進行状態を表示します"
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
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

        private const val CHANNEL_ID = "foldex_sync"
        private const val FOREGROUND_NOTIFICATION_ID = 4344

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
