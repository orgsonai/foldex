package com.zerotoship.foldex.ui.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zerotoship.foldex.core.data.repo.SyncJobRepository
import com.zerotoship.foldex.core.model.SyncJob
import com.zerotoship.foldex.sync.scheduler.SyncScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SyncJobsViewModel @Inject constructor(
    private val repository: SyncJobRepository,
    private val scheduler: SyncScheduler,
) : ViewModel() {

    data class UiState(val jobs: List<SyncJob> = emptyList())

    sealed interface Event {
        data class Message(val text: String) : Event
    }

    val state: StateFlow<UiState> = repository.observeAll()
        .map { UiState(jobs = it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    private val _events = Channel<Event>(Channel.BUFFERED)
    val events: Flow<Event> = _events.receiveAsFlow()

    fun runNow(job: SyncJob) {
        scheduler.runNow(job)
        _events.trySend(Event.Message("「${job.name}」を実行キューに入れました"))
    }

    fun setEnabled(job: SyncJob, enabled: Boolean) = viewModelScope.launch {
        val updated = job.copy(enabled = enabled)
        repository.upsert(updated)
        scheduler.apply(updated)
    }

    fun delete(job: SyncJob) = viewModelScope.launch {
        scheduler.cancel(job.id)
        repository.delete(job.id)
        _events.trySend(Event.Message("「${job.name}」を削除しました"))
    }
}
