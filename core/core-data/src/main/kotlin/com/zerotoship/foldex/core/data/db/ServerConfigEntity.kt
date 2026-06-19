// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.core.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 自機サーバー (SFTP / FTP) 設定の永続化レコード。
 * 公開情報のみ保持し、機密 (パスワードハッシュ・公開鍵リスト・ホスト鍵・FTPS 証明書)
 * は [EncryptedCredentialEntity] を ID 参照で結ぶ。
 */
@Entity(tableName = "server_configs")
data class ServerConfigEntity(
    @PrimaryKey val id: String,
    val type: String,
    val name: String,
    val enabled: Boolean,

    val port: Int,
    val bindAddress: String,
    val wifiOnlyMode: Boolean,

    val rootUri: String,
    val readOnly: Boolean,

    val authMode: String,
    val username: String?,
    val passwordHashRef: String?,
    val authorizedKeysRef: String?,

    val hostKeyRef: String?,
    val ftpsEnabled: Boolean,
    val ftpsTlsCertRef: String?,

    val autoStartOnAppLaunch: Boolean,
    val autoStartOnBoot: Boolean,

    val createdAt: Long,
    val updatedAt: Long,
    val lastStartedAt: Long?,
)
