package com.zerotoship.foldex.core.data.repo

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.zerotoship.foldex.core.model.DeleteBehavior
import com.zerotoship.foldex.core.model.SyncBackupPolicy
import com.zerotoship.foldex.core.model.ThemeMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "foldex_settings")

/**
 * [UserSettings] を DataStore (Preferences) で永続化するリポジトリ。
 *
 * 値ごとに setter を用意し、UI 側はトグル操作で即時保存する (保存ボタンは持たない)。
 */
@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val ds get() = context.settingsDataStore

    val settings: Flow<UserSettings> = ds.data.map { p ->
        UserSettings(
            themeMode = p[KEY_THEME_MODE]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
                ?: ThemeMode.SYSTEM,
            dynamicColor = p[KEY_DYNAMIC_COLOR] ?: false,
            showExtensionBadge = p[KEY_EXTENSION_BADGE] ?: true,
            confirmBeforeDelete = p[KEY_CONFIRM_DELETE] ?: true,
            undoTimeoutSeconds = p[KEY_UNDO_TIMEOUT] ?: 5,
            deleteBehavior = p[KEY_DELETE_BEHAVIOR]?.let { runCatching { DeleteBehavior.valueOf(it) }.getOrNull() }
                ?: DeleteBehavior.TRASH,
            trashRetentionDays = p[KEY_TRASH_RETENTION] ?: 30,
            syncDeleteBackup = p[KEY_SYNC_BACKUP] ?: true,
            syncBackupGenerations = p[KEY_SYNC_BACKUP_GENS] ?: 3,
            syncBackupThresholdMb = p[KEY_SYNC_BACKUP_THRESHOLD] ?: 50,
            syncBackupPolicyOverThreshold = p[KEY_SYNC_BACKUP_POLICY]
                ?.let { runCatching { SyncBackupPolicy.valueOf(it) }.getOrNull() } ?: SyncBackupPolicy.ASK,
            editorEditableLimitKb = p[KEY_EDITOR_LIMIT_KB] ?: 512,
            notifyOnFileOpComplete = p[KEY_NOTIFY_FILEOP] ?: true,
            notifyOnExtractComplete = p[KEY_NOTIFY_EXTRACT] ?: true,
            notifyOnSyncComplete = p[KEY_NOTIFY_SYNC] ?: true,
        )
    }

    suspend fun setThemeMode(mode: ThemeMode) = edit { it[KEY_THEME_MODE] = mode.name }

    suspend fun setDynamicColor(enabled: Boolean) = edit { it[KEY_DYNAMIC_COLOR] = enabled }

    suspend fun setShowExtensionBadge(enabled: Boolean) = edit { it[KEY_EXTENSION_BADGE] = enabled }

    suspend fun setConfirmBeforeDelete(enabled: Boolean) = edit { it[KEY_CONFIRM_DELETE] = enabled }

    suspend fun setUndoTimeoutSeconds(seconds: Int) = edit { it[KEY_UNDO_TIMEOUT] = seconds }

    suspend fun setDeleteBehavior(behavior: DeleteBehavior) = edit { it[KEY_DELETE_BEHAVIOR] = behavior.name }

    suspend fun setTrashRetentionDays(days: Int) = edit { it[KEY_TRASH_RETENTION] = days }

    suspend fun setSyncDeleteBackup(enabled: Boolean) = edit { it[KEY_SYNC_BACKUP] = enabled }

    suspend fun setSyncBackupGenerations(n: Int) = edit { it[KEY_SYNC_BACKUP_GENS] = n }

    suspend fun setSyncBackupThresholdMb(mb: Int) = edit { it[KEY_SYNC_BACKUP_THRESHOLD] = mb }

    suspend fun setSyncBackupPolicyOverThreshold(policy: SyncBackupPolicy) =
        edit { it[KEY_SYNC_BACKUP_POLICY] = policy.name }

    suspend fun setEditorEditableLimitKb(kb: Int) = edit { it[KEY_EDITOR_LIMIT_KB] = kb }

    suspend fun setNotifyOnFileOpComplete(enabled: Boolean) = edit { it[KEY_NOTIFY_FILEOP] = enabled }

    suspend fun setNotifyOnExtractComplete(enabled: Boolean) = edit { it[KEY_NOTIFY_EXTRACT] = enabled }

    suspend fun setNotifyOnSyncComplete(enabled: Boolean) = edit { it[KEY_NOTIFY_SYNC] = enabled }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        ds.edit(block)
    }

    private companion object {
        val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
        val KEY_DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val KEY_EXTENSION_BADGE = booleanPreferencesKey("extension_badge")
        val KEY_CONFIRM_DELETE = booleanPreferencesKey("confirm_before_delete")
        val KEY_UNDO_TIMEOUT = intPreferencesKey("undo_timeout_seconds")
        val KEY_DELETE_BEHAVIOR = stringPreferencesKey("delete_behavior")
        val KEY_TRASH_RETENTION = intPreferencesKey("trash_retention_days")
        val KEY_SYNC_BACKUP = booleanPreferencesKey("sync_delete_backup")
        val KEY_SYNC_BACKUP_GENS = intPreferencesKey("sync_backup_generations")
        val KEY_SYNC_BACKUP_THRESHOLD = intPreferencesKey("sync_backup_threshold_mb")
        val KEY_SYNC_BACKUP_POLICY = stringPreferencesKey("sync_backup_policy")
        val KEY_EDITOR_LIMIT_KB = intPreferencesKey("editor_editable_limit_kb")
        val KEY_NOTIFY_FILEOP = booleanPreferencesKey("notify_fileop_complete")
        val KEY_NOTIFY_EXTRACT = booleanPreferencesKey("notify_extract_complete")
        val KEY_NOTIFY_SYNC = booleanPreferencesKey("notify_sync_complete")
    }
}
