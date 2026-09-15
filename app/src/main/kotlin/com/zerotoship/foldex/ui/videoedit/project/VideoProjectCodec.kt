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
import com.zerotoship.foldex.ui.videoedit.model.LayerClip
import com.zerotoship.foldex.ui.videoedit.model.MainClip
import com.zerotoship.foldex.ui.videoedit.model.MediaAsset
import com.zerotoship.foldex.ui.videoedit.model.TextClip
import com.zerotoship.foldex.ui.videoedit.model.US_PER_SECOND
import com.zerotoship.foldex.ui.videoedit.model.VideoCodecChoice
import com.zerotoship.foldex.ui.videoedit.model.VideoEditOps
import com.zerotoship.foldex.ui.videoedit.model.VideoProject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer

/**
 * 下書きの形式。版番号付きの明示的なバイナリで、Java のオブジェクト直列化や enum の序数に頼らない (画像編集と同じ方針)。
 * 読むときは値の範囲と参照 (クリップ → 素材) を確かめ、壊れた値でプロジェクトを組み立てない。
 * 形式を変えるときは [SCHEMA] を上げ、古い版を読めるようにする。
 */
internal object VideoProjectCodec {
    const val SCHEMA = 2
    const val MAX_BYTES = 8L * 1024 * 1024
    private const val MAGIC = 0x46445856 // FDXV
    private const val MAX_STRING_BYTES = 64 * 1024
    private const val MAX_ITEMS = 100_000
    private const val MAX_TIME_US = 1_000L * 3_600 * US_PER_SECOND
    private const val MAX_VOLUME = 16f

    val ID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

    fun write(draft: VideoDraft, output: OutputStream) {
        val out = DataOutputStream(output)
        out.writeInt(MAGIC)
        out.writeInt(SCHEMA)
        with(Writer(out)) {
            string(draft.id)
            out.writeLong(draft.revision)
            out.writeLong(draft.updatedAt)
            string(draft.name)
            project(draft.project)
            export(draft.exportSettings)
        }
        out.flush()
    }

    fun read(input: InputStream): VideoDraft {
        val data = DataInputStream(input)
        require(data.readInt() == MAGIC)
        val schema = data.readInt()
        if (schema !in 1..SCHEMA) throw VideoDraftException(VideoDraftFailure.UNSUPPORTED)
        return with(Reader(data, schema)) {
            val id = string()
            val revision = data.readLong()
            val updatedAt = data.readLong()
            val name = string()
            require(ID.matches(id) && revision >= 0 && updatedAt >= 0)
            val project = project()
            val export = export()
            require(data.read() == -1)
            VideoDraft(id, revision, updatedAt, name, project, export)
        }
    }

    private class Writer(val out: DataOutputStream) {
        fun string(value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            if (bytes.size > MAX_STRING_BYTES) throw VideoDraftException(VideoDraftFailure.TOO_LARGE)
            out.writeInt(bytes.size)
            out.write(bytes)
        }

        fun <T> list(values: Collection<T>, write: (T) -> Unit) {
            if (values.size > MAX_ITEMS) throw VideoDraftException(VideoDraftFailure.TOO_LARGE)
            out.writeInt(values.size)
            values.forEach(write)
        }

        fun project(project: VideoProject) {
            list(project.assets.values) { asset ->
                string(asset.id); string(asset.kind.name); string(asset.source); string(asset.name)
                out.writeLong(asset.durationUs); out.writeInt(asset.widthPx); out.writeInt(asset.heightPx)
                out.writeBoolean(asset.hasAudio)
                val stamp = asset.stamp
                out.writeBoolean(stamp != null)
                if (stamp != null) {
                    out.writeLong(stamp.sizeBytes); out.writeLong(stamp.modifiedMs)
                }
            }
            list(project.mainTrack, ::mainClip)
            list(project.texts) { text ->
                string(text.id); out.writeLong(text.startUs); out.writeLong(text.durationUs); string(text.text)
                out.writeFloat(text.sizeFraction); out.writeInt(text.color); out.writeBoolean(text.outlined)
                out.writeFloat(text.centerX); out.writeFloat(text.centerY)
            }
            list(project.audioTrack) { clip ->
                string(clip.id); string(clip.assetId); out.writeLong(clip.startUs); out.writeLong(clip.sourceInUs); out.writeLong(clip.sourceOutUs)
                out.writeFloat(clip.volume); out.writeBoolean(clip.muted); out.writeLong(clip.fadeInUs); out.writeLong(clip.fadeOutUs)
            }
            string(project.aspect.name)
            out.writeInt(project.background)
            out.writeInt(project.frameRate.numerator)
            out.writeInt(project.frameRate.denominator)
            list(project.layers) { layer ->
                mainClip(layer.clip)
                out.writeLong(layer.startUs)
                out.writeFloat(layer.centerX); out.writeFloat(layer.centerY)
                out.writeFloat(layer.widthFraction); out.writeFloat(layer.opacity); out.writeBoolean(layer.visible)
            }
        }

        fun mainClip(clip: MainClip) {
            string(clip.id); string(clip.assetId); out.writeLong(clip.sourceInUs); out.writeLong(clip.sourceOutUs)
            out.writeDouble(clip.speed); out.writeBoolean(clip.keepPitch); out.writeFloat(clip.volume); out.writeBoolean(clip.muted)
            out.writeLong(clip.fadeInUs); out.writeLong(clip.fadeOutUs)
            out.writeInt(clip.quarterTurns); out.writeBoolean(clip.flipHorizontal); string(clip.fill.name)
        }

