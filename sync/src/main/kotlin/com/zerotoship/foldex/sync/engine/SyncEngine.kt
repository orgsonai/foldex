package com.zerotoship.foldex.sync.engine

import com.zerotoship.foldex.core.common.Result
import com.zerotoship.foldex.core.data.repo.SyncJobRepository
import com.zerotoship.foldex.core.data.repo.SyncStateRepository
import com.zerotoship.foldex.core.model.FileUri
import com.zerotoship.foldex.core.model.StorageProvider
import com.zerotoship.foldex.core.model.SyncDirection
import com.zerotoship.foldex.core.model.SyncJob
import com.zerotoship.foldex.sync.model.SyncAction
import com.zerotoship.foldex.sync.model.SyncProgress
import com.zerotoship.foldex.sync.model.SyncResult
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 片方向同期の実行ファサード。UI / Worker からはこのクラスの [run] だけを呼べばよい。
 * 流れは仕様書 §8-A の通り: 列挙 (TreeWalker) → 差分 (DiffEngine) → 実行 (Executor) →
 * 後処理 (lastRun 更新)。
 *
 * [storage] には URI 種別で実装を振り分ける [StorageProvider] (app の StorageProviderRouter 等) を渡す。
 * 接続/切断のライフサイクルは呼び出し側に委ねる (共有プロバイダを勝手に切断しないため)。
 */
@Singleton
class SyncEngine @Inject constructor(
    private val jobRepository: SyncJobRepository,
    private val stateRepository: SyncStateRepository,
) {

    suspend fun run(
        job: SyncJob,
        storage: StorageProvider,
        onProgress: (SyncProgress) -> Unit = {},
    ): SyncResult {
        val startedAt = System.currentTimeMillis()
        val tracker = ProgressTracker(onProgress)
        tracker.enterScanning()

        return try {
            val localRoot = FileUri.fromStorageString(job.localUri)
            val remoteRoot = FileUri.fromStorageString(job.remoteUri)
            val filter = Filter(job.filter)
            val toRemote = job.direction == SyncDirection.TO_REMOTE

            val localTree = when (
                val r = TreeWalker(storage, localRoot, filter, treatMissingRootAsEmpty = !toRemote).walk()
            ) {
                is Result.Success -> r.value
                is Result.Failure -> return failed(job, startedAt, "ローカルの列挙に失敗: ${r.error.message}", tracker)
            }
            val remoteTree = when (
                val r = TreeWalker(storage, remoteRoot, filter, treatMissingRootAsEmpty = toRemote).walk()
            ) {
                is Result.Success -> r.value
                is Result.Failure -> return failed(job, startedAt, "リモートの列挙に失敗: ${r.error.message}", tracker)
            }

            val previous = stateRepository.snapshotForJob(job.id)
            val actions = DiffEngine().computeActions(
                DiffEngine.Input(
                    direction = job.direction,
                    conflictPolicy = job.conflictPolicy,
                    deleteEnabled = job.deleteEnabled,
                    local = localTree,
                    remote = remoteTree,
                    previous = previous,
                ),
            )

            tracker.planReady(
                actionCount = actions.count { it !is SyncAction.Skip },
                byteEstimate = actions.sumOf { a ->
                    when (a) {
                        is SyncAction.Upload -> a.size
                        is SyncAction.Download -> a.size
                        is SyncAction.Conflict -> if (toRemote) a.local.size else a.remote.size
                        else -> 0L
                    }
                },
            )

            val report = Executor(
                direction = job.direction,
                localProvider = storage,
                remoteProvider = storage,
                localRoot = localRoot,
                remoteRoot = remoteRoot,
                parallelism = job.parallelism,
                stateRepo = stateRepository,
                jobId = job.id,
                tracker = tracker,
            ).execute(actions, localTree, remoteTree)

            tracker.enterFinalizing()
            val finishedAt = System.currentTimeMillis()
            val result = SyncResult(
                jobId = job.id,
                outcome = if (report.failed == 0) SyncResult.Outcome.SUCCESS else SyncResult.Outcome.PARTIAL,
                uploaded = report.uploaded,
                downloaded = report.downloaded,
                deleted = report.deleted,
                conflicts = report.conflicts,
                skipped = report.skipped,
                failed = report.failed,
                transferredBytes = report.transferredBytes,
                startedAt = startedAt,
                finishedAt = finishedAt,
                errors = report.errors,
            )
            runCatching { jobRepository.updateLastRun(job.id, result.toSummaryLine(), finishedAt) }
            tracker.done()
            result
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed(job, startedAt, e.message ?: e.toString(), tracker)
        }
    }

    private suspend fun failed(job: SyncJob, startedAt: Long, message: String, tracker: ProgressTracker): SyncResult {
        val finishedAt = System.currentTimeMillis()
        val result = SyncResult.failedToScan(job.id, startedAt, finishedAt, message)
        runCatching { jobRepository.updateLastRun(job.id, result.toSummaryLine(), finishedAt) }
        tracker.done()
        return result
    }
}
