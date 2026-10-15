// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.videoedit.model

/** 選択したクリップを基準にする操作。再生位置にある別のクリップを誤って編集しない。 */
object ClipEditing {
    fun range(project: VideoProject, ref: ClipRef?): LongRange? = when (ref) {
        is ClipRef.Main -> project.mainIndexOf(ref.id).takeIf { it >= 0 }?.let { index ->
            val start = project.mainStartUs(index)
            start..start + project.mainTrack[index].durationUs
        }
        is ClipRef.Layer -> project.layers.firstOrNull { it.id == ref.id }?.let { it.startUs..it.endUs }
        is ClipRef.Text -> project.texts.firstOrNull { it.id == ref.id }?.let { it.startUs..it.endUs }
        is ClipRef.Audio -> project.audioTrack.firstOrNull { it.id == ref.id }?.let { it.startUs..it.endUs }
        null -> null
    }

    fun canCut(project: VideoProject, ref: ClipRef?, atUs: Long): Boolean {
        val range = range(project, ref) ?: return false
        val frame = project.frameRate.frameDurationUs
        return atUs - range.first >= frame && range.last - atUs >= frame
    }

    /** 再生位置より前/後を除く。メインは前詰め、独立した段では他の段を動かさない。 */
    fun trimToPlayhead(project: VideoProject, ref: ClipRef, atUs: Long, start: Boolean): VideoProject {
        if (!canCut(project, ref, atUs)) return project
        return when (ref) {
            is ClipRef.Main -> {
                val index = project.mainIndexOf(ref.id)
                val clip = project.mainTrack[index]
                val sourceUs = clip.sourceTimeAt(atUs - project.mainStartUs(index))
                if (start) VideoEditOps.trimMainStart(project, ref.id, sourceUs)
                else VideoEditOps.trimMainEnd(project, ref.id, sourceUs)
            }
            is ClipRef.Layer -> if (start) LayerEditOps.trimStart(project, ref.id, atUs) else LayerEditOps.trimEnd(project, ref.id, atUs)
            is ClipRef.Text -> if (start) VideoEditOps.trimTextStart(project, ref.id, atUs) else VideoEditOps.trimTextEnd(project, ref.id, atUs)
            is ClipRef.Audio -> if (start) VideoEditOps.trimAudioStart(project, ref.id, atUs) else VideoEditOps.trimAudioEnd(project, ref.id, atUs)
        }
    }

    fun split(project: VideoProject, ref: ClipRef, atUs: Long, newId: String): VideoProject {
        if (!canCut(project, ref, atUs)) return project
        return when (ref) {
            is ClipRef.Main -> VideoEditOps.splitMainAt(project, atUs, newId)
            is ClipRef.Layer -> LayerEditOps.split(project, ref.id, atUs, newId)
            is ClipRef.Audio -> VideoEditOps.splitAudioAt(project, ref.id, atUs, newId)
            is ClipRef.Text -> {
                val clip = project.texts.first { it.id == ref.id }
                project.copy(texts = project.texts.flatMap {
                    if (it.id != ref.id) listOf(it) else listOf(
                        clip.copy(durationUs = atUs - clip.startUs),
                        clip.copy(id = newId, startUs = atUs, durationUs = clip.endUs - atUs),
                    )
                })
            }
        }
    }

    fun neighborBoundary(project: VideoProject, timeUs: Long, forward: Boolean): Long {
        val boundaries = buildList {
            add(0L)
            var end = 0L
            project.mainTrack.forEach { end += it.durationUs; add(end) }
            add(project.durationUs)
        }
        return if (forward) boundaries.firstOrNull { it > timeUs } ?: project.durationUs
        else boundaries.lastOrNull { it < timeUs } ?: 0L
    }
}
