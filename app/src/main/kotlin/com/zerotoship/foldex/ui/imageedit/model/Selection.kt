// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.imageedit.model

import kotlin.math.max
import kotlin.math.min

/**
 * 選択範囲。矩形・楕円・投げ縄を **1 つの形に揃える**ための器。
 *
 * 揃えないと「塗りつぶし」「透明にする」「反転」の実装がツールの数だけ増える。
 * ここで `contains` / `bounds` を持たせておけば、後から足すツール (v4 のマジックワンド) も
 * この interface を実装するだけで既存の操作がそのまま効く。
 *
 * 座標は**論理キャンバス座標**。画素に焼かないので、プレビュー解像度で選んだ範囲を
 * 保存時に原寸で描き直せる (拡大してもギザギザにならない)。
 *
 * `android.graphics.Path` を持たないのは、モデル層を純 Kotlin に保って JVM テストで
 * 回すため ([EditGeom] と同じ理由)。Android の Path への変換は engine 層で行う。
 */
sealed interface SelectionSpec {

    /** この選択を含む最小の矩形。塗りつぶしの走査範囲を絞るのに使う。 */
    val bounds: EditRect

    /** 面積を持たない選択 (点をタップしただけ、など)。 */
    val isEmpty: Boolean get() = bounds.isEmpty

    /** 論理キャンバス座標の点が選択の内側か。 */
    fun contains(x: Float, y: Float): Boolean

    data class Rect(val rect: EditRect) : SelectionSpec {
        override val bounds: EditRect get() = rect

        override fun contains(x: Float, y: Float): Boolean =
            x >= rect.left && x < rect.right && y >= rect.top && y < rect.bottom
    }

    /** [rect] に内接する楕円。 */
    data class Ellipse(val rect: EditRect) : SelectionSpec {
        override val bounds: EditRect get() = rect

        override fun contains(x: Float, y: Float): Boolean {
            val rx = rect.width / 2f
            val ry = rect.height / 2f
            if (rx <= 0f || ry <= 0f) return false
            val nx = (x - (rect.left + rx)) / rx
            val ny = (y - (rect.top + ry)) / ry
            return nx * nx + ny * ny <= 1f
        }
    }

    /** 投げ縄。指の軌跡をそのまま多角形として閉じる。 */
    data class Lasso(val points: List<EditPoint>) : SelectionSpec {
        override val bounds: EditRect
            get() {
                if (points.isEmpty()) return EditRect(0f, 0f, 0f, 0f)
                var l = points[0].x
                var t = points[0].y
                var r = l
                var b = t
                for (p in points) {
                    l = min(l, p.x); t = min(t, p.y)
                    r = max(r, p.x); b = max(b, p.y)
                }
                return EditRect(l, t, r, b)
            }

        /**
         * 交差数判定 (even-odd)。点から右に伸ばした半直線が辺を何回横切るかを数え、
         * 奇数なら内側。凹んだ形や自分と交差する軌跡でも破綻しない。
         */
        override fun contains(x: Float, y: Float): Boolean {
            if (points.size < MIN_POLYGON_POINTS) return false
            var inside = false
            var j = points.lastIndex
            for (i in points.indices) {
                val pi = points[i]
                val pj = points[j]
                // 辺が走査線をまたぐときだけ交点を計算する (ゼロ除算をここで避けている)。
                if ((pi.y > y) != (pj.y > y) &&
                    x < (pj.x - pi.x) * (y - pi.y) / (pj.y - pi.y) + pi.x
                ) {
                    inside = !inside
                }
                j = i
            }
            return inside
        }
    }

    /** 硬い円形ブラシの軌跡。半径と点列を論理座標で保持し、保存倍率で粗くならない。 */
    data class BrushStroke(val points: List<EditPoint>, val radius: Float, val clip: EditRect? = null) : SelectionSpec {
        init {
            require(points.isNotEmpty() && points.size <= MAX_BRUSH_POINTS)
            require(radius.isFinite() && radius > 0f)
            require(points.all { it.x.isFinite() && it.y.isFinite() })
            require(clip == null || (!clip.isEmpty && listOf(clip.left, clip.top, clip.right, clip.bottom).all { it.isFinite() }))
        }

