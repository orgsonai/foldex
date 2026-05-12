package com.zerotoship.foldex.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zerotoship.foldex.core.data.repo.OpenWithMode
import com.zerotoship.foldex.core.data.repo.OpenWithRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class OpenWithSettingsViewModel @Inject constructor(
    private val repo: OpenWithRepository,
) : ViewModel() {

    val overrides: StateFlow<Map<String, OpenWithMode>> =
        repo.overrides.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    fun set(extension: String, mode: OpenWithMode) {
        viewModelScope.launch { repo.set(extension, mode) }
    }
}
