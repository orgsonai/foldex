package com.zerotoship.foldex.core.model

/** 削除操作の既定の行き先 (設定画面で選択)。 */
enum class DeleteBehavior {
    /** ゴミ箱へ移動 (後で復元可能)。リモート/SAF はサポート外のため完全削除になる。 */
    TRASH,

    /** 完全に削除する。 */
    PERMANENT,

    /** 削除のたびにゴミ箱へ移動するか完全削除するか尋ねる。 */
    ASK,
}