        fun export(settings: ExportSettings) {
            string(settings.preset.name); string(settings.resolution.name); out.writeInt(settings.fps); string(settings.codec.name)
            out.writeInt(settings.targetMiB); out.writeInt(settings.audioKbps); out.writeBoolean(settings.keepLocation)
        }
    }

    private class Reader(val input: DataInputStream, val schema: Int) {
        // 不正な件数で巨大なリストを先に確保しない。
        private var remainingItems = MAX_ITEMS

        fun count(): Int {
            val n = input.readInt()
            require(n in 0..remainingItems)
            remainingItems -= n
            return n
        }

        fun string(): String {
            val length = input.readInt()
            require(length in 0..MAX_STRING_BYTES)
            val bytes = ByteArray(length)
            input.readFully(bytes)
            return Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
        }

        /** 知らない名前は、新しい版で増えた値とみなして UNSUPPORTED にする。 */
        inline fun <reified E : Enum<E>> enum(): E {
            val name = string()
            return enumValues<E>().firstOrNull { it.name == name } ?: throw VideoDraftException(VideoDraftFailure.UNSUPPORTED)
        }

        fun time(): Long = input.readLong().also { require(it in 0..MAX_TIME_US) }

        fun float(range: ClosedFloatingPointRange<Float>): Float = input.readFloat().also { require(it.isFinite() && it in range) }

        fun project(): VideoProject {
            val assets = List(count()) {
                val id = string()
                val kind = enum<AssetKind>()
                val source = string()
                val name = string()
                val durationUs = time()
                val width = input.readInt()
                val height = input.readInt()
                val hasAudio = input.readBoolean()
                val stamp = if (input.readBoolean()) AssetStamp(input.readLong(), input.readLong()) else null
                require(id.isNotEmpty() && source.isNotEmpty() && width >= 0 && height >= 0)
                require(kind == AssetKind.IMAGE || durationUs > 0)
                MediaAsset(id, kind, source, name, durationUs, width, height, hasAudio, stamp)
            }
            val byId = assets.associateBy { it.id }
            require(byId.size == assets.size)

            val main = List(count()) { mainClip(byId) }

            val texts = List(count()) {
                val id = string()
                val start = time()
                val duration = time()
                val text = string()
                val size = float(0.001f..1f)
                val color = input.readInt()
                val outlined = input.readBoolean()
                val centerX = float(0f..1f)
                val centerY = float(0f..1f)
                require(id.isNotEmpty() && duration > 0)
                TextClip(id, start, duration, text, size, color, outlined, centerX, centerY)
            }

            val audio = List(count()) {
                val id = string()
                val assetId = string()
                val start = time()
                val sourceIn = time()
                val sourceOut = time()
                val volume = float(0f..MAX_VOLUME)
                val muted = input.readBoolean()
                val fadeIn = time()
                val fadeOut = time()
                require(id.isNotEmpty() && assetId in byId && sourceIn < sourceOut)
                AudioClip(id, assetId, start, sourceIn, sourceOut, volume, muted, fadeIn, fadeOut)
            }

            val aspect = enum<CanvasAspect>()
            val background = input.readInt()
            val frameRate = FrameRate(input.readInt(), input.readInt())
            val layers = if (schema >= 2) List(count()) {
                val clip = mainClip(byId)
                LayerClip(clip, time(), float(0f..1f), float(0f..1f), float(0.05f..2f), float(0f..1f), input.readBoolean())
            } else emptyList()
            // 選択は ID で探すので、トラックをまたいでも重ならないこと。
            val ids = main.map { it.id } + texts.map { it.id } + audio.map { it.id } + layers.map { it.id }
            require(ids.toSet().size == ids.size)
            return VideoProject(byId, main, texts, audio, aspect, background, frameRate, layers)
        }

        fun mainClip(byId: Map<String, MediaAsset>): MainClip {
            val id = string()
            val assetId = string()
            val sourceIn = time()
            val sourceOut = time()
            val speed = input.readDouble()
            val keepPitch = input.readBoolean()
            val volume = float(0f..MAX_VOLUME)
            val muted = input.readBoolean()
            val fadeIn = time()
            val fadeOut = time()
            val turns = input.readInt()
            val flip = input.readBoolean()
            val fill = enum<FrameFill>()
            val kind = byId[assetId]?.kind
            require(id.isNotEmpty() && kind != null && kind != AssetKind.AUDIO && sourceIn < sourceOut)
            require(speed.isFinite() && speed in VideoEditOps.MIN_SPEED..VideoEditOps.MAX_SPEED && turns in 0..3)
            return MainClip(id, assetId, sourceIn, sourceOut, speed, keepPitch, volume, muted, fadeIn, fadeOut, turns, flip, fill)
        }

        fun export(): ExportSettings {
            val preset = enum<ExportPreset>()
            val resolution = enum<ExportResolution>()
            val fps = input.readInt()
            val codec = enum<VideoCodecChoice>()
            val targetMiB = input.readInt()
            val audioKbps = input.readInt()
            val keepLocation = input.readBoolean()
            require(fps in 1..240 && targetMiB in 1..1_000_000 && audioKbps in 1..1_024)
            return ExportSettings(preset, resolution, fps, codec, targetMiB, audioKbps, keepLocation)
        }
    }
}
