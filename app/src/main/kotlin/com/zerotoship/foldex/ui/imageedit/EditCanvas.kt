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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke as DrawStroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.zerotoship.foldex.ui.imageedit.model.EditPoint
import com.zerotoship.foldex.ui.imageedit.model.EditRect
import com.zerotoship.foldex.ui.imageedit.model.EditSize
import com.zerotoship.foldex.ui.imageedit.model.Stroke
import com.zerotoship.foldex.ui.imageedit.model.StrokeMode
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 編集中のプレビューを表示するキャンバス。
 *
 * 操作の割り当ては全ツール共通で固定する (迷わせないため):
 *  - **指 2 本**: ズーム (0.5〜8 倍) と移動。どのツール中でも効く
 *  - **指 1 本**: 選んでいるツールの操作 ([CanvasMode])
 */
@Composable
fun EditCanvas(
    preview: Bitmap?,
    /** 論理キャンバスの大きさ。画面座標との変換に使う。 */
    canvasSize: EditSize?,
    mode: CanvasMode,
    modifier: Modifier = Modifier,
) {
    var zoom by remember { mutableFloatStateOf(1f) }
    var panX by remember { mutableFloatStateOf(0f) }
    var panY by remember { mutableFloatStateOf(0f) }
    var viewport by remember { mutableStateOf(Size.Zero) }
    var grabbed by remember { mutableStateOf(CropGrab.NONE) }
    // 描画中のストローク (画面座標)。指を離したら論理座標へ直して確定する。
    val livePoints = remember { mutableStateListOf<Offset>() }

    val handleTouchPx = with(LocalDensity.current) { HANDLE_TOUCH_DP.dp.toPx() }
    val image = remember(preview) { preview?.takeIf { !it.isRecycled }?.asImageBitmap() }

    // 画像が実際に描かれる矩形。ジェスチャの座標変換と描画の両方がこれを見る。
    val imageRect = if (image == null || viewport == Size.Zero) {
        Rect.Zero
    } else {
        imageRectOf(viewport, image.width, image.height, zoom, panX, panY)
    }

    // ジェスチャ処理の中から最新値を読むためのラッチ。pointerInput のキーに入れると
    // ズームや設定の変化のたびにジェスチャが再起動してドラッグが途切れる。
    val latestRect = rememberUpdatedState(imageRect)
    val latestMode = rememberUpdatedState(mode)
    val latestZoom = rememberUpdatedState(zoom)
    val latestCanvas = rememberUpdatedState(canvasSize)

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { viewport = Size(it.width.toFloat(), it.height.toFloat()) }
            .pointerInput(mode.key) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val startMode = latestMode.value
                    val startRect = latestRect.value

                    grabbed = if (startMode is CanvasMode.Crop && !startRect.isEmpty) {
                        hitTestCrop(down.position, startMode.rect, startRect, handleTouchPx)
                    } else {
                        CropGrab.NONE
                    }
                    if (startMode is CanvasMode.Brush && !startRect.isEmpty) {
                        livePoints.clear()
                        livePoints.add(down.position)
                    }

                    var multiTouch = false
                    do {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.count { it.pressed }
                        val rect = latestRect.value
                        val current = latestMode.value
                        when {
                            pressed >= 2 -> {
                                // 2 本指: ズーム + 移動。描きかけのストロークは捨てる
                                // (拡大しようとして線が引かれるのを防ぐ)。
                                multiTouch = true
                                livePoints.clear()
                                grabbed = CropGrab.NONE
                                val z = event.calculateZoom()
                                val pan = event.calculatePan()
                                if (z != 1f || pan != Offset.Zero) {
                                    zoom = (zoom * z).coerceIn(MIN_ZOOM, MAX_ZOOM)
                                    panX += pan.x
                                    panY += pan.y
                                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                                }
                            }
                            pressed == 1 && !multiTouch -> when (current) {
                                is CanvasMode.Crop -> {
                                    val pan = event.calculatePan()
                                    if (grabbed != CropGrab.NONE && pan != Offset.Zero && !rect.isEmpty) {
                                        current.onChange(
                                            dragCrop(
                                                current.rect,
                                                grabbed,
                                                pan.x / rect.width,
                                                pan.y / rect.height,
                                            ),
                                        )
                                        event.changes.forEach { if (it.positionChanged()) it.consume() }
                                    }
                                }
                                is CanvasMode.Brush -> {
                                    val pos = event.changes.firstOrNull { it.pressed }?.position
                                    if (pos != null) {
                                        val last = livePoints.lastOrNull()
                                        if (last == null ||
                                            abs(pos.x - last.x) + abs(pos.y - last.y) >= MIN_POINT_DISTANCE_PX
                                        ) {
                                            livePoints.add(pos)
                                        }
                                        event.changes.forEach { if (it.positionChanged()) it.consume() }
                                    }
                                }
                                is CanvasMode.TextMove -> {
                                    val pan = event.calculatePan()
                                    val canvas = latestCanvas.value
                                    if (pan != Offset.Zero && !rect.isEmpty && canvas != null) {
                                        current.onDrag(
                                            pan.x / rect.width * canvas.width,
                                            pan.y / rect.height * canvas.height,
                                        )
                                        event.changes.forEach { if (it.positionChanged()) it.consume() }
                                    }
                                }
                                is CanvasMode.View -> {
                                    if (latestZoom.value > 1f) {
                                        val pan = event.calculatePan()
                                        if (pan != Offset.Zero) {
                                            panX += pan.x
                                            panY += pan.y
                                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                                        }
                                    }
                                }
                            }
                        }
                    } while (event.changes.any { it.pressed })

                    // 指を離した。ツールごとの後始末。
                    val finished = latestMode.value
                    if (finished is CanvasMode.Brush && livePoints.isNotEmpty() && !multiTouch) {
                        val canvas = latestCanvas.value
                        val rect = latestRect.value
                        if (canvas != null && !rect.isEmpty) {
                            finished.onStroke(
                                buildStroke(livePoints.toList(), rect, canvas, finished.settings),
                            )
                        }
                    }
                    if (finished is CanvasMode.TextMove) finished.onDragEnd()
                    livePoints.clear()
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
            when (mode) {
                is CanvasMode.Crop -> drawCropOverlay(imageRect, mode.rect)
                is CanvasMode.Brush -> drawLiveStroke(livePoints, mode.settings)
                is CanvasMode.TextMove -> mode.bounds?.let { bounds ->
                    canvasSize?.let { drawTextFrame(imageRect, bounds, it) }
                }
                is CanvasMode.View -> Unit
            }
        }
    }
}

