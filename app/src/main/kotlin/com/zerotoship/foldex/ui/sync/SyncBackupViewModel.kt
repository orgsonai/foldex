package com.zerotoship.foldex.ui.sync

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zerotoship.foldex.core.common.Result
import com.zerotoship.foldex.core.data.log.AppLogger
import com.zerotoship.foldex.core.data.repo.SyncBackupRepository
import com.zerotoship.foldex.core.data.repo.SyncJobRepository
import com.zerotoship.foldex.core.model.FileUri
import com.zerotoship.foldex.core.model.StorageProvider
import com.zerotoship.foldex.core.model.WriteMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

data class SyncBackupUiState(
    val jobName: String = "",
    val generations: List<GenerationView> = emptyList(),
    val loading: Boolean = true,
    /** 一括復元の事前確認 (衝突あり)。null = 待機なし。 */
    val pendingBatchRestore: PendingBatchRestore? = null,
    /** 世代の L または R をクリックして詳細を出す要求。null = 非表示。 */
    val pendingDetail: PendingDetail? = null,
)

data class PendingDetail(
    val generationId: String,
    val createdAt: Long,
    /** "local" / "remote"。 */
    val side: String,
    val files: List<SyncBackupRepository.BackupFile>,
)

/** 1 世代の表示用ビュー (ローカル/リモート件数も出す)。 */
data class GenerationView(
    val gen: SyncBackupRepository.Generation,
    val localCount: Int,
    val remoteCount: Int,
)

/** 一括復元前の上書き確認待ち。 */
data class PendingBatchRestore(
    val generationId: String,
    val totalFiles: Int,
    /** 衝突 (既に同じパスにファイルが存在する) のサンプル。表示用なので 20 件まで。 */
    val conflicts: List<String>,
    val totalConflicts: Int,
)

