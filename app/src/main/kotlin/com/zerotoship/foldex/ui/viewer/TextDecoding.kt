package com.zerotoship.foldex.ui.viewer

import org.mozilla.universalchardet.UniversalDetector
import java.nio.charset.Charset

/** juniversalchardet によるエンコーディング自動判定。判定不能なら UTF-8。 */
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
        val detector = UniversalDetector(null)
        detector.handleData(bytes, 0, bytes.size.coerceAtMost(64 * 1024))
        detector.dataEnd()
        val name = detector.detectedCharset
        detector.reset()
        return name?.let { runCatching { Charset.forName(it) }.getOrNull() } ?: Charsets.UTF_8
    }
}
