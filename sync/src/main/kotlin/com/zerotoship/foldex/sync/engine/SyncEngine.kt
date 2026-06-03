package com.zerotoship.foldex.sync.engine

import com.zerotoship.foldex.core.common.Result
import com.zerotoship.foldex.core.data.repo.SettingsRepository
import com.zerotoship.foldex.core.data.repo.SyncBackupRepository
import com.zerotoship.foldex.core.data.repo.SyncJobRepository
import com.zerotoship.foldex.core.data.repo.SyncStateRepository
import com.zerotoship.foldex.core.model.FileUri
import com.zerotoship.foldex.core.model.StorageProvider
import com.zerotoship.foldex.core.model.SyncBackupPolicy
import com.zerotoship.foldex.core.model.SyncDirection
import com.zerotoship.foldex.core.model.SyncJob
import com.zerotoship.foldex.sync.model.SkipReason
import com.zerotoship.foldex.sync.model.SyncAction
import com.zerotoship.foldex.sync.model.SyncProgress
import com.zerotoship.foldex.sync.model.SyncResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
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
    private val settingsRepository: SettingsRepository,
    private val backupRepository: SyncBackupRepository,
    private val appLogger: com.zerotoship.foldex.core.data.log.AppLogger,
) {

    suspend fun run(
        job: SyncJob,
        storage: StorageProvider,
        onProgress: (SyncProgress) -> Unit = {},
    ): SyncResult {
        val startedAt = System.currentTimeMillis()
        val tracker = ProgressTracker(onProgress)
        tracker.enterScanning()
        // 実行の境界をログに残す (詳細な per-file ログは Executor の onAction が出す)。
        appLogger.info("Sync(${job.name})", "── 同期を開始しました ──")

        return try {
            val localRoot = FileUri.fromStorageString(job.localUri)
            val remoteRoot = FileUri.fromStorageString(job.remoteUri)
            val filter = Filter(job.filter)
            val toRemote = job.direction == SyncDirection.TO_REMOTE
            val bidirectional = job.direction == SyncDirection.BIDIRECTIONAL

            val localTree = when (
                val r = TreeWalker(storage, localRoot, filter, treatMissingRootAsEmpty = !toRemote || bidirectional).walk()
            ) {
                is Result.Success -> r.value
                is Result.Failure -> return failed(job, startedAt, "ローカルの列挙に失敗: ${r.error.message}", tracker)
            }
            val remoteTree = when (
                val r = TreeWalker(storage, remoteRoot, filter, treatMissingRootAsEmpty = toRemote || bidirectional).walk()
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
                        is SyncAction.Conflict -> if (bidirectional) maxOf(a.local.size, a.remote.size)
                            else if (toRemote) a.local.size else a.remote.size
                        else -> 0L
                    }
                },
            )

            // delete 同期が有効で実際に削除アクションがあるなら、削除前バックアップの世代を用意する。
            val settings = runCatching { settingsRepository.settings.first() }.getOrNull()
            val hasDeletes = job.deleteEnabled &&
                actions.any { it is SyncAction.DeleteLocal || it is SyncAction.DeleteRemote }
            val backupConfig = if (hasDeletes && settings != null && settings.syncDeleteBackup) {
                val genDir = backupRepository.beginGeneration(job.id, settings.syncBackupGenerations)
                Executor.BackupConfig(
                    genDir = genDir,
                    thresholdBytes = settings.syncBackupThresholdMb.toLong() * 1024L * 1024L,
                    // バックグラウンド実行では ASK を確認できないので BACKUP に倒す。SKIP のみ「退避しない」。
                    skipOverThreshold = settings.syncBackupPolicyOverThreshold == SyncBackupPolicy.SKIP,
                    repo = backupRepository,
                )
            } else {
                null
            }

            val tag = "Sync(${job.name})"
            // 「なぜ転送されないか」を追えるよう、スキップの理由をログに残す。
            logSkipReasons(tag, actions)
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
                backup = backupConfig,
                // 各アクションを集約ログに記録 (詳細ログ → 実行ログ画面から確認可能)。
                onAction = { message, level ->
                    if (level == Executor.ActionLevel.ERROR) appLogger.error(tag, message)
                    else appLogger.info(tag, message)
                },
            ).execute(actions, localTree, remoteTree)
            backupConfig?.let { runCatching { backupRepository.pruneEmpty(it.genDir) } }

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
            // 実行サマリを集約ログに記録。個別のエラーは Executor.onAction で既に書き出し済み。
            appLogger.info(tag, result.toSummaryLine())
            tracker.done()
            result
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed(job, startedAt, e.message ?: e.toString(), tracker, e)
        }
    }

    /**
     * スキップされたアクションの理由をログに出す。
     * - 全体の内訳を 1 行 (例: `スキップ内訳: 同期済み 2199, 競合スキップ 1`)。
     * - UNCHANGED 以外は「なぜ送られないか」が重要なので 1 件ずつ (件数が多い時は打ち切る)。
     * UNCHANGED は通常運転で大量に出るため個別には出さない。
     */
    private fun logSkipReasons(tag: String, actions: List<SyncAction>) {
        val skips = actions.filterIsInstance<SyncAction.Skip>()
        if (skips.isEmpty()) return
        val byReason = skips.groupingBy { it.reason }.eachCount()
        appLogger.info(tag, "スキップ内訳: " + byReason.entries.joinToString(", ") { "${skipReasonLabel(it.key)} ${it.value}" })
        val notable = skips.filter { it.reason != SkipReason.UNCHANGED }
        notable.take(MAX_SKIP_LOG).forEach { appLogger.info(tag, "スキップ(${skipReasonLabel(it.reason)}): ${it.path}") }
        if (notable.size > MAX_SKIP_LOG) appLogger.info(tag, "スキップ(ほか ${notable.size - MAX_SKIP_LOG} 件は省略)")
    }

    private fun skipReasonLabel(reason: SkipReason): String = when (reason) {
        SkipReason.UNCHANGED -> "同期済み"
        SkipReason.DELETE_DISABLED -> "削除無効のため未削除"
        SkipReason.REMOTE_ONLY -> "リモートのみ(取り込まない)"
        SkipReason.LOCAL_ONLY -> "ローカルのみ(送らない)"
        SkipReason.CONFLICT_SKIPPED -> "競合スキップ"
    }

    private suspend fun failed(
        job: SyncJob,
        startedAt: Long,
        message: String,
        tracker: ProgressTracker,
        cause: Throwable? = null,
    ): SyncResult {
        val finishedAt = System.currentTimeMillis()
        val result = SyncResult.failedToScan(job.id, startedAt, finishedAt, message)
        runCatching { jobRepository.updateLastRun(job.id, result.toSummaryLine(), finishedAt) }
        appLogger.error("Sync(${job.name})", message, cause)
        tracker.done()
        return result
    }

    private companion object {
        /** スキップを個別ログに出す最大件数 (これを超えた分は件数だけ残す)。 */
        const val MAX_SKIP_LOG = 50
    }
}
