// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.storage.webdav

import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * GET レスポンスの ResponseBody.byteStream() に Response の close を連動させるラッパ。
 */
internal class WebDavInputStream(
    private val response: Response,
) : InputStream() {
    private val delegate: InputStream =
        response.body?.byteStream() ?: throw IOException("WebDAV GET returned no body")

    override fun read(): Int = delegate.read()
    override fun read(b: ByteArray, off: Int, len: Int): Int = delegate.read(b, off, len)
    override fun available(): Int = delegate.available()
    override fun skip(n: Long): Long = delegate.skip(n)

    override fun close() {
        try {
            delegate.close()
        } finally {
            runCatching { response.close() }
        }
    }
}

/**
 * PUT を「OutputStream 風 API」で扱うために、ローカルの一時ファイルにバッファリングし、
 * close() のタイミングで実 PUT を発行する。サイズ任意のアップロードに対応するために
 * Pipe ではなくファイル経由を採用している (二重書き込みのコストはあるが実装が単純)。
 */
internal class WebDavOutputStream(
    private val client: OkHttpClient,
    private val requestBuilder: Request.Builder,
    private val tempFile: File,
) : OutputStream() {
    private val delegate: OutputStream = FileOutputStream(tempFile)

    override fun write(b: Int) = delegate.write(b)
    override fun write(b: ByteArray, off: Int, len: Int) = delegate.write(b, off, len)
    override fun flush() = delegate.flush()

    override fun close() {
        var closeError: Throwable? = null
        try {
            delegate.close()
            val body = tempFile.asRequestBody(MEDIA_TYPE_OCTET_STREAM)
            val req = requestBuilder.put(body).build()
            client.newCall(req).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException(
                        "WebDAV PUT failed: HTTP ${response.code} ${response.message}",
                    )
                }
            }
        } catch (t: Throwable) {
            closeError = t
        } finally {
            runCatching { tempFile.delete() }
        }
        if (closeError != null) throw closeError
    }

    companion object {
        private val MEDIA_TYPE_OCTET_STREAM = "application/octet-stream".toMediaTypeOrNull()
    }
}
