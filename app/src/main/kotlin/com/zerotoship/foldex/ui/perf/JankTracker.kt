// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.perf

import android.app.Activity
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.FrameMetrics
import android.view.Window
import com.zerotoship.foldex.core.data.log.AppLogger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * 「もっさりする」を数字にするための計測。**この端末では adb が使えない**ので、
 * アプリ自身にフレームの遅延を測らせてアプリ内ログへ書く (設定 → ログ で読める)。
 *
 * `Window.OnFrameMetricsAvailableListener` は 1 フレーム描くたびに、その内訳
 * (入力処理 / レイアウト / 描画 / GPU への受け渡し) をナノ秒で渡してくる。
 * **どの段階で時間を食っているかまで分かる**のが要点で、
 *  - レイアウトが支配的 → Compose 側 (自分たちのコード) の問題
 *  - 描画・バッファ交換が支配的 → 描き過ぎ (overdraw) か GPU 側
 * と、次に見る場所が変わる。合計時間だけ見ても切り分けられない。
 *
 * 計測そのものが重くならないよう、コールバックは専用スレッドで受け、
 * その場では**数を足すだけ**にしてある。ログに書くのは [FLUSH_INTERVAL_MS] ごと 1 行だけ。
 */
@Singleton
class JankTracker @Inject constructor(
    private val logger: AppLogger,
) {

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var window: Window? = null
    private var listener: Window.OnFrameMetricsAvailableListener? = null

    /** 1 フレームに使ってよい時間 (ms)。端末のリフレッシュレートから決める。 */
    private var budgetMs: Double = DEFAULT_BUDGET_MS

    /** いま見ている画面。ログに一緒に残さないと、どこが遅いのか分からない。 */
    @Volatile private var screen: String = "-"

    // ---- 集計 (計測スレッドからのみ触る) ----
    private var total = 0
    private var late = 0
    private var severe = 0
    private var worstMs = 0.0
    /** 遅れたフレームの合計時間。平均を出すのに使う。 */
    private var sumLateMs = 0.0
    private var sumInput = 0L
    private var sumLayout = 0L
    private var sumDraw = 0L
    private var sumSync = 0L
    private var sumSwap = 0L
    private var windowStartMs = 0L

    val isRunning: Boolean get() = listener != null

    /** 計測を始める。既に動いていれば何もしない。 */
    fun attach(activity: Activity) {
        if (listener != null) return
        val w = activity.window ?: return

        // Activity.display は API 30 以上。minSdk 26 なので古い側は WindowManager から取る。
        val refresh = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                activity.display?.refreshRate
            } else {
                @Suppress("DEPRECATION")
                activity.windowManager.defaultDisplay?.refreshRate
            }
        }.getOrNull() ?: DEFAULT_REFRESH_HZ
        budgetMs = if (refresh > 1f) 1000.0 / refresh else DEFAULT_BUDGET_MS

        val t = HandlerThread("jank-tracker").apply { start() }
        val h = Handler(t.looper)
        val l = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
            record(metrics)
        }
        runCatching { w.addOnFrameMetricsAvailableListener(l, h) }
            .onFailure {
                t.quitSafely()
                return
            }

        thread = t
        handler = h
        window = w
        listener = l
        reset(System.currentTimeMillis())
        logger.info(TAG, "measurement started (budget ${fmt(budgetMs)}ms @ ${refresh.roundToInt()}Hz)")
    }

    /** 計測を止める。溜まっている分は最後に 1 行書き出す。 */
    fun detach() {
        val l = listener ?: return
        runCatching { window?.removeOnFrameMetricsAvailableListener(l) }
        flushIfAny()
        listener = null
        window = null
        handler = null
        thread?.quitSafely()
        thread = null
        logger.info(TAG, "measurement stopped")
    }

    /**
     * 画面が変わったことを伝える。切り替わりの直前までの分をそこで書き出すので、
     * 「どの画面が遅いか」が混ざらない。
     */
    fun setScreen(name: String) {
        if (name == screen) return
        handler?.post { flushIfAny() } ?: flushIfAny()
        screen = name
    }

    // ---- 計測スレッド ----

    private fun record(metrics: FrameMetrics) {
        val totalNs = metrics.getMetric(FrameMetrics.TOTAL_DURATION)
        val ms = totalNs / 1_000_000.0
        total++
        if (ms > budgetMs) {
            late++
            if (ms > budgetMs * SEVERE_FACTOR) severe++
            if (ms > worstMs) worstMs = ms
            sumLateMs += ms
            // 内訳は「遅れたフレーム」についてだけ足す。速いフレームまで混ぜると
            // 平均が薄まって、どこが犯人か見えなくなる。
            sumInput += metrics.getMetric(FrameMetrics.INPUT_HANDLING_DURATION)
            sumLayout += metrics.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION) +
                metrics.getMetric(FrameMetrics.ANIMATION_DURATION)
            sumDraw += metrics.getMetric(FrameMetrics.DRAW_DURATION)
            sumSync += metrics.getMetric(FrameMetrics.SYNC_DURATION) +
                metrics.getMetric(FrameMetrics.COMMAND_ISSUE_DURATION)
            sumSwap += metrics.getMetric(FrameMetrics.SWAP_BUFFERS_DURATION)
        }
        val now = System.currentTimeMillis()
        if (now - windowStartMs >= FLUSH_INTERVAL_MS) flush(now)
    }

    private fun flushIfAny() {
        if (late > 0) flush(System.currentTimeMillis()) else reset(System.currentTimeMillis())
    }

    private fun flush(now: Long) {
        if (total == 0) {
            reset(now)
            return
        }
        if (late == 0) {
            // 遅れが無い区間は 1 行も書かない。ログが薄まって読めなくなるため。
            reset(now)
            return
        }
        val pct = (late * 100.0 / total).roundToInt()
        val breakdown = breakdown()
        val seconds = ((now - windowStartMs) / 1000.0).roundToInt().coerceAtLeast(1)
        // 平均を出すのが要点。「75% が遅延」でも平均 18ms なら体感はほぼ変わらないし、
        // 平均 60ms なら別問題。worst だけでは、たまたま 1 枚重かったのか
        // 全体が重いのかを区別できない。
        val avg = if (late > 0) sumLateMs / late else 0.0
        val line = "$screen  late $late/$total (${pct}%)  avg ${fmt(avg)}ms  " +
            "worst ${fmt(worstMs)}ms  severe $severe  ${seconds}s  $breakdown"
        // 4 分の 1 以上のフレームが遅れていれば体感で分かる水準なので WARN に上げる。
        if (pct >= WARN_PERCENT) logger.warn(TAG, line) else logger.info(TAG, line)
        reset(now)
    }

    /** 遅れたフレームの時間を、段階ごとの割合にして返す。 */
    private fun breakdown(): String {
        val sum = sumInput + sumLayout + sumDraw + sumSync + sumSwap
        if (sum <= 0L) return ""
        fun p(v: Long) = (v * 100.0 / sum).roundToInt()
        return "input=${p(sumInput)}% layout=${p(sumLayout)}% draw=${p(sumDraw)}% " +
            "sync=${p(sumSync)}% swap=${p(sumSwap)}%"
    }

    private fun reset(now: Long) {
        total = 0
        late = 0
        severe = 0
        worstMs = 0.0
        sumLateMs = 0.0
        sumInput = 0
        sumLayout = 0
        sumDraw = 0
        sumSync = 0
        sumSwap = 0
        windowStartMs = now
    }

    private fun fmt(ms: Double): String = ((ms * 10).roundToInt() / 10.0).toString()

    private companion object {
        const val TAG = "Jank"

        /** リフレッシュレートが取れなかったときの想定。 */
        const val DEFAULT_REFRESH_HZ = 60f
        const val DEFAULT_BUDGET_MS = 1000.0 / 60

        /** 書き出す間隔。短くするとログが埋まり、長くすると操作と対応が取れなくなる。 */
        const val FLUSH_INTERVAL_MS = 10_000L

        /** 予算の何倍を「はっきり分かる引っかかり」とみなすか。 */
        const val SEVERE_FACTOR = 2.0

        /** この割合以上遅れていたら WARN (ログ画面で色が付く)。 */
        const val WARN_PERCENT = 25
    }
}
