// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.imageedit.draft

import com.zerotoship.foldex.core.model.FileUri
import com.zerotoship.foldex.ui.imageedit.engine.ExportReceipt
import com.zerotoship.foldex.ui.imageedit.engine.FileFingerprint
import com.zerotoship.foldex.ui.imageedit.model.*
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream

/** 明示的な version 付き DTO。Java のオブジェクト直列化や enum ordinal に依存しない。 */
internal object ImageDraftCodec {
    const val SCHEMA = 1
    const val MAX_BYTES = 16L * 1024 * 1024
    private const val MAGIC = 0x46445844 // FDXD

    fun write(draft: ImageDraft, output: OutputStream) {
        val out = DataOutputStream(output)
        out.writeInt(MAGIC)
        out.writeInt(SCHEMA)
        with(Writer(out)) {
            string(draft.id); out.writeLong(draft.revision); out.writeLong(draft.updatedAt)
            string(draft.name); string(draft.origin.localPath); optional(draft.origin.uri, ::string)
            fingerprint(draft.origin.fingerprint)
            list(draft.assets.entries.toList()) { string(it.key); fingerprint(it.value) }
            document(draft.document)
            out.writeBoolean(draft.dirty)
            optional(draft.pendingExport) { string(it.destination.toStorageString()); fingerprint(it.expected) }
            out.writeBoolean(draft.pendingExportMetadataWarning)
        }
        out.flush()
    }

    fun read(input: InputStream): ImageDraft {
        val data = DataInputStream(input)
        require(data.readInt() == MAGIC)
        if (data.readInt() != SCHEMA) throw DraftException(DraftFailure.UNSUPPORTED)
        return with(Reader(data)) {
            val id = string(); val revision = data.readLong(); val updated = data.readLong()
            require(ID.matches(id) && revision >= 0 && updated >= 0)
            val name = string()
            val origin = DraftOrigin(string(), optional(::string), fingerprint())
            require(java.io.File(origin.localPath).isAbsolute)
            require(origin.uri == null || FileUri.fromStorageStringOrNull(origin.uri) != null)
            val assetList = list { string() to fingerprint() }
            val assets = assetList.toMap()
            require(assets.isNotEmpty() && assets.size == assetList.size)
            require(assets.all { (key, fp) -> HASH.matches(key) && key == fp.sha256 })
            val doc = document()
            require(doc.layers.filterIsInstance<Layer.Image>().all { it.sourceKey in assets })
            val dirty = data.readBoolean()
            val receipt = optional {
                ExportReceipt(FileUri.fromStorageString(string()), fingerprint())
            }
            val warning = data.readBoolean()
            require(data.read() == -1)
            ImageDraft(id, revision, updated, name, origin, assets, doc, dirty, receipt, warning)
        }
    }

    val ID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    val HASH = Regex("[0-9a-f]{64}")

