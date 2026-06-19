// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.server.ftp

import com.zerotoship.foldex.core.model.ServerLogEvent
import com.zerotoship.foldex.server.log.ServerLogger
import kotlinx.coroutines.runBlocking
import org.apache.ftpserver.ftplet.DefaultFtplet
import org.apache.ftpserver.ftplet.FtpReply
import org.apache.ftpserver.ftplet.FtpRequest
import org.apache.ftpserver.ftplet.FtpSession
import org.apache.ftpserver.ftplet.FtpletResult

/**
 * 書き込み系コマンド (STOR/APPE/STOU/MKD/RMD/DELE/RNFR/RNTO) の成否を
 * Foldex のサーバーログ＋ android.util.Log に流して原因切り分けする Ftplet。
 *
 * 実機で「FTP でアップロードできない」と報告されたが Apache FtpServer 自体は
 * デバッグログを slf4j に投げるだけで Foldex の UI からは何も見えない。
 * ここで反応コード (5xx) を可視化して、PASV 失敗 / 権限不足 / パス解決失敗
 * のどれかを判別する。
 */
internal class FoldexFtpDiagnosticFtplet(
    private val configId: String,
    private val logger: ServerLogger,
) : DefaultFtplet() {

    override fun afterCommand(
        session: FtpSession,
        request: FtpRequest,
        reply: FtpReply?,
    ): FtpletResult {
        val cmd = request.command?.uppercase() ?: return FtpletResult.DEFAULT
        if (cmd !in WRITE_COMMANDS) return FtpletResult.DEFAULT
        val code = reply?.code ?: -1
        val arg = request.argument
        val client = session.clientAddress?.toString() ?: "?"
        val ok = reply?.isPositive ?: false
        val tag = if (ok) "ok" else "ng"
        android.util.Log.w(
            TAG,
            "FTP[$configId] $cmd $arg -> $code ($tag) from $client",
        )
        if (!ok) {
            runCatching {
                runBlocking {
                    logger.record(
                        configId = configId,
                        event = ServerLogEvent.FILE_OP_FAILED,
                        clientAddress = client,
                        username = session.user?.name,
                        details = "cmd=$cmd,arg=${arg ?: ""},reply=$code,msg=${reply?.message?.take(120)}",
                    )
                }
            }
        }
        return FtpletResult.DEFAULT
    }

    private companion object {
        const val TAG = "FoldexFtpDiag"
        val WRITE_COMMANDS = setOf(
            "STOR", "APPE", "STOU", "MKD", "XMKD",
            "RMD", "XRMD", "DELE", "RNFR", "RNTO",
        )
    }
}
