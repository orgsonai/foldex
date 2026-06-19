// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.viewer

import org.mozilla.universalchardet.UniversalDetector
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * テキストの文字コード自動判定。
 *
 * 重要 (文字化け対策): 純 ASCII や「ASCII が多めの UTF-8」を juniversalchardet にそのままかけると
 * US-ASCII と判定されがちで、しかも検出は先頭 64KB のサンプルしか見ない。その文字コードでファイル
 * 全体を読み書きすると、サンプル外にある日本語などの多バイト文字を取りこぼして文字化けし、保存時には
 * その文字が欠落する。
 *
 * そこで「BOM → ファイル全体が妥当な UTF-8 か → それ以外を検出器」の順で判定し、ASCII/UTF-8 は必ず
 * UTF-8 に寄せる (ASCII は UTF-8 の部分集合なので無損失で、勝手に ASCII へ変わって壊れることがない)。
 */
object TextDecoding {

    val supported: List<Charset> = listOf(
        Charsets.UTF_8,
        Charset.forName("Shift_JIS"),
        Charset.forName("EUC-JP"),
        Charset.forName("ISO-2022-JP"),
        Charsets.UTF_16,
        Charsets.US_ASCII,
        Charsets.ISO_8859_1,
    )

    fun detect(bytes: ByteArray): Charset {
        // 1) BOM があれば最優先 (確実)。
        bomCharset(bytes)?.let { return it }

        // 2) ファイル全体が妥当な UTF-8 なら UTF-8 で確定。
        //    ASCII のみのファイルもここで UTF-8 になる (= 勝手に US-ASCII へ変わらない)。
        if (isValidUtf8(bytes)) return Charsets.UTF_8

        // 3) UTF-8 でないものだけ juniversalchardet で日本語系 (Shift_JIS / EUC-JP など) を判定。
        val detector = UniversalDetector(null)
        detector.handleData(bytes, 0, bytes.size.coerceAtMost(64 * 1024))
        detector.dataEnd()
        val name = detector.detectedCharset
        detector.reset()
        val detected = name?.let { runCatching { Charset.forName(it) }.getOrNull() }

        // US-ASCII / 不明は UTF-8 にフォールバック (保存時に多バイト文字を欠落させないため)。
        return when (detected) {
            null, Charsets.US_ASCII -> Charsets.UTF_8
            else -> detected
        }
    }

    /**
     * ファイル全体を厳密に UTF-8 としてデコードできるか (不正バイト・途中で切れた多バイト列があれば
     * false)。巨大ファイルでも一時メモリを食わないよう、小さな CharBuffer を使い回して走査する。
     */
    private fun isValidUtf8(bytes: ByteArray): Boolean {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val input = ByteBuffer.wrap(bytes)
        val out = CharBuffer.allocate(8 * 1024)
        // decode() を必ず最低 1 回呼ぶ (do/while)。空入力 (0 バイトのファイル) を while(hasRemaining())
        // で回すと decode が一度も呼ばれず、デコーダが ST_RESET のまま flush(out) に入って
        // IllegalStateException("Current state = RESET, new state = FLUSHED") を投げる。
        // 空入力でも decode を 1 回通せば RESET→END に遷移し、後続の flush が正しく行える。
        do {
            out.clear()
            val result = decoder.decode(input, out, /* endOfInput = */ true)
            if (result.isError) return false
            if (result.isUnderflow) break // 入力を読み切った
            // OVERFLOW なら out が満杯なだけ。クリアして続行。
        } while (input.hasRemaining())
        out.clear()
        return !decoder.flush(out).isError
    }

    /** 先頭の BOM から文字コードを判定。なければ null。 */
    private fun bomCharset(b: ByteArray): Charset? = when {
        b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte() ->
            Charsets.UTF_8
        b.size >= 2 && b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte() -> Charsets.UTF_16LE
        b.size >= 2 && b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte() -> Charsets.UTF_16BE
        else -> null
    }
}
