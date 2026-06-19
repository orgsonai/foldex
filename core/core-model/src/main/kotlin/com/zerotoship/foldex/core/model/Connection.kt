// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.core.model

/**
 * 接続設定 (公開情報のみ)。認証情報は [Credential] として別途暗号化保存する。
 */
sealed class Connection {
    abstract val id: String
    abstract val name: String
    abstract val protocol: Protocol
    abstract val host: String
    abstract val port: Int
    abstract val username: String?
    abstract val authMethod: AuthMethod
    abstract val charset: String

    /**
     * 接続を開いたときに自動で navigate する相対パス (任意)。
     * SMB は share 配下、SFTP/FTP は root からのパス。空文字なら "/" (ルート) を開く。
     */
    open val initialPath: String get() = ""

    data class Smb(
        override val id: String,
        override val name: String,
        override val host: String,
        override val port: Int = Protocol.SMB.defaultPort,
        override val username: String?,
        override val authMethod: AuthMethod,
        val share: String,
        val domain: String? = null,
        override val charset: String = "UTF-8",
        override val initialPath: String = "",
    ) : Connection() {
        override val protocol: Protocol = Protocol.SMB
    }

    data class Sftp(
        override val id: String,
        override val name: String,
        override val host: String,
        override val port: Int = Protocol.SFTP.defaultPort,
        override val username: String?,
        override val authMethod: AuthMethod,
        val hostKeyFingerprint: String? = null,
        override val charset: String = "UTF-8",
        override val initialPath: String = "",
    ) : Connection() {
        override val protocol: Protocol = Protocol.SFTP
    }

    data class Ftp(
        override val id: String,
        override val name: String,
        override val host: String,
        override val port: Int = Protocol.FTP.defaultPort,
        override val username: String?,
        override val authMethod: AuthMethod,
        val useTls: Boolean = false,
        val passiveMode: Boolean = true,
        override val charset: String = "UTF-8",
        override val initialPath: String = "",
    ) : Connection() {
        override val protocol: Protocol = Protocol.FTP
    }

    data class WebDav(
        override val id: String,
        override val name: String,
        override val host: String,
        override val port: Int = Protocol.WEBDAV.defaultPort,
        override val username: String?,
        override val authMethod: AuthMethod,
        val basePath: String = "/",
        val useHttps: Boolean = true,
        override val charset: String = "UTF-8",
    ) : Connection() {
        override val protocol: Protocol = Protocol.WEBDAV
    }
}
