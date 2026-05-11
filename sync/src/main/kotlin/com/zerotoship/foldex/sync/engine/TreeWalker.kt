package com.zerotoship.foldex.sync.engine

import com.zerotoship.foldex.core.common.Result
import com.zerotoship.foldex.core.model.FileUri
import com.zerotoship.foldex.core.model.NodeType
import com.zerotoship.foldex.core.model.StorageError
import com.zerotoship.foldex.core.model.StorageProvider
import com.zerotoship.foldex.sync.model.SyncEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.toList

/**
 * [root] 以下を再帰的に列挙し、相対パス -> [SyncEntry] のマップを返す。
 * ディレクトリ自体はマップに含めず、ファイルのみ。[filter] で除外されたファイルは含めない。
 * ディレクトリは枝刈りせず常に潜る (exclude によるディレクトリ枝刈りは P7 で検討)。
 *
 * @param treatMissingRootAsEmpty `true` のとき root が存在しなくても空マップを返す
 *   (同期先がまだ無いケース)。`false` のとき root 不在は [StorageError.NotFound] を返す。
 */
internal class TreeWalker(
    private val provider: StorageProvider,
    private val root: FileUri,
    private val filter: Filter,
    private val treatMissingRootAsEmpty: Boolean,
) {

    suspend fun walk(): Result<Map<String, SyncEntry>, StorageError> {
        when (val stat = provider.stat(root)) {
            is Result.Failure -> {
                return if (stat.error is StorageError.NotFound && treatMissingRootAsEmpty) {
                    Result.Success(emptyMap())
                } else {
                    stat
                }
            }
            is Result.Success -> {
                if (!stat.value.isDirectory) {
                    return Result.Failure(
                        StorageError.IoError("同期ルートがディレクトリではありません: ${root.toStorageString()}"),
                    )
                }
            }
        }

        val out = LinkedHashMap<String, SyncEntry>()
        return try {
            collect(root, prefix = "", out = out)
            Result.Success(out)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.Failure(StorageError.IoError("列挙に失敗しました: ${root.toStorageString()}", e))
        }
    }

    private suspend fun collect(dirUri: FileUri, prefix: String, out: MutableMap<String, SyncEntry>) {
        val nodes = provider.list(dirUri).toList()
        for (node in nodes) {
            currentCoroutineContext().ensureActive()
            val relPath = if (prefix.isEmpty()) node.name else "$prefix/${node.name}"
            when (node.type) {
                NodeType.DIRECTORY -> collect(node.uri, relPath, out)
                NodeType.FILE -> {
                    if (filter.accepts(relPath, node.size)) {
                        out[relPath] = SyncEntry(
                            path = relPath,
                            size = node.size,
                            mtimeSeconds = node.lastModified?.epochSeconds ?: 0L,
                        )
                    }
                }
                NodeType.SYMLINK, NodeType.UNKNOWN -> Unit // 同期対象外
            }
        }
    }
}
