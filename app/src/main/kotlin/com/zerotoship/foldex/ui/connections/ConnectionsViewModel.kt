// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

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
        val defaultPort = if (protocol == Protocol.WEBDAV) 443 else protocol.defaultPort
        _editing.value = EditingState(
            id = UUID.randomUUID().toString(),
            isNew = true,
            protocol = protocol,
            name = "",
            host = "",
            portText = defaultPort.toString(),
            username = "",
            password = "",
            share = "",
            domain = "",
            anonymous = false,
            hostKeyFingerprint = "",
            useTls = false,
            passiveMode = true,
            charset = "UTF-8",
            basePath = "/",
            useHttps = true,
            sftpAuthMode = SftpAuthMode.PASSWORD,
            sftpPrivateKeyPem = "",
            sftpPublicKeyOpenSsh = "",
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
                portText = connection.port.toString(),
                username = connection.username.orEmpty(),
                password = "",
                // 共有名と初期パスを 1 本の「パス」に結合して表示 (例: public + /docs → public/docs)。
                share = connection.share + connection.initialPath,
                domain = connection.domain.orEmpty(),
                anonymous = connection.authMethod == AuthMethod.ANONYMOUS,
                hostKeyFingerprint = "",
                useTls = false,
                passiveMode = true,
                charset = connection.charset,
                basePath = "/",
                useHttps = true,
                initialPath = "",
                sftpAuthMode = SftpAuthMode.PASSWORD,
                sftpPrivateKeyPem = "",
                sftpPublicKeyOpenSsh = "",
            )
            is Connection.Sftp -> EditingState(
                id = connection.id,
                isNew = false,
                protocol = Protocol.SFTP,
                name = connection.name,
                host = connection.host,
                portText = connection.port.toString(),
                username = connection.username.orEmpty(),
                password = "",
                share = "",
                domain = "",
                anonymous = false,
                hostKeyFingerprint = connection.hostKeyFingerprint.orEmpty(),
                useTls = false,
                passiveMode = true,
                charset = connection.charset,
                basePath = "/",
                useHttps = true,
                initialPath = connection.initialPath,
                sftpAuthMode = if (connection.authMethod == AuthMethod.PUBLIC_KEY) {
                    SftpAuthMode.PUBLIC_KEY
                } else {
                    SftpAuthMode.PASSWORD
                },
                sftpPrivateKeyPem = "",
                sftpPublicKeyOpenSsh = "",
            )
            is Connection.Ftp -> EditingState(
                id = connection.id,
                isNew = false,
                protocol = Protocol.FTP,
                name = connection.name,
                host = connection.host,
                portText = connection.port.toString(),
                username = connection.username.orEmpty(),
                password = "",
                share = "",
                domain = "",
                anonymous = connection.authMethod == AuthMethod.ANONYMOUS,
                hostKeyFingerprint = "",
                useTls = connection.useTls,
                passiveMode = connection.passiveMode,
                charset = connection.charset,
                basePath = "/",
                useHttps = true,
                initialPath = connection.initialPath,
                sftpAuthMode = SftpAuthMode.PASSWORD,
                sftpPrivateKeyPem = "",
                sftpPublicKeyOpenSsh = "",
            )
            is Connection.WebDav -> EditingState(
                id = connection.id,
                isNew = false,
                protocol = Protocol.WEBDAV,
                name = connection.name,
                host = connection.host,
                portText = connection.port.toString(),
                username = connection.username.orEmpty(),
                password = "",
                share = "",
                domain = "",
                anonymous = connection.authMethod == AuthMethod.ANONYMOUS,
                hostKeyFingerprint = "",
                useTls = false,
                passiveMode = true,
                charset = connection.charset,
                basePath = connection.basePath,
                useHttps = connection.useHttps,
                sftpAuthMode = SftpAuthMode.PASSWORD,
                sftpPrivateKeyPem = "",
                sftpPublicKeyOpenSsh = "",
            )
        }
    }

    /** SFTP: クライアント秘密鍵を生成し、公開鍵 (authorized_keys 用) を編集状態に格納する。 */
    fun generateSftpKeyPair() {
        val current = _editing.value ?: return
        if (current.protocol != Protocol.SFTP) return
        val kp = SshClientKeyHelper.generate()
        val pem = SshClientKeyHelper.toPkcs8Pem(kp)
        val ssh = SshClientKeyHelper.toOpenSshPublic(kp, comment = "foldex@${current.host.ifBlank { "android" }}")
        _editing.value = current.copy(
            sftpAuthMode = SftpAuthMode.PUBLIC_KEY,
            sftpPrivateKeyPem = pem,
            sftpPublicKeyOpenSsh = ssh,
        )
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
        val newPort = when (protocol) {
            Protocol.WEBDAV -> if (current.useHttps) 443 else 80
            else -> protocol.defaultPort
        }
        _editing.value = current.copy(
            protocol = protocol,
            portText = newPort.toString(),
            anonymous = if (protocol == Protocol.SFTP) false else current.anonymous,
            useTls = if (protocol == Protocol.FTP) current.useTls else false,
            passiveMode = if (protocol == Protocol.FTP) current.passiveMode else true,
        )
    }

    /**
     * `sftp://user@host:port/path` のような URI を解釈し、現在の編集状態に反映する。
     * 不正な書式なら何もしない。protocol/host/port/username/basePath を埋める。
     */
    fun applyUri(uri: String): Boolean {
        val current = _editing.value ?: return false
        val trimmed = uri.trim()
        // scheme://[user[:pass]@]host[:port][/path]
        val re = Regex("^([a-zA-Z][a-zA-Z0-9+.\\-]*)://(?:([^@/]+)@)?\\[?([^/:\\]]+)\\]?(?::(\\d+))?(/.*)?$")
        val m = re.matchEntire(trimmed) ?: return false
        val scheme = m.groupValues[1].lowercase()
        val userinfo = m.groupValues[2]
        val host = m.groupValues[3]
        val portStr = m.groupValues[4]
        val path = m.groupValues[5].ifEmpty { "/" }
        val protocol = when (scheme) {
            "sftp", "ssh" -> Protocol.SFTP
            "ftp" -> Protocol.FTP
            "ftps" -> Protocol.FTP
            "smb", "cifs" -> Protocol.SMB
            "webdav", "dav", "http" -> Protocol.WEBDAV
            "webdavs", "davs", "https" -> Protocol.WEBDAV
            else -> return false
        }
        val (user, pass) = userinfo.split(':', limit = 2).let {
            if (it.size == 2) it[0] to it[1] else (it.firstOrNull().orEmpty() to "")
        }
        val defaultPort = when (protocol) {
            Protocol.WEBDAV -> if (scheme == "https" || scheme == "webdavs" || scheme == "davs") 443 else 80
            else -> protocol.defaultPort
        }
        val port = portStr.toIntOrNull() ?: defaultPort
        // SMB の path は //host/share/sub → share とサブパス。
        val (smbShare, smbSub) = if (protocol == Protocol.SMB) {
            val parts = path.trimStart('/').split('/', limit = 2)
            (parts.getOrNull(0).orEmpty() to (parts.getOrNull(1)?.let { "/$it" } ?: "/"))
        } else "" to "/"
        _editing.value = current.copy(
            protocol = protocol,
            host = host,
            portText = port.toString(),
            username = user.ifBlank { current.username },
            password = pass.ifBlank { current.password },
            share = if (protocol == Protocol.SMB) smbShare.ifBlank { current.share } else current.share,
            basePath = when (protocol) {
                Protocol.WEBDAV -> path
                Protocol.SMB -> smbSub
                else -> current.basePath
            },
            useHttps = when (protocol) {
                Protocol.WEBDAV -> scheme == "https" || scheme == "webdavs" || scheme == "davs"
                else -> current.useHttps
            },
            useTls = when (protocol) {
                Protocol.FTP -> scheme == "ftps"
                else -> current.useTls
            },
            // 名前は空ならスキーム+ホストで補完。
            name = if (current.name.isBlank()) "$scheme://$host" else current.name,
        )
        return true
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
            Protocol.WEBDAV -> saveWebDav(draft)
        }
    }

    private fun saveWebDav(draft: EditingState) {
        viewModelScope.launch {
            val authMethod = if (draft.anonymous) AuthMethod.ANONYMOUS else AuthMethod.PASSWORD
            val basePath = draft.basePath.trim().ifBlank { "/" }
                .let { if (it.startsWith("/")) it else "/$it" }
            val connection = Connection.WebDav(
                id = draft.id,
                name = draft.name.trim(),
                host = draft.host.trim(),
                port = draft.effectivePort(),
                username = draft.username.trim().ifBlank { null },
                authMethod = authMethod,
                basePath = basePath,
                useHttps = draft.useHttps,
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

    private fun saveSmb(draft: EditingState) {
        viewModelScope.launch {
            val authMethod = if (draft.anonymous) AuthMethod.ANONYMOUS else AuthMethod.PASSWORD
            // 「パス」フィールドに `share/sub/path` 形式で入力されたら、先頭セグメントが share、
            // 残りを initialPath として分解する (SMB プロトコル仕様: share は単一の名前)。
            // 空欄も許可 (share = "" / 初期パスはルート)。
            val rawShare = draft.share.trim().trim('/')
            val firstSlash = rawShare.indexOf('/')
            val parsedShare = if (firstSlash >= 0) rawShare.substring(0, firstSlash) else rawShare
            val parsedSub = if (firstSlash >= 0) rawShare.substring(firstSlash) else ""
            val initialPath = listOf(parsedSub, draft.initialPath.trim())
                .firstOrNull { it.isNotBlank() }
                ?.let { normalizeInitialPath(it) }
                ?: ""
            val connection = Connection.Smb(
                id = draft.id,
                name = draft.name.trim(),
                host = draft.host.trim(),
                port = draft.effectivePort(),
                username = draft.username.trim().ifBlank { null },
                authMethod = authMethod,
                share = parsedShare,
                domain = draft.domain.trim().ifBlank { null },
                initialPath = initialPath,
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
                port = draft.effectivePort(),
                username = draft.username.trim().ifBlank { null },
                authMethod = authMethod,
                useTls = draft.useTls,
                passiveMode = draft.passiveMode,
                charset = draft.charset.trim().ifBlank { "UTF-8" },
                initialPath = normalizeInitialPath(draft.initialPath),
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
        if (draft.sftpAuthMode == SftpAuthMode.PUBLIC_KEY) {
            // 公開鍵モード: 編集セッション中に新規生成された場合のみ秘密鍵を更新する。
            // 既存接続を編集して鍵を変えないときは Credential = null で repo に渡し、既存の鍵を温存する。
            if (draft.isNew && draft.sftpPrivateKeyPem.isBlank()) {
                sendEvent(ConnectionEvent.Message("「鍵を生成」を押して公開鍵を発行してください"))
                return
            }
        } else {
            // パスワードモード: 初回はパスワード必須。
            if (draft.isNew && draft.password.isBlank()) {
                sendEvent(ConnectionEvent.Message("初回はパスワードが必要"))
                return
            }
        }
        viewModelScope.launch {
            val authMethod = if (draft.sftpAuthMode == SftpAuthMode.PUBLIC_KEY) {
                AuthMethod.PUBLIC_KEY
            } else {
                AuthMethod.PASSWORD
            }
            val connection = Connection.Sftp(
                id = draft.id,
                name = draft.name.trim(),
                host = draft.host.trim(),
                port = draft.effectivePort(),
                username = draft.username.trim(),
                authMethod = authMethod,
                hostKeyFingerprint = draft.hostKeyFingerprint.trim().ifBlank { null },
                initialPath = normalizeInitialPath(draft.initialPath),
            )
            val credential: Credential? = when {
                draft.sftpAuthMode == SftpAuthMode.PUBLIC_KEY && draft.sftpPrivateKeyPem.isNotBlank() ->
                    Credential.SshPrivateKey(
                        keyData = draft.sftpPrivateKeyPem.toByteArray(Charsets.UTF_8),
                        passphrase = null,
                    )
                draft.sftpAuthMode == SftpAuthMode.PASSWORD && draft.password.isNotEmpty() ->
                    Credential.Password(draft.password.toByteArray(Charsets.UTF_8))
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

    /** ドラッグ並び替えの確定保存。[orderedIds] の順序を永続化する。 */
    fun applyOrder(orderedIds: List<String>) {
        viewModelScope.launch {
            runCatching { repository.reorder(orderedIds) }
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

    /** "/" 始まりに揃え、末尾スラッシュは取る。空白入力は "" を返す。 */
    private fun normalizeInitialPath(raw: String): String {
        val trimmed = raw.trim().trim('/')
        return if (trimmed.isEmpty()) "" else "/$trimmed"
    }

    /** SFTP の認証方式 UI 選択。 */
    enum class SftpAuthMode { PASSWORD, PUBLIC_KEY }

    data class EditingState(
        val id: String,
        val isNew: Boolean,
        val protocol: Protocol,
        val name: String,
        val host: String,
        val portText: String, // 空欄を許可。保存時に [effectivePort] でフォールバック。
        val username: String,
        val password: String,
        val share: String,
        val domain: String,
        val anonymous: Boolean,
        val hostKeyFingerprint: String,
        val useTls: Boolean,
        val passiveMode: Boolean,
        val charset: String,
        val basePath: String,
        val useHttps: Boolean,
        /** SMB/SFTP/FTP の「接続を開いた直後に開くサブパス」(任意)。空ならルートから。 */
        val initialPath: String = "",
        // SFTP 専用: 認証方式 + 生成済み鍵ペア (UI 表示・保存用)。
        val sftpAuthMode: SftpAuthMode = SftpAuthMode.PASSWORD,
        val sftpPrivateKeyPem: String = "",
        val sftpPublicKeyOpenSsh: String = "",
    ) {
        /** 保存時のポート: 入力が空 or 不正なら protocol の既定。 */
        fun effectivePort(): Int = portText.trim().toIntOrNull()
            ?.takeIf { it in 1..65535 }
            ?: when (protocol) {
                Protocol.WEBDAV -> if (useHttps) 443 else 80
                else -> protocol.defaultPort
            }
    }

    sealed class ConnectionEvent {
        data class Message(val text: String) : ConnectionEvent()
    }
}
