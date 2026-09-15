// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.videoedit.model

import kotlin.math.ceil

/**
 * 編集操作。すべて「前のプロジェクト → 新しいプロジェクト」の純関数で、条件に合わなければ元をそのまま返す。
 * ID の発行は呼び出し側で行う (テストで結果を固定できるように)。
 */
object VideoEditOps {
    const val MIN_SPEED = 0.1
    const val MAX_SPEED = 16.0

    /** 静止画には素材の長さが無いので、ここで区切る。 */
    const val MAX_IMAGE_DURATION_US: Long = 3_600L * US_PER_SECOND
    const val DEFAULT_IMAGE_DURATION_US: Long = 3L * US_PER_SECOND
    const val DEFAULT_TEXT_DURATION_US: Long = 3L * US_PER_SECOND

    /** 映像・静止画はメイントラックの末尾へ、音声は [atUs] の位置へ置く。長さの分からない映像・音声は追加しない。 */
    fun appendMedia(project: VideoProject, asset: MediaAsset, clipId: String, atUs: Long): VideoProject {
        if (asset.kind != AssetKind.IMAGE && asset.durationUs <= 0L) return project
        val assets = project.assets + (asset.id to asset)
        return when (asset.kind) {
            AssetKind.VIDEO -> project.copy(
                assets = assets,
                mainTrack = project.mainTrack + MainClip(clipId, asset.id, 0L, asset.durationUs),
            )
            AssetKind.IMAGE -> project.copy(
                assets = assets,
                mainTrack = project.mainTrack + MainClip(clipId, asset.id, 0L, DEFAULT_IMAGE_DURATION_US),
            )
            AssetKind.AUDIO -> project.copy(
                assets = assets,
                audioTrack = (project.audioTrack + AudioClip(clipId, asset.id, atUs.coerceAtLeast(0L), 0L, asset.durationUs))
                    .sortedBy { it.startUs },
            )
        }
    }

    /** 素材の終わり。静止画は [MAX_IMAGE_DURATION_US] で区切る。 */
    fun sourceLimitUs(project: VideoProject, assetId: String): Long {
        val asset = project.assets[assetId] ?: return 0L
        return if (asset.kind == AssetKind.IMAGE) MAX_IMAGE_DURATION_US else asset.durationUs
    }

    /** 速度 [speed] のクリップで、タイムライン上の 1 コマを残すのに必要な素材の長さ。 */
    fun minSourceUs(project: VideoProject, speed: Double): Long =
        ceil(project.frameRate.frameDurationUs * speed).toLong().coerceAtLeast(1L)

    fun clampMainSourceIn(project: VideoProject, clip: MainClip, sourceInUs: Long): Long =
        sourceInUs.clampBetween(0L, clip.sourceOutUs - minSourceUs(project, clip.speed))

    fun clampMainSourceOut(project: VideoProject, clip: MainClip, sourceOutUs: Long): Long =
        sourceOutUs.clampBetween(clip.sourceInUs + minSourceUs(project, clip.speed), sourceLimitUs(project, clip.assetId))

    // ---- メイントラック ----

    /** [atUs] でメインクリップを 2 つに分ける。クリップの端から 1 コマ未満の位置では分けない。 */
    fun splitMainAt(project: VideoProject, atUs: Long, newId: String): VideoProject {
        val (index, local) = project.mainClipAt(atUs) ?: return project
        val clip = project.mainTrack[index]
        val minUs = project.frameRate.frameDurationUs
        if (local < minUs || clip.durationUs - local < minUs) return project
        val cut = clip.sourceTimeAt(local)
        if (cut <= clip.sourceInUs || cut >= clip.sourceOutUs) return project
        val list = project.mainTrack.toMutableList()
        list[index] = clip.copy(sourceOutUs = cut, fadeOutUs = 0L).clampFades()
        list.add(index + 1, clip.copy(id = newId, sourceInUs = cut, fadeInUs = 0L).clampFades())
        return project.copy(mainTrack = list)
    }

    fun trimMainStart(project: VideoProject, id: String, sourceInUs: Long): VideoProject =
        updateMain(project, id) { clip -> clip.copy(sourceInUs = clampMainSourceIn(project, clip, sourceInUs)).clampFades() }

    fun trimMainEnd(project: VideoProject, id: String, sourceOutUs: Long): VideoProject =
        updateMain(project, id) { clip -> clip.copy(sourceOutUs = clampMainSourceOut(project, clip, sourceOutUs)).clampFades() }