        private val strokeBounds = EditRect(
            points.minOf { it.x } - radius, points.minOf { it.y } - radius,
            points.maxOf { it.x } + radius, points.maxOf { it.y } + radius,
        )
        override val bounds: EditRect = if (clip == null) strokeBounds else
            strokeBounds.intersect(clip) ?: EditRect(0f, 0f, 0f, 0f)

        override fun contains(x: Float, y: Float): Boolean {
            if (bounds.isEmpty || x < bounds.left || x > bounds.right || y < bounds.top || y > bounds.bottom) return false
            if (clip != null && (x >= clip.right || y >= clip.bottom)) return false
            val radiusSquared = radius * radius
            var previous = points.first()
            for (point in points) {
                val dx = point.x - previous.x
                val dy = point.y - previous.y
                val lengthSquared = dx * dx + dy * dy
                val t = if (lengthSquared == 0f) 0f else
                    (((x - previous.x) * dx + (y - previous.y) * dy) / lengthSquared).coerceIn(0f, 1f)
                val distanceX = x - (previous.x + dx * t)
                val distanceY = y - (previous.y + dy * t)
                if (distanceX * distanceX + distanceY * distanceY <= radiusSquared) return true
                previous = point
            }
            return false
        }
    }

    /** 自動選択の閉じた輪郭。even-odd で穴と離れた領域をともに保持する。 */
    data class Contours(val rings: List<List<EditPoint>>) : SelectionSpec {
        private val polygons = rings.filter { it.size >= 3 }.map { Lasso(it) }
        override val bounds: EditRect = if (polygons.isEmpty()) {
            EditRect(0f, 0f, 0f, 0f)
        } else {
            val boxes = polygons.map { it.bounds }
            EditRect(boxes.minOf { it.left }, boxes.minOf { it.top }, boxes.maxOf { it.right }, boxes.maxOf { it.bottom })
        }

        override fun contains(x: Float, y: Float): Boolean =
            polygons.fold(false) { inside, ring -> inside xor ring.contains(x, y) }
    }

    /**
     * 「追加 (＋)」「除外 (−)」の積み重ね。[parts] を**先頭から順に**適用する。
     *
     * 反転もこれで表せる (キャンバス全体を足してから今の選択を引く) ので、
     * 反転専用の型を作っていない。
     */
    data class Combined(val parts: List<Part>) : SelectionSpec {
        override val bounds: EditRect
            get() {
                // 除外は範囲を広げないので、追加した分だけを見れば足りる。
                val added = parts.filter { it.op == BoolOp.ADD }.map { it.spec.bounds }
                if (added.isEmpty()) return EditRect(0f, 0f, 0f, 0f)
                return EditRect(
                    left = added.minOf { it.left },
                    top = added.minOf { it.top },
                    right = added.maxOf { it.right },
                    bottom = added.maxOf { it.bottom },
                )
            }

        override fun contains(x: Float, y: Float): Boolean {
            var inside = false
            for (part in parts) {
                val hit = part.spec.contains(x, y)
                inside = when (part.op) {
                    BoolOp.ADD -> inside || hit
                    BoolOp.SUBTRACT -> inside && !hit
                }
            }
            return inside
        }
    }

    /** [Combined] の 1 手。 */
    data class Part(val op: BoolOp, val spec: SelectionSpec)

    companion object {
        /** 多角形として成立する最小の点数。 */
        private const val MIN_POLYGON_POINTS = 3
        const val MAX_BRUSH_POINTS = 4096

        /** キャンバス全体を覆う選択。反転の下敷きに使う。 */
        fun whole(canvas: EditSize): SelectionSpec =
            Rect(EditRect.ofSize(canvas.width, canvas.height))
    }
}

/** 選択範囲どうしの組み合わせ方。 */
enum class BoolOp { ADD, SUBTRACT }

