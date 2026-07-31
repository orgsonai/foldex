// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.imageedit.model

/**
 * 論理キャンバスの座標系が変わったとき、その上に載っているレイヤーを追従させる。
 *
 * 切り抜き・回転・反転は画像レイヤー自身のフィールド (crop / quarterTurns / flip) で
 * 表現されるが、**後から描いた線や文字は別の座標系に住んでいる**。追従させないと、
 * 「文字を入れてから切り抜いたら文字がずれた」ことになる。
 *
 * 画像レイヤーはここでは触らない (呼び出し側が自分のフィールドを書き換える)。
 *
 * テキストの大きさは描画側 (`DocumentRenderer.measureText`) でしか測れないので、
 * このモジュールを純 Kotlin に保つために [textSize] として外から渡してもらう。
 */
object CanvasTransforms {

    /** 切り抜きで原点が動いた分を平行移動する。 */
    fun translate(layer: Layer, dx: Float, dy: Float): Layer = when (layer) {
        is Layer.Image -> layer
        is Layer.Drawing -> layer.mapPoints { EditPoint(it.x + dx, it.y + dy) }
        is Layer.Text -> layer.movedBy(dx, dy)
    }

    /**
     * 右に 90 度まわす。キャンバスは [canvas] (回転前) から幅と高さが入れ替わる。
     * 点 (x, y) は (H - y, x) へ移る。
     */
    fun rotateRight(layer: Layer, canvas: EditSize, textSize: (Layer.Text) -> EditSize): Layer =
        when (layer) {
            is Layer.Image -> layer
            is Layer.Drawing -> layer.mapPoints { EditPoint(canvas.height - it.y, it.x) }
            is Layer.Text -> layer.recentered(textSize(layer), rotationDelta = 90f) { cx, cy ->
                (canvas.height - cy) to cx
            }
        }

    /** 左に 90 度まわす。点 (x, y) は (y, W - x) へ移る。 */
    fun rotateLeft(layer: Layer, canvas: EditSize, textSize: (Layer.Text) -> EditSize): Layer =
        when (layer) {
            is Layer.Image -> layer
            is Layer.Drawing -> layer.mapPoints { EditPoint(it.y, canvas.width - it.x) }
            is Layer.Text -> layer.recentered(textSize(layer), rotationDelta = -90f) { cx, cy ->
                cy to (canvas.width - cx)
            }
        }

    /**
     * 左右反転。
     *
     * 線は鏡像にするが、**文字は鏡文字にしない** (読めなくなるため)。位置だけ移す。
     * 傾けてある文字は、鏡に映すと傾きの向きが逆になるので角度の符号を反転する。
     */
    fun flipHorizontal(layer: Layer, canvas: EditSize, textSize: (Layer.Text) -> EditSize): Layer =
        when (layer) {
            is Layer.Image -> layer
            is Layer.Drawing -> layer.mapPoints { EditPoint(canvas.width - it.x, it.y) }
            is Layer.Text -> layer.recenteredMirrored(textSize(layer)) { cx, cy ->
                (canvas.width - cx) to cy
            }
        }

    /** 上下反転。左右反転と同じ考え方。 */
    fun flipVertical(layer: Layer, canvas: EditSize, textSize: (Layer.Text) -> EditSize): Layer =
        when (layer) {
            is Layer.Image -> layer
            is Layer.Drawing -> layer.mapPoints { EditPoint(it.x, canvas.height - it.y) }
            is Layer.Text -> layer.recenteredMirrored(textSize(layer)) { cx, cy ->
                cx to (canvas.height - cy)
            }
        }

    private fun Layer.Drawing.mapPoints(transform: (EditPoint) -> EditPoint): Layer.Drawing =
        copy(strokes = strokes.map { stroke -> stroke.copy(points = stroke.points.map(transform)) })

    /**
     * テキストは「未回転のブロックを左上に置いてから中心で回す」描き方をするので、
     * 位置の変換も**中心**で行う。左上をそのまま移すと回転のたびにずれていく。
     */
    private fun Layer.Text.recentered(
        size: EditSize,
        rotationDelta: Float,
        moveCenter: (Float, Float) -> Pair<Float, Float>,
    ): Layer.Text {
        val cx = transform.offsetX + size.width / 2f
        val cy = transform.offsetY + size.height / 2f
        val (ncx, ncy) = moveCenter(cx, cy)
        return copy(
            transform = transform.copy(
                offsetX = ncx - size.width / 2f,
                offsetY = ncy - size.height / 2f,
                rotationDeg = normalizeDegrees(transform.rotationDeg + rotationDelta),
            ),
        )
    }

    private fun Layer.Text.recenteredMirrored(
        size: EditSize,
        moveCenter: (Float, Float) -> Pair<Float, Float>,
    ): Layer.Text {
        val cx = transform.offsetX + size.width / 2f
        val cy = transform.offsetY + size.height / 2f
        val (ncx, ncy) = moveCenter(cx, cy)
        return copy(
            transform = transform.copy(
                offsetX = ncx - size.width / 2f,
                offsetY = ncy - size.height / 2f,
                rotationDeg = normalizeDegrees(-transform.rotationDeg),
            ),
        )
    }

    /** -180..180 に収める (スライダーの範囲と揃える)。 */
    private fun normalizeDegrees(degrees: Float): Float {
        var d = degrees % 360f
        if (d > 180f) d -= 360f
        if (d < -180f) d += 360f
        return d
    }
}
