// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.settings

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

/**
 * 実行ログ 1 行を「見やすく色付けして表示する」ための解析とパレット (表示専用)。
 *
 * ログファイル自体のフォーマットは AppLogger が決めており、ここでは一切変えない
 * (コピー/共有は生のテキストのまま)。画面に出すときだけ日付・レベルを畳んで、
 * 種別 (同期開始 / 転送 / 削除 / エラー) を色と記号で区別する。
 *
 * 期待する行フォーマット: `2026-05-16 12:34:56.789  [LEVEL]  [TAG]  message`
 * (区切りは半角スペース 2 個。スタックトレース等の続き行は解析できないので生のまま出す)
 */
internal enum class LogKind {
    START,
    UPLOAD,
    DOWNLOAD,
    TRANSFER,
    DELETE,
    SUMMARY_OK,
    SUMMARY_NG,
    WARN,
    ERROR,
    INFO,
}

/** 行頭に置く 1 文字の記号。色が見えない環境 (コピー後など) でも種別が分かるようにする。 */
internal val LogKind.marker: String
    get() = when (this) {
        LogKind.START -> "▶"
        LogKind.UPLOAD -> "↑"
        LogKind.DOWNLOAD -> "↓"
        LogKind.TRANSFER -> "⇅"
        LogKind.DELETE -> "−"
        LogKind.SUMMARY_OK -> "✓"
        LogKind.SUMMARY_NG -> "!"
        LogKind.WARN -> "!"
        LogKind.ERROR -> "✕"
        LogKind.INFO -> "·"
    }

@Immutable
internal data class ParsedLogLine(
    /** `yyyy-MM-dd`。空文字なら解析できなかった行 (生のまま表示する)。 */
    val date: String,
    /** `HH:mm:ss` (ミリ秒は画面では落とす)。 */
    val time: String,
    /** `Sync(写真)` → `写真` のように短くしたタグ。 */
    val tag: String,
    val message: String,
    val kind: LogKind,
    val raw: String,
) {
    val parsed: Boolean get() = date.isNotEmpty()
}

internal fun parseLogLine(raw: String): ParsedLogLine {
    val unparsed = ParsedLogLine("", "", "", raw, LogKind.INFO, raw)
    // AppLogger は各要素をスペース 2 個で区切って書いている。message 内のスペース 2 個は
    // 潰したくないので limit=4 で「4 つ目以降は全部 message」とする。
    val parts = raw.split("  ", limit = 4)
    if (parts.size < 4) return unparsed

    val stamp = parts[0]
    val sp = stamp.indexOf(' ')
    if (sp != 10 || stamp.getOrNull(4) != '-') return unparsed
    val date = stamp.substring(0, sp)
    val time = stamp.substring(sp + 1).substringBefore('.')

    val level = parts[1].trim().removeSurrounding("[", "]")
    if (level != "INFO" && level != "WARN" && level != "ERROR") return unparsed

    val rawTag = parts[2].trim().removeSurrounding("[", "]")
    val tag = if (rawTag.startsWith("Sync(") && rawTag.endsWith(")")) {
        rawTag.substring("Sync(".length, rawTag.length - 1)
    } else {
        rawTag
    }
    val message = parts[3]

    val kind = when {
        level == "ERROR" -> LogKind.ERROR
        level == "WARN" -> LogKind.WARN
        message.startsWith("── 同期開始") -> LogKind.START
        message.startsWith("アップロード") -> LogKind.UPLOAD
        message.startsWith("ダウンロード") -> LogKind.DOWNLOAD
        message.startsWith("両側更新") -> LogKind.TRANSFER
        message.startsWith("削除(") -> LogKind.DELETE
        message.startsWith("成功 / 転送") -> LogKind.SUMMARY_OK
        message.startsWith("一部失敗 / 転送") || message.startsWith("失敗 / 転送") -> LogKind.SUMMARY_NG
        else -> LogKind.INFO
    }
    return ParsedLogLine(date = date, time = time, tag = tag, message = message, kind = kind, raw = raw)
}

/**
 * ログ表示用の配色。Material の colorScheme には「アップロード用の色」のような枠が無いので、
 * ここでライト / ダーク別に手で持つ。アクセント (primary) とエラー色だけはテーマ側から受け取り、
 * ユーザーが選んだアクセントカラーに追従させる。
 */
@Immutable
internal data class LogPalette(
    val time: Color,
    val tag: Color,
    val body: Color,
    val muted: Color,
    val start: Color,
    val upload: Color,
    val download: Color,
    val transfer: Color,
    val delete: Color,
    val summaryOk: Color,
    val summaryNg: Color,
    val warn: Color,
    val error: Color,
)

