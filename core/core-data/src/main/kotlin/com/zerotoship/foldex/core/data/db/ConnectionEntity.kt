package com.zerotoship.foldex.core.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "connections")
data class ConnectionEntity(
    @PrimaryKey val id: String,
    val name: String,
    val protocol: String,
    val host: String,
    val port: Int,
    val username: String?,
    val authMethod: String,

    val smbShare: String? = null,
    val smbDomain: String? = null,
    val sftpHostKeyFingerprint: String? = null,
    val webdavBasePath: String? = null,
    val webdavUseHttps: Boolean = true,
    val ftpUseTls: Boolean = false,
    val ftpPassiveMode: Boolean = true,

    val credentialRef: String? = null,

    val charset: String = "UTF-8",
    val createdAt: Long,
    val updatedAt: Long,
    val lastConnectedAt: Long? = null,
    val sortOrder: Int = 0,
)
