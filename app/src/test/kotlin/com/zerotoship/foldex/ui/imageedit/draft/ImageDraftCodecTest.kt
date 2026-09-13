// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.imageedit.draft

import com.zerotoship.foldex.core.model.FileUri
import com.zerotoship.foldex.ui.imageedit.engine.ExportReceipt
import com.zerotoship.foldex.ui.imageedit.engine.FileFingerprint
import com.zerotoship.foldex.ui.imageedit.model.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID

class ImageDraftCodecTest {
    private val fp = FileFingerprint(256, "a".repeat(64))
    private val ring = listOf(EditPoint(5f, 5f), EditPoint(100f, 5f), EditPoint(100f, 100f), EditPoint(5f, 100f))
    private val selection = SelectionSpec.Combined(listOf(
        SelectionSpec.Part(BoolOp.ADD, SelectionSpec.Rect(EditRect(0f, 0f, 200f, 200f))),
        SelectionSpec.Part(BoolOp.SUBTRACT, SelectionSpec.Ellipse(EditRect(20f, 20f, 80f, 80f))),
        SelectionSpec.Part(BoolOp.ADD, SelectionSpec.Contours(listOf(ring, ring.map { EditPoint(it.x / 2, it.y / 2) }))),
        SelectionSpec.Part(BoolOp.SUBTRACT, SelectionSpec.Lasso(ring)),
    ))
    private fun draft(): ImageDraft {
        val base = EditDocument.ofSingleImage("base", "写真", fp.sha256, EditSize(1600, 1200), ImageFormat.WEBP)
        val transform = LayerTransform(10.5f, -20f, 1.2f, 15f)
        val document = base.copy(
            canvas = CanvasSpec(800, 600, Background.Solid(0xff222222.toInt())),
            output = OutputSpec(.5f, ImageFormat.WEBP, 74, 0xff445566.toInt(), MetadataPolicy.REMOVE_LOCATION, 200_000),
            selection = selection,
            layers = listOf(
                (base.layers.single() as Layer.Image).copy(crop = EditRect(10f, 20f, 810f, 620f),
                    quarterTurns = 3, flipH = true, flipV = true, sourceTransparency = Transparency.HAS_ALPHA),
                Layer.Drawing("draw", "線", transform = transform, alpha = .7f, strokes = listOf(
                    Stroke(ring, 5f, 0x12345678, hardness = .3f), Stroke(ring, 20f, -1, StrokeMode.ERASE))),
                Layer.Fill("fill", "透明化", visible = false, area = selection, color = -1, mode = FillMode.CLEAR),
                Layer.Fill("paint", "塗り", area = SelectionSpec.Ellipse(EditRect(0f, 0f, 20f, 50f)), color = 0xff123456.toInt(), mode = FillMode.COLOR),
                Layer.Mosaic("mosaic", "モザイク", transform = transform, area = selection, blockSizePx = 12.5f),
                Layer.Text("text", "文字", transform = transform, text = "日本語\n😀 二行目\\literal", style = TextStyleSpec(
                    23f, 0xff234567.toInt(), TextFont.MONOSPACE, true, TextOutline.WHITE, 0x12345678)),
            ),
        )
        return ImageDraft(UUID.randomUUID().toString(), 42, 123456, "画像.png",
            DraftOrigin("/original/image.png", "saf://content://provider/document/photo", fp),
            mapOf(fp.sha256 to fp), document, true,
            ExportReceipt(FileUri.Saf("content://provider/document/export"), fp))
    }
    private fun bytes(draft: ImageDraft) = ByteArrayOutputStream().also { ImageDraftCodec.write(draft, it) }.toByteArray()
    private fun decode(bytes: ByteArray) = ImageDraftCodec.read(ByteArrayInputStream(bytes))

    @Test fun `全レイヤーと複合選択と出力方針を数値の変化なく往復する`() {
        val draft = draft()
        assertEquals(draft, decode(bytes(draft)))
    }

    @Test fun `透過背景と任意項目なしの記録を復元する`() {
        val draft = draft().let { it.copy(pendingExport = null, origin = it.origin.copy(uri = null),
            document = it.document.copy(canvas = it.document.canvas.copy(background = Background.Transparent),
                selection = null, output = it.document.output.copy(targetBytes = null, metadata = MetadataPolicy.SHARE))) }
        assertEquals(draft, decode(bytes(draft)))
    }

