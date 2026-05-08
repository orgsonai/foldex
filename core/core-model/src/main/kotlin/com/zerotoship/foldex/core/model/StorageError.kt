package com.zerotoship.foldex.core.model

sealed class StorageError(open val message: String, open val cause: Throwable? = null) {
    data class NotConnected(override val message: String = "Not connected") : StorageError(message)
    data class AuthenticationFailed(override val message: String, override val cause: Throwable? = null) : StorageError(message, cause)
    data class HostUnreachable(val host: String, override val cause: Throwable? = null) : StorageError("Host unreachable: $host", cause)
    data class NotFound(val uri: FileUri) : StorageError("Not found: ${uri.toStorageString()}")
    data class AlreadyExists(val uri: FileUri) : StorageError("Already exists: ${uri.toStorageString()}")
    data class PermissionDenied(val uri: FileUri) : StorageError("Permission denied: ${uri.toStorageString()}")
    data class IoError(override val message: String, override val cause: Throwable? = null) : StorageError(message, cause)
    data class Cancelled(override val message: String = "Cancelled") : StorageError(message)
    data class ProtocolError(val protocol: Protocol, override val message: String, override val cause: Throwable? = null) : StorageError(message, cause)
    data class Unknown(override val message: String, override val cause: Throwable? = null) : StorageError(message, cause)
}
