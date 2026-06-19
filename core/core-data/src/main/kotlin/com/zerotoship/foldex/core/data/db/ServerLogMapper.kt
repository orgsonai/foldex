// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.core.data.db

import com.zerotoship.foldex.core.model.ServerLog
import com.zerotoship.foldex.core.model.ServerLogEvent

internal fun ServerLogEntity.toModel(): ServerLog = ServerLog(
    id = id,
    configId = configId,
    event = ServerLogEvent.fromStorageKey(event) ?: ServerLogEvent.AUTH_FAILED,
    clientAddress = clientAddress,
    username = username,
    timestamp = timestamp,
    details = details,
)

internal fun ServerLog.toEntity(): ServerLogEntity = ServerLogEntity(
    id = id,
    configId = configId,
    event = event.storageKey,
    clientAddress = clientAddress,
    username = username,
    timestamp = timestamp,
    details = details,
)
