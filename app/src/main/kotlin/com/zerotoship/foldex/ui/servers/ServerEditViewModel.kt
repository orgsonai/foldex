package com.zerotoship.foldex.ui.servers

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zerotoship.foldex.core.data.repo.ServerConfigRepository
import com.zerotoship.foldex.core.model.ServerAuthMode
import com.zerotoship.foldex.core.model.ServerConfig
import com.zerotoship.foldex.core.model.ServerType
import com.zerotoship.foldex.server.security.Argon2idHasher
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/**
 * サーバー設定の追加・編集画面の状態。
 *
 * navigation 引数 `id` が null の場合は新規、そうでなければ既存設定を初期値に
 * 読み込む。パスワード入力欄はメモリ上の plain 文字列であり、保存時に
 * Argon2id ハッシュ化してから [ServerConfigRepository] に渡すので、Repository
 * 自体は plain を一切受け取らない。
 */
@HiltViewModel
class ServerEditViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: ServerConfigRepository,
    private val hasher: Argon2idHasher,
) : ViewModel() {

    private val targetId: String? = savedStateHandle["id"]

    private val _state = MutableStateFlow(ServerEditState.empty(isNew = targetId == null))
    val state: StateFlow<ServerEditState> = _state.asStateFlow()

    private val _events = Channel<ServerEditEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    init {
        viewModelScope.launch {
            val id = targetId ?: return@launch
            val existing = repository.findById(id) ?: return@launch
            _state.value = ServerEditState.fromConfig(existing)
        }
    }

    fun update(transform: (ServerEditState) -> ServerEditState) {
        _state.update(transform)
    }

    fun changeAuthMode(mode: ServerAuthMode) {
        _state.update { it.copy(authMode = mode) }
    }

    fun changeType(type: ServerType) {
        _state.update { current ->
            if (current.type == type) return@update current
            val newPort = type.defaultPort
            current.copy(
                type = type,
                port = newPort,
                // FTP は公開鍵認証を持たないので、FTP 切替時は PASSWORD に寄せる。
                authMode = if (type == ServerType.FTP &&
                    (current.authMode == ServerAuthMode.PUBLIC_KEY ||
                        current.authMode == ServerAuthMode.PASSWORD_OR_PUBLIC_KEY)
                ) {
                    ServerAuthMode.PASSWORD
                } else {
                    current.authMode
                },
            )
        }
    }

    fun save() {
        val draft = _state.value
        val error = draft.validate()
        if (error != null) {
            _events.trySend(ServerEditEvent.Message(error))
            return
        }
        viewModelScope.launch {
            try {
                val config = draft.toConfig()
                repository.upsert(config)
                if (draft.password.isNotEmpty() && draft.authMode.requiresPassword()) {
                    val encoded = hasher.hash(draft.password.toByteArray(Charsets.UTF_8))
                    repository.savePasswordHash(config.id, encoded.toByteArray(Charsets.UTF_8))
                }
                if (draft.authorizedKeys.isNotBlank() && draft.authMode.requiresAuthorizedKeys()) {
                    repository.saveAuthorizedKeys(
                        config.id,
                        draft.authorizedKeys.toByteArray(Charsets.UTF_8),
                    )
                }
                _events.trySend(ServerEditEvent.Saved)
            } catch (t: Throwable) {
                _events.trySend(ServerEditEvent.Message("保存エラー: ${t.message}"))
            }
        }
    }

    sealed class ServerEditEvent {
        object Saved : ServerEditEvent()
        data class Message(val text: String) : ServerEditEvent()
    }
}

data class ServerEditState(
    val id: String,
    val isNew: Boolean,
    val type: ServerType,
    val name: String,
    val port: Int,
    val wifiOnlyMode: Boolean,
    val rootPath: String,
    val readOnly: Boolean,
    val authMode: ServerAuthMode,
    val username: String,
    val password: String,
    val authorizedKeys: String,
    val autoStartOnAppLaunch: Boolean,
    val autoStartOnBoot: Boolean,
) {

    fun validate(): String? {
        if (name.isBlank()) return "名前は必須です"
        if (rootPath.isBlank()) return "ルートパスは必須です"
        if (!rootPath.startsWith("/")) return "ルートパスは絶対パスで入力してください"
        if (port !in 1..65535) return "ポート番号は 1〜65535 の範囲で入力してください"
        if (authMode.requiresUsername() && username.isBlank()) return "ユーザー名は必須です"
        if (isNew && authMode.requiresPassword() && password.isBlank()) {
            return "新規作成時はパスワードが必要です"
        }
        if (authMode.requiresAuthorizedKeys() && authorizedKeys.isBlank() && isNew) {
            return "新規作成時は authorized_keys が必要です"
        }
        return null
    }

    fun toConfig(): ServerConfig {
        val now = System.currentTimeMillis()
        return ServerConfig(
            id = id,
            type = type,
            name = name.trim(),
            port = port,
            bindAddress = if (wifiOnlyMode) ServerConfig.BIND_WIFI_ONLY else ServerConfig.BIND_ALL_INTERFACES,
            wifiOnlyMode = wifiOnlyMode,
            rootUri = "local://${rootPath.trim()}",
            readOnly = readOnly,
            authMode = authMode,
            username = username.trim().ifBlank { null },
            autoStartOnAppLaunch = autoStartOnAppLaunch,
            autoStartOnBoot = autoStartOnBoot,
            createdAt = now,
            updatedAt = now,
        )
    }

    companion object {
        fun empty(isNew: Boolean): ServerEditState = ServerEditState(
            id = UUID.randomUUID().toString(),
            isNew = isNew,
            type = ServerType.SFTP,
            name = "",
            port = ServerType.SFTP.defaultPort,
            wifiOnlyMode = true,
            rootPath = "/storage/emulated/0",
            readOnly = false,
            authMode = ServerAuthMode.PASSWORD,
            username = "foldex",
            password = "",
            authorizedKeys = "",
            autoStartOnAppLaunch = false,
            autoStartOnBoot = false,
        )

        fun fromConfig(config: ServerConfig): ServerEditState = ServerEditState(
            id = config.id,
            isNew = false,
            type = config.type,
            name = config.name,
            port = config.port,
            wifiOnlyMode = config.bindAddress == ServerConfig.BIND_WIFI_ONLY,
            rootPath = config.rootUri.removePrefix("local://"),
            readOnly = config.readOnly,
            authMode = config.authMode,
            username = config.username.orEmpty(),
            password = "",
            authorizedKeys = "",
            autoStartOnAppLaunch = config.autoStartOnAppLaunch,
            autoStartOnBoot = config.autoStartOnBoot,
        )
    }
}

private fun ServerAuthMode.requiresUsername(): Boolean =
    this != ServerAuthMode.ANONYMOUS

private fun ServerAuthMode.requiresPassword(): Boolean =
    this == ServerAuthMode.PASSWORD || this == ServerAuthMode.PASSWORD_OR_PUBLIC_KEY

private fun ServerAuthMode.requiresAuthorizedKeys(): Boolean =
    this == ServerAuthMode.PUBLIC_KEY || this == ServerAuthMode.PASSWORD_OR_PUBLIC_KEY
