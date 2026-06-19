// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.core.data.db

import com.zerotoship.foldex.core.model.AuthMethod
import com.zerotoship.foldex.core.model.Connection
import com.zerotoship.foldex.core.model.Protocol

internal fun ConnectionEntity.toModel(): Connection {
    val auth = AuthMethod.fromWireName(authMethod)
    val proto = Protocol.entries.firstOrNull { it.scheme == protocol }
        ?: error("Unknown protocol: $protocol")
    return when (proto) {
        Protocol.SMB -> Connection.Smb(
            id = id,
            name = name,
            host = host,
            port = port,
            username = username,
            authMethod = auth,
            share = smbShare ?: error("smbShare missing for SMB connection $id"),
            domain = smbDomain,
            charset = charset,
            initialPath = initialPath.orEmpty(),
        )
        Protocol.SFTP -> Connection.Sftp(
            id = id,
            name = name,
            host = host,
            port = port,
            username = username,
            authMethod = auth,
            hostKeyFingerprint = sftpHostKeyFingerprint,
            charset = charset,
            initialPath = initialPath.orEmpty(),
        )
        Protocol.FTP -> Connection.Ftp(
            id = id,
            name = name,
            host = host,
            port = port,
            username = username,
            authMethod = auth,
            useTls = ftpUseTls,
            passiveMode = ftpPassiveMode,
            charset = charset,
            initialPath = initialPath.orEmpty(),
        )
        Protocol.WEBDAV -> Connection.WebDav(
            id = id,
            name = name,
            host = host,
            port = port,
            username = username,
            authMethod = auth,
            basePath = webdavBasePath ?: "/",
            useHttps = webdavUseHttps,
            charset = charset,
        )
    }
}

internal fun Connection.toEntity(
    credentialRef: String?,
    createdAt: Long,
    updatedAt: Long,
    lastConnectedAt: Long? = null,
    sortOrder: Int = 0,
): ConnectionEntity = ConnectionEntity(
    id = id,
    name = name,
    protocol = protocol.scheme,
    host = host,
    port = port,
    username = username,
    authMethod = authMethod.wireName,
    smbShare = (this as? Connection.Smb)?.share,
    smbDomain = (this as? Connection.Smb)?.domain,
    sftpHostKeyFingerprint = (this as? Connection.Sftp)?.hostKeyFingerprint,
    webdavBasePath = (this as? Connection.WebDav)?.basePath,
    webdavUseHttps = (this as? Connection.WebDav)?.useHttps ?: true,
    ftpUseTls = (this as? Connection.Ftp)?.useTls ?: false,
    ftpPassiveMode = (this as? Connection.Ftp)?.passiveMode ?: true,
    initialPath = initialPath.takeIf { it.isNotBlank() },
    credentialRef = credentialRef,
    charset = charset,
    createdAt = createdAt,
    updatedAt = updatedAt,
    lastConnectedAt = lastConnectedAt,
    sortOrder = sortOrder,
)
