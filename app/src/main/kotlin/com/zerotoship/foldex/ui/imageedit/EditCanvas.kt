// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.zerotoship.foldex.ui.imageedit

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.zerotoship.foldex.ui.imageedit.model.EditRect
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 編集中のプレビューを表示するキャンバス。
 *
 * 操作の割り当ては全ツール共通で固定する (迷わせないため):
 *  - **指 2 本**: ズーム (0.5〜8 倍) と移動
 *  - **指 1 本**: ツールの操作。切り抜き中なら枠のドラッグ、それ以外は拡大時の移動
 */
@Composable
fun EditCanvas(
    preview: Bitmap?,
    /** 切り抜き枠 (0..1 の正規化座標)。null なら切り抜きモードではない。 */
    cropRect: EditRect?,
    onCropRectChange: (EditRect) -> Unit,
    modifier: Modifier = Modifier,
) {
    var zoom by remember { mutableFloatStateOf(1f) }
    var panX by remember { mutableFloatStateOf(0f) }
    var panY by remember { mutableFloatStateOf(0f) }
    var viewport by remember { mutableStateOf(Size.Zero) }
    var grabbed by remember { mutableStateOf(CropGrab.NONE) }

    val handleTouchPx = with(LocalDensity.current) { HANDLE_TOUCH_DP.dp.toPx() }
    val image = remember(preview) { preview?.takeIf { !it.isRecycled }?.asImageBitmap() }

    // 画像が実際に描かれる矩形。ジェスチャの座標変換と描画の両方がこれを見る。
    val imageRect = if (image == null || viewport == Size.Zero) {
        Rect.Zero
    } else {
        imageRectOf(viewport, image.width, image.height, zoom, panX, panY)
    }

    // ジェスチャ処理の中から最新値を読むためのラッチ。pointerInput のキーに入れると
    // ズーム/枠の変化のたびにジェスチャが再起動してドラッグが途切れるため、
    // キーは「切り抜き中かどうか」だけにする。
    val latestRect = rememberUpdatedState(imageRect)
    val latestCrop = rememberUpdatedState(cropRect)
    val latestZoom = rememberUpdatedState(zoom)

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { viewport = Size(it.width.toFloat(), it.height.toFloat()) }
            .pointerInput(cropRect != null) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val startCrop = latestCrop.value
                    val startRect = latestRect.value
                    grabbed = if (startCrop != null && !startRect.isEmpty) {
                        hitTestCrop(down.position, startCrop, startRect, handleTouchPx)
                    } else {
                        CropGrab.NONE
                    }
                    do {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.count { it.pressed }
                        val rect = latestRect.value
                        val crop = latestCrop.value
                        when {
                            pressed >= 2 -> {
                                // 2 本指: ズーム + 移動。切り抜き中でも効く。
                                val z = event.calculateZoom()
                                val pan = event.calculatePan()
                                if (z != 1f || pan != Offset.Zero) {
                                    zoom = (zoom * z).coerceIn(MIN_ZOOM, MAX_ZOOM)
                                    panX += pan.x
                                    panY += pan.y
                                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                                }
                                grabbed = CropGrab.NONE
                            }
                            pressed == 1 && crop != null && grabbed != CropGrab.NONE -> {
                                val pan = event.calculatePan()
                                if (pan != Offset.Zero && !rect.isEmpty) {
                                    onCropRectChange(
                                        dragCrop(crop, grabbed, pan.x / rect.width, pan.y / rect.height),
                                    )
                                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                                }
                            }
                            pressed == 1 && crop == null && latestZoom.value > 1f -> {
                                // 拡大表示中の 1 本指: 画像を動かす。
                                val pan = event.calculatePan()
                                if (pan != Offset.Zero) {
                                    panX += pan.x
                                    panY += pan.y
                                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                                }
                            }
                        }
                    } while (event.changes.any { it.pressed })
                    grabbed = CropGrab.NONE
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            if (image == null || imageRect.isEmpty) return@Canvas
            // 透明部分が分かるように市松模様を敷く。
            drawCheckerboard(imageRect)
            drawImage(
                image = image,
                dstOffset = IntOffset(imageRect.left.roundToInt(), imageRect.top.roundToInt()),
                dstSize = IntSize(
                    imageRect.width.roundToInt().coerceAtLeast(1),
                    imageRect.height.roundToInt().coerceAtLeast(1),
                ),
                filterQuality = FilterQuality.Medium,
            )
            cropRect?.let { drawCropOverlay(imageRect, it) }
        }
    }
}