internal fun logPalette(dark: Boolean, primary: Color, error: Color): LogPalette = if (dark) {
    LogPalette(
        time = Color(0xFF8A8F98),
        tag = Color(0xFF9AA4B2),
        body = Color(0xFFE3E3E3),
        muted = Color(0xFF9AA0A6),
        start = primary,
        upload = Color(0xFF7FB3FF),
        download = Color(0xFF6FD3A5),
        transfer = Color(0xFFC59BFF),
        delete = Color(0xFFFFA76B),
        summaryOk = Color(0xFF6FD3A5),
        summaryNg = Color(0xFFFFCB6B),
        warn = Color(0xFFFFCB6B),
        error = error,
    )
} else {
    LogPalette(
        time = Color(0xFF6B7280),
        tag = Color(0xFF4B5563),
        body = Color(0xFF1B1B1B),
        muted = Color(0xFF6B7280),
        start = primary,
        upload = Color(0xFF1D4ED8),
        download = Color(0xFF047857),
        transfer = Color(0xFF6D28D9),
        delete = Color(0xFFB45309),
        summaryOk = Color(0xFF047857),
        summaryNg = Color(0xFF92400E),
        warn = Color(0xFF92400E),
        error = error,
    )
}

internal fun LogPalette.colorFor(kind: LogKind): Color = when (kind) {
    LogKind.START -> start
    LogKind.UPLOAD -> upload
    LogKind.DOWNLOAD -> download
    LogKind.TRANSFER -> transfer
    LogKind.DELETE -> delete
    LogKind.SUMMARY_OK -> summaryOk
    LogKind.SUMMARY_NG -> summaryNg
    LogKind.WARN -> warn
    LogKind.ERROR -> error
    LogKind.INFO -> body
}

/**
 * 1 行を色付きの [AnnotatedString] に組み立てる。
 * `時刻 / ジョブ名 / 記号 / 種別ラベル / パス / 補足` の順で、パスだけ太字にして目で追いやすくする。
 */
internal fun ParsedLogLine.annotated(palette: LogPalette): AnnotatedString = buildAnnotatedString {
    if (!parsed) {
        withStyle(SpanStyle(color = palette.muted)) { append(raw) }
        return@buildAnnotatedString
    }
    val accent = palette.colorFor(kind)
    val bold = kind == LogKind.START || kind == LogKind.SUMMARY_OK || kind == LogKind.SUMMARY_NG

    withStyle(SpanStyle(color = palette.time)) { append(time) }
    append(' ')
    if (tag.isNotEmpty()) {
        withStyle(SpanStyle(color = palette.tag)) { append(tag.take(TAG_MAX_CHARS)) }
        append(' ')
    }
    withStyle(SpanStyle(color = accent, fontWeight = FontWeight.Bold)) { append(kind.marker) }
    append(' ')

    // 「ラベル: パス (補足)」形式なら 3 分割して塗り分ける。それ以外 (同期開始・サマリ等) は一色。
    val sep = message.indexOf(": ")
    if (sep <= 0 || kind == LogKind.START || kind == LogKind.SUMMARY_OK || kind == LogKind.SUMMARY_NG) {
        withStyle(
            SpanStyle(color = accent, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal),
        ) {
            append(message)
        }
        return@buildAnnotatedString
    }

    withStyle(SpanStyle(color = accent)) { append(message.substring(0, sep + 1)) }
    append(' ')
    val rest = message.substring(sep + 2)
    val tail = tailIndexOf(rest)
    if (tail < 0) {
        withStyle(SpanStyle(color = palette.body, fontWeight = FontWeight.Bold)) { append(rest) }
    } else {
        withStyle(SpanStyle(color = palette.body, fontWeight = FontWeight.Bold)) { append(rest.substring(0, tail)) }
        val tailColor = if (kind == LogKind.ERROR) palette.error else palette.muted
        withStyle(SpanStyle(color = tailColor)) { append(rest.substring(tail)) }
    }
}

/**
 * パスの後ろに付く補足部分の開始位置。`— 理由` (失敗) を優先し、無ければ末尾の ` (1.2 MB)`。
 * 見つからなければ -1 (行末までパス扱い)。
 */
private fun tailIndexOf(rest: String): Int {
    val reason = rest.indexOf(" — ")
    if (reason > 0) return reason
    val size = rest.lastIndexOf(" (")
    return if (size > 0 && rest.endsWith(")")) size else -1
}

private const val TAG_MAX_CHARS = 14
