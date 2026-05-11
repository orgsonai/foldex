package com.zerotoship.foldex.core.model

/**
 * 同期方向。P6 では片方向のみで、双方向 (BIDIRECTIONAL) は P8 で追加予定。
 */
enum class SyncDirection(val wireName: String) {
    /** ローカル → リモート (アップロード/ミラー)。 */
    TO_REMOTE("to_remote"),

    /** リモート → ローカル (ダウンロード/ミラー)。 */
    TO_LOCAL("to_local"),
    ;

    companion object {
        fun fromWireName(value: String): SyncDirection =
            entries.firstOrNull { it.wireName == value }
                ?: error("Unknown SyncDirection wire name: $value")
    }
}
