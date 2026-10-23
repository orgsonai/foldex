// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.viewer

import android.content.Context

/**
 * 動画ごとに「前回止めた位置」を覚えておく (続きから再生)。キーは再生に使う URI
 * (端末内は file://、NAS は配信用の content://。どちらも同じファイルなら毎回同じ文字列になる)。
 *
 * 開く瞬間に同期で読みたいので DataStore ではなく SharedPreferences に置く。
 * 増え続けないよう、新しい方から [MAX_ENTRIES] 件だけ残す。
 */
internal object VideoPositionStore {

    private const val PREFS = "video_positions"
    private const val MAX_ENTRIES = 200

    /** これより手前で止めたときは、続きとして覚えない (開いてすぐ閉じただけ)。 */
    private const val MIN_POSITION_MS = 5_000L

    /** 終わりまでこれ未満なら、見終わったとみなして忘れる。 */
    private const val END_MARGIN_MS = 5_000L

    /** 覚えている位置 (ミリ秒)。無ければ 0。 */
    fun get(context: Context, key: String): Long =
        prefs(context).getString(key, null)?.substringBefore(':')?.toLongOrNull() ?: 0L

    /** 止めた位置を覚える。始まってすぐ・見終わった後なら忘れる。[durationMs] が分からなければ 0 以下でよい。 */
    fun save(context: Context, key: String, positionMs: Long, durationMs: Long) {
        val finished = durationMs > 0 && positionMs >= durationMs - END_MARGIN_MS
        if (positionMs < MIN_POSITION_MS || finished) {
            remove(context, key)
            return
        }
        val prefs = prefs(context)
        val editor = prefs.edit().putString(key, "$positionMs:${System.currentTimeMillis()}")
        val all = prefs.all
        if (all.size >= MAX_ENTRIES) {
            // 古い順に、上限を超える分を消す。
            all.entries
                .filter { it.key != key }
                .sortedBy { (it.value as? String)?.substringAfter(':')?.toLongOrNull() ?: 0L }
                .take(all.size - MAX_ENTRIES + 1)
                .forEach { editor.remove(it.key) }
        }
        editor.apply()
    }

    fun remove(context: Context, key: String) {
        prefs(context).edit().remove(key).apply()
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
