// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.core.model

/**
 * 動画の早送り・巻き戻しで飛ばす量。[percent] が true なら [amount] は動画の長さに対する %、
 * false なら秒数。
 */
data class VideoSkip(val amount: Int, val percent: Boolean) {

    /**
     * 飛ばす量 (ミリ秒)。割合指定で長さが分からない (配信中など、[durationMs] が 0 以下) ときは
     * [FALLBACK_SECONDS] 秒にする。短い動画でも 1 秒は飛ばす。
     */
    fun incrementMs(durationMs: Long): Long =
        if (!percent) {
            amount * 1000L
        } else if (durationMs <= 0) {
            FALLBACK_SECONDS * 1000L
        } else {
            (durationMs * amount / 100).coerceAtLeast(1000L)
        }

    companion object {
        const val FALLBACK_SECONDS = 10
        val DEFAULT = VideoSkip(10, percent = false)

        /** 設定で選べる値。 */
        val CHOICES = listOf(
            VideoSkip(5, false),
            VideoSkip(10, false),
            VideoSkip(15, false),
            VideoSkip(30, false),
            VideoSkip(1, true),
            VideoSkip(5, true),
            VideoSkip(10, true),
        )
    }
}
