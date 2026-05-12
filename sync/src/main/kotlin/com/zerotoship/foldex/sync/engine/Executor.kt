package com.zerotoship.foldex.sync.engine

import com.zerotoship.foldex.core.common.Result
import com.zerotoship.foldex.core.data.repo.SyncBackupRepository
import com.zerotoship.foldex.core.data.repo.SyncStateRepository
import com.zerotoship.foldex.core.model.FileUri
import java.io.File
import com.zerotoship.foldex.core.model.StorageError
import com.zerotoship.foldex.core.model.StorageProvider
import com.zerotoship.foldex.core.model.SyncDirection
import com.zerotoship.foldex.core.model.SyncState
import com.zerotoship.foldex.core.model.WriteMode
import com.zerotoship.foldex.sync.model.ConflictResolution
import com.zerotoship.foldex.sync.model.ConflictSide
import com.zerotoship.foldex.sync.model.SyncAction
import com.zerotoship.foldex.sync.model.SyncEntry
import com.zerotoship.foldex.sync.model.SyncResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.IOException
import java.io.InputStream
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * DiffEngine が出した [SyncAction] を並列度 [parallelism] (1〜8) で実行する。
 * 転送が成功するたびに転送先を再 stat して [SyncStateRepository] に前回同期状態を書き戻す
 * — これが無いと次回 diff で「サーバが付け直した mtime」と前回値が食い違って毎回再転送になる。
 * 仕様書 §8-A / §8-I。
 *
 * 失敗した個別アクションは捕捉して [Report.errors] に積み、他のアクションは続行する
 * (1 ファイルの失敗で同期全体を止めない)。[CancellationException] だけは伝播させる。
 */
