package com.zerotoship.foldex.core.model

enum class Protocol(val scheme: String, val defaultPort: Int) {
    SMB("smb", 445),
    SFTP("sftp", 22),
    FTP("ftp", 21),
    WEBDAV("webdav", 443),
}
