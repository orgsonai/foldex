// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.videoedit.engine

import android.net.Uri
import android.os.Build
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.OverlaySettings
import androidx.media3.common.SpeedParameters
import androidx.media3.common.VideoCompositorSettings
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.audio.SpeedProvider
import androidx.media3.common.audio.ToInt16PcmAudioProcessor
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.effect.StaticOverlaySettings
import androidx.media3.effect.TextureOverlay
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import com.zerotoship.foldex.core.model.filetype.FileTypeRegistry
import com.zerotoship.foldex.ui.videoedit.model.AssetKind
import com.zerotoship.foldex.ui.videoedit.model.FrameFill
import com.zerotoship.foldex.ui.videoedit.plan.GainCurve
import com.zerotoship.foldex.ui.videoedit.plan.OutputSpec
import com.zerotoship.foldex.ui.videoedit.plan.PlannedGap
import com.zerotoship.foldex.ui.videoedit.plan.PlannedItem
import com.zerotoship.foldex.ui.videoedit.plan.PlannedMedia
import com.zerotoship.foldex.ui.videoedit.plan.PlannedText
import com.zerotoship.foldex.ui.videoedit.plan.RenderPlan
import java.io.File
import kotlin.math.roundToInt

/**
 * [RenderPlan] を Media3 の [Composition] に変換する。Media3 の型を扱うのは engine/ だけにする (設計書 §3)。
 *
 * 段の構成: 透明な時計 → 手前から奥のレイヤー → メイン → 音声。文字は合成全体へ最後に重ねる。
 * 文字の Bitmap は書き出しごとに作り直す。Media3 が書き出しの終わりに重ねの資源を解放するため、使い回さない。
 */
@androidx.annotation.OptIn(UnstableApi::class, ExperimentalApi::class)
internal object CompositionAdapter {

    private val VIDEO = setOf(C.TRACK_TYPE_VIDEO)
    private val AUDIO = setOf(C.TRACK_TYPE_AUDIO)
    private val AUDIO_AND_VIDEO = setOf(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_VIDEO)

