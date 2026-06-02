package com.zerotoship.foldex.sync.scheduler

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.zerotoship.foldex.core.data.repo.SyncJobRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 時刻指定スケジュール (DAILY/WEEKLY/MONTHLY/DATETIME) のアラーム発火を受ける。
 *
 * [SyncScheduler] が `setAndAllowWhileIdle` で登録したアラームがここに届く。
 * 受けたら当該ジョブを即時 WorkManager 実行し、繰り返し種別なら次回アラームを再登録する
 * (実行が制約で走らなくても次回が途切れないよう、ワーカー完了を待たずここで再登録する)。
 */
@AndroidEntryPoint
class SyncAlarmReceiver : BroadcastReceiver() {

    @Inject lateinit var scheduler: SyncScheduler

    @Inject lateinit var jobRepository: SyncJobRepository

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_FIRE) return
        val jobId = intent.getStringExtra(EXTRA_JOB_ID) ?: return
        // BroadcastReceiver は短命なので goAsync で実行枠を確保してから DB アクセスする。
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                val job = jobRepository.findById(jobId) ?: return@launch
                if (!job.enabled) return@launch
                scheduler.fireNow(job)
                if (job.schedule.isRecurringOneShot) scheduler.scheduleNext(job)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_FIRE = "com.zerotoship.foldex.sync.action.ALARM_FIRE"
        const val EXTRA_JOB_ID = "jobId"
    }
}
