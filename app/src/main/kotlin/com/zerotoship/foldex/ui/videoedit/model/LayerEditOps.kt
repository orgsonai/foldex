// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.videoedit.model

import kotlin.math.roundToLong

/** 映像レイヤーの操作。メインの前詰めに影響されない絶対時刻で扱う。 */
object LayerEditOps {
    fun add(project: VideoProject, asset: MediaAsset, id: String, atUs: Long): VideoProject {
        if (asset.kind == AssetKind.AUDIO || (asset.kind == AssetKind.VIDEO && asset.durationUs <= 0L)) return project
        val length = if (asset.kind == AssetKind.IMAGE) VideoEditOps.DEFAULT_IMAGE_DURATION_US else asset.durationUs
        return project.copy(
            assets = project.assets + (asset.id to asset),
            layers = project.layers + LayerClip(MainClip(id, asset.id, 0L, length, muted = true), atUs.coerceAtLeast(0L)),
        )
    }

    fun update(project: VideoProject, id: String, transform: (LayerClip) -> LayerClip): VideoProject =
        project.copy(layers = project.layers.map { if (it.id == id) transform(it) else it })

    /** 区間・速度・音量の規則をメインと共用する。時刻・配置と他のクリップは変えない。 */
    fun editSource(project: VideoProject, id: String, transform: (VideoProject) -> VideoProject): VideoProject =
        update(project, id) { layer ->
            val edited = transform(project.copy(mainTrack = listOf(layer.clip)))
            layer.copy(clip = edited.mainTrack.singleOrNull()?.takeIf { it.id == id } ?: layer.clip)
        }

    fun move(project: VideoProject, id: String, startUs: Long): VideoProject =
        update(project, id) { it.copy(startUs = startUs.coerceAtLeast(0L)) }

    fun trimStart(project: VideoProject, id: String, startUs: Long): VideoProject =
        update(project, id) { layer ->
            val clip = layer.clip
            val earliest = maxOf(0L, layer.startUs - (clip.sourceInUs / clip.speed).roundToLong())
            val latest = maxOf(earliest, layer.endUs - project.frameRate.frameDurationUs)
            val start = startUs.coerceIn(earliest, latest)
            val sourceIn = clip.sourceInUs + ((start - layer.startUs) * clip.speed).roundToLong()
            val edited = VideoEditOps.trimMainStart(project.copy(mainTrack = listOf(clip)), id, sourceIn).mainTrack.single()
            // 素材の丸めや短いクリップの制限後にも、右端は固定する。
            layer.copy(clip = edited, startUs = (layer.endUs - edited.durationUs).coerceAtLeast(0L))
        }

    fun trimEnd(project: VideoProject, id: String, endUs: Long): VideoProject =
        update(project, id) { layer ->
            val clip = layer.clip
            val sourceOut = clip.sourceInUs + ((endUs - layer.startUs) * clip.speed).roundToLong()
            val edited = VideoEditOps.trimMainEnd(project.copy(mainTrack = listOf(clip)), id, sourceOut).mainTrack.single()
            layer.copy(clip = edited)
        }

    fun split(project: VideoProject, id: String, atUs: Long, newId: String): VideoProject {
        val index = project.layers.indexOfFirst { it.id == id }
        if (index < 0) return project
        val layer = project.layers[index]
        val parts = VideoEditOps.splitMainAt(project.copy(mainTrack = listOf(layer.clip)), atUs - layer.startUs, newId).mainTrack
        if (parts.size != 2) return project
        return project.copy(layers = project.layers.toMutableList().apply {
            this[index] = layer.copy(clip = parts[0])
            add(index + 1, layer.copy(clip = parts[1], startUs = layer.startUs + parts[0].durationUs))
        })
    }

    fun reorder(project: VideoProject, id: String, offset: Int): VideoProject {
        val index = project.layers.indexOfFirst { it.id == id }
        if (index < 0) return project
        val target = (index + offset).coerceIn(project.layers.indices)
        return project.copy(layers = project.layers.toMutableList().apply { add(target, removeAt(index)) })
    }
}
