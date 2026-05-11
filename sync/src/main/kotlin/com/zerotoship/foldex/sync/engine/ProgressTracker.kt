package com.zerotoship.foldex.sync.engine

import com.zerotoship.foldex.sync.model.SyncProgress
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 同期進捗の集計。Executor が並列にアクションを処理するためカウンタはスレッドセーフ。
 * 状態が変わるたびに [listener] へ最新の [SyncProgress] を 1 つ流す。
 */
internal class ProgressTracker(
    private val listener: (SyncProgress) -> Unit,
) {

    @Volatile private var phase: SyncProgress.Phase = SyncProgress.Phase.SCANNING
    @Volatile private var totalActions: Int = 0
    @Volatile private var totalBytes: Long = 0
    @Volatile private var currentPath: String? = null
    private val completed = AtomicInteger(0)
    private val transferred = AtomicLong(0)

    fun snapshot(): SyncProgress = SyncProgress(
        phase = phase,
        totalActions = totalActions,
        completedActions = completed.get(),
        currentPath = currentPath,
        transferredBytes = transferred.get(),
        totalBytes = totalBytes,
    )

    private fun report() = listener(snapshot())

    fun enterScanning() {
        phase = SyncProgress.Phase.SCANNING
        report()
    }

    fun planReady(actionCount: Int, byteEstimate: Long) {
        phase = SyncProgress.Phase.EXECUTING
        totalActions = actionCount
        totalBytes = byteEstimate
        report()
    }

    fun actionStarted(path: String) {
        currentPath = path
        report()
    }

    fun addBytes(delta: Long) {
        transferred.addAndGet(delta)
    }

    fun actionCompleted() {
        completed.incrementAndGet()
        report()
    }

    fun enterFinalizing() {
        phase = SyncProgress.Phase.FINALIZING
        currentPath = null
        report()
    }

    fun done() {
        phase = SyncProgress.Phase.DONE
        currentPath = null
        report()
    }
}
