// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.storage.ftp

import org.apache.commons.net.ftp.FTPClient
import java.io.InputStream
import java.io.OutputStream

/**
 * Commons Net の `retrieveFileStream` / `storeFileStream` は、ストリーム close 後に
 * `completePendingCommand()` を呼ばないと次のコマンドが詰まる。close 連動でその後始末を行う。
 */
internal class FtpInputStream(
    private val client: FTPClient,
    private val delegate: InputStream,
) : InputStream() {
    override fun read(): Int = delegate.read()
    override fun read(b: ByteArray, off: Int, len: Int): Int = delegate.read(b, off, len)
    override fun available(): Int = delegate.available()
    override fun skip(n: Long): Long = delegate.skip(n)

    override fun close() {
        try {
            delegate.close()
        } finally {
            runCatching { client.completePendingCommand() }
        }
    }
}

internal class FtpOutputStream(
    private val client: FTPClient,
    private val delegate: OutputStream,
) : OutputStream() {
    override fun write(b: Int) = delegate.write(b)
    override fun write(b: ByteArray, off: Int, len: Int) = delegate.write(b, off, len)
    override fun flush() = delegate.flush()

    override fun close() {
        try {
            delegate.close()
        } finally {
            runCatching { client.completePendingCommand() }
        }
    }
}
