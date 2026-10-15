// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.videoedit.engine

import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.StatFs
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.media3.common.util.UnstableApi
import com.zerotoship.foldex.core.common.Result
import com.zerotoship.foldex.ui.imageedit.engine.FileFingerprint
import com.zerotoship.foldex.ui.imageedit.formatBytes
import com.zerotoship.foldex.ui.videoedit.model.ExportEstimate
import com.zerotoship.foldex.ui.videoedit.model.ExportSettings
import com.zerotoship.foldex.ui.videoedit.model.VideoExportError
import com.zerotoship.foldex.ui.videoedit.model.VideoExportFailure
import com.zerotoship.foldex.ui.videoedit.model.VideoProject
import com.zerotoship.foldex.ui.videoedit.model.VideoSaveNaming
import com.zerotoship.foldex.ui.videoedit.plan.ExportCheck
import com.zerotoship.foldex.ui.videoedit.plan.OutputFacts
import com.zerotoship.foldex.ui.videoedit.plan.PlanNotice
import com.zerotoship.foldex.ui.videoedit.plan.RenderPlan
import com.zerotoship.foldex.ui.videoedit.plan.RenderPlanner
import com.zerotoship.foldex.ui.videoedit.plan.UnmetCondition
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID

/**
 * 書き出しの流れ全体 (設計書 §8-1)。
 *
 * 計画 → 素材の確認 → 空き容量 → Transformer で一時ファイルへ → 出力の検証 → (容量指定なら書き出し直し)
 * → 素材の再確認 → 保存先へ配置。素材のファイルには一切書き込まない。
 * どこで止まっても一時ファイルは消し、完成していない出力を保存先に残さない。
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal class VideoExportCoordinator(private val context: Context) {

    private val runner = TransformerRunner(context)
    private val journal = VideoExportJournal(File(context.filesDir, JOURNAL_PATH))

    suspend fun export(
        project: VideoProject,
        settings: ExportSettings,
        destination: ExportDestination,
        onProgress: (ExportProgress) -> Unit,
    ): VideoExportOutcome {
        val plan = when (val planned = RenderPlanner.plan(project, settings)) {
            is Result.Success -> planned.value
            is Result.Failure -> return VideoExportOutcome.Failed(planned.error)
        }
        if (!GATE.tryLock()) return failed(VideoExportFailure.BUSY)
        try {
            return exportLocked(project, settings, plan, destination, onProgress)
        } finally {
            GATE.unlock()
        }
    }

    private suspend fun exportLocked(
        project: VideoProject,
        settings: ExportSettings,
        plan: RenderPlan,
        destination: ExportDestination,
        onProgress: (ExportProgress) -> Unit,
    ): VideoExportOutcome {
        onProgress(ExportProgress(ExportStage.CHECKING))
        withContext(Dispatchers.IO) { checkSources(project, plan) }?.let { return VideoExportOutcome.Failed(it) }
        val workDirectory = when (destination) {
            is ExportDestination.LocalFolder -> destination.directory
            is ExportDestination.Document -> File(context.cacheDir, CACHE_DIRECTORY)
        }
        val estimatedBytes = ExportEstimate.estimate(settings, plan.durationUs)?.estimatedBytes ?: 0L
        withContext(Dispatchers.IO) { checkSpace(workDirectory, estimatedBytes) }?.let { return VideoExportOutcome.Failed(it) }

        val temp = File(workDirectory, "$TEMP_PREFIX${UUID.randomUUID()}$TEMP_SUFFIX")
        try {
            withContext(Dispatchers.IO) { journal.begin(temp) }
            val facts = when (val encoded = encode(plan, temp, onProgress)) {
                is Result.Success -> encoded.value
                is Result.Failure -> return VideoExportOutcome.Failed(encoded.error)
            }
            // 時間のかかる書き出しの間に素材が差し替えられていたら、違う内容の出力を保存しない。
            withContext(Dispatchers.IO) { checkSources(project, plan) }?.let { return VideoExportOutcome.Failed(it) }
            onProgress(ExportProgress(ExportStage.SAVING))
            val unmet = ExportCheck.unmet(plan.output, plan.durationUs, facts)
            val checkCancelled = cancellationCheck()
            return withContext(Dispatchers.IO) {
                when (destination) {
                    is ExportDestination.LocalFolder -> placeLocal(temp, destination, plan.output.audioOnly, unmet, plan.notices)
                    is ExportDestination.Document -> placeDocument(temp, destination.uri, unmet, plan.notices, checkCancelled)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            return failed(VideoExportFailure.WRITE_FAILED)
        } catch (_: Exception) {
            return failed(VideoExportFailure.UNKNOWN)
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                temp.delete()
                journal.clear()
            }
        }
    }

    /** 書き出して検証する。容量指定で目標を超えたら、ビットレートを下げて最大 2 回まで書き出し直す。 */
    private suspend fun encode(
        plan: RenderPlan,
        temp: File,
        onProgress: (ExportProgress) -> Unit,
    ): Result<OutputFacts, VideoExportError> {
        var videoKbps = plan.output.videoKbps
        var attempt = 1
        while (true) {
            withContext(Dispatchers.IO) { temp.delete() }
            val composition = withContext(Dispatchers.Default) { CompositionAdapter.build(plan) }
            onProgress(ExportProgress(ExportStage.ENCODING, null, attempt))
            val currentAttempt = attempt
            val outcome = runner.run(composition, temp.absolutePath, plan.output, videoKbps) { percent ->
                onProgress(ExportProgress(ExportStage.ENCODING, percent, currentAttempt))
            }
            if (outcome is TransformerRunner.Outcome.Failed) {
                return Result.Failure(VideoExportError(VideoExportFailure.fromMedia3ErrorCode(outcome.errorCode), code = outcome.codeName))
            }
            onProgress(ExportProgress(ExportStage.VERIFYING, null, attempt))
            val facts = withContext(Dispatchers.IO) { verify(plan, temp) }
                ?: return Result.Failure(VideoExportError(VideoExportFailure.INVALID_OUTPUT))
            val target = plan.output.targetBytes
            if (target == null || facts.sizeBytes <= target || attempt >= ExportCheck.MAX_TARGET_ATTEMPTS) {
                return Result.Success(facts)
            }
            val next = ExportCheck.retryVideoKbps(videoKbps, target, facts.sizeBytes)
            // これ以上下げられなければ、超えた結果を「条件未達」として見せる。
            if (next < RenderPlanner.MIN_VIDEO_KBPS) return Result.Success(facts)
            videoKbps = next
            attempt++
        }
    }

    /** 壊れた出力なら null。 */
    private fun verify(plan: RenderPlan, temp: File): OutputFacts? {
        val facts = try {
            OutputInspector.inspect(temp)
        } catch (_: Exception) {
            return null
        }
        if (ExportCheck.isBroken(plan.output, facts)) return null
        if (!plan.output.audioOnly && !OutputInspector.framesDecodable(temp, facts.durationUs)) return null
        return facts
    }

    private fun checkSources(project: VideoProject, plan: RenderPlan): VideoExportError? {
        for (media in plan.media) {
            val asset = project.assets[media.assetId] ?: return VideoExportError(VideoExportFailure.SOURCE_MISSING, media.name)
            val current = SourceStamps.read(context, asset.source)
                ?: return VideoExportError(VideoExportFailure.SOURCE_MISSING, asset.name)
            val recorded = asset.stamp
            if (recorded != null && !recorded.matches(current)) {
                return VideoExportError(VideoExportFailure.SOURCE_CHANGED, asset.name)
            }
        }
        return null
    }

    private fun checkSpace(directory: File, estimatedBytes: Long): VideoExportError? {
        if (!directory.isDirectory && !directory.mkdirs()) return VideoExportError(VideoExportFailure.WRITE_FAILED)
        if (!directory.canWrite()) return VideoExportError(VideoExportFailure.WRITE_FAILED)
        val available = runCatching { StatFs(directory.path).availableBytes }.getOrDefault(Long.MAX_VALUE)
        val required = ExportCheck.requiredFreeBytes(estimatedBytes)
        return if (available < required) VideoExportError(VideoExportFailure.NOT_ENOUGH_SPACE, formatBytes(required)) else null
    }

    private fun placeLocal(
        temp: File,
        destination: ExportDestination.LocalFolder,
        audioOnly: Boolean,
        unmet: List<UnmetCondition>,
        notices: Set<PlanNotice>,
    ): VideoExportOutcome {
        // Transformer は close するだけで同期しない。名前を確定する前に内容をディスクへ書き切らせる。
        FileChannel.open(temp.toPath(), StandardOpenOption.WRITE).use { it.force(true) }
        val extension = VideoSaveNaming.extension(audioOnly)
        for (number in 1..MAX_NAME_ATTEMPTS) {
            val target = File(destination.directory, VideoSaveNaming.candidate(destination.baseName, extension, number))
            try {
                Files.createFile(target.toPath())
            } catch (_: FileAlreadyExistsException) {
                continue
            }
            try {
                // 空の枠を排他的に確保してから、同じフォルダ内の改名で置き換える。数 GB でもコピーしない。
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (error: Exception) {
                // 自分で確保した空の枠だけを消す。
                if (target.length() == 0L) target.delete()
                throw error
            }
            MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), null, null)
            return VideoExportOutcome.Saved(target.name, target.absolutePath, target.length(), unmet, notices)
        }
        return failed(VideoExportFailure.DESTINATION_EXISTS)
    }

    private fun placeDocument(
        temp: File,
        uri: Uri,
        unmet: List<UnmetCondition>,
        notices: Set<PlanNotice>,
        checkCancelled: () -> Unit,
    ): VideoExportOutcome {
        val resolver = context.contentResolver
        try {
            val expected = FileFingerprint.read(temp, checkCancelled)
            val descriptor = resolver.openFileDescriptor(uri, "w") ?: return discardDocument(uri)
            ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { output ->
                temp.inputStream().use { input ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    while (true) {
                        checkCancelled()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                }
                output.flush()
                // パイプで受け取る Provider は同期できない。その場合は下の読み戻しで確かめる。
                runCatching { output.fd.sync() }
            }
            // close で送信が完了する Provider もあるので、閉じてから読み戻して照合する。
            val actual = resolver.openInputStream(uri)?.use { FileFingerprint.read(it, checkCancelled) }
            if (actual != expected) return discardDocument(uri)
            return VideoExportOutcome.Saved(displayName(uri), null, expected.size, unmet, notices)
        } catch (cancelled: CancellationException) {
            runCatching { DocumentsContract.deleteDocument(resolver, uri) }
            throw cancelled
        } catch (_: Exception) {
            return discardDocument(uri)
        }
    }

    /** 中身の確かでない文書を残さない。この文書は書き出しのために作ったもので、元の素材ではない。 */
    private fun discardDocument(uri: Uri): VideoExportOutcome {
        runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
        return failed(VideoExportFailure.WRITE_FAILED)
    }

    private fun displayName(uri: Uri): String = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment.orEmpty()

    private suspend fun cancellationCheck(): () -> Unit {
        val job = currentCoroutineContext()[Job]
        return { if (job != null && !job.isActive) throw CancellationException("export cancelled") }
    }

    private fun failed(reason: VideoExportFailure) = VideoExportOutcome.Failed(VideoExportError(reason))

    companion object {
        const val TEMP_PREFIX = ".foldex-export-"
        private const val TEMP_SUFFIX = ".part"
        private const val CACHE_DIRECTORY = "videoedit-export"
        private const val JOURNAL_PATH = "videoedit/export-journal.txt"
        private const val MAX_NAME_ATTEMPTS = 1000
        private const val COPY_BUFFER_BYTES = 256 * 1024

        /** 書き出しは端末全体で 1 本ずつ。端末のコーデックの同時使用数に上限があるため (設計書 §6-5)。 */
        private val GATE = Mutex()

        /**
         * 編集画面を開いたときに呼ぶ。書き出し中でなければ、前回の途中で終わった書き出しの一時ファイルを片付ける。
         * 片付けるものがあれば true。IO スレッドから呼ぶこと。
         */
        fun recoverInterrupted(context: Context): Boolean {
            if (GATE.isLocked) return false
            File(context.cacheDir, CACHE_DIRECTORY).listFiles()?.forEach { if (it.name.startsWith(TEMP_PREFIX)) it.delete() }
            return VideoExportJournal(File(context.filesDir, JOURNAL_PATH)).recover()
        }
    }
}