    /** 長さとタイムライン上の位置を保ち、素材から使う区間だけをずらす。 */
    fun shiftMainSource(project: VideoProject, id: String, sourceInUs: Long): VideoProject =
        updateMain(project, id) { clip ->
            val asset = project.assets[clip.assetId]
            if (asset?.kind != AssetKind.VIDEO) return@updateMain clip
            val start = sourceInUs.clampBetween(0L, asset.durationUs - clip.sourceDurationUs)
            clip.copy(sourceInUs = start, sourceOutUs = start + clip.sourceDurationUs)
        }

    /** [from] 番目を取り除いてから [to] 番目に差し込む。 */
    fun moveMain(project: VideoProject, from: Int, to: Int): VideoProject {
        val list = project.mainTrack
        if (from !in list.indices || to !in list.indices || from == to) return project
        return project.copy(mainTrack = list.toMutableList().apply { add(to, removeAt(from)) })
    }

    fun setMainSpeed(project: VideoProject, id: String, speed: Double): VideoProject =
        updateMain(project, id) { it.copy(speed = speed.coerceIn(MIN_SPEED, MAX_SPEED)).clampFades() }

    fun setMainFades(project: VideoProject, id: String, fadeInUs: Long, fadeOutUs: Long): VideoProject =
        updateMain(project, id) { it.copy(fadeInUs = fadeInUs, fadeOutUs = fadeOutUs).clampFades() }

    fun updateMain(project: VideoProject, id: String, transform: (MainClip) -> MainClip): VideoProject {
        val index = project.mainIndexOf(id)
        if (index < 0) return project
        val list = project.mainTrack.toMutableList()
        list[index] = transform(list[index])
        return project.copy(mainTrack = list)
    }

    // ---- 文字 ----

    fun addText(project: VideoProject, id: String, atUs: Long, text: String): VideoProject =
        project.copy(
            texts = project.texts + TextClip(
                id = id,
                startUs = atUs.coerceAtLeast(0L),
                durationUs = DEFAULT_TEXT_DURATION_US,
                text = text,
            ),
        )

    fun moveText(project: VideoProject, id: String, startUs: Long): VideoProject =
        updateText(project, id) { it.copy(startUs = startUs.coerceAtLeast(0L)) }

    /** 終わりの位置を保ったまま開始を動かす。1 コマは残す。 */
    fun trimTextStart(project: VideoProject, id: String, startUs: Long): VideoProject =
        updateText(project, id) { clip ->
            val start = startUs.clampBetween(0L, clip.endUs - project.frameRate.frameDurationUs)
            clip.copy(startUs = start, durationUs = clip.endUs - start)
        }

    fun trimTextEnd(project: VideoProject, id: String, endUs: Long): VideoProject =
        updateText(project, id) { clip ->
            val end = endUs.coerceAtLeast(clip.startUs + project.frameRate.frameDurationUs)
            clip.copy(durationUs = end - clip.startUs)
        }

    fun updateText(project: VideoProject, id: String, transform: (TextClip) -> TextClip): VideoProject {
        val index = project.texts.indexOfFirst { it.id == id }
        if (index < 0) return project
        val list = project.texts.toMutableList()
        list[index] = transform(list[index])
        return project.copy(texts = list)
    }

    // ---- 音声 ----

    /** 音声クリップを [atUs] で 2 つに分ける。 */
    fun splitAudioAt(project: VideoProject, id: String, atUs: Long, newId: String): VideoProject {
        val index = project.audioTrack.indexOfFirst { it.id == id }
        if (index < 0) return project
        val clip = project.audioTrack[index]
        val minUs = project.frameRate.frameDurationUs
        val local = atUs - clip.startUs
        if (local < minUs || clip.durationUs - local < minUs) return project
        val cut = clip.sourceInUs + local
        val list = project.audioTrack.toMutableList()
        list[index] = clip.copy(sourceOutUs = cut, fadeOutUs = 0L).clampFades()
        list.add(index + 1, clip.copy(id = newId, startUs = atUs, sourceInUs = cut, fadeInUs = 0L).clampFades())
        return project.copy(audioTrack = list)
    }

    fun moveAudio(project: VideoProject, id: String, startUs: Long): VideoProject =
        updateAudio(project, id) { it.copy(startUs = startUs.coerceAtLeast(0L)) }

