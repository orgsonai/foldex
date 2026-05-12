package com.zerotoship.foldex.ui.sync

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zerotoship.foldex.core.data.repo.SyncBackupRepository
import com.zerotoship.foldex.core.data.repo.SyncJobRepository
import com.zerotoship.foldex.core.model.FileUri
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

data class SyncBackupUiState(
    val jobName: String = "",
    val generations: List<SyncBackupRepository.Generation> = emptyList(),
    val loading: Boolean = true,
)

@HiltViewModel
class SyncBackupViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val backupRepo: SyncBackupRepository,
    private val jobRepo: SyncJobRepository,
) : ViewModel() {

    private val jobId: String = savedStateHandle["id"] ?: ""

    private val _state = MutableStateFlow(SyncBackupUiState())
    val state: StateFlow<SyncBackupUiState> = _state.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    private var localRoot: File? = null

    init {
        viewModelScope.launch {
            val job = jobRepo.findById(jobId)
            localRoot = (job?.localUri?.let { FileUri.fromStorageStringOrNull(it) } as? FileUri.Local)
                ?.let { File(it.absolutePath) }
            _state.value = _state.value.copy(jobName = job?.name ?: "")
            refresh()
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true)
            _state.value = _state.value.copy(generations = backupRepo.generations(jobId), loading = false)
        }
    }

    fun restoreLocal(generationId: String) {
        val root = localRoot
        if (root == null) {
            viewModelScope.launch { _messages.send("ローカルのフォルダが特定できません") }
            return
        }
        viewModelScope.launch {
            var restored = 0
            var skipped = 0
            backupRepo.filesIn(jobId, generationId).filter { it.side == "local" }.forEach { f ->
                if (backupRepo.restoreLocalFile(jobId, generationId, f.relativePath, root)) restored++ else skipped++
            }
            _messages.send(
                if (skipped == 0) "${restored}件をローカルに復元しました"
                else "${restored}件を復元 (${skipped}件は既存のためスキップ)",
            )
            refresh()
        }
    }

    fun delete(generationId: String) {
        viewModelScope.launch { backupRepo.deleteGeneration(jobId, generationId); refresh() }
    }
}