/**
 * キャンバスで受け付ける 1 本指操作。2 本指のズーム/移動はモードによらず常に効く。
 */
sealed interface CanvasMode {
    /** ジェスチャ処理を張り替える単位。設定値が変わっただけでは再起動させない。 */
    val key: String

    /** 何も編集しない (拡大中は 1 本指で移動)。 */
    data object View : CanvasMode {
        override val key: String get() = "view"
    }

    data class Crop(
        val rect: EditRect,
        val onChange: (EditRect) -> Unit,
    ) : CanvasMode {
        override val key: String get() = "crop"
    }

    data class Brush(
        val settings: BrushSettings,
        val onStroke: (Stroke) -> Unit,
    ) : CanvasMode {
        override val key: String get() = "brush"
    }

    data class TextMove(
        /** 編集中テキストの範囲 (論理キャンバス座標)。枠線で示す。 */
        val bounds: EditRect?,
        /** 論理キャンバス座標での移動量。 */
        val onDrag: (Float, Float) -> Unit,
        val onDragEnd: () -> Unit,
    ) : CanvasMode {
        override val key: String get() = "textmove"
    }
}

/** ブラシの設定。太さは**画面上の px** で持つ (見たままの太さで描けるように)。 */
data class BrushSettings(
    val widthScreenPx: Float,
    val color: Int,
    val mode: StrokeMode,
    val hardness: Float,
    val alpha: Float,
)

// ---- 座標変換 ----

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

/**
 * 画面座標の軌跡を論理キャンバス座標のストロークに直す。
 * 太さも同じ比率で換算するので、画面で見たとおりの太さで残る。
 */
