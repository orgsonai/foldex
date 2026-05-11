package com.zerotoship.foldex.sync.scheduler

import androidx.work.Constraints
import androidx.work.NetworkType
import com.zerotoship.foldex.core.model.SyncJob

/**
 * SyncJob の制約設定を WorkManager の [Constraints] に変換する — 仕様書 §8-K。
 * Wi-Fi 限定なら [NetworkType.UNMETERED]、そうでなければ [NetworkType.CONNECTED]。
 */
internal fun syncConstraints(job: SyncJob): Constraints =
    Constraints.Builder()
        .setRequiredNetworkType(if (job.requiresWifi) NetworkType.UNMETERED else NetworkType.CONNECTED)
        .setRequiresCharging(job.requiresCharging)
        .setRequiresBatteryNotLow(job.requiresBatteryNotLow)
        .build()
