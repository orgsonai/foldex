package com.zerotoship.foldex.server.ftp

import com.zerotoship.foldex.core.model.ServerConfig
import org.apache.ftpserver.usermanager.impl.BaseUser
import org.apache.ftpserver.usermanager.impl.ConcurrentLoginPermission
import org.apache.ftpserver.usermanager.impl.WritePermission

/**
 * 設定 [config] と要求された [username] から Apache FtpServer の [BaseUser] を組み立てる。
 *
 * - homeDirectory: rootUri (例 local:///storage/emulated/0) のパス部分を渡し、
 *   Apache FtpServer 内蔵の NativeFileSystemFactory がその配下のみへ path traversal を
 *   防ぎつつ操作する。
 * - readOnly = true の場合は [WritePermission] を Authority に含めず、
 *   Apache FtpServer 側で書き込みコマンドを拒否する。
 */
internal fun buildFtpUser(
    config: ServerConfig,
    username: String,
    rootPath: String,
): BaseUser {
    val authorities = mutableListOf<org.apache.ftpserver.ftplet.Authority>(
        ConcurrentLoginPermission(0, 0),
    )
    if (!config.readOnly) authorities.add(WritePermission())
    return BaseUser().apply {
        name = username
        homeDirectory = rootPath
        maxIdleTime = 0
        this.authorities = authorities
    }
}
