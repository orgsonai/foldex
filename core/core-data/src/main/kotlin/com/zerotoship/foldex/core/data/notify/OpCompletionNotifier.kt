package com.zerotoship.foldex.core.data.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * コピー/移動・解凍・同期などの長時間操作が「完了したとき」に出すシステム通知。
 *
 * 進行中の前景サービス通知 ([com.zerotoship.foldex.fileop.FileOpService] / SyncWorker) とは別物で、
 * こちらは完了を一度だけ知らせて自動的に消える (タップでアプリ起動)。
 * 各操作の ON/OFF は [UserSettings] の notify* フラグで制御する (呼び出し側で判定してから呼ぶ)。
 *
 * Android 13+ では POST_NOTIFICATIONS が未許可だと表示されない。その場合でも例外は投げず
 * 黙って何もしない (runCatching で握る)。
 */
@Singleton
class OpCompletionNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun notify(title: String, text: String) {
        ensureChannel()
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val contentIntent = launch?.let {
            PendingIntent.getActivity(
                context,
                0,
                it,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .apply { contentIntent?.let { setContentIntent(it) } }
            .build()
        runCatching {
            NotificationManagerCompat.from(context).notify(nextId(), notif)
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "操作完了のお知らせ", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "コピー・移動・解凍・同期などが終わったときに通知します"
                setShowBadge(true)
            },
        )
    }

    /** 完了通知が連続しても上書きで消えないよう、ID を回しながら払い出す。 */
    private fun nextId(): Int = ID_BASE + (counter.getAndIncrement() % 50)

    private companion object {
        const val CHANNEL_ID = "foldex_op_done"
        const val ID_BASE = 7000
        val counter = AtomicInteger(0)
    }
}
