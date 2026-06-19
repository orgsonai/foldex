// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.core.data.repo

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Properties
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ローカルファイルのゴミ箱。削除されたファイル/フォルダをアプリ専用領域へ退避し、復元・完全削除・空にする等を行う。
 *
 * 退避先はユーザー領域と同じボリューム上の `Android/data/<pkg>/files/trash/` を優先する
 * (`renameTo` が高速に効くため)。リモート/SAF のファイルはゴミ箱対象外。
 */
@Singleton
class TrashRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val root: File
        get() = File(context.getExternalFilesDir(null) ?: context.filesDir, "trash").apply { mkdirs() }

    data class Entry(
        val id: String,
        val originalPath: String,
        val name: String,
        val deletedAt: Long,
        val isDirectory: Boolean,
        val sizeBytes: Long,
    )

    suspend fun moveToTrash(file: File): Boolean = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext false
        val id = "${System.currentTimeMillis()}-${file.absolutePath.hashCode().toUInt()}"
        val dir = File(root, id).apply { mkdirs() }
        val payload = File(dir, file.name)
        val moved = runCatching { file.renameTo(payload) }.getOrDefault(false) ||
            runCatching { file.copyRecursively(payload, overwrite = true) && file.deleteRecursively() }.getOrDefault(false)
        if (!moved) { dir.deleteRecursively(); return@withContext false }
        val size = payload.walkTopDown().filter { it.isFile }.map { it.length() }.sum()
        runCatching {
            Properties().apply {
                setProperty("originalPath", file.absolutePath)
                setProperty("name", file.name)
                setProperty("deletedAt", System.currentTimeMillis().toString())
                setProperty("isDirectory", payload.isDirectory.toString())
                setProperty("size", size.toString())
            }.let { props -> File(dir, META).outputStream().use { props.store(it, "Foldex trash") } }
        }
        true
    }

    suspend fun list(): List<Entry> = withContext(Dispatchers.IO) {
        root.listFiles()?.mapNotNull(::readEntry)?.sortedByDescending { it.deletedAt } ?: emptyList()
    }

    suspend fun restore(id: String): Boolean = withContext(Dispatchers.IO) {
        val dir = File(root, id)
        val entry = readEntry(dir) ?: return@withContext false
        val payload = File(dir, entry.name)
        val target = File(entry.originalPath)
        if (target.exists()) return@withContext false // 上書きはしない
        target.parentFile?.mkdirs()
        val ok = runCatching { payload.renameTo(target) }.getOrDefault(false) ||
            runCatching { payload.copyRecursively(target, overwrite = false) && payload.deleteRecursively() }.getOrDefault(false)
        if (ok) dir.deleteRecursively()
        ok
    }

    suspend fun deletePermanently(id: String) = withContext(Dispatchers.IO) {
        File(root, id).deleteRecursively(); Unit
    }

    suspend fun empty() = withContext(Dispatchers.IO) {
        root.listFiles()?.forEach { it.deleteRecursively() }; Unit
    }

    suspend fun currentSizeBytes(): Long = withContext(Dispatchers.IO) {
        root.walkTopDown().filter { it.isFile }.map { it.length() }.sum()
    }

    /** [days] 日より古いエントリを削除する。[days] <= 0 なら何もしない。 */
    suspend fun purgeOlderThan(days: Int) = withContext(Dispatchers.IO) {
        if (days <= 0) return@withContext
        val cutoff = System.currentTimeMillis() - days * 24L * 60 * 60 * 1000
        list().filter { it.deletedAt in 1 until cutoff }.forEach { File(root, it.id).deleteRecursively() }
    }

    private fun readEntry(dir: File): Entry? {
        if (!dir.isDirectory) return null
        val metaFile = File(dir, META)
        if (!metaFile.exists()) return null
        val props = runCatching { Properties().apply { metaFile.inputStream().use { load(it) } } }.getOrNull() ?: return null
        val originalPath = props.getProperty("originalPath") ?: return null
        return Entry(
            id = dir.name,
            originalPath = originalPath,
            name = props.getProperty("name") ?: dir.name,
            deletedAt = props.getProperty("deletedAt")?.toLongOrNull() ?: 0L,
            isDirectory = props.getProperty("isDirectory")?.toBoolean() ?: false,
            sizeBytes = props.getProperty("size")?.toLongOrNull() ?: 0L,
        )
    }

    private companion object {
        const val META = ".foldex_meta"
    }
}
