package com.zerotoship.foldex.core.data.repo

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * delete 同期で削除されるファイルを世代単位でバックアップする領域。
 *
 * レイアウト: `Android/data/<pkg>/files/sync-backup/<jobId>/<runTimestamp>/{local|remote}/<相対パス>`。
 * 1 回の同期実行が 1 世代。古い世代は [beginGeneration] のたびに指定件数まで剪定する。
 * リモート/SAF を含む全プロトコルの削除をローカル領域へ退避する (アプリ内に残す — 仕様書 §8-G の拡張)。
 */
@Singleton
class SyncBackupRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private fun jobDir(jobId: String): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "sync-backup/${safe(jobId)}").apply { mkdirs() }

    data class Generation(val id: String, val createdAt: Long, val sizeBytes: Long, val fileCount: Int)

    /** 新しい世代ディレクトリを作り、(自身を含めて) 新しい順に [keepGenerations] 件だけ残す。 */
    fun beginGeneration(jobId: String, keepGenerations: Int): File {
        val root = jobDir(jobId)
        val gen = File(root, System.currentTimeMillis().toString()).apply { mkdirs() }
        root.listFiles { f -> f.isDirectory }
            ?.sortedByDescending { it.name }
            ?.drop(keepGenerations.coerceAtLeast(1))
            ?.forEach { it.deleteRecursively() }
        return gen
    }

    /** 削除予定ファイルの内容を世代へ退避する。[side] は "local" / "remote"。 */
    fun backupContent(genDir: File, side: String, relativePath: String, content: InputStream) {
        val target = File(File(genDir, side), relativePath.trimStart('/'))
        target.parentFile?.mkdirs()
        content.use { ins -> target.outputStream().use { ins.copyTo(it) } }
    }

    /** 退避が 1 件も無かった世代を片付ける。 */
    fun pruneEmpty(genDir: File) {
        if (genDir.walkTopDown().none { it.isFile }) genDir.deleteRecursively()
    }

    suspend fun generations(jobId: String): List<Generation> = withContext(Dispatchers.IO) {
        jobDir(jobId).listFiles { f -> f.isDirectory }
            ?.sortedByDescending { it.name }
            ?.map { dir ->
                val files = dir.walkTopDown().filter { it.isFile }.toList()
                Generation(
                    id = dir.name,
                    createdAt = dir.name.toLongOrNull() ?: dir.lastModified(),
                    sizeBytes = files.sumOf { it.length() },
                    fileCount = files.size,
                )
            } ?: emptyList()
    }

    suspend fun currentSizeBytes(jobId: String): Long = withContext(Dispatchers.IO) {
        jobDir(jobId).walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    suspend fun clear(jobId: String) = withContext(Dispatchers.IO) {
        jobDir(jobId).listFiles()?.forEach { it.deleteRecursively() }; Unit
    }

    private fun safe(s: String) = s.replace(Regex("[^A-Za-z0-9._-]"), "_").ifEmpty { "job" }
}
