package com.zerotoship.foldex.ui.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zerotoship.foldex.core.data.repo.SettingsRepository
import com.zerotoship.foldex.core.data.repo.SyncJobRepository
import com.zerotoship.foldex.core.model.SyncJob
import com.zerotoship.foldex.sync.scheduler.SyncScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

@HiltViewModel
class SyncJobsViewModel @Inject constructor(
    private val repository: SyncJobRepository,
    private val scheduler: SyncScheduler,
    private val settingsRepo: SettingsRepository,
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
            watchQueueTimeout(job)
        }.onFailure { e ->
            _events.trySend(Event.Message("実行の登録に失敗: ${e.javaClass.simpleName}: ${e.message ?: ""}"))
        }
    }

    /**
     * キュー (ENQUEUED) のまま設定分数を超えても実行が始まらなかったジョブを自動で解除する。
     * 制約 (Wi-Fi 限定・要充電など) が満たされず滞留したジョブの掃除。設定で無効化できる。
     * 監視は画面/プロセスが生きている間だけ働く (タスクキル時は手動解除ボタンが受け皿)。
     */
    private fun watchQueueTimeout(job: SyncJob) = viewModelScope.launch {
        val settings = runCatching { settingsRepo.settings.first() }.getOrNull() ?: return@launch
        if (!settings.syncQueueTimeoutEnabled) return@launch
        val timeoutMs = settings.syncQueueTimeoutMinutes.coerceAtLeast(1) * 60_000L
        // タイムアウトまでに RUNNING へ遷移すれば監視終了。遷移しなければ null が返る。
        val started = withTimeoutOrNull(timeoutMs) {
            scheduler.observeStatus(job.id).first { it == SyncScheduler.JobRunStatus.RUNNING }
            true
        }
        if (started != null) return@launch
        // まだキュー待ちなら解除する (既に IDLE/RUNNING に変わっていたら何もしない)。
        val current = scheduler.observeStatus(job.id).first()
        if (current == SyncScheduler.JobRunStatus.ENQUEUED) {
            runCatching { scheduler.cancelRun(job.id) }
            _events.trySend(
                Event.Message(
                    "「${job.name}」はキューで${settings.syncQueueTimeoutMinutes}分待っても開始しなかったため解除しました",
                ),
            )
        }
    }

    /**
     * 実行中 / キュー待ちのジョブを手動で解除する。制約 (Wi-Fi 限定など) 未充足で
     * 「キュー中」のまま滞留したときの脱出口。定期予約自体は残す。
     */
    fun cancelRun(job: SyncJob) {
        runCatching {
            scheduler.cancelRun(job.id)
        }.onSuccess {
            _events.trySend(Event.Message("「${job.name}」の実行を解除しました"))
        }.onFailure { e ->
            _events.trySend(Event.Message("解除に失敗: ${e.javaClass.simpleName}: ${e.message ?: ""}"))
        }
    }

    fun setEnabled(job: SyncJob, enabled: Boolean) = viewModelScope.launch {
        val updated = job.copy(enabled = enabled)
        repository.upsert(updated)
        runCatching { scheduler.apply(updated) }.onFailure { e ->
            _events.trySend(Event.Message("スケジューラ更新に失敗: ${e.javaClass.simpleName}"))
        }
    }

    /** ドラッグ並び替えの確定保存。[orderedIds] の順序を永続化する。 */
    fun applyOrder(orderedIds: List<String>) = viewModelScope.launch {
        runCatching { repository.reorder(orderedIds) }
    }

    fun delete(job: SyncJob) = viewModelScope.launch {
        runCatching { scheduler.cancel(job.id) }
        repository.delete(job.id)
        _events.trySend(Event.Message("「${job.name}」を削除しました"))
    }
}
