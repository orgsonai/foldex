package com.zerotoship.foldex.ui.sync

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zerotoship.foldex.core.data.repo.ConnectionRepository
import com.zerotoship.foldex.core.data.repo.SyncJobRepository
import com.zerotoship.foldex.core.model.Connection
import com.zerotoship.foldex.core.model.ConflictPolicy
import com.zerotoship.foldex.core.model.FileUri
import com.zerotoship.foldex.core.model.SyncDirection
import com.zerotoship.foldex.core.model.SyncFilter
import com.zerotoship.foldex.core.model.SyncJob
import com.zerotoship.foldex.sync.scheduler.SyncScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class SyncJobEditViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val jobRepository: SyncJobRepository,
    private val connectionRepository: ConnectionRepository,
    private val scheduler: SyncScheduler,
) : ViewModel() {

    private val targetId: String? = savedStateHandle["id"]
    private var createdAt: Long = 0L

    val connections: StateFlow<List<Connection>> = connectionRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _state = MutableStateFlow(SyncJobEditState.empty(isNew = targetId == null))
    val state: StateFlow<SyncJobEditState> = _state.asStateFlow()

    private val _events = Channel<SyncJobEditEvent>(Channel.BUFFERED)
    val events: Flow<SyncJobEditEvent> = _events.receiveAsFlow()

    init {
        if (targetId != null) {
            viewModelScope.launch {
                val job = jobRepository.findById(targetId) ?: return@launch
                createdAt = job.createdAt
                _state.value = SyncJobEditState.fromJob(job)
            }
        }
    }

    fun update(transform: (SyncJobEditState) -> SyncJobEditState) {
        _state.value = transform(_state.value)
    }

    fun save() = viewModelScope.launch {
        val s = _state.value
        val error = s.validate()
        if (error != null) {
            _events.trySend(SyncJobEditEvent.Message(error))
            return@launch
        }
        val connection = connections.value.firstOrNull { it.id == s.connectionId }
        if (connection == null) {
            _events.trySend(SyncJobEditEvent.Message("選択した接続が見つかりません"))
            return@launch
        }
        val job = s.toJob(connection, createdAt)
        jobRepository.upsert(job)
        // upsert で createdAt/updatedAt が確定するので、スケジューラには保存後の値を渡す
        val saved = jobRepository.findById(job.id) ?: job
        scheduler.apply(saved)
        _events.trySend(SyncJobEditEvent.Saved)
    }
}

sealed interface SyncJobEditEvent {
    data object Saved : SyncJobEditEvent
    data class Message(val text: String) : SyncJobEditEvent
}

data class SyncJobEditState(
    val id: String,
    val isNew: Boolean,
    val name: String,
    val enabled: Boolean,
    val direction: SyncDirection,
    val localPath: String,
    val connectionId: String?,
    val remotePath: String,
    val conflictPolicy: ConflictPolicy,
    val intervalMinutes: Int,
    val requiresWifi: Boolean,
    val requiresCharging: Boolean,
    val requiresBatteryNotLow: Boolean,
    val deleteEnabled: Boolean,
    val includePatternsText: String,
    val excludePatternsText: String,
    val maxFileSizeMb: String,
) {
    fun validate(): String? = when {
        name.isBlank() -> "名前を入力してください"
        localPath.isBlank() -> "ローカルのパスを入力してください"
        connectionId.isNullOrBlank() -> "リモートの接続を選択してください"
        intervalMinutes < 0 -> "間隔は 0 以上にしてください"
        intervalMinutes in 1..14 -> "定期実行の間隔は 0 (手動のみ) か 15 分以上にしてください"
        maxFileSizeMb.isNotBlank() && (maxFileSizeMb.trim().toLongOrNull()?.let { it <= 0 } != false) ->
            "最大ファイルサイズには正の数を入力してください"
        else -> null
    }

    fun toJob(connection: Connection, existingCreatedAt: Long): SyncJob = SyncJob(
        id = id,
        name = name.trim(),
        enabled = enabled,
        localUri = FileUri.Local(localPath.trim()).toStorageString(),
        remoteUri = FileUri.Remote(connection.protocol, connection.id, remotePath.trim()).toStorageString(),
        direction = direction,
        conflictPolicy = conflictPolicy,
        filter = SyncFilter(
            includePatterns = parseGlobLines(includePatternsText),
            excludePatterns = parseGlobLines(excludePatternsText),
            maxFileSize = maxFileSizeMb.trim().toLongOrNull()?.takeIf { it > 0 }?.let { it * 1024L * 1024L },
        ),
        intervalMinutes = intervalMinutes,
        requiresWifi = requiresWifi,
        requiresCharging = requiresCharging,
        requiresBatteryNotLow = requiresBatteryNotLow,
        deleteEnabled = deleteEnabled,
        createdAt = existingCreatedAt,
        updatedAt = 0L,
    )

    companion object {
        fun empty(isNew: Boolean) = SyncJobEditState(
            id = UUID.randomUUID().toString(),
            isNew = isNew,
            name = "",
            enabled = true,
            direction = SyncDirection.TO_REMOTE,
            localPath = "/storage/emulated/0",
            connectionId = null,
            remotePath = "",
            conflictPolicy = ConflictPolicy.NEWER_WINS,
            intervalMinutes = 0,
            requiresWifi = true,
            requiresCharging = false,
            requiresBatteryNotLow = true,
            deleteEnabled = false,
            includePatternsText = "",
            excludePatternsText = "",
            maxFileSizeMb = "",
        )

        fun fromJob(job: SyncJob): SyncJobEditState {
            val local = (FileUri.fromStorageStringOrNull(job.localUri) as? FileUri.Local)
            val remote = (FileUri.fromStorageStringOrNull(job.remoteUri) as? FileUri.Remote)
            return SyncJobEditState(
                id = job.id,
                isNew = false,
                name = job.name,
                enabled = job.enabled,
                direction = job.direction,
                localPath = local?.absolutePath ?: job.localUri,
                connectionId = remote?.connectionId,
                remotePath = remote?.path ?: "",
                conflictPolicy = job.conflictPolicy,
                intervalMinutes = job.intervalMinutes,
                requiresWifi = job.requiresWifi,
                requiresCharging = job.requiresCharging,
                requiresBatteryNotLow = job.requiresBatteryNotLow,
                deleteEnabled = job.deleteEnabled,
                includePatternsText = job.filter.includePatterns.joinToString("\n"),
                excludePatternsText = job.filter.excludePatterns.joinToString("\n"),
                maxFileSizeMb = job.filter.maxFileSize?.let { (it / (1024L * 1024L)).toString() } ?: "",
            )
        }

        private fun parseGlobLines(text: String): List<String> =
            text.lines().map { it.trim() }.filter { it.isNotEmpty() }
    }
}