/** ビューポートの中で画像が占める矩形 (Fit + ズーム + 移動)。 */
private fun imageRectOf(
    viewport: Size,
    imageWidth: Int,
    imageHeight: Int,
    zoom: Float,
    panX: Float,
    panY: Float,
): Rect {
    val baseScale = min(viewport.width / imageWidth, viewport.height / imageHeight)
    val w = imageWidth * baseScale * zoom
    val h = imageHeight * baseScale * zoom
    val left = (viewport.width - w) / 2f + panX
    val top = (viewport.height - h) / 2f + panY
    return Rect(left, top, left + w, top + h)
}

// ---- 描画 ----

private fun DrawScope.drawCheckerboard(area: Rect) {
    val cell = CHECKER_CELL_PX
    val light = Color(0xFF3A3A3A)
    val dark = Color(0xFF2E2E2E)
    drawRect(color = dark, topLeft = area.topLeft, size = area.size)
    var row = 0
    var y = area.top
    while (y < area.bottom) {
        var col = 0
        var x = area.left
        while (x < area.right) {
            if ((row + col) % 2 == 0) {
                val w = min(cell, area.right - x)
                val h = min(cell, area.bottom - y)
                drawRect(color = light, topLeft = Offset(x, y), size = Size(w, h))
            }
            x += cell
            col++
        }
        y += cell
        row++
    }
}

/** 枠の外を暗くし、罫線と隅ハンドルを描く。 */
private fun DrawScope.drawCropOverlay(area: Rect, crop: EditRect) {
    val l = area.left + crop.left * area.width
    val t = area.top + crop.top * area.height
    val r = area.left + crop.right * area.width
    val b = area.top + crop.bottom * area.height
    val shade = Color(0x99000000)

    // 枠の外側 4 領域を暗幕で覆う。
    drawRect(shade, topLeft = Offset(area.left, area.top), size = Size(area.width, t - area.top))
    drawRect(shade, topLeft = Offset(area.left, b), size = Size(area.width, area.bottom - b))
    drawRect(shade, topLeft = Offset(area.left, t), size = Size(l - area.left, b - t))
    drawRect(shade, topLeft = Offset(r, t), size = Size(area.right - r, b - t))

    // 三分割の目安線 (構図を合わせやすくする)。
    val thin = 1f
    val guide = Color(0x66FFFFFF)
    for (i in 1..2) {
        val x = l + (r - l) * i / 3f
        val y = t + (b - t) * i / 3f
        drawLine(guide, Offset(x, t), Offset(x, b), strokeWidth = thin)
        drawLine(guide, Offset(l, y), Offset(r, y), strokeWidth = thin)
    }

    // 外枠と隅ハンドル。
    val white = Color.White
    drawRect(
        color = white,
        topLeft = Offset(l, t),
        size = Size(r - l, b - t),
        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f),
    )
    val arm = HANDLE_ARM_PX
    val thickness = 6f
    listOf(
        Triple(l, t, 1f to 1f),
        Triple(r, t, -1f to 1f),
        Triple(l, b, 1f to -1f),
        Triple(r, b, -1f to -1f),
    ).forEach { (x, y, dir) ->
        val (dx, dy) = dir
        drawLine(white, Offset(x, y), Offset(x + arm * dx, y), strokeWidth = thickness)
        drawLine(white, Offset(x, y), Offset(x, y + arm * dy), strokeWidth = thickness)
    }
}

// ---- 切り抜き枠の操作 ----

internal enum class CropGrab { NONE, MOVE, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT, LEFT, RIGHT, TOP, BOTTOM }

/** タップ位置がどこを掴んだかを判定する。隅 → 辺 → 内側 の順に優先。 */
internal fun hitTestCrop(pos: Offset, crop: EditRect, area: Rect, touchPx: Float): CropGrab {
    val l = area.left + crop.left * area.width
    val t = area.top + crop.top * area.height
    val r = area.left + crop.right * area.width
    val b = area.top + crop.bottom * area.height

    val nearL = abs(pos.x - l) <= touchPx
    val nearR = abs(pos.x - r) <= touchPx
    val nearT = abs(pos.y - t) <= touchPx
    val nearB = abs(pos.y - b) <= touchPx
    val insideX = pos.x in (l - touchPx)..(r + touchPx)
    val insideY = pos.y in (t - touchPx)..(b + touchPx)

    return when {
        nearL && nearT -> CropGrab.TOP_LEFT
        nearR && nearT -> CropGrab.TOP_RIGHT
        nearL && nearB -> CropGrab.BOTTOM_LEFT
        nearR && nearB -> CropGrab.BOTTOM_RIGHT
        nearL && insideY -> CropGrab.LEFT
        nearR && insideY -> CropGrab.RIGHT
        nearT && insideX -> CropGrab.TOP
        nearB && insideX -> CropGrab.BOTTOM
        insideX && insideY -> CropGrab.MOVE
        else -> CropGrab.NONE
    }
}