    private class Writer(val out: DataOutputStream) {
        private var remainingItems = 2_000_000
        fun string(value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            if (bytes.size > 1024 * 1024) throw DraftException(DraftFailure.TOO_LARGE)
            out.writeInt(bytes.size); out.write(bytes)
        }
        fun <T> optional(value: T?, write: (T) -> Unit) {
            out.writeBoolean(value != null)
            if (value != null) write(value)
        }
        fun <T> list(values: List<T>, write: (T) -> Unit) {
            if (values.size > 1_000_000 || values.size > remainingItems) throw DraftException(DraftFailure.TOO_LARGE)
            remainingItems -= values.size
            out.writeInt(values.size); values.forEach(write)
        }
        fun fingerprint(fp: FileFingerprint) { out.writeLong(fp.size); string(fp.sha256) }
        fun point(p: EditPoint) { out.writeFloat(p.x); out.writeFloat(p.y) }
        fun rect(r: EditRect) {
            out.writeFloat(r.left); out.writeFloat(r.top); out.writeFloat(r.right); out.writeFloat(r.bottom)
        }
        fun transform(t: LayerTransform) {
            out.writeFloat(t.offsetX); out.writeFloat(t.offsetY); out.writeFloat(t.scale); out.writeFloat(t.rotationDeg)
        }
        fun selection(s: SelectionSpec, depth: Int = 0) {
            if (depth > 64) throw DraftException(DraftFailure.TOO_LARGE)
            when (s) {
                is SelectionSpec.Rect -> { string("rect"); rect(s.rect) }
                is SelectionSpec.Ellipse -> { string("ellipse"); rect(s.rect) }
                is SelectionSpec.Lasso -> { string("lasso"); list(s.points, ::point) }
                is SelectionSpec.Contours -> { string("contours"); list(s.rings) { list(it, ::point) } }
                is SelectionSpec.Combined -> {
                    string("combined"); list(s.parts) { string(it.op.name); selection(it.spec, depth + 1) }
                }
            }
        }
        fun document(doc: EditDocument) {
            if (doc.layers.size > 10_000) throw DraftException(DraftFailure.TOO_LARGE)
            out.writeInt(doc.canvas.width); out.writeInt(doc.canvas.height)
            optional((doc.canvas.background as? Background.Solid)?.color, out::writeInt)
            with(doc.output) {
                out.writeFloat(scale); string(format.name); out.writeInt(quality)
                out.writeInt(jpegBackground); string(metadata.name); optional(targetBytes, out::writeInt)
            }
            string(doc.activeLayerId); optional(doc.selection) { selection(it) }
            list(doc.layers) { layer ->
                string(when (layer) {
                    is Layer.Image -> "image"; is Layer.Drawing -> "drawing"; is Layer.Text -> "text"
                    is Layer.Fill -> "fill"; is Layer.Mosaic -> "mosaic"
                })
                string(layer.id); string(layer.name); out.writeBoolean(layer.visible)
                out.writeFloat(layer.alpha); transform(layer.transform)
                when (layer) {
                    is Layer.Image -> {
                        string(layer.sourceKey); out.writeInt(layer.sourceSize.width); out.writeInt(layer.sourceSize.height)
                        string(layer.sourceTransparency.name); optional(layer.crop, ::rect)
                        out.writeInt(layer.quarterTurns); out.writeBoolean(layer.flipH); out.writeBoolean(layer.flipV)
                    }
                    is Layer.Drawing -> list(layer.strokes) {
                        list(it.points, ::point); out.writeFloat(it.widthPx); out.writeInt(it.color)
                        string(it.mode.name); out.writeFloat(it.hardness)
                    }
                    is Layer.Text -> {
                        string(layer.text)
                        with(layer.style) {
                            out.writeFloat(sizePx); out.writeInt(color); string(font.name); out.writeBoolean(bold)
                            string(outline.name); optional(backgroundColor, out::writeInt)
                        }
                    }
                    is Layer.Fill -> { selection(layer.area); out.writeInt(layer.color); string(layer.mode.name) }
                    is Layer.Mosaic -> { selection(layer.area); out.writeFloat(layer.blockSizePx) }
                }
            }
        }
    }

