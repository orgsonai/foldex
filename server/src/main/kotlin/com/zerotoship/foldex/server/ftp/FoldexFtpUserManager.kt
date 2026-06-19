package com.zerotoship.foldex.server.ftp

import com.zerotoship.foldex.core.data.repo.ServerConfigRepository
import com.zerotoship.foldex.core.model.ServerAuthMode
import com.zerotoship.foldex.core.model.ServerLogEvent
import com.zerotoship.foldex.server.log.ServerLogger
import com.zerotoship.foldex.server.security.Argon2idHasher
import kotlinx.coroutines.runBlocking
import org.apache.ftpserver.ftplet.Authentication
import org.apache.ftpserver.ftplet.AuthenticationFailedException
import org.apache.ftpserver.ftplet.User
import org.apache.ftpserver.ftplet.UserManager
import org.apache.ftpserver.usermanager.AnonymousAuthentication
import org.apache.ftpserver.usermanager.UsernamePasswordAuthentication

/**
 * 1 サーバー設定 ([configId]) に紐づく Apache FtpServer の [UserManager] 実装。
 *
 * Apache FtpServer は同期 API なので、Repository / Logger 呼び出しは
 * [runBlocking] でブリッジする。authenticate 成功時は configId に対応する
 * 設定の username で [buildFtpUser] により User を組み立てる。
 *
 * doesExist / getUserByName は内部 (path 解決等) からも呼ばれるので、
 * 認証成功した username だけ「存在する」と返すと Apache FtpServer 側の
 * 動作が壊れるため、authMode と一致する username を許容する形にする。
 */
internal class FoldexFtpUserManager(
    private val configId: String,
    private val rootPath: String,
    private val repository: ServerConfigRepository,
    private val hasher: Argon2idHasher,
    private val logger: ServerLogger,
) : UserManager {

    override fun authenticate(authentication: Authentication): User {
        // Apache FtpServer 側で予期する例外は AuthenticationFailedException のみ。
        // その他の Throwable (Room の SQLiteException, ClassNotFoundException 等) を
        // MINA 経由で漏らすとプロセスごとクラッシュしうるので、外側で全部つかみ
        // 認証失敗扱いに統一する。
        return try {
            authenticateInner(authentication)
        } catch (e: AuthenticationFailedException) {
            throw e
        } catch (t: Throwable) {
            android.util.Log.e(TAG, "FTP authenticate failed unexpectedly", t)
            runCatching {
                runBlocking {
                    logger.record(
                        configId = configId,
                        event = ServerLogEvent.AUTH_FAILED,
                        clientAddress = "ftp",
                        username = (authentication as? UsernamePasswordAuthentication)?.username,
                        details = "internal_error:${t.javaClass.simpleName}:${t.message}",
                    )
                }
            }
            throw AuthenticationFailedException("Internal error: ${t.javaClass.simpleName}")
        }
    }

    private fun authenticateInner(authentication: Authentication): User {
        val config = runBlocking { repository.findById(configId) }
            ?: throw AuthenticationFailedException("Server config not found")

        val (username, plainPassword, isAnonymous) = when (authentication) {
            is UsernamePasswordAuthentication ->
                Triple(authentication.username ?: "", authentication.password ?: "", false)
            is AnonymousAuthentication ->
                Triple("anonymous", "", true)
            else ->
                throw AuthenticationFailedException("Unsupported authentication: ${authentication.javaClass.simpleName}")
        }

        val authorized = runBlocking {
            when (config.authMode) {
                ServerAuthMode.ANONYMOUS -> true
                ServerAuthMode.PASSWORD, ServerAuthMode.PASSWORD_OR_PUBLIC_KEY -> {
                    if (isAnonymous) return@runBlocking false
                    if (config.username != username) return@runBlocking false
                    val hashBytes = repository.loadCredentialBytes(config.passwordHashRef)
                        ?: return@runBlocking false
                    hasher.verify(
                        plainPassword.toByteArray(Charsets.UTF_8),
                        String(hashBytes, Charsets.UTF_8),
                    )
                }
                // FTP は公開鍵認証を持たないので PUBLIC_KEY 系は常に拒否。
                ServerAuthMode.PUBLIC_KEY -> false
            }
        }

        runBlocking {
            logger.record(
                configId = configId,
                event = if (authorized) ServerLogEvent.AUTH_SUCCESS else ServerLogEvent.AUTH_FAILED,
                clientAddress = "ftp",
                username = username,
                details = "method=password,mode=${config.authMode.name}",
            )
        }
        if (!authorized) throw AuthenticationFailedException("Authentication failed")
        return buildFtpUser(config, username, rootPath)
    }

    override fun getUserByName(username: String?): User? = runCatching {
        if (username == null) return@runCatching null
        val config = runBlocking { repository.findById(configId) } ?: return@runCatching null
        when (config.authMode) {
            ServerAuthMode.ANONYMOUS -> buildFtpUser(config, username, rootPath)
            ServerAuthMode.PASSWORD, ServerAuthMode.PASSWORD_OR_PUBLIC_KEY -> {
                if (config.username == username) buildFtpUser(config, username, rootPath) else null
            }
            ServerAuthMode.PUBLIC_KEY -> null
        }
    }.getOrElse {
        android.util.Log.e(TAG, "FTP getUserByName failed", it)
        null
    }

    override fun getAllUserNames(): Array<String> = runCatching {
        val config = runBlocking { repository.findById(configId) } ?: return@runCatching emptyArray()
        when (config.authMode) {
            ServerAuthMode.ANONYMOUS -> arrayOf("anonymous")
            ServerAuthMode.PASSWORD, ServerAuthMode.PASSWORD_OR_PUBLIC_KEY ->
                config.username?.let { arrayOf(it) } ?: emptyArray()
            ServerAuthMode.PUBLIC_KEY -> emptyArray()
        }
    }.getOrElse {
        android.util.Log.e(TAG, "FTP getAllUserNames failed", it)
        emptyArray()
    }

    override fun delete(username: String?) {
        throw UnsupportedOperationException("Not supported on Foldex managed config")
    }

    override fun save(user: User?) {
        throw UnsupportedOperationException("Not supported on Foldex managed config")
    }

    override fun doesExist(username: String?): Boolean = getUserByName(username) != null

    override fun getAdminName(): String = "__foldex_admin__"

    override fun isAdmin(username: String?): Boolean = false

    private companion object {
        const val TAG = "FoldexFtpUserMgr"
    }
}
