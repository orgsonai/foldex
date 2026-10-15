// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.videoedit.engine

import android.content.Context
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.AudioEncoderSettings
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.zerotoship.foldex.ui.videoedit.plan.ExportCheck
import com.zerotoship.foldex.ui.videoedit.plan.OutputSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/** Media3 Transformer を 1 回動かす。Media3 の結果を、Media3 に依存しない値に置き換えて返す。 */
@androidx.annotation.OptIn(UnstableApi::class)
internal class TransformerRunner(private val context: Context) {

    sealed interface Outcome {
        data object Completed : Outcome

        data class Failed(val errorCode: Int, val codeName: String) : Outcome
    }

    /**
     * [composition] を [outputPath] へ書き出す。
     *
     * Transformer は作ったスレッドの Looper からしか触れないので、作成・進捗の取得・取消をすべてメインスレッドで行う。
     * コルーチンを取り消すと書き出しも取り消す。途中のファイルは呼び出し側が消す。
     */
    suspend fun run(
        composition: Composition,
        outputPath: String,
        output: OutputSpec,
        videoKbps: Int,
        onProgress: (Int?) -> Unit,
    ): Outcome = withContext(Dispatchers.Main) {
        var transformer: Transformer? = null
        val poller = launch {
            val holder = ProgressHolder()
            while (isActive) {
                delay(PROGRESS_INTERVAL_MS)
                val current = transformer ?: continue
                val state = current.getProgress(holder)
                onProgress(if (state == Transformer.PROGRESS_STATE_AVAILABLE) holder.progress else null)
            }
        }
        try {
            suspendCancellableCoroutine<Outcome> { continuation ->
                val listener = object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        if (continuation.isActive) continuation.resume(Outcome.Completed)
                    }

                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        if (continuation.isActive) {
                            continuation.resume(Outcome.Failed(exportException.errorCode, exportException.errorCodeName))
                        }
                    }
                }
                val encoders = DefaultEncoderFactory.Builder(context).apply {
                    if (!output.audioOnly) {
                        setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(videoKbps * 1000).build())
                    }
                    setRequestedAudioEncoderSettings(AudioEncoderSettings.Builder().setBitrate(output.audioKbps * 1000).build())
                }.build()
                val builder = Transformer.Builder(context)
                    // Android 15 以降の MediaMetrics へ編集の統計を渡さない (設計書 §9)。
                    .setUsePlatformDiagnostics(false)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .setEncoderFactory(encoders)
                    .addListener(listener)
                if (!output.audioOnly) builder.setVideoMimeType(ExportCheck.videoMimeType(output.codec))
                val created = builder.build()
                transformer = created
                created.start(composition, outputPath)
            }
        } catch (cancelled: CancellationException) {
            transformer?.cancel()
            throw cancelled
        } finally {
            poller.cancel()
        }
    }

    private companion object {
        const val PROGRESS_INTERVAL_MS = 250L
    }
}