    /** 中身の位置を保ったまま左端を動かす。素材の先頭より前には伸ばせない。 */
    fun trimAudioStart(project: VideoProject, id: String, startUs: Long): VideoProject =
        updateAudio(project, id) { clip ->
            val earliest = maxOf(0L, clip.startUs - clip.sourceInUs)
            val start = startUs.clampBetween(earliest, clip.endUs - project.frameRate.frameDurationUs)
            clip.copy(startUs = start, sourceInUs = clip.sourceInUs + (start - clip.startUs)).clampFades()
        }

    /** 右端を動かす。素材の終わりより後ろには伸ばせない。 */
    fun trimAudioEnd(project: VideoProject, id: String, endUs: Long): VideoProject =
        updateAudio(project, id) { clip ->
            val latest = clip.startUs + (sourceLimitUs(project, clip.assetId) - clip.sourceInUs)
            val end = endUs.clampBetween(clip.startUs + project.frameRate.frameDurationUs, latest)
            clip.copy(sourceOutUs = clip.sourceInUs + (end - clip.startUs)).clampFades()
        }

    fun setAudioFades(project: VideoProject, id: String, fadeInUs: Long, fadeOutUs: Long): VideoProject =
        updateAudio(project, id) { it.copy(fadeInUs = fadeInUs, fadeOutUs = fadeOutUs).clampFades() }

    fun updateAudio(project: VideoProject, id: String, transform: (AudioClip) -> AudioClip): VideoProject {
        val index = project.audioTrack.indexOfFirst { it.id == id }
        if (index < 0) return project
        val list = project.audioTrack.toMutableList()
        list[index] = transform(list[index])
        return project.copy(audioTrack = list)
    }

    // ---- 共通 ----

    fun delete(project: VideoProject, ref: ClipRef): VideoProject = when (ref) {
        is ClipRef.Main -> project.copy(mainTrack = project.mainTrack.filterNot { it.id == ref.id })
        is ClipRef.Text -> project.copy(texts = project.texts.filterNot { it.id == ref.id })
        is ClipRef.Audio -> project.copy(audioTrack = project.audioTrack.filterNot { it.id == ref.id })
        is ClipRef.Layer -> project.copy(layers = project.layers.filterNot { it.id == ref.id })
    }

    /** メインは直後に差し込み、文字と音声は元の終わりの位置に置く。 */
    fun duplicate(project: VideoProject, ref: ClipRef, newId: String): VideoProject = when (ref) {
        is ClipRef.Layer -> {
            val layer = project.layers.firstOrNull { it.id == ref.id }
            if (layer == null) project else project.copy(layers = project.layers + layer.copy(clip = layer.clip.copy(id = newId), startUs = layer.endUs))
        }
        is ClipRef.Main -> {
            val index = project.mainIndexOf(ref.id)
            if (index < 0) {
                project
            } else {
                project.copy(mainTrack = project.mainTrack.toMutableList().apply { add(index + 1, this[index].copy(id = newId)) })
            }
        }
        is ClipRef.Text -> {
            val clip = project.texts.firstOrNull { it.id == ref.id }
            if (clip == null) project else project.copy(texts = project.texts + clip.copy(id = newId, startUs = clip.endUs))
        }
        is ClipRef.Audio -> {
            val clip = project.audioTrack.firstOrNull { it.id == ref.id }
            if (clip == null) {
                project
            } else {
                project.copy(audioTrack = (project.audioTrack + clip.copy(id = newId, startUs = clip.endUs)).sortedBy { it.startUs })
            }
        }
    }

    private fun MainClip.clampFades(): MainClip {
        val fadeIn = fadeInUs.clampBetween(0L, durationUs)
        val fadeOut = fadeOutUs.clampBetween(0L, durationUs - fadeIn)
        return if (fadeIn == fadeInUs && fadeOut == fadeOutUs) this else copy(fadeInUs = fadeIn, fadeOutUs = fadeOut)
    }

    private fun AudioClip.clampFades(): AudioClip {
        val fadeIn = fadeInUs.clampBetween(0L, durationUs)
        val fadeOut = fadeOutUs.clampBetween(0L, durationUs - fadeIn)
        return if (fadeIn == fadeInUs && fadeOut == fadeOutUs) this else copy(fadeInUs = fadeIn, fadeOutUs = fadeOut)
    }

    /** 上限が下限を下回る (素材が極端に短い) ときは下限を返し、例外にしない。 */
    private fun Long.clampBetween(low: Long, high: Long): Long = if (high < low) low else coerceIn(low, high)
}
