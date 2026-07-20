// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

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

/**
 * このジョブを expedited (即時実行) として積んでよいかどうか。
 *
 * JobScheduler の仕様上、expedited なジョブに付けられる制約は**ネットワークとストレージ残量だけ**。
 * 「充電中のみ」「バッテリー低下時は実行しない」を併用すると `JobInfo` の生成時点で
 * `IllegalArgumentException` ("An expedited job can only have network and storage constraints") が
 * 投げられる。この生成は WorkManager の try/catch の外側で行われるためエンキュー自体が失敗し、
 * ジョブが永久に実行されない (= 設定した瞬間に同期が動かなくなる) 状態になっていた。
 *
 * そこでバッテリー系の制約が付いたジョブは expedited を諦め、通常の WorkManager ジョブとして積む。
 * 起動は AlarmManager (`setAndAllowWhileIdle`) が担っており、開始後は SyncWorker が自前で前景化
 * するため、即時性はやや落ちるものの実行そのものは従来どおり最後まで走り切れる。
 */
internal fun canExpedite(job: SyncJob): Boolean = !job.requiresCharging && !job.requiresBatteryNotLow
