// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.sync.model

/**
 * 1 回の同期実行の結果サマリ。SyncJob.lastRunResult に保存する短い文字列も生成する。
 */
data class SyncResult(
    val jobId: String,
    val outcome: Outcome,
    val uploaded: Int = 0,
    val downloaded: Int = 0,
    val deleted: Int = 0,
    val conflicts: Int = 0,
    val skipped: Int = 0,
    val failed: Int = 0,
    val transferredBytes: Long = 0,
    val startedAt: Long,
    val finishedAt: Long,
    val errors: List<ActionError> = emptyList(),
) {
    enum class Outcome {
        /** 全アクション成功 (Skip は成功扱い)。 */
        SUCCESS,

        /** 一部のアクションが失敗した。 */
        PARTIAL,

        /** 列挙そのものに失敗するなど、ほぼ何もできなかった。 */
        FAILED,
    }

    data class ActionError(val path: String, val message: String)

    /**
     * 実際に転送したファイル件数。`uploaded` / `downloaded` に加え、
     * 両側変更があってポリシーで解決した [conflicts] も「転送した」に含める
     * (= 競合は失敗でも別カウントでもなく、勝者側を流した転送)。
     */
    val transferredCount: Int get() = uploaded + downloaded + conflicts

    /**
     * この実行で実際に処理対象として走査したファイル総数。
     * 転送・削除・スキップ・失敗の合計 = 「何件を相手に同期判定したか」を表す。
     */
    val processedCount: Int get() = transferredCount + deleted + skipped + failed

    /** SyncJob.lastRunResult に格納する 1 行サマリ。 */
    fun toSummaryLine(): String = buildString {
        append(
            when (outcome) {
                Outcome.SUCCESS -> "成功"
                Outcome.PARTIAL -> "一部失敗"
                Outcome.FAILED -> "失敗"
            },
        )
        append(" / 転送 ").append(transferredCount)
        if (conflicts > 0) append(" (両側更新 ").append(conflicts).append(")")
        if (deleted > 0) append(" 削除 ").append(deleted)
        if (failed > 0) append(" 失敗 ").append(failed)
        // 末尾に「スキップ数」と「処理した総数」を出して、何件を判定して何件が転送対象外
        // (変更なし等) だったかをログから読み取れるようにする。
        append(" / スキップ ").append(skipped)
        append(" / 合計 ").append(processedCount).append(" 件")
    }

    companion object {
        fun failedToScan(jobId: String, startedAt: Long, finishedAt: Long, message: String): SyncResult =
            SyncResult(
                jobId = jobId,
                outcome = Outcome.FAILED,
                startedAt = startedAt,
                finishedAt = finishedAt,
                errors = listOf(ActionError(path = "", message = message)),
            )
    }
}
