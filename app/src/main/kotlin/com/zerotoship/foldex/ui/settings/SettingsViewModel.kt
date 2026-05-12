package com.zerotoship.foldex.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zerotoship.foldex.core.data.repo.SettingsRepository
import com.zerotoship.foldex.core.data.repo.UserSettings
import com.zerotoship.foldex.core.model.DeleteBehavior
import com.zerotoship.foldex.core.model.ThemeMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repo: SettingsRepository,
) : ViewModel() {

    val settings: StateFlow<UserSettings> = repo.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UserSettings())

    fun setThemeMode(mode: ThemeMode) = viewModelScope.launch { repo.setThemeMode(mode) }
    fun setDynamicColor(enabled: Boolean) = viewModelScope.launch { repo.setDynamicColor(enabled) }
    fun setShowExtensionBadge(enabled: Boolean) = viewModelScope.launch { repo.setShowExtensionBadge(enabled) }
    fun setConfirmBeforeDelete(enabled: Boolean) = viewModelScope.launch { repo.setConfirmBeforeDelete(enabled) }
    fun setUndoTimeoutSeconds(seconds: Int) = viewModelScope.launch { repo.setUndoTimeoutSeconds(seconds) }
    fun setDeleteBehavior(behavior: DeleteBehavior) = viewModelScope.launch { repo.setDeleteBehavior(behavior) }
    fun setTrashRetentionDays(days: Int) = viewModelScope.launch { repo.setTrashRetentionDays(days) }
}
