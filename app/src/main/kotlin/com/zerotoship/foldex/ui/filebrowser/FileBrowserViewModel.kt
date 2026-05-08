package com.zerotoship.foldex.ui.filebrowser

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import com.zerotoship.foldex.core.model.FileNode
import com.zerotoship.foldex.core.model.FileUri
import com.zerotoship.foldex.storage.local.LocalStorageProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import javax.inject.Inject

@HiltViewModel
class FileBrowserViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val localStorage: LocalStorageProvider,
) : ViewModel() {

    private val prefs = context.getSharedPreferences("foldex_browser", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(FileBrowserState())
    val state: StateFlow<FileBrowserState> = _state.asStateFlow()

    init {
        val hasPerm = checkStoragePermission()
        val safRootUri = prefs.getString(KEY_SAF_ROOT, null)
        _state.value = _state.value.copy(
            hasStoragePermission = hasPerm,
            hasSafRoot = safRootUri != null,
        )
        when {
            hasPerm -> navigateTo(
                FileUri.Local(Environment.getExternalStorageDirectory().absolutePath),
                displayName = "内部ストレージ",
            )
            safRootUri != null -> navigateTo(FileUri.Saf(safRootUri), displayName = "ストレージ")
        }
    }

    fun checkStoragePermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            val perm = android.Manifest.permission.READ_EXTERNAL_STORAGE
            context.checkSelfPermission(perm) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }

    fun onStoragePermissionGranted() {
        _state.value = _state.value.copy(hasStoragePermission = true, breadcrumbs = emptyList())
        navigateTo(
            FileUri.Local(Environment.getExternalStorageDirectory().absolutePath),
            displayName = "内部ストレージ",
        )
    }

    fun onSafRootPicked(treeUri: Uri) {
        context.contentResolver.takePersistableUriPermission(
            treeUri,
            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )
        prefs.edit().putString(KEY_SAF_ROOT, treeUri.toString()).apply()
        _state.value = _state.value.copy(hasSafRoot = true, breadcrumbs = emptyList())
        navigateTo(FileUri.Saf(treeUri.toString()), displayName = "ストレージ")
    }

    fun navigateTo(uri: FileUri, displayName: String) {
        val newCrumbs = _state.value.breadcrumbs + BreadcrumbItem(uri, displayName)
        _state.value = _state.value.copy(breadcrumbs = newCrumbs)
        loadFiles(uri)
    }

    fun navigateToIndex(index: Int) {
        val crumbs = _state.value.breadcrumbs
        if (index !in crumbs.indices) return
        val newCrumbs = crumbs.take(index + 1)
        _state.value = _state.value.copy(breadcrumbs = newCrumbs)
        loadFiles(newCrumbs.last().uri)
    }

    fun navigateUp(): Boolean {
        val size = _state.value.breadcrumbs.size
        if (size <= 1) return false
        navigateToIndex(size - 2)
        return true
    }

    fun setViewMode(mode: ViewMode) {
        _state.value = _state.value.copy(viewMode = mode)
    }

    fun refresh() {
        val uri = _state.value.currentUri ?: return
        loadFiles(uri)
    }

    private fun loadFiles(uri: FileUri) {
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, error = null)
            try {
                val files = mutableListOf<FileNode>()
                localStorage.list(uri).collect { files.add(it) }
                _state.value = _state.value.copy(isLoading = false, files = files)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = _state.value.copy(isLoading = false, error = e.message ?: "エラーが発生しました")
            }
        }
    }

    companion object {
        private const val KEY_SAF_ROOT = "saf_root_uri"
    }
}
