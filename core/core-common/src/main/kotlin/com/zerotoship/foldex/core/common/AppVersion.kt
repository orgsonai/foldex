// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.core.common

/**
 * バージョン文字列の比較。
 *
 * 更新確認で「GitHub のタグ (`v1.3.0`)」と「今入っているアプリの versionName (`1.2.2`)」を
 * 突き合わせるために使う。文字列のまま比較すると `1.10.0` < `1.9.0` になってしまうので、
 * ドット区切りの数値として比べる。
 *
 * 扱える形:
 * - 先頭の `v` / `V` は無視する (`v1.2.2` と `1.2.2` は同じ)
 * - 桁数が違ってもよい (`1.2` と `1.2.0` は同じ)
 * - `-alpha` のような接尾辞が付いたものは、**同じ数値なら正式版より古い**とみなす
 *   (セマンティックバージョニングの規則。`1.3.0-alpha` < `1.3.0`)
 */
object AppVersion {

    /** [a] が [b] より新しければ正、古ければ負、同じなら 0 を返す。 */
    fun compare(a: String, b: String): Int {
        val (numsA, preA) = split(a)
        val (numsB, preB) = split(b)

        val size = maxOf(numsA.size, numsB.size)
        for (i in 0 until size) {
            // 桁が足りない側は 0 とみなす (1.2 == 1.2.0)。
            val diff = numsA.getOrElse(i) { 0 }.compareTo(numsB.getOrElse(i) { 0 })
            if (diff != 0) return diff
        }

        // 数値が同じなら、接尾辞の有無で決める。付いていない方が新しい。
        return when {
            preA == null && preB == null -> 0
            preA == null -> 1
            preB == null -> -1
            else -> preA.compareTo(preB)
        }
    }

    /** [latest] が [current] より新しいなら true。比較できない入力では false (更新なし扱い)。 */
    fun isNewerThan(latest: String, current: String): Boolean =
        runCatching { compare(latest, current) > 0 }.getOrDefault(false)

    /** `"v1.3.0-alpha"` → `[1, 3, 0]` と `"alpha"` に分ける。 */
    private fun split(raw: String): Pair<List<Int>, String?> {
        val trimmed = raw.trim().removePrefix("v").removePrefix("V")
        val dash = trimmed.indexOf('-')
        val numeric = if (dash >= 0) trimmed.substring(0, dash) else trimmed
        val pre = if (dash >= 0) trimmed.substring(dash + 1).takeIf { it.isNotEmpty() } else null
        // 数字として読めない要素は 0 にする (壊れた入力でも落とさない)。
        val nums = numeric.split('.').map { it.trim().toIntOrNull() ?: 0 }
        return nums to pre
    }
}