    @Test fun `穴と離れた領域の選択判定が復元前後で一致する`() {
        val original = draft()
        val restored = decode(bytes(original))
        for (y in 0..210 step 7) for (x in 0..210 step 7) {
            assertEquals(original.document.selection!!.contains(x.toFloat(), y.toFloat()),
                restored.document.selection!!.contains(x.toFloat(), y.toFloat()))
        }
    }

    @Test fun `未知のschemaは明示的に非対応となる`() {
        try {
            decode(bytes(draft()).apply { this[7] = (ImageDraftCodec.SCHEMA + 1).toByte() })
            fail("Unsupported version was accepted")
        } catch (e: DraftException) { assertEquals(DraftFailure.UNSUPPORTED, e.reason) }
    }

    @Test fun `切れた記録を部分的に復元しない`() {
        val bytes = bytes(draft())
        for (length in listOf(0, 7, 20, bytes.size / 2, bytes.size - 1)) {
            assertTrue(runCatching { decode(bytes.copyOf(length)) }.isFailure)
        }
    }

    @Test fun `末尾に未知のデータを持つ記録を黙って読み飛ばさない`() {
        assertTrue(runCatching { decode(bytes(draft()) + byteArrayOf(0)) }.isFailure)
    }

    @Test fun `素材参照の欠落と重複レイヤーを受け入れない`() {
        val draft = draft()
        assertTrue(runCatching { decode(bytes(draft.copy(assets = emptyMap()))) }.isFailure)
        assertTrue(runCatching { decode(bytes(draft.copy(document = draft.document.copy(
            layers = draft.document.layers + draft.document.layers.last())))) }.isFailure)
    }

    @Test fun `NaNや過大な出力寸法を描画モデルへ渡さない`() {
        val draft = draft()
        for (scale in listOf(Float.NaN, Float.POSITIVE_INFINITY, -1f, 100f)) {
            assertTrue(runCatching { decode(bytes(draft.copy(document = draft.document.copy(
                output = draft.document.output.copy(scale = scale))))) }.isFailure)
        }
    }

    @Test fun `過度に深い選択の入れ子を保存しない`() {
        var area: SelectionSpec = SelectionSpec.Rect(EditRect(0f, 0f, 1f, 1f))
        repeat(66) { area = SelectionSpec.Combined(listOf(SelectionSpec.Part(BoolOp.ADD, area))) }
        val draft = draft()
        assertTrue(runCatching { bytes(draft.copy(document = draft.document.copy(selection = area))) }.isFailure)
    }

    @Test fun `不正な文字列長で大容量メモリを確保しない`() {
        val bytes = bytes(draft())
        for (i in 8..11) bytes[i] = 0x7f
        assertTrue(runCatching { decode(bytes) }.isFailure)
    }
    @Test fun `1_8_5の実際のcodecで作った下書きを同じ内容で読み込める`() {
        val encoded = javaClass.getResourceAsStream("/imageedit/draft-1.8.5-schema1.base64")!!.bufferedReader().use { it.readText() }.trim()
        val restored = decode(java.util.Base64.getDecoder().decode(encoded))
        assertEquals(draft().copy(id = restored.id), restored)
        assertEquals(restored, decode(bytes(restored)))
    }

    @Test fun `ブラシの半径と端のクリップと複合選択を保存復元する`() {
        val original = draft()
        val stroke = SelectionSpec.BrushStroke(listOf(EditPoint(-3f, 20f), EditPoint(30f, 30f)), 6f, EditRect.ofSize(800, 600))
        val area = original.document.selection.applying(SelectionMode.SUBTRACT, stroke)
        val updated = original.copy(document = original.document.copy(selection = area,
            layers = original.document.layers + Layer.Mosaic("new-mosaic", "mosaic", area = area!!, blockSizePx = 16f)))
        assertEquals(updated, decode(bytes(updated)))
    }

    @Test fun `schema1に新しい選択型が現れても勝手に受け入れない`() {
        val original = draft()
        val updated = original.copy(document = original.document.copy(selection =
            SelectionSpec.BrushStroke(listOf(EditPoint(20f, 20f)), 6f)))
        try {
            decode(bytes(updated).apply { this[7] = 1 })
            fail("Unsupported schema 1 operation was accepted")
        } catch (error: DraftException) { assertEquals(DraftFailure.UNSUPPORTED, error.reason) }
    }

}
