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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
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

    data class UiState(
        val jobs: List<SyncJob> = emptyList(),
        /** 各ジョブ ID → 現在の状態 (実行中 / キュー中 / IDLE)。WorkManager から購読。 */
        val statuses: Map<String, SyncScheduler.JobRunStatus> = emptyMap(),
    )

    sealed interface Event {
        data class Message(val text: String) : Event
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val state: StateFlow<UiState> = repository.observeAll()
        .flatMapLatest { jobs ->
            if (jobs.isEmpty()) {
                flowOf(UiState(jobs = emptyList()))
            } else {
                // 各ジョブの状態 Flow を結合して、ジョブ一覧 + 状態マップを 1 つの UiState に。
                val statusFlows = jobs.map { job -> scheduler.observeStatus(job.id).map { job.id to it } }
                combine(statusFlows) { pairs ->
                    UiState(jobs = jobs, statuses = pairs.toMap())
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    private val _events = Channel<Event>(Channel.BUFFERED)
    val events: Flow<Event> = _events.receiveAsFlow()

    fun runNow(job: SyncJob) {
        // WorkManager の enqueue は通常成功するが、Android 14+ の FGS 制約や、
        // WorkManager / Hilt の初期化異常で同期的に例外を投げてプロセスを落とすケースがある。
        // ここで握って UI スレッドに伝搬させないことで、少なくとも「無音でアプリが閉じる」のは防ぐ。
        runCatching {
            scheduler.runNow(job)
        }.onSuccess {
            _events.trySend(Event.Message("「${job.name}」を実行キューに入れました"))
        }.onFailure { e ->
            _events.trySend(Event.Message("実行の登録に失敗: ${e.javaClass.simpleName}: ${e.message ?: ""}"))
        }
    }

    fun setEnabled(job: SyncJob, enabled: Boolean) = viewModelScope.launch {
        val updated = job.copy(enabled = enabled)
        repository.upsert(updated)
        runCatching { scheduler.apply(updated) }.onFailure { e ->
            _events.trySend(Event.Message("スケジューラ更新に失敗: ${e.javaClass.simpleName}"))
        }
    }

    fun delete(job: SyncJob) = viewModelScope.launch {
        runCatching { scheduler.cancel(job.id) }
        repository.delete(job.id)
        _events.trySend(Event.Message("「${job.name}」を削除しました"))
    }
}
