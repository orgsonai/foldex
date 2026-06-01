package com.zerotoship.foldex.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zerotoship.foldex.core.data.repo.SettingsRepository
import com.zerotoship.foldex.core.data.repo.UserSettings
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
) : ViewModel() {

    val settings: StateFlow<UserSettings> = repo.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UserSettings())

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
}
