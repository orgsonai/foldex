// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.server

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.zerotoship.foldex.core.data.repo.ServerConfigRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 端末再起動後、`autoStartOnBoot` が有効なサーバー設定を自動起動する — 仕様書 §9-J。
 * Wi-Fi 限定のサーバーは起動直後にネットワークが未接続だと [ServerService] 側で起動失敗するが、
 * その場合は何も起動しないだけ (ユーザーが後で手動起動できる)。
 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject lateinit var repository: ServerConfigRepository

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != ACTION_QUICKBOOT_POWERON
        ) {
            return
        }
        val appContext = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                repository.observeAll().first()
                    .filter { it.autoStartOnBoot }
                    .forEach { config ->
                        ContextCompat.startForegroundService(
                            appContext,
                            ServerService.startIntent(appContext, config.id),
                        )
                    }
            } catch (_: Throwable) {
                // 自動起動の失敗は致命的ではない
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val ACTION_QUICKBOOT_POWERON = "android.intent.action.QUICKBOOT_POWERON"
    }
}
