package com.zerotoship.foldex.core.model

data class Permissions(
    val readable: Boolean = true,
    val writable: Boolean = false,
    val executable: Boolean = false,
) {
    companion object {
        val READ_ONLY = Permissions(readable = true, writable = false, executable = false)
        val READ_WRITE = Permissions(readable = true, writable = true, executable = false)
        val UNKNOWN = Permissions(readable = false, writable = false, executable = false)
    }
}
