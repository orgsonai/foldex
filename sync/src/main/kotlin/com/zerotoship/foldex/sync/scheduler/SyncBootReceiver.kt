// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.sync.scheduler

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 端末再起動後とアプリ更新後にアラームを貼り直す。
 *
 * AlarmManager のアラームは端末再起動でもアプリ更新 (`MY_PACKAGE_REPLACED`) でも消えるため、
 * 有効なジョブの次回アラームを再登録する。
 * [SyncScheduler.rescheduleAll] は手動のみのジョブには no-op (nextFireTimeMillis が null)。
 */
@AndroidEntryPoint
class SyncBootReceiver : BroadcastReceiver() {

    @Inject lateinit var scheduler: SyncScheduler

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                scheduler.rescheduleAll()
            } finally {
                pending.finish()
            }
        }
    }
}