/** 指でなぞって作る形。 */
enum class SelectionShape {
    OBJECT,
    BRUSH,
    RECT,
    ELLIPSE,

    /** 投げ縄。なぞった軌跡をそのまま閉じる。 */
    LASSO,
}

/** 次に指で描いた形を、今の選択にどう反映するか。 */
enum class SelectionMode {
    /** 今までの選択を捨てて描いた形にする。 */
    REPLACE,

    /** 描いた形を足す。 */
    ADD,

    /** 描いた形を取り除く。 */
    SUBTRACT,
}

/**
 * 描いた形 [drawn] を今の選択へ [mode] で反映する。
 *
 * [Combined] を入れ子にせず 1 段の List に**平らに積む**のが要点。入れ子にすると
 * `contains` の再帰が選択のたびに 1 段深くなり、塗りつぶしの走査が指数的に重くなる。
 */
fun SelectionSpec?.applying(mode: SelectionMode, drawn: SelectionSpec): SelectionSpec? {
    if (drawn.isEmpty) return this
    val current = this
    if (current == null || mode == SelectionMode.REPLACE) {
        return if (mode == SelectionMode.SUBTRACT) null else drawn
    }
    val op = if (mode == SelectionMode.ADD) BoolOp.ADD else BoolOp.SUBTRACT
    val head = if (current is SelectionSpec.Combined) {
        current.parts
    } else {
        listOf(SelectionSpec.Part(BoolOp.ADD, current))
    }
    return SelectionSpec.Combined(head + SelectionSpec.Part(op, drawn))
}

/**
 * 選択範囲の全ての点を [transform] で移す。切り抜き・回転・反転でキャンバスの座標系が
 * 変わったときに、選択や塗りつぶしを追従させるために使う ([CanvasTransforms])。
 *
 * 矩形と楕円は**対角の 2 点だけ**を移して作り直す。90 度単位の回転と反転しか来ないので、
 * 対角 2 点を移して向きを揃え直せば軸に沿った矩形のまま正しく移る。
 */
fun SelectionSpec.mapPoints(transform: (EditPoint) -> EditPoint): SelectionSpec = when (this) {
    is SelectionSpec.Rect -> SelectionSpec.Rect(rect.mapCorners(transform))
    is SelectionSpec.Ellipse -> SelectionSpec.Ellipse(rect.mapCorners(transform))
    is SelectionSpec.Lasso -> SelectionSpec.Lasso(points.map(transform))
    is SelectionSpec.Contours -> SelectionSpec.Contours(rings.map { it.map(transform) })
    is SelectionSpec.BrushStroke -> {
        val origin = transform(EditPoint(0f, 0f))
        val edge = transform(EditPoint(radius, 0f))
        SelectionSpec.BrushStroke(points.map(transform), kotlin.math.hypot(edge.x - origin.x, edge.y - origin.y), clip?.mapCorners(transform))
    }
    is SelectionSpec.Combined -> SelectionSpec.Combined(
        parts.map { it.copy(spec = it.spec.mapPoints(transform)) },
    )
}

private fun EditRect.mapCorners(transform: (EditPoint) -> EditPoint): EditRect {
    val a = transform(EditPoint(left, top))
    val b = transform(EditPoint(right, bottom))
    return EditRect(a.x, a.y, b.x, b.y).normalized()
}

/**
 * 選択を反転する。キャンバス全体から今の選択を引くだけ。
 *
 * 反転を 2 回かけると `全体 − (全体 − X)` になり、`contains` は X と一致する。
 * 式は伸びるが結果は正しいので、専用の打ち消し処理は置いていない。
 */
fun SelectionSpec?.invertedIn(canvas: EditSize): SelectionSpec {
    val whole = SelectionSpec.whole(canvas)
    val current = this ?: return whole
    return SelectionSpec.Combined(
        listOf(
            SelectionSpec.Part(BoolOp.ADD, whole),
            SelectionSpec.Part(BoolOp.SUBTRACT, current),
        ),
    )
}
