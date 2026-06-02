package com.zerotoship.foldex.sync.scheduler

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.zerotoship.foldex.core.data.repo.SyncJobRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 端末再起動後にアラームを貼り直す。
 *
 * AlarmManager のアラームは再起動で消えるため、有効なジョブの次回アラームを再登録する。
 * INTERVAL の定期 (PeriodicWorkRequest) は WorkManager 側が再起動後に自動復元するので対象外。
 * [SyncScheduler.scheduleNext] は INTERVAL/手動ジョブには no-op (nextFireTimeMillis が null)。
 */
@AndroidEntryPoint
class SyncBootReceiver : BroadcastReceiver() {

    @Inject lateinit var scheduler: SyncScheduler

    @Inject lateinit var jobRepository: SyncJobRepository

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                jobRepository.observeAll().first()
                    .filter { it.enabled }
                    .forEach { scheduler.scheduleNext(it) }
            } finally {
                pending.finish()
            }
        }
    }
}
