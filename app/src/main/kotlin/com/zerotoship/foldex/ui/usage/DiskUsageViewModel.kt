package com.zerotoship.foldex.ui.usage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zerotoship.foldex.core.model.FileNode
import com.zerotoship.foldex.core.model.FileUri
import com.zerotoship.foldex.core.model.ListOptions
import com.zerotoship.foldex.core.model.NodeType
import com.zerotoship.foldex.core.model.StorageProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * 使用量分析 (gdu 風) の状態を持つ ViewModel。
 *
 * 指定フォルダの直下の各項目について、配下を再帰的に実測した合計バイト数を求め、大きい順に
 * 並べてバー付きで見せる。フォルダをタップすると中へ潜って同じ分析を続ける ([enter])。
 * 走査は [scanJob] にまとめ、潜る/戻る/閉じるのたびにキャンセルして貼り直す (リモートでも中断可能)。
 */
@HiltViewModel
class DiskUsageViewModel @Inject constructor(
    private val storage: StorageProvider,
) : ViewModel() {

    /** 1 項目 = 直下の子 [node] と、その配下を含む合計バイト数 [bytes]。 */
    data class Entry(val node: FileNode, val bytes: Long)

    data class UiState(
        val title: String = "",
        val pathLabel: String = "",
        val entries: List<Entry> = emptyList(),
        val totalBytes: Long = 0,
        /** 走査中か (スピナー + 進捗表示用)。 */
        val scanning: Boolean = false,
        /** 直下の項目のうち実測が済んだ数 / 全体数 (進捗バー用)。 */
        val scannedCount: Int = 0,
        val totalCount: Int = 0,
        val canGoUp: Boolean = false,
    )

    /** 潜ったフォルダのスタック (パンくず代わり)。末尾が現在地。 */
    private val stack = ArrayDeque<Pair<FileUri, String>>()
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()
    private var scanJob: Job? = null
    private var started = false

    /** 最初の対象フォルダで分析を開始する (Activity から 1 回だけ)。 */
    fun start(uriString: String, name: String) {
        if (started) return
        started = true
        val uri = FileUri.fromStorageStringOrNull(uriString) ?: return
        stack.addLast(uri to name)
        scan()
    }

    /** フォルダ項目をタップ → その中へ潜って分析を続ける。 */
    fun enter(entry: Entry) {
        if (entry.node.type != NodeType.DIRECTORY) return
        stack.addLast(entry.node.uri to entry.node.name)
        scan()
    }

    /** 1 階層戻る。最上位 (開始フォルダ) ならこれ以上戻れず false (Activity が終了する)。 */
    fun goUp(): Boolean {
        if (stack.size <= 1) return false
        stack.removeLast()
        scan()
        return true
    }

    /** 進行中の走査を中断する (大きいリモートフォルダ等)。 */
    fun cancelScan() {
        scanJob?.cancel()
        _state.value = _state.value.copy(scanning = false)
    }

    private fun scan() {
        scanJob?.cancel()
        val (uri, name) = stack.last()
        _state.value = UiState(
            title = name,
            pathLabel = uri.toStorageString(),
            scanning = true,
            canGoUp = stack.size > 1,
        )
        scanJob = viewModelScope.launch {
            val children = ArrayList<FileNode>()
            runCatching {
                storage.list(uri, ListOptions(showHidden = true)).collect { children.add(it) }
            }
            _state.value = _state.value.copy(totalCount = children.size)

            val entries = ArrayList<Entry>(children.size)
            var total = 0L
            withContext(Dispatchers.IO) {
                for ((i, child) in children.withIndex()) {
                    currentCoroutineContext().ensureActive()
                    val bytes = measure(child)
                    total += bytes
                    entries.add(Entry(child, bytes))
                    // 大きい順に並べ替えて逐次反映 (実測が進むたびに上位が確定していく)。
                    val sorted = entries.sortedByDescending { it.bytes }
                    val totalSoFar = total
                    val done = i + 1
                    _state.value = _state.value.copy(
                        entries = sorted,
                        totalBytes = totalSoFar,
                        scannedCount = done,
                    )
                }
            }
            _state.value = _state.value.copy(scanning = false)
        }
    }

    /** [node] 配下を再帰的に実測して合計バイト数を返す。失敗した枝は 0。 */
    private suspend fun measure(node: FileNode): Long = when (node.type) {
        NodeType.FILE -> node.size.coerceAtLeast(0L)
        NodeType.DIRECTORY -> {
            var sum = 0L
            runCatching {
                storage.list(node.uri, ListOptions(showHidden = true)).collect { child ->
                    currentCoroutineContext().ensureActive()
                    sum += measure(child)
                }
            }
            sum
        }
        else -> 0L
    }
}