/** 掴んだ場所に応じて枠を動かす。0..1 に収め、最小サイズを下回らせない。 */
internal fun dragCrop(crop: EditRect, grab: CropGrab, dxN: Float, dyN: Float): EditRect {
    fun clampRect(l: Float, t: Float, r: Float, b: Float): EditRect = EditRect(
        left = l.coerceIn(0f, r - MIN_CROP),
        top = t.coerceIn(0f, b - MIN_CROP),
        right = r.coerceIn(l + MIN_CROP, 1f),
        bottom = b.coerceIn(t + MIN_CROP, 1f),
    )
    return when (grab) {
        CropGrab.MOVE -> {
            val w = crop.width
            val h = crop.height
            val l = (crop.left + dxN).coerceIn(0f, 1f - w)
            val t = (crop.top + dyN).coerceIn(0f, 1f - h)
            EditRect(l, t, l + w, t + h)
        }
        CropGrab.LEFT -> clampRect(crop.left + dxN, crop.top, crop.right, crop.bottom)
        CropGrab.RIGHT -> clampRect(crop.left, crop.top, crop.right + dxN, crop.bottom)
        CropGrab.TOP -> clampRect(crop.left, crop.top + dyN, crop.right, crop.bottom)
        CropGrab.BOTTOM -> clampRect(crop.left, crop.top, crop.right, crop.bottom + dyN)
        CropGrab.TOP_LEFT -> clampRect(crop.left + dxN, crop.top + dyN, crop.right, crop.bottom)
        CropGrab.TOP_RIGHT -> clampRect(crop.left, crop.top + dyN, crop.right + dxN, crop.bottom)
        CropGrab.BOTTOM_LEFT -> clampRect(crop.left + dxN, crop.top, crop.right, crop.bottom + dyN)
        CropGrab.BOTTOM_RIGHT -> clampRect(crop.left, crop.top, crop.right + dxN, crop.bottom + dyN)
        CropGrab.NONE -> crop
    }
}

/**
 * 枠を [ratio] (幅 / 高さ) に合わせ直す。中心を保ったまま、はみ出さない範囲で最大にする。
 * [canvasWidth] / [canvasHeight] は論理キャンバスの画素数 — 正規化座標では縦横比が
 * 歪むので、実画素で比率を計算する必要がある。
 */
internal fun fitCropToRatio(
    crop: EditRect,
    ratio: Float,
    canvasWidth: Int,
    canvasHeight: Int,
): EditRect {
    val cx = (crop.left + crop.right) / 2f
    val cy = (crop.top + crop.bottom) / 2f
    // 現在の幅を基準に、比率を満たす高さを正規化座標で求める。
    var wN = crop.width
    var hN = wN * canvasWidth / (ratio * canvasHeight)
    if (hN > 1f) {
        hN = 1f
        wN = hN * ratio * canvasHeight / canvasWidth
    }
    // 中心を保ったまま 0..1 に収める。
    val halfW = min(wN, 1f) / 2f
    val halfH = min(hN, 1f) / 2f
    val ccx = cx.coerceIn(halfW, 1f - halfW)
    val ccy = cy.coerceIn(halfH, 1f - halfH)
    return EditRect(ccx - halfW, ccy - halfH, ccx + halfW, ccy + halfH)
}

/** 切り抜きの比率プリセット。 */
enum class CropAspect(val label: String, val ratio: Float?) {
    FREE("自由", null),
    ORIGINAL("元の比率", null),
    SQUARE("1:1", 1f),
    R4_3("4:3", 4f / 3f),
    R3_4("3:4", 3f / 4f),
    R16_9("16:9", 16f / 9f),
    R9_16("9:16", 9f / 16f),
    ;

    /** [ORIGINAL] はキャンバスの比率を使うので、その解決を含めた実効比率を返す。 */
    fun effectiveRatio(canvasWidth: Int, canvasHeight: Int): Float? = when (this) {
        FREE -> null
        ORIGINAL -> canvasWidth.toFloat() / canvasHeight
        else -> ratio
    }
}

private const val MIN_ZOOM = 0.5f
private const val MAX_ZOOM = 8f
private const val MIN_CROP = 0.02f
private const val HANDLE_TOUCH_DP = 28
private const val HANDLE_ARM_PX = 40f
private const val CHECKER_CELL_PX = 24f
