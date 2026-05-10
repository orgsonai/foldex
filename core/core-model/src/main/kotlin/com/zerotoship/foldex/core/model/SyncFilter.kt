package com.zerotoship.foldex.core.model

/**
 * 同期対象を絞り込むフィルタ。仕様書 §8-H に従い glob のみサポート。
 *
 * - [includePatterns] が空のときは「全て include」。
 * - [excludePatterns] は include を通過したファイルから除外する。
 * - [maxFileSize] は null なら無制限。
 */
data class SyncFilter(
    val includePatterns: List<String> = emptyList(),
    val excludePatterns: List<String> = emptyList(),
    val maxFileSize: Long? = null,
) {
    companion object {
        val EMPTY: SyncFilter = SyncFilter()
    }
}
