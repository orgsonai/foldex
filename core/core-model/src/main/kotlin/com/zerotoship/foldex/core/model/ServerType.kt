package com.zerotoship.foldex.core.model

enum class ServerType(val wireName: String, val defaultPort: Int) {
    SFTP("sftp", 2022),
    FTP("ftp", 2121),
    ;

    companion object {
        fun fromWireName(value: String): ServerType =
            entries.firstOrNull { it.wireName == value }
                ?: error("Unknown ServerType wire name: $value")
    }
}