@HiltViewModel
class SyncBackupViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val backupRepo: SyncBackupRepository,
    private val jobRepo: SyncJobRepository,
    private val storage: StorageProvider,
    private val appLogger: AppLogger,
) : ViewModel() {

    private val jobId: String = savedStateHandle["id"] ?: ""

    private val _state = MutableStateFlow(SyncBackupUiState())
    val state: StateFlow<SyncBackupUiState> = _state.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    private var localRoot: File? = null
    private var remoteRoot: FileUri? = null

    init {
        viewModelScope.launch {
            val job = jobRepo.findById(jobId)
            val local = job?.localUri?.let { FileUri.fromStorageStringOrNull(it) }
            localRoot = (local as? FileUri.Local)?.let { File(it.absolutePath) }
            remoteRoot = job?.remoteUri?.let { FileUri.fromStorageStringOrNull(it) }
            _state.value = _state.value.copy(jobName = job?.name ?: "")
            refresh()
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true)
            val gens = backupRepo.generations(jobId)
            val views = withContext(Dispatchers.IO) {
                gens.map { g ->
                    val files = backupRepo.filesIn(jobId, g.id)
                    GenerationView(
                        gen = g,
                        localCount = files.count { it.side == "local" },
                        remoteCount = files.count { it.side == "remote" },
                    )
                }
            }
            _state.value = _state.value.copy(generations = views, loading = false)
        }
    }

    /**
     * 一括復元を要求 (衝突をチェック)。衝突があれば `pendingBatchRestore` に積み、
     * UI のダイアログ確認後に [confirmBatchRestore] が実体を書き戻す。衝突なしなら即実行。
     */
    fun requestBatchRestore(generationId: String) {
        viewModelScope.launch {
            val files = backupRepo.filesIn(jobId, generationId)
            if (files.isEmpty()) {
                _messages.send("このバックアップにはファイルがありません")
                return@launch
            }
            val conflicts = withContext(Dispatchers.IO) { detectConflicts(files) }
            if (conflicts.isEmpty()) {
                executeBatchRestore(generationId, overwrite = false)
            } else {
                _state.value = _state.value.copy(
                    pendingBatchRestore = PendingBatchRestore(
                        generationId = generationId,
                        totalFiles = files.size,
                        conflicts = conflicts.take(20),
                        totalConflicts = conflicts.size,
                    ),
                )
            }
        }
    }

    /** 上書き確認ダイアログでユーザーが選んだ結果に従って実行する (null = キャンセル)。 */
    fun confirmBatchRestore(overwrite: Boolean?) {
        val pending = _state.value.pendingBatchRestore ?: return
        _state.value = _state.value.copy(pendingBatchRestore = null)
        if (overwrite == null) return // cancel
        executeBatchRestore(pending.generationId, overwrite = overwrite)
    }

    private fun executeBatchRestore(generationId: String, overwrite: Boolean) {
        viewModelScope.launch {
            val files = backupRepo.filesIn(jobId, generationId)
            var restoredLocal = 0
            var restoredRemote = 0
            var skipped = 0
            var failed = 0
            for (f in files) {
                val ok = when (f.side) {
                    "local" -> restoreOneLocal(generationId, f, overwrite)
                    "remote" -> restoreOneRemote(generationId, f, overwrite)
                    else -> false
                }
                when {
                    ok && f.side == "local" -> restoredLocal++
                    ok && f.side == "remote" -> restoredRemote++
                    !ok -> {
                        // 上書き不要なら「既存のためスキップ」、それ以外は失敗
                        val exists = checkExists(f)
                        if (exists && !overwrite) skipped++ else failed++
                    }
                }
            }
            val msg = buildString {
                append("一括復元: L=").append(restoredLocal)
                append(" / R=").append(restoredRemote)
                if (skipped > 0) append(" (既存スキップ ").append(skipped).append(")")
                if (failed > 0) append(" 失敗 ").append(failed)
            }
            appLogger.info("SyncBackup", msg)
            _messages.send(msg)
            refresh()
        }
    }

    private suspend fun restoreOneLocal(
        generationId: String,
        f: SyncBackupRepository.BackupFile,
        overwrite: Boolean,
    ): Boolean {
        val root = localRoot ?: return false
        return backupRepo.restoreLocalFile(jobId, generationId, f.relativePath, root, overwrite)
    }

    private suspend fun restoreOneRemote(
        generationId: String,
        f: SyncBackupRepository.BackupFile,
        overwrite: Boolean,
    ): Boolean = withContext(Dispatchers.IO) {
        val root = remoteRoot ?: return@withContext false
        val target = childUri(root, f.relativePath) ?: return@withContext false
        // 既存チェック
        val exists = storage.stat(target) is Result.Success
        if (exists && !overwrite) return@withContext false
        val src = backupRepo.backupFile(jobId, generationId, "remote", f.relativePath)
        if (!src.isFile) return@withContext false
        runCatching {
            when (val r = storage.openOutput(target, WriteMode.OVERWRITE)) {
                is Result.Success -> r.value.use { out -> src.inputStream().use { it.copyTo(out) } }
                is Result.Failure -> error(r.error.message ?: "openOutput failed")
            }
            true
        }.getOrElse { false }
    }

    private suspend fun detectConflicts(files: List<SyncBackupRepository.BackupFile>): List<String> = withContext(Dispatchers.IO) {
        val conflicts = ArrayList<String>(8)
        for (f in files) {
            if (checkExists(f)) {
                conflicts.add("${if (f.side == "local") "L" else "R"}: ${f.relativePath}")
            }
        }
        conflicts
    }

    private suspend fun checkExists(f: SyncBackupRepository.BackupFile): Boolean {
        return when (f.side) {
            "local" -> {
                val root = localRoot ?: return false
                File(root, f.relativePath.trimStart('/')).exists()
            }
            "remote" -> {
                val root = remoteRoot ?: return false
                val u = childUri(root, f.relativePath) ?: return false
                storage.stat(u) is Result.Success
            }
            else -> false
        }
    }

    /** 親 URI 配下に相対パスを連結した子 URI を作る。Local / Remote / SAF を扱う。 */
    private fun childUri(parent: FileUri, relativePath: String): FileUri? {
        val rel = relativePath.trim('/')
        return when (parent) {
            is FileUri.Local -> FileUri.Local("${parent.absolutePath.trimEnd('/')}/$rel")
            is FileUri.Remote -> FileUri.Remote(parent.protocol, parent.connectionId, "${parent.path.trimEnd('/')}/$rel")
            // SAF はバッチ復元ではサポート外 (子 URI の解決が tree とドキュメント名の組合せで複雑)。
            is FileUri.Saf -> null
        }
    }

    fun delete(generationId: String) {
        viewModelScope.launch { backupRepo.deleteGeneration(jobId, generationId); refresh() }
    }

    /** 世代の L または R をクリック → その側のバックアップファイル一覧を取得して詳細ダイアログを開く。 */
    fun showSideDetail(view: GenerationView, side: String) {
        viewModelScope.launch {
            val files = backupRepo.filesIn(jobId, view.gen.id).filter { it.side == side }
            _state.value = _state.value.copy(
                pendingDetail = PendingDetail(
                    generationId = view.gen.id,
                    createdAt = view.gen.createdAt,
                    side = side,
                    files = files,
                ),
            )
        }
    }

    fun dismissDetail() {
        _state.value = _state.value.copy(pendingDetail = null)
    }
}
