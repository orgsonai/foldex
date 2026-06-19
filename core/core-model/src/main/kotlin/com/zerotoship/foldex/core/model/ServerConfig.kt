// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.core.model

/**
 * 自機 (Foldex がインストールされた端末) で稼働させる SFTP / FTP サーバーの設定。
 * 公開情報のみ。パスワードハッシュ・公開鍵リスト・ホスト鍵・FTPS 証明書は
 * `EncryptedCredentialEntity` 経由で別管理し、ここでは ID 参照のみ持つ。
 */
data class ServerConfig(
    val id: String,
    val type: ServerType,
    val name: String,
    val enabled: Boolean = false,

    val port: Int,
    val bindAddress: String = BIND_WIFI_ONLY,
    val wifiOnlyMode: Boolean = true,

    val rootUri: String,
    val readOnly: Boolean = false,

    val authMode: ServerAuthMode,
    val username: String? = null,
    val passwordHashRef: String? = null,
    val authorizedKeysRef: String? = null,

    /** SFTP 専用: Ed25519 ホスト秘密鍵への参照。 */
    val hostKeyRef: String? = null,

    /** FTP 専用: Explicit FTPS の有効化。 */
    val ftpsEnabled: Boolean = false,

    /** FTP 専用: 自己署名 TLS 証明書 (PKCS12) への参照。 */
    val ftpsTlsCertRef: String? = null,

    val autoStartOnAppLaunch: Boolean = false,
    val autoStartOnBoot: Boolean = false,

    val createdAt: Long,
    val updatedAt: Long,
    val lastStartedAt: Long? = null,
) {
    companion object {
        const val BIND_WIFI_ONLY: String = "wifi_only"
        const val BIND_ALL_INTERFACES: String = "0.0.0.0"
    }
}
