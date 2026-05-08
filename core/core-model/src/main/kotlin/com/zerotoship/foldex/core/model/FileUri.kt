package com.zerotoship.foldex.core.model

sealed class FileUri {
    data class Local(val absolutePath: String) : FileUri()
    data class Saf(val documentUri: String) : FileUri()
    data class Remote(val protocol: Protocol, val connectionId: String, val path: String) : FileUri()

    fun toStorageString(): String = when (this) {
        is Local -> "local://$absolutePath"
        is Saf -> "saf://$documentUri"
        is Remote -> "${protocol.scheme}://$connectionId/$path"
    }
}
