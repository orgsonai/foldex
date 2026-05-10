package com.zerotoship.foldex.core.data.charset

import org.mozilla.universalchardet.UniversalDetector
import java.nio.charset.Charset
import java.nio.charset.IllegalCharsetNameException
import java.nio.charset.UnsupportedCharsetException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * juniversalchardet (Mozilla の Universal Charset Detector の Java 移植) を
 * ラップしたシンプルな文字コード判定ユーティリティ。
 *
 * 主に FTP の SJIS/EUC-JP ファイル名や、テキストファイルの自動オープン時に
 * 利用することを想定する。判定不能な場合は null を返すので、呼び出し側で
 * フォールバック (例: UTF-8) を決めること。
 */
@Singleton
class CharsetDetector @Inject constructor() {

    /**
     * バイト列から文字コードを推定する。判定できない場合は null。
     *
     * @param bytes 判定対象のバイト列 (テキストの先頭数 KB を渡せば十分)
     * @param maxBytes 解析するバイト数の上限 (大きいバッファでも先頭だけ見る)
     */
    fun detect(bytes: ByteArray, maxBytes: Int = DEFAULT_SAMPLE_BYTES): Charset? {
        if (bytes.isEmpty()) return null
        val limit = minOf(bytes.size, maxBytes)
        val detector = UniversalDetector(null)
        detector.handleData(bytes, 0, limit)
        detector.dataEnd()
        val name = detector.detectedCharset
        detector.reset()
        if (name.isNullOrBlank()) return null
        return runCatching { Charset.forName(name) }
            .getOrElse { e ->
                if (e is IllegalCharsetNameException || e is UnsupportedCharsetException) null
                else throw e
            }
    }

    companion object {
        /** 4KB あれば判定精度はほぼ飽和する。 */
        const val DEFAULT_SAMPLE_BYTES: Int = 4 * 1024
    }
}
