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
            dynamicColor = p[KEY_DYNAMIC_COLOR] ?: true,
            showExtensionBadge = p[KEY_EXTENSION_BADGE] ?: true,
            confirmBeforeDelete = p[KEY_CONFIRM_DELETE] ?: true,
            undoTimeoutSeconds = p[KEY_UNDO_TIMEOUT] ?: 5,
            deleteBehavior = p[KEY_DELETE_BEHAVIOR]?.let { runCatching { DeleteBehavior.valueOf(it) }.getOrNull() }
                ?: DeleteBehavior.TRASH,
            trashRetentionDays = p[KEY_TRASH_RETENTION] ?: 30,
        )
    }

    suspend fun setThemeMode(mode: ThemeMode) = edit { it[KEY_THEME_MODE] = mode.name }

    suspend fun setDynamicColor(enabled: Boolean) = edit { it[KEY_DYNAMIC_COLOR] = enabled }

    suspend fun setShowExtensionBadge(enabled: Boolean) = edit { it[KEY_EXTENSION_BADGE] = enabled }

    suspend fun setConfirmBeforeDelete(enabled: Boolean) = edit { it[KEY_CONFIRM_DELETE] = enabled }

    suspend fun setUndoTimeoutSeconds(seconds: Int) = edit { it[KEY_UNDO_TIMEOUT] = seconds }

    suspend fun setDeleteBehavior(behavior: DeleteBehavior) = edit { it[KEY_DELETE_BEHAVIOR] = behavior.name }

    suspend fun setTrashRetentionDays(days: Int) = edit { it[KEY_TRASH_RETENTION] = days }

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
    }
}
