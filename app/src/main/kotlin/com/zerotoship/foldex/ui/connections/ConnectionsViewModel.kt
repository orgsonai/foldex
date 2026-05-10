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

    fun startCreate(protocol: Protocol = Protocol.SMB) {
        _editing.value = EditingState(
            id = UUID.randomUUID().toString(),
            isNew = true,
            protocol = protocol,
            name = "",
            host = "",
            port = protocol.defaultPort,
            username = "",
            password = "",
            share = "",
            domain = "",
            anonymous = false,
            hostKeyFingerprint = "",
            useTls = false,
            passiveMode = true,
            charset = "UTF-8",
        )
    }

    fun startEdit(connection: Connection) {
        _editing.value = when (connection) {
            is Connection.Smb -> EditingState(
                id = connection.id,
                isNew = false,
                protocol = Protocol.SMB,
                name = connection.name,
                host = connection.host,
                port = connection.port,
                username = connection.username.orEmpty(),
                password = "",
                share = connection.share,
                domain = connection.domain.orEmpty(),
                anonymous = connection.authMethod == AuthMethod.ANONYMOUS,
                hostKeyFingerprint = "",
                useTls = false,
                passiveMode = true,
                charset = connection.charset,
            )
            is Connection.Sftp -> EditingState(
                id = connection.id,
                isNew = false,
                protocol = Protocol.SFTP,
                name = connection.name,
                host = connection.host,
                port = connection.port,
                username = connection.username.orEmpty(),
                password = "",
                share = "",
                domain = "",
                anonymous = false,
                hostKeyFingerprint = connection.hostKeyFingerprint.orEmpty(),
                useTls = false,
                passiveMode = true,
                charset = connection.charset,
            )
            is Connection.Ftp -> EditingState(
                id = connection.id,
                isNew = false,
                protocol = Protocol.FTP,
                name = connection.name,
                host = connection.host,
                port = connection.port,
                username = connection.username.orEmpty(),
                password = "",
                share = "",
                domain = "",
                anonymous = connection.authMethod == AuthMethod.ANONYMOUS,
                hostKeyFingerprint = "",
                useTls = connection.useTls,
                passiveMode = connection.passiveMode,
                charset = connection.charset,
            )
            else -> {
                sendEvent(ConnectionEvent.Message("${connection.protocol.scheme.uppercase()} 編集は P5 以降"))
                return
            }
        }
    }

    fun cancelEdit() {
        _editing.value = null
    }

    fun updateField(transform: (EditingState) -> EditingState) {
        val current = _editing.value ?: return
        _editing.value = transform(current)
    }

    /**
     * プロトコル切替: ポートのデフォルトを連動させ、別プロトコル固有のフィールドはクリア。
     */
    fun changeProtocol(protocol: Protocol) {
        val current = _editing.value ?: return
        if (current.protocol == protocol) return
        _editing.value = current.copy(
            protocol = protocol,
            port = protocol.defaultPort,
            anonymous = if (protocol == Protocol.SFTP) false else current.anonymous,
            useTls = if (protocol == Protocol.FTP) current.useTls else false,
            passiveMode = if (protocol == Protocol.FTP) current.passiveMode else true,
        )
    }

    fun save() {
        val draft = _editing.value ?: return
        if (draft.name.isBlank() || draft.host.isBlank()) {
            sendEvent(ConnectionEvent.Message("名前 / ホストは必須"))
            return
        }
        when (draft.protocol) {
            Protocol.SMB -> saveSmb(draft)
            Protocol.SFTP -> saveSftp(draft)
            Protocol.FTP -> saveFtp(draft)
            else -> sendEvent(ConnectionEvent.Message("${draft.protocol.scheme.uppercase()} はまだサポートしていません"))
        }
    }

    private fun saveSmb(draft: EditingState) {
        if (draft.share.isBlank()) {
            sendEvent(ConnectionEvent.Message("共有名は必須"))
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
                else -> null
            }
            persist(connection, credential, draft.isNew)
        }
    }

    private fun saveFtp(draft: EditingState) {
        viewModelScope.launch {
            val authMethod = if (draft.anonymous) AuthMethod.ANONYMOUS else AuthMethod.PASSWORD
            val connection = Connection.Ftp(
                id = draft.id,
                name = draft.name.trim(),
                host = draft.host.trim(),
                port = draft.port,
                username = draft.username.trim().ifBlank { null },
                authMethod = authMethod,
                useTls = draft.useTls,
                passiveMode = draft.passiveMode,
                charset = draft.charset.trim().ifBlank { "UTF-8" },
            )
            val credential: Credential? = when {
                draft.anonymous -> Credential.Anonymous
                draft.password.isNotEmpty() -> Credential.Password(draft.password.toByteArray(Charsets.UTF_8))
                draft.isNew -> Credential.Anonymous
                else -> null
            }
            persist(connection, credential, draft.isNew)
        }
    }

    private fun saveSftp(draft: EditingState) {
        if (draft.username.isBlank()) {
            sendEvent(ConnectionEvent.Message("SFTP はユーザー名が必須"))
            return
        }
        if (draft.isNew && draft.password.isBlank()) {
            sendEvent(ConnectionEvent.Message("初回はパスワードが必要"))
            return
        }
        viewModelScope.launch {
            val connection = Connection.Sftp(
                id = draft.id,
                name = draft.name.trim(),
                host = draft.host.trim(),
                port = draft.port,
                username = draft.username.trim(),
                authMethod = AuthMethod.PASSWORD,
                hostKeyFingerprint = draft.hostKeyFingerprint.trim().ifBlank { null },
            )
            val credential: Credential? = when {
                draft.password.isNotEmpty() -> Credential.Password(draft.password.toByteArray(Charsets.UTF_8))
                else -> null
            }
            persist(connection, credential, draft.isNew)
        }
    }

    private suspend fun persist(connection: Connection, credential: Credential?, isNew: Boolean) {
        try {
            repository.save(connection, credential)
            _editing.value = null
            sendEvent(ConnectionEvent.Message(if (isNew) "接続を追加しました" else "接続を更新しました"))
        } catch (t: Throwable) {
            sendEvent(ConnectionEvent.Message("保存エラー: ${t.message}"))
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
        val protocol: Protocol,
        val name: String,
        val host: String,
        val port: Int,
        val username: String,
        val password: String,
        val share: String,
        val domain: String,
        val anonymous: Boolean,
        val hostKeyFingerprint: String,
        val useTls: Boolean,
        val passiveMode: Boolean,
        val charset: String,
    )

    sealed class ConnectionEvent {
        data class Message(val text: String) : ConnectionEvent()
    }
}
