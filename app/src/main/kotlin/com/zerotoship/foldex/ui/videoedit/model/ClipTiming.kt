// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.videoedit.model

import java.math.BigDecimal
import kotlin.math.roundToLong

/** 数値入力でもドラッグと同じ非破壊の区間・フェード規則を使う。開始位置は独立した段だけが持つ。 */
object ClipTiming {
    enum class Field { START, DURATION, SOURCE_IN, SOURCE_OUT }

    data class Bounds(val valueUs: Long, val minUs: Long, val maxUs: Long) {
        fun accepts(value: Long): Boolean = value in minUs..maxUs
    }

    // 下書きの時刻の保存範囲。数値入力からオーバーフローする位置を作らない。
    private const val MAX_TIME_US = 1_000L * 3_600 * US_PER_SECOND

    fun bounds(project: VideoProject, ref: ClipRef, field: Field): Bounds? {
        val range = ClipEditing.range(project, ref) ?: return null
        val duration = range.last - range.first
        val frame = project.frameRate.frameDurationUs
        if (field == Field.START) {
            if (ref is ClipRef.Main) return null
            return Bounds(range.first, 0L, (MAX_TIME_US - duration).coerceAtLeast(0L))
        }
        if (ref is ClipRef.Text) {
            return if (field == Field.DURATION) Bounds(duration, frame, MAX_TIME_US - range.first) else null
        }
        val main = when (ref) {
            is ClipRef.Main -> project.mainTrack.firstOrNull { it.id == ref.id }
            is ClipRef.Layer -> project.layers.firstOrNull { it.id == ref.id }?.clip
            else -> null
        }
        val audio = (ref as? ClipRef.Audio)?.let { project.audioTrack.firstOrNull { clip -> clip.id == it.id } }
        val assetId = main?.assetId ?: audio?.assetId ?: return null
        val asset = project.assets[assetId] ?: return null
        val speed = main?.speed ?: 1.0
        val sourceIn = main?.sourceInUs ?: audio!!.sourceInUs
        val sourceOut = main?.sourceOutUs ?: audio!!.sourceOutUs
        val minSource = VideoEditOps.minSourceUs(project, speed)
        val limit = VideoEditOps.sourceLimitUs(project, assetId)
        if (field != Field.DURATION && asset.kind == AssetKind.IMAGE) return null
        val bounds = when (field) {
            Field.DURATION -> Bounds(duration, frame, ((limit - sourceIn) / speed).roundToLong())
            Field.SOURCE_IN -> Bounds(sourceIn, 0L, sourceOut - minSource)
            Field.SOURCE_OUT -> Bounds(sourceOut, sourceIn + minSource, limit)
            Field.START -> return null
        }
        return bounds.takeIf { it.minUs <= it.maxUs }
    }

    /** 範囲外の指定は黙って丸めず、元のプロジェクトを返す。パネルは入力エラーを表示する。 */
    fun set(project: VideoProject, ref: ClipRef, field: Field, valueUs: Long): VideoProject {
        if (bounds(project, ref, field)?.accepts(valueUs) != true) return project
        if (field == Field.START) return when (ref) {
            is ClipRef.Text -> VideoEditOps.moveText(project, ref.id, valueUs)
            is ClipRef.Audio -> VideoEditOps.moveAudio(project, ref.id, valueUs)
            is ClipRef.Layer -> LayerEditOps.move(project, ref.id, valueUs)
            is ClipRef.Main -> project
        }
        if (ref is ClipRef.Text) return VideoEditOps.updateText(project, ref.id) { it.copy(durationUs = valueUs) }
        if (ref is ClipRef.Audio) {
            val clip = project.audioTrack.first { it.id == ref.id }
            val next = VideoEditOps.updateAudio(project, ref.id) {
                when (field) {
                    Field.SOURCE_IN -> it.copy(sourceInUs = valueUs)
                    Field.SOURCE_OUT -> it.copy(sourceOutUs = valueUs)
                    Field.DURATION -> it.copy(sourceOutUs = it.sourceInUs + valueUs)
                    Field.START -> it
                }
            }
            return VideoEditOps.setAudioFades(next, ref.id, clip.fadeInUs, clip.fadeOutUs)
        }
        val edit: (VideoProject) -> VideoProject = { source ->
            val clip = source.mainTrack.first { it.id == ref.id }
            when (field) {
                Field.SOURCE_IN -> VideoEditOps.trimMainStart(source, ref.id, valueUs)
                Field.SOURCE_OUT -> VideoEditOps.trimMainEnd(source, ref.id, valueUs)
                Field.DURATION -> VideoEditOps.trimMainEnd(source, ref.id, clip.sourceInUs + (valueUs * clip.speed).roundToLong())
                Field.START -> source
            }
        }
        return if (ref is ClipRef.Layer) LayerEditOps.editSource(project, ref.id, edit) else edit(project)
    }
}

/** 入力欄は丸めた表示時刻で初期化せず、元のマイクロ秒をすべて残す。 */
fun editorSeconds(timeUs: Long): String = BigDecimal.valueOf(timeUs, 6).stripTrailingZeros().toPlainString()