    private class Reader(val input: DataInputStream) {
        // 不正な長さ・入れ子で巨大なリストを先に確保しない。
        private var remainingItems = 2_000_000
        fun count(max: Int = 1_000_000): Int {
            val n = input.readInt()
            require(n in 0..max && n <= remainingItems)
            remainingItems -= n
            return n
        }
        fun string(): String {
            val length = input.readInt()
            require(length in 0..1024 * 1024)
            val bytes = ByteArray(length)
            input.readFully(bytes)
            val decoder = Charsets.UTF_8.newDecoder()
            return decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        }
        fun <T> optional(read: () -> T): T? = if (input.readBoolean()) read() else null
        fun <T> list(read: () -> T): List<T> = List(count()) { read() }
        inline fun <reified E : Enum<E>> enum(): E {
            val name = string()
            return enumValues<E>().firstOrNull { it.name == name } ?: throw DraftException(DraftFailure.UNSUPPORTED)
        }
        fun number(): Float = input.readFloat().also { require(it.isFinite() && kotlin.math.abs(it) <= 10_000_000f) }
        fun positive(): Float = number().also { require(it > 0) }
        fun unit(): Float = number().also { require(it in 0f..1f) }
        fun dimension(): Int = input.readInt().also { require(it in 1..1_000_000) }
        fun fingerprint(): FileFingerprint = FileFingerprint(input.readLong(), string()).also {
            require(it.size > 0 && HASH.matches(it.sha256))
        }
        fun point(): EditPoint = EditPoint(number(), number())
        fun rect(): EditRect = EditRect(number(), number(), number(), number()).also {
            require(it.right >= it.left && it.bottom >= it.top)
        }
        fun transform(): LayerTransform = LayerTransform(number(), number(), positive(), number())
        fun selection(depth: Int = 0): SelectionSpec {
            require(depth <= 64)
            return when (string()) {
                "rect" -> SelectionSpec.Rect(rect())
                "ellipse" -> SelectionSpec.Ellipse(rect())
                "lasso" -> SelectionSpec.Lasso(list(::point))
                "contours" -> SelectionSpec.Contours(list { list(::point) })
                "combined" -> SelectionSpec.Combined(list { SelectionSpec.Part(enum(), selection(depth + 1)) })
                else -> throw DraftException(DraftFailure.UNSUPPORTED)
            }
        }
        fun document(): EditDocument {
            val canvas = CanvasSpec(dimension(), dimension(), optional(input::readInt)?.let { Background.Solid(it) } ?: Background.Transparent)
            val output = OutputSpec(positive(), enum(), input.readInt(), input.readInt(), enum(), optional(input::readInt))
            require(output.quality in 1..100 && (output.targetBytes == null || output.targetBytes > 0))
            require(output.jpegBackground ushr 24 == 255)
            val active = string(); val selection = optional { selection() }
            val layers = List(count(10_000)) {
                val type = string(); val id = string(); val name = string(); val visible = input.readBoolean()
                val alpha = unit(); val transform = transform()
                when (type) {
                    "image" -> {
                        val source = string(); val size = EditSize(dimension(), dimension())
                        val transparency = enum<Transparency>(); val crop = optional(::rect)
                        val turns = input.readInt().also { require(it in 0..3) }
                        require(crop == null || (!crop.isEmpty && crop.left >= 0 && crop.top >= 0 &&
                            crop.right <= size.width && crop.bottom <= size.height))
                        Layer.Image(id, name, visible, alpha, transform, source, size, transparency, crop, turns,
                            input.readBoolean(), input.readBoolean())
                    }
                    "drawing" -> Layer.Drawing(id, name, visible, alpha, transform, list {
                        Stroke(list(::point), positive(), input.readInt(), enum(), unit())
                    })
                    "text" -> Layer.Text(id, name, visible, alpha, transform, string(),
                        TextStyleSpec(positive(), input.readInt(), enum(), input.readBoolean(), enum(), optional(input::readInt)))
                    "fill" -> Layer.Fill(id, name, visible, alpha, transform, selection(), input.readInt(), enum())
                    "mosaic" -> Layer.Mosaic(id, name, visible, alpha, transform, selection(), positive())
                    else -> throw DraftException(DraftFailure.UNSUPPORTED)
                }
            }
            require(layers.isNotEmpty() && layers.map { it.id }.distinct().size == layers.size)
            // CE0-D は既存の単一素材エディタの保存形式。複数素材は CE3 で schema を拡張する。
            require(layers.count { it is Layer.Image } == 1 && layers.any { it.id == active && it is Layer.Image })
            val doc = EditDocument(canvas, output, layers, active, selection)
            require(doc.outputSize.longEdge <= 8192)
            return doc
        }
    }
}
