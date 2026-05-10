package com.zerotoship.foldex.storage.sftp

import net.schmizz.sshj.sftp.RemoteFile
import java.io.InputStream
import java.io.OutputStream

/**
 * [RemoteFile] に紐づく InputStream を返しつつ、close 時に元ファイルも閉じるラッパー。
 */
internal class SftpInputStream(
    private val file: RemoteFile,
) : InputStream() {
    private val delegate: InputStream = file.RemoteFileInputStream()

    override fun read(): Int = delegate.read()
    override fun read(b: ByteArray, off: Int, len: Int): Int = delegate.read(b, off, len)
    override fun available(): Int = delegate.available()
    override fun skip(n: Long): Long = delegate.skip(n)

    override fun close() {
        try {
            delegate.close()
        } finally {
            runCatching { file.close() }
        }
    }
}

internal class SftpOutputStream(
    private val file: RemoteFile,
) : OutputStream() {
    private val delegate: OutputStream = file.RemoteFileOutputStream()

    override fun write(b: Int) = delegate.write(b)
    override fun write(b: ByteArray, off: Int, len: Int) = delegate.write(b, off, len)
    override fun flush() = delegate.flush()

    override fun close() {
        try {
            delegate.close()
        } finally {
            runCatching { file.close() }
        }
    }
}
