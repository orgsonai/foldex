package com.zerotoship.foldex.storage.smb

import com.hierynomus.smbj.share.File as SmbjFile
import java.io.InputStream
import java.io.OutputStream

/**
 * smbj の [SmbjFile] を裏に持つ [InputStream]。close 時に File ハンドルも閉じる。
 */
internal class SmbInputStream(private val file: SmbjFile) : InputStream() {
    private val delegate: InputStream = file.inputStream

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

/**
 * smbj の [SmbjFile] を裏に持つ [OutputStream]。close 時に File ハンドルも閉じる。
 */
internal class SmbOutputStream(private val file: SmbjFile) : OutputStream() {
    private val delegate: OutputStream = file.outputStream

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