    fun build(plan: RenderPlan): Composition {
        val output = plan.output
        // Media3 は先の段ほど手前。透明な静止画を時計にし、最前面の短い動画の fps / 長さに出力を引きずらせない。
        val layers = plan.layers.asReversed()
        val sequences = buildList {
            if (!output.audioOnly && layers.isNotEmpty()) add(clockSequence(plan))
            layers.forEach { layer ->
                val layerWidth = (output.width * layer.widthFraction).roundToInt().coerceAtLeast(2)
                val layerHeight = (layerWidth / layer.aspectRatio).roundToInt().coerceAtLeast(2)
                val layerOutput = if (output.audioOnly) output else output.copy(width = layerWidth, height = layerHeight)
                add(sequence(layer.items(plan.durationUs), layerOutput, if (output.audioOnly) AUDIO else AUDIO_AND_VIDEO))
            }
            if (plan.main.isNotEmpty()) add(sequence(plan.main, output, if (output.audioOnly) AUDIO else AUDIO_AND_VIDEO))
            plan.audioLanes.forEach { lane -> add(sequence(lane, output, AUDIO)) }
        }
        val builder = Composition.Builder(sequences)
        if (!output.audioOnly && layers.isNotEmpty()) {
            builder.setVideoCompositorSettings(object : VideoCompositorSettings {
                override fun getOutputSize(inputSizes: MutableList<Size>): Size = Size(output.width, output.height)

                override fun getOverlaySettings(inputId: Int, presentationTimeUs: Long): OverlaySettings {
                    if (inputId == 0) return StaticOverlaySettings.Builder().setAlphaScale(0f).build()
                    val layer = layers.getOrNull(inputId - 1) ?: return StaticOverlaySettings.Builder().build()
                    val alpha = if (presentationTimeUs >= layer.startUs && presentationTimeUs < layer.endUs) layer.opacity else 0f
                    return StaticOverlaySettings.Builder()
                        .setBackgroundFrameAnchor(layer.centerX * 2f - 1f, 1f - layer.centerY * 2f)
                        .setAlphaScale(alpha)
                        .build()
                }
            })
        }
        if (!output.audioOnly && plan.texts.isNotEmpty()) {
            val overlays = plan.texts.map<PlannedText, TextureOverlay> { TimedTextOverlay(it, output.width, output.height) }
            builder.setEffects(Effects(emptyList<AudioProcessor>(), listOf<Effect>(OverlayEffect(overlays))))
        }
        // HDR の素材は SDR に変換して揃える (設計書 §2-4)。OpenGL での変換は API 29 以降。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
        }
        return builder.build()
    }

    private fun clockSequence(plan: RenderPlan): EditedMediaItemSequence {
        val item = MediaItem.Builder().setUri("asset:///video_edit_clock.png")
            .setMimeType(MimeTypes.IMAGE_PNG).setImageDurationMs((plan.durationUs + 999L) / 1000L).build()
        return EditedMediaItemSequence.Builder(VIDEO).addItem(
            EditedMediaItem.Builder(item).setDurationUs(plan.durationUs).setFrameRate(plan.output.frameRate).build(),
        ).build()
    }

    private fun sequence(items: List<PlannedItem>, output: OutputSpec, trackTypes: Set<Int>): EditedMediaItemSequence {
        val withVideo = C.TRACK_TYPE_VIDEO in trackTypes
        val builder = EditedMediaItemSequence.Builder(trackTypes)
        for (item in items) {
            when (item) {
                is PlannedGap -> if (item.durationUs > 0L) builder.addGap(item.durationUs)
                is PlannedMedia -> builder.addItem(editedItem(item, output, withVideo))
            }
        }
        return builder.build()
    }

    private fun editedItem(item: PlannedMedia, output: OutputSpec, withVideo: Boolean): EditedMediaItem {
        val uri = if (item.source.startsWith("content://")) Uri.parse(item.source) else Uri.fromFile(File(item.source))
        if (item.kind == AssetKind.IMAGE) {
            val mediaItem = MediaItem.Builder()
                .setUri(uri)
                // Transformer は MIME から静止画かを判定する。拡張子の無い content:// でも迷わないように渡す。
                .setMimeType(FileTypeRegistry.mimeTypeFor(item.name)?.takeIf { it.startsWith("image/") })
                .setImageDurationMs((item.durationUs + 999L) / 1000L)
                .build()
            return EditedMediaItem.Builder(mediaItem)
                .setDurationUs(item.durationUs)
                .setFrameRate(output.frameRate)
                .setEffects(Effects(emptyList<AudioProcessor>(), videoEffects(item, output)))
                .build()
        }
        val clipping = MediaItem.ClippingConfiguration.Builder()
            .setStartPositionUs(item.sourceInUs)
            .setEndPositionUs(item.sourceOutUs)
            .build()
        val mediaItem = MediaItem.Builder().setUri(uri).setClippingConfiguration(clipping).build()
        val videoEffects = if (withVideo && item.kind == AssetKind.VIDEO) videoEffects(item, output) else emptyList()
        val builder = EditedMediaItem.Builder(mediaItem).setEffects(Effects(audioProcessors(item.gain), videoEffects))
        // 動画ではコマ数の上限として働く。素材の fps が低ければ何もしない。
        if (withVideo && item.kind == AssetKind.VIDEO) builder.setFrameRate(output.frameRate)
        if (item.speed != 1.0) builder.setSpeed(SpeedParameters(ConstantSpeedProvider(item.speed.toFloat()), item.keepPitch))
        return builder.build()
    }

    /** 16bit → 2ch → 音量の順。Transformer は音声を含む全素材が同じ ch 数の 16bit PCM であることを求める。 */
    private fun audioProcessors(gain: GainCurve): List<AudioProcessor> = buildList {
        add(ToInt16PcmAudioProcessor())
        add(stereoMixer())
        if (!gain.isUnity) add(ClipGainAudioProcessor(gain))
    }

    /** 1〜6ch を 2ch に揃える。mono / stereo は Transformer 既定と同じ係数、3ch 以上は音の大きさを保つ係数。 */
    private fun stereoMixer(): ChannelMixingAudioProcessor = ChannelMixingAudioProcessor().apply {
        putChannelMixingMatrix(ChannelMixingMatrix.createForConstantGain(1, 2))
        putChannelMixingMatrix(ChannelMixingMatrix.createForConstantGain(2, 2))
        for (channels in 3..6) putChannelMixingMatrix(ChannelMixingMatrix.createForConstantPower(channels, 2))
    }

    private fun videoEffects(item: PlannedMedia, output: OutputSpec): List<Effect> = buildList {
        // プレビューと同じ順: 左右反転してから回転する。
        if (item.flipHorizontal) add(ScaleAndRotateTransformation.Builder().setScale(-1f, 1f).build())
        // quarterTurns は時計回り (プレビューの rotationZ と同じ)。Media3 の回転は反時計回りなので逆に指定する。
        if (item.quarterTurns != 0) {
            add(ScaleAndRotateTransformation.Builder().setRotationDegrees(360f - 90f * item.quarterTurns).build())
        }
        val layout = if (item.fill == FrameFill.FILL) Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP else Presentation.LAYOUT_SCALE_TO_FIT
        add(Presentation.createForWidthAndHeight(output.width, output.height, layout))
    }

    private class ConstantSpeedProvider(private val speed: Float) : SpeedProvider {
        override fun getSpeed(timeUs: Long): Float = speed

        override fun getNextSpeedChangeTimeUs(timeUs: Long): Long = C.TIME_UNSET
    }
}
