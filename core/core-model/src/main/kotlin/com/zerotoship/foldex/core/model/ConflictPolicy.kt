package com.zerotoship.foldex.core.model

/**
 * ローカルとリモートの両方が変更された場合の解決方針。
 *
 * `MANUAL` は実装しない (KEEP_BOTH で代替) — 仕様書 §8-F。
 */
enum class ConflictPolicy(val wireName: String) {
    /** mtime が新しい方を採用 (デフォルト推奨)。 */
    NEWER_WINS("newer_wins"),

    /** ローカル優先。 */
    LOCAL_WINS("local_wins"),

    /** リモート優先。 */
    REMOTE_WINS("remote_wins"),

    /**
     * 両方残す。負けた方を `name (conflict YYYY-MM-DD HH-MM-SS).ext` にリネーム
     * (Dropbox/Syncthing 流)。
     */
    KEEP_BOTH("keep_both"),

    /** スキップしてユーザーに通知。 */
    SKIP("skip"),
    ;

    companion object {
        fun fromWireName(value: String): ConflictPolicy =
            entries.firstOrNull { it.wireName == value }
                ?: error("Unknown ConflictPolicy wire name: $value")
    }
}
