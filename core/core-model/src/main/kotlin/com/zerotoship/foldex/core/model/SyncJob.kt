package com.zerotoship.foldex.core.model

/**
 * 1 つの同期ジョブ。仕様書 §8-J の SyncJobEntity に対応するドメインモデル。
 *
 * 永続化と UI の両方から参照されるので core-model に置く。`localUri` /
 * `remoteUri` は `FileUri.toStorageString()` 形式 (現状は string ベース)。
 */
data class SyncJob(
    val id: String,
    val name: String,
    val enabled: Boolean,

    val localUri: String,
    val remoteUri: String,
    val direction: SyncDirection,

    val conflictPolicy: ConflictPolicy,
    val filter: SyncFilter,

    /** WorkManager の最小制約は 15 分。0 のときは「手動のみ」(定期実行しない)。 */
    val intervalMinutes: Int,

    val requiresWifi: Boolean = true,
    val requiresCharging: Boolean = false,
    val requiresBatteryNotLow: Boolean = true,

    /** デフォルト false (削除しない、事故防止)。 */
    val deleteEnabled: Boolean = false,

    val parallelism: Int = 3,
    val retryCount: Int = 3,

    val createdAt: Long,
    val updatedAt: Long,
    val lastRunAt: Long? = null,
    val lastRunResult: String? = null,
)
