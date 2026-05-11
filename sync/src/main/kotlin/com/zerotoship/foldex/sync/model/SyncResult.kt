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

    val transferredCount: Int get() = uploaded + downloaded

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
        if (deleted > 0) append(" 削除 ").append(deleted)
        if (conflicts > 0) append(" 競合 ").append(conflicts)
        if (skipped > 0) append(" スキップ ").append(skipped)
        if (failed > 0) append(" 失敗 ").append(failed)
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