internal class Executor(
    private val direction: SyncDirection,
    private val localProvider: StorageProvider,
    private val remoteProvider: StorageProvider,
    private val localRoot: FileUri,
    private val remoteRoot: FileUri,
    private val parallelism: Int,
    private val stateRepo: SyncStateRepository,
    private val jobId: String,
    private val tracker: ProgressTracker,
    private val backup: BackupConfig? = null,
    private val now: () -> Long = System::currentTimeMillis,
) {

    /** delete 同期で削除前にファイルを退避する設定。null なら退避しない。 */
    class BackupConfig(
        val genDir: File,
        val thresholdBytes: Long,
        /** しきい値超過時にバックアップせず削除するか (ASK は呼び出し側で BACKUP に解決済み)。 */
        val skipOverThreshold: Boolean,
        val repo: SyncBackupRepository,
    )

    data class Report(
        val uploaded: Int,
        val downloaded: Int,
        val deleted: Int,
        val conflicts: Int,
        val skipped: Int,
        val failed: Int,
        val transferredBytes: Long,
        val errors: List<SyncResult.ActionError>,
    )

    suspend fun execute(
        actions: List<SyncAction>,
        currentLocal: Map<String, SyncEntry>,
        currentRemote: Map<String, SyncEntry>,
    ): Report {
        val work = actions.filterNot { it is SyncAction.Skip }
        val skipped = actions.size - work.size

        val uploaded = AtomicInteger(0)
        val downloaded = AtomicInteger(0)
        val deleted = AtomicInteger(0)
        val conflicts = AtomicInteger(0)
        val failed = AtomicInteger(0)
        val bytes = AtomicLong(0)
        val errors: MutableList<SyncResult.ActionError> = Collections.synchronizedList(mutableListOf())

        val semaphore = Semaphore(parallelism.coerceIn(1, 8))
        coroutineScope {
            work.map { action ->
                async {
                    semaphore.withPermit {
                        tracker.actionStarted(action.path)
                        try {
                            when (val done = runAction(action, currentLocal, currentRemote)) {
                                is Done.Transfer -> {
                                    bytes.addAndGet(done.bytes)
                                    when (done.kind) {
                                        TransferKind.UPLOAD -> uploaded.incrementAndGet()
                                        TransferKind.DOWNLOAD -> downloaded.incrementAndGet()
                                        TransferKind.CONFLICT -> conflicts.incrementAndGet()
                                    }
                                }
                                Done.Delete -> deleted.incrementAndGet()
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            failed.incrementAndGet()
                            errors.add(SyncResult.ActionError(action.path, e.message ?: e.toString()))
                        }
                        tracker.actionCompleted()
                    }
                }
            }.awaitAll()
        }

        return Report(
            uploaded = uploaded.get(),
            downloaded = downloaded.get(),
            deleted = deleted.get(),
            conflicts = conflicts.get(),
            skipped = skipped,
            failed = failed.get(),
            transferredBytes = bytes.get(),
            errors = errors.toList(),
        )
    }

    private enum class TransferKind { UPLOAD, DOWNLOAD, CONFLICT }

    private sealed interface Done {
        data class Transfer(val bytes: Long, val kind: TransferKind) : Done
        data object Delete : Done
    }

    private suspend fun runAction(
        action: SyncAction,
        currentLocal: Map<String, SyncEntry>,
        currentRemote: Map<String, SyncEntry>,
    ): Done = when (action) {
        is SyncAction.Upload -> Done.Transfer(
            bytes = transferAndRecord(
                path = action.path,
                source = localProvider, sourceRoot = localRoot, sourceEntry = currentLocal.getValue(action.path),
                dest = remoteProvider, destRoot = remoteRoot, destIsRemote = true,
            ),
            kind = TransferKind.UPLOAD,
        )

        is SyncAction.Download -> Done.Transfer(
            bytes = transferAndRecord(
                path = action.path,
                source = remoteProvider, sourceRoot = remoteRoot, sourceEntry = currentRemote.getValue(action.path),
                dest = localProvider, destRoot = localRoot, destIsRemote = false,
            ),
            kind = TransferKind.DOWNLOAD,
        )

        is SyncAction.DeleteRemote -> {
            backupBeforeDelete("remote", action.path)
            unwrap(remoteProvider.delete(childUri(remoteRoot, action.path), recursive = false))
            stateRepo.deletePath(jobId, action.path)
            Done.Delete
        }

        is SyncAction.DeleteLocal -> {
            backupBeforeDelete("local", action.path)
            unwrap(localProvider.delete(childUri(localRoot, action.path), recursive = false))
            stateRepo.deletePath(jobId, action.path)
            Done.Delete
        }

        is SyncAction.Conflict -> {
            // 片方向はジョブの方向、双方向は解決結果 (勝者) で「どちらをソースにするか」が決まる。
            val takeLocal = when (direction) {
                SyncDirection.TO_REMOTE -> true
                SyncDirection.TO_LOCAL -> false
                SyncDirection.BIDIRECTIONAL -> when (val res = action.resolution) {
                    ConflictResolution.TakeLocal -> true
                    ConflictResolution.TakeRemote -> false
                    // KeepBoth は敗者側をリネームする。renameSide=REMOTE なら local が勝者。
                    is ConflictResolution.KeepBoth -> res.renameSide == ConflictSide.REMOTE
                    ConflictResolution.Skip -> error("Skip resolution should not reach Executor")
                }
            }
            (action.resolution as? ConflictResolution.KeepBoth)?.let { keepBoth ->
                renameLosingSide(keepBoth, action.path)
            }
            val bytes = if (takeLocal) {
                transferAndRecord(
                    path = action.path,
                    source = localProvider, sourceRoot = localRoot, sourceEntry = action.local,
                    dest = remoteProvider, destRoot = remoteRoot, destIsRemote = true,
                )
            } else {
                transferAndRecord(
                    path = action.path,
                    source = remoteProvider, sourceRoot = remoteRoot, sourceEntry = action.remote,
                    dest = localProvider, destRoot = localRoot, destIsRemote = false,
                )
            }
            Done.Transfer(bytes, TransferKind.CONFLICT)
        }

        is SyncAction.Skip -> error("Skip actions are not executed")
    }

    /** 削除予定ファイルをバックアップ世代へ退避する (設定が有効なときのみ)。ディレクトリは対象外。 */
    private suspend fun backupBeforeDelete(side: String, relPath: String) {
        val b = backup ?: return
        val (provider, root) = if (side == "local") localProvider to localRoot else remoteProvider to remoteRoot
        val uri = childUri(root, relPath)
        val size = (provider.stat(uri) as? Result.Success)?.value?.size ?: 0L
        if (b.skipOverThreshold && size > b.thresholdBytes) return
        runCatching {
            when (val inp = provider.openInput(uri)) {
                is Result.Success -> b.repo.backupContent(b.genDir, side, relPath, inp.value)
                is Result.Failure -> Unit // ディレクトリ等は退避できないのでスキップ
            }
        }
    }

    private suspend fun renameLosingSide(keepBoth: ConflictResolution.KeepBoth, path: String) {
        val (provider, rootUri) = when (keepBoth.renameSide) {
            ConflictSide.LOCAL -> localProvider to localRoot
            ConflictSide.REMOTE -> remoteProvider to remoteRoot
        }
        ensureParentDir(provider, rootUri, keepBoth.renamedPath)
        unwrap(provider.rename(childUri(rootUri, path), childUri(rootUri, keepBoth.renamedPath)))
    }

    private suspend fun transferAndRecord(
        path: String,
        source: StorageProvider,
        sourceRoot: FileUri,
        sourceEntry: SyncEntry,
        dest: StorageProvider,
        destRoot: FileUri,
        destIsRemote: Boolean,
    ): Long {
        val destUri = childUri(destRoot, path)
        ensureParentDir(dest, destRoot, path)
        val transferred = copyStream(source.openInput(childUri(sourceRoot, path)), dest, destUri)

        val destEntry = when (val s = dest.stat(destUri)) {
            is Result.Success -> SyncEntry(path, s.value.size, s.value.lastModified?.epochSeconds ?: sourceEntry.mtimeSeconds)
            is Result.Failure -> sourceEntry.copy(path = path) // 再 stat 失敗時はソース値で代用
        }
        val state = if (destIsRemote) {
            SyncState(jobId, path, sourceEntry.size, sourceEntry.mtimeSeconds, destEntry.size, destEntry.mtimeSeconds, now())
        } else {
            SyncState(jobId, path, destEntry.size, destEntry.mtimeSeconds, sourceEntry.size, sourceEntry.mtimeSeconds, now())
        }
        stateRepo.upsert(state)
        return transferred
    }

    private suspend fun ensureParentDir(provider: StorageProvider, rootUri: FileUri, path: String) {
        val parent = parentRelativePath(path)
        if (parent.isEmpty()) return
        when (val r = provider.mkdir(childUri(rootUri, parent), recursive = true)) {
            is Result.Success -> Unit
            is Result.Failure -> if (r.error !is StorageError.AlreadyExists) unwrap(r)
        }
    }

    private suspend fun copyStream(
        input: Result<InputStream, StorageError>,
        dest: StorageProvider,
        destUri: FileUri,
    ): Long {
        val ins = unwrap(input)
        ins.use { source ->
            val outs = unwrap(dest.openOutput(destUri, WriteMode.OVERWRITE))
            outs.use { sink ->
                val buffer = ByteArray(COPY_BUFFER_SIZE)
                var total = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = source.read(buffer)
                    if (read < 0) break
                    sink.write(buffer, 0, read)
                    total += read
                    tracker.addBytes(read.toLong())
                }
                sink.flush()
                return total
            }
        }
    }

    private fun <T> unwrap(result: Result<T, StorageError>): T = when (result) {
        is Result.Success -> result.value
        is Result.Failure -> throw IOException(result.error.message, result.error.cause)
    }

    private companion object {
        const val COPY_BUFFER_SIZE = 64 * 1024
    }
}
