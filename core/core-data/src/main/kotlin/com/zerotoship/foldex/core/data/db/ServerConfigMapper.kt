package com.zerotoship.foldex.core.data.db

import com.zerotoship.foldex.core.model.ServerAuthMode
import com.zerotoship.foldex.core.model.ServerConfig
import com.zerotoship.foldex.core.model.ServerType

internal fun ServerConfigEntity.toModel(): ServerConfig = ServerConfig(
    id = id,
    type = ServerType.fromWireName(type),
    name = name,
    enabled = enabled,
    port = port,
    bindAddress = bindAddress,
    wifiOnlyMode = wifiOnlyMode,
    rootUri = rootUri,
    readOnly = readOnly,
    authMode = ServerAuthMode.fromWireName(authMode),
    username = username,
    passwordHashRef = passwordHashRef,
    authorizedKeysRef = authorizedKeysRef,
    hostKeyRef = hostKeyRef,
    ftpsEnabled = ftpsEnabled,
    ftpsTlsCertRef = ftpsTlsCertRef,
    autoStartOnAppLaunch = autoStartOnAppLaunch,
    autoStartOnBoot = autoStartOnBoot,
    createdAt = createdAt,
    updatedAt = updatedAt,
    lastStartedAt = lastStartedAt,
)

internal fun ServerConfig.toEntity(): ServerConfigEntity = ServerConfigEntity(
    id = id,
    type = type.wireName,
    name = name,
    enabled = enabled,
    port = port,
    bindAddress = bindAddress,
    wifiOnlyMode = wifiOnlyMode,
    rootUri = rootUri,
    readOnly = readOnly,
    authMode = authMode.wireName,
    username = username,
    passwordHashRef = passwordHashRef,
    authorizedKeysRef = authorizedKeysRef,
    hostKeyRef = hostKeyRef,
    ftpsEnabled = ftpsEnabled,
    ftpsTlsCertRef = ftpsTlsCertRef,
    autoStartOnAppLaunch = autoStartOnAppLaunch,
    autoStartOnBoot = autoStartOnBoot,
    createdAt = createdAt,
    updatedAt = updatedAt,
    lastStartedAt = lastStartedAt,
)
