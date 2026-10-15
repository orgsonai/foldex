// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.videoedit.project

import com.zerotoship.foldex.ui.videoedit.model.AssetKind
import com.zerotoship.foldex.ui.videoedit.model.AssetStamp
import com.zerotoship.foldex.ui.videoedit.model.AudioClip
import com.zerotoship.foldex.ui.videoedit.model.CanvasAspect
import com.zerotoship.foldex.ui.videoedit.model.ExportPreset
import com.zerotoship.foldex.ui.videoedit.model.ExportResolution
import com.zerotoship.foldex.ui.videoedit.model.ExportSettings
import com.zerotoship.foldex.ui.videoedit.model.FrameFill
import com.zerotoship.foldex.ui.videoedit.model.FrameRate
import com.zerotoship.foldex.ui.videoedit.model.MainClip
import com.zerotoship.foldex.ui.videoedit.model.MediaAsset
import com.zerotoship.foldex.ui.videoedit.model.TextClip
import com.zerotoship.foldex.ui.videoedit.model.US_PER_SECOND
import com.zerotoship.foldex.ui.videoedit.model.VideoCodecChoice
import com.zerotoship.foldex.ui.videoedit.model.VideoProject

private const val S = US_PER_SECOND

/** 全種類のクリップと、既定値ではない値を含む下書き。形式の往復で落ちる項目が無いことを確かめるため。 */
internal fun sampleDraft(id: String = "0f8fad5b-d9cb-469f-a165-70867728950e", revision: Long = 0): VideoDraft {
    val video = MediaAsset(
        "a1", AssetKind.VIDEO, "/storage/emulated/0/DCIM/旅行.mp4", "旅行.mp4", 12 * S, 1080, 1920,
        hasAudio = true, stamp = AssetStamp(123_456, 1_700_000_000_000),
    )
    val image = MediaAsset("a2", AssetKind.IMAGE, "content://media/external/images/media/9", "photo.jpg", 0, 4000, 3000)
    val music = MediaAsset("a3", AssetKind.AUDIO, "/storage/emulated/0/Music/bgm.m4a", "bgm.m4a", 180 * S, hasAudio = true, stamp = AssetStamp(-1, -1))
    val project = VideoProject(
        assets = listOf(video, image, music).associateBy { it.id },
        mainTrack = listOf(
            MainClip(
                "c1", "a1", 1 * S, 9 * S, speed = 1.5, keepPitch = false, volume = 1.8f, muted = false,
                fadeInUs = S / 2, fadeOutUs = S, quarterTurns = 3, flipHorizontal = true, fill = FrameFill.FILL,
            ),
            MainClip("c2", "a2", 0, 3 * S),
        ),
        texts = listOf(
            TextClip("t1", 2 * S, 3 * S, "こんにちは\n2 行目", sizeFraction = 0.12f, color = 0xFFFFCC00.toInt(), outlined = false, centerX = 0.25f, centerY = 0.9f),
        ),
        audioTrack = listOf(AudioClip("m1", "a3", 0, 10 * S, 40 * S, volume = 0.4f, muted = true, fadeInUs = 2 * S, fadeOutUs = 3 * S)),
        aspect = CanvasAspect.R9_16,
        background = 0xFFFFFFFF.toInt(),
        frameRate = FrameRate(30_000, 1_001),
    )
    val settings = ExportSettings(ExportPreset.TARGET_SIZE, ExportResolution.P720, 60, VideoCodecChoice.HEVC, 25, 128, keepLocation = false)
    return VideoDraft(id, revision, 1_700_000_000_000, "旅行.mp4", project, settings)
}
