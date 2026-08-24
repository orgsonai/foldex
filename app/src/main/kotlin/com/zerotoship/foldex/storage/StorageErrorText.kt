// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.storage

import android.content.Context
import com.zerotoship.foldex.R
import com.zerotoship.foldex.core.model.StorageError

/** Converts diagnostic storage errors into stable, locale-aware user text. */
fun StorageError.toUserMessage(context: Context): String = when (this) {
    is StorageError.NotConnected -> context.getString(R.string.storage_error_not_connected)
    is StorageError.AuthenticationFailed -> context.getString(R.string.storage_error_authentication)
    is StorageError.HostUnreachable -> context.getString(R.string.storage_error_host_unreachable, host)
    is StorageError.NotFound -> context.getString(R.string.storage_error_not_found, uri.displayName())
    is StorageError.AlreadyExists -> context.getString(R.string.storage_error_already_exists, uri.displayName())
    is StorageError.PermissionDenied -> context.getString(R.string.storage_error_permission_denied, uri.displayName())
    is StorageError.Cancelled -> context.getString(R.string.storage_error_cancelled)
    is StorageError.IoError -> context.getString(R.string.storage_error_io)
    is StorageError.ProtocolError ->
        context.getString(R.string.storage_error_protocol, protocol.scheme.uppercase())
    is StorageError.Unknown -> context.getString(R.string.storage_error_unknown)
}