private fun buildStroke(
    screenPoints: List<Offset>,
    imageRect: Rect,
    canvas: EditSize,
    settings: BrushSettings,
): Stroke {
    val scaleX = canvas.width / imageRect.width
    val scaleY = canvas.height / imageRect.height
    val points = screenPoints.map { p ->
        EditPoint(
            x = (p.x - imageRect.left) * scaleX,
            y = (p.y - imageRect.top) * scaleY,
        )
    }
    val color = if (settings.mode == StrokeMode.ERASE) {
        settings.color
    } else {
        withAlpha(settings.color, settings.alpha)
    }
    return Stroke(
        points = Stroke.simplify(points, minDistance = MIN_POINT_DISTANCE_PX * scaleX),
        widthPx = settings.widthScreenPx * scaleX,
        color = color,
        mode = settings.mode,
        hardness = settings.hardness,
    )
}

private fun withAlpha(color: Int, alpha: Float): Int {
    val a = (alpha.coerceIn(0f, 1f) * 255).roundToInt()
    return (color and 0x00FFFFFF) or (a shl 24)
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

/** 描いている最中の線。指を離すまでは確定していないので、ここで重ねて見せる。 */
private fun DrawScope.drawLiveStroke(points: List<Offset>, settings: BrushSettings) {
    if (points.isEmpty()) return
    val path = Path()
    path.moveTo(points[0].x, points[0].y)
    for (i in 1 until points.size) {
        val prev = points[i - 1]
        val cur = points[i]
        path.quadraticTo(prev.x, prev.y, (prev.x + cur.x) / 2f, (prev.y + cur.y) / 2f)
    }
    points.lastOrNull()?.let { path.lineTo(it.x, it.y) }
    // 消しゴムは「消える様子」を重ねて描けないので、半透明の白でなぞった跡を示す。
    val color = if (settings.mode == StrokeMode.ERASE) {
        Color.White.copy(alpha = 0.6f)
    } else {
        Color(settings.color).copy(alpha = settings.alpha)
    }
    drawPath(
        path = path,
        color = color,
        style = DrawStroke(
            width = settings.widthScreenPx,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
        ),
    )
}

/** 編集中テキストの位置を点線の枠で示す。 */
private fun DrawScope.drawTextFrame(area: Rect, bounds: EditRect, canvas: EditSize) {
    val sx = area.width / canvas.width
    val sy = area.height / canvas.height
    val pad = 6f
    val left = area.left + bounds.left * sx - pad
    val top = area.top + bounds.top * sy - pad
    val right = area.left + bounds.right * sx + pad
    val bottom = area.top + bounds.bottom * sy + pad
    drawRect(
        color = Color(0xFF4C8DFF),
        topLeft = Offset(left, top),
        size = Size((right - left).coerceAtLeast(1f), (bottom - top).coerceAtLeast(1f)),
        style = DrawStroke(
            width = 2f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f)),
        ),
    )
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
    val guide = Color(0x66FFFFFF)
    for (i in 1..2) {
        val x = l + (r - l) * i / 3f
        val y = t + (b - t) * i / 3f
        drawLine(guide, Offset(x, t), Offset(x, b), strokeWidth = 1f)
        drawLine(guide, Offset(l, y), Offset(r, y), strokeWidth = 1f)
    }

    // 外枠と隅ハンドル。
    val white = Color.White
    drawRect(
        color = white,
        topLeft = Offset(l, t),
        size = Size(r - l, b - t),
        style = DrawStroke(width = 2f),
    )
    val arm = HANDLE_ARM_PX
    listOf(
        Triple(l, t, 1f to 1f),
        Triple(r, t, -1f to 1f),
        Triple(l, b, 1f to -1f),
        Triple(r, b, -1f to -1f),
    ).forEach { (x, y, dir) ->
        val (dx, dy) = dir
        drawLine(white, Offset(x, y), Offset(x + arm * dx, y), strokeWidth = 6f)
        drawLine(white, Offset(x, y), Offset(x, y + arm * dy), strokeWidth = 6f)
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
    var wN = crop.width
    var hN = wN * canvasWidth / (ratio * canvasHeight)
    if (hN > 1f) {
        hN = 1f
        wN = hN * ratio * canvasHeight / canvasWidth
    }
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

/** これ未満しか動いていない点は捨てる (画面 px)。 */
private const val MIN_POINT_DISTANCE_PX = 3f
