// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zerotoship.foldex.core.data.repo.SettingsRepository
import com.zerotoship.foldex.core.data.repo.UserSettings
import com.zerotoship.foldex.core.data.update.UpdateChecker
import com.zerotoship.foldex.core.data.update.UpdateStatus
import com.zerotoship.foldex.core.model.AppColorTheme
import com.zerotoship.foldex.core.model.DeleteBehavior
import com.zerotoship.foldex.core.model.SyncBackupPolicy
import com.zerotoship.foldex.core.model.ThemeMode
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repo: SettingsRepository,
    private val updateChecker: UpdateChecker,
) : ViewModel() {

    val settings: StateFlow<UserSettings> = repo.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UserSettings())

    /** 更新確認の状態。ユーザーが設定画面で押したときだけ動かす (自動確認はしない)。 */
    private val _updateStatus = MutableStateFlow<UpdateStatus>(UpdateStatus.Idle)
    val updateStatus: StateFlow<UpdateStatus> = _updateStatus.asStateFlow()

    /**
     * 変更履歴ダイアログを出すか。
     * 閉じても [updateStatus] は残すので、設定の行には「1.3.0 が公開されています」が出たままになる。
     */
    private val _showUpdateDialog = MutableStateFlow(false)
    val showUpdateDialog: StateFlow<Boolean> = _showUpdateDialog.asStateFlow()

    fun checkForUpdate() {
        // 連打で何本も走らせない。
        if (_updateStatus.value is UpdateStatus.Checking) return
        _updateStatus.value = UpdateStatus.Checking
        viewModelScope.launch {
            val current = runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
            }.getOrNull().orEmpty()
            val result = updateChecker.check(current)
            _updateStatus.value = result
            _showUpdateDialog.value = result is UpdateStatus.Available
        }
    }

    /** 更新が見つかったあと、行の表示だけ残してダイアログを閉じる。 */
    fun dismissUpdateDialog() {
        _showUpdateDialog.value = false
    }

    /** 行をもう一度押したとき、既に見つかっている更新のダイアログを開き直す。 */
    fun reopenUpdateDialog() {
        if (_updateStatus.value is UpdateStatus.Available) _showUpdateDialog.value = true
    }

    /** 内部/外部キャッシュの合計バイト数。設定画面が開かれたら refresh する。 */
    private val _cacheBytes = MutableStateFlow<Long?>(null)
    val cacheBytes: StateFlow<Long?> = _cacheBytes.asStateFlow()

    fun refreshCacheSize() {
        viewModelScope.launch {
            _cacheBytes.value = withContext(Dispatchers.IO) {
                dirSize(context.cacheDir) + dirSize(context.externalCacheDir)
            }
        }
    }

    /** 内部+外部のキャッシュを全削除。Foldex の `cacheDir` 配下のみ消し、sync-backup や crash log は残す。 */
    fun clearCache(onDone: (Long) -> Unit) {
        viewModelScope.launch {
            val freed = withContext(Dispatchers.IO) {
                val before = dirSize(context.cacheDir) + dirSize(context.externalCacheDir)
                context.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
                context.externalCacheDir?.listFiles()?.forEach { it.deleteRecursively() }
                before
            }
            _cacheBytes.value = 0L
            onDone(freed)
        }
    }

    private fun dirSize(dir: File?): Long {
        if (dir == null || !dir.exists()) return 0
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    fun setThemeMode(mode: ThemeMode) = viewModelScope.launch { repo.setThemeMode(mode) }
    fun setDynamicColor(enabled: Boolean) = viewModelScope.launch { repo.setDynamicColor(enabled) }
    fun setColorTheme(theme: AppColorTheme) = viewModelScope.launch { repo.setColorTheme(theme) }
    fun setShowExtensionBadge(enabled: Boolean) = viewModelScope.launch { repo.setShowExtensionBadge(enabled) }
    fun setConfirmBeforeDelete(enabled: Boolean) = viewModelScope.launch { repo.setConfirmBeforeDelete(enabled) }
    fun setUndoTimeoutSeconds(seconds: Int) = viewModelScope.launch { repo.setUndoTimeoutSeconds(seconds) }
    fun setDeleteBehavior(behavior: DeleteBehavior) = viewModelScope.launch { repo.setDeleteBehavior(behavior) }
    fun setTrashRetentionDays(days: Int) = viewModelScope.launch { repo.setTrashRetentionDays(days) }
    fun setSyncDeleteBackup(enabled: Boolean) = viewModelScope.launch { repo.setSyncDeleteBackup(enabled) }
    fun setSyncBackupGenerations(n: Int) = viewModelScope.launch { repo.setSyncBackupGenerations(n) }
    fun setSyncBackupThresholdMb(mb: Int) = viewModelScope.launch { repo.setSyncBackupThresholdMb(mb) }
    fun setSyncBackupPolicyOverThreshold(p: SyncBackupPolicy) = viewModelScope.launch { repo.setSyncBackupPolicyOverThreshold(p) }
    fun setEditorEditableLimitKb(kb: Int) = viewModelScope.launch { repo.setEditorEditableLimitKb(kb) }
    fun setNotifyOnFileOpComplete(enabled: Boolean) = viewModelScope.launch { repo.setNotifyOnFileOpComplete(enabled) }
    fun setNotifyOnExtractComplete(enabled: Boolean) = viewModelScope.launch { repo.setNotifyOnExtractComplete(enabled) }
    fun setNotifyOnSyncComplete(enabled: Boolean) = viewModelScope.launch { repo.setNotifyOnSyncComplete(enabled) }
    fun setSyncQueueTimeoutEnabled(enabled: Boolean) = viewModelScope.launch { repo.setSyncQueueTimeoutEnabled(enabled) }
    fun setSyncQueueTimeoutMinutes(minutes: Int) = viewModelScope.launch { repo.setSyncQueueTimeoutMinutes(minutes) }
}
