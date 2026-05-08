package com.zerotoship.foldex.ui.connections

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zerotoship.foldex.core.data.repo.ConnectionRepository
import com.zerotoship.foldex.core.model.AuthMethod
import com.zerotoship.foldex.core.model.Connection
import com.zerotoship.foldex.core.model.Credential
import com.zerotoship.foldex.core.model.Protocol
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class ConnectionsViewModel @Inject constructor(
    private val repository: ConnectionRepository,
) : ViewModel() {

    val connections: StateFlow<List<Connection>> = repository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _editing = MutableStateFlow<EditingState?>(null)
    val editing: StateFlow<EditingState?> = _editing.asStateFlow()

    private val _events = Channel<ConnectionEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    fun startCreate() {
        _editing.value = EditingState(
            id = UUID.randomUUID().toString(),
            isNew = true,
            name = "",
            host = "",
            port = Protocol.SMB.defaultPort,
            username = "",
            password = "",
            share = "",
            domain = "",
            anonymous = false,
        )
    }

    fun startEdit(connection: Connection) {
        if (connection !is Connection.Smb) {
            sendEvent(ConnectionEvent.Message("SMB 以外の編集は P5 以降"))
            return
        }
        _editing.value = EditingState(
            id = connection.id,
            isNew = false,
            name = connection.name,
            host = connection.host,
            port = connection.port,
            username = connection.username.orEmpty(),
            password = "",
            share = connection.share,
            domain = connection.domain.orEmpty(),
            anonymous = connection.authMethod == AuthMethod.ANONYMOUS,
        )
    }

    fun cancelEdit() {
        _editing.value = null
    }

    fun updateField(transform: (EditingState) -> EditingState) {
        val current = _editing.value ?: return
        _editing.value = transform(current)
    }

    fun save() {
        val draft = _editing.value ?: return
        if (draft.name.isBlank() || draft.host.isBlank() || draft.share.isBlank()) {
            sendEvent(ConnectionEvent.Message("名前 / ホスト / 共有名は必須"))
            return
        }
        viewModelScope.launch {
            val authMethod = if (draft.anonymous) AuthMethod.ANONYMOUS else AuthMethod.PASSWORD
            val connection = Connection.Smb(
                id = draft.id,
                name = draft.name.trim(),
                host = draft.host.trim(),
                port = draft.port,
                username = draft.username.trim().ifBlank { null },
                authMethod = authMethod,
                share = draft.share.trim(),
                domain = draft.domain.trim().ifBlank { null },
            )
            val credential: Credential? = when {
                draft.anonymous -> Credential.Anonymous
                draft.password.isNotEmpty() -> Credential.Password(draft.password.toByteArray(Charsets.UTF_8))
                draft.isNew -> Credential.Anonymous
                else -> null // 既存編集でパスワード未入力 → 既存の credentialRef を維持
            }
            try {
                repository.save(connection, credential)
                _editing.value = null
                sendEvent(ConnectionEvent.Message(if (draft.isNew) "接続を追加しました" else "接続を更新しました"))
            } catch (t: Throwable) {
                sendEvent(ConnectionEvent.Message("保存エラー: ${t.message}"))
            }
        }
    }

    fun delete(connection: Connection) {
        viewModelScope.launch {
            try {
                repository.delete(connection.id)
                sendEvent(ConnectionEvent.Message("「${connection.name}」を削除しました"))
            } catch (t: Throwable) {
                sendEvent(ConnectionEvent.Message("削除エラー: ${t.message}"))
            }
        }
    }

    private fun sendEvent(event: ConnectionEvent) {
        _events.trySend(event)
    }

    data class EditingState(
        val id: String,
        val isNew: Boolean,
        val name: String,
        val host: String,
        val port: Int,
        val username: String,
        val password: String,
        val share: String,
        val domain: String,
        val anonymous: Boolean,
    )

    sealed class ConnectionEvent {
        data class Message(val text: String) : ConnectionEvent()
    }
}
