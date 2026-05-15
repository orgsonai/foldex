package com.zerotoship.foldex.streaming

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.provider.OpenableColumns
import android.system.ErrnoException
import android.system.OsConstants
import com.zerotoship.foldex.core.common.Result
import com.zerotoship.foldex.core.model.FileUri
import com.zerotoship.foldex.core.model.Protocol
import com.zerotoship.foldex.core.model.filetype.FileTypeRegistry
import com.zerotoship.foldex.storage.StorageProviderRouter
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.InputStream

/**
 * リモート (SMB/SFTP/FTP/WebDAV) のファイルを `content://` URI として公開する ContentProvider。
 *
 * ExoPlayer に渡すと **全ダウンロードを待たずに動画再生** + **seek 可** で再生できる。
 *
 * 実装方針:
 * - Android 8.0+ の [StorageManager.openProxyFileDescriptor] を使い、
 *   [ProxyFileDescriptorCallback] で onRead(offset, size, data) を実装する。
 * - 各 onRead で `StorageProviderRouter.openInputRange(uri, offset)` を呼び出して
 *   その位置からの InputStream を開く。SMB/SFTP/FTP/WebDAV ともネイティブな範囲読み取り
 *   (SMB2 READ at offset / SFTP RemoteFile(offset) / FTP REST / WebDAV Range) に対応している。
 * - 連続読み取り (sequential read) を高速化するため、前回 onRead 終了位置のストリームを
 *   保持しておき、次回 onRead が連続位置なら同じストリームを使い回す (seek が起きたら閉じて再オープン)。
 * - Android 8.0 未満 (minSdk 26 想定なので発生しないが念のため) は createPipe にフォールバック。
 *
 * URI 形式:
 * ```
 * content://<applicationId>.streaming/stream
 *   ?proto=smb|sftp|ftp|webdav
 *   &conn=<connectionId>
 *   &path=<URLエンコードされた絶対パス>
 *   &name=<表示名>
 *   &size=<bytes>  (省略可・PFD のサイズに使う)
 * ```
 */
class RemoteStreamProvider : ContentProvider() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface RouterEntryPoint {
        fun router(): StorageProviderRouter
    }

    private fun router(context: Context): StorageProviderRouter {
        val entry = EntryPointAccessors.fromApplication(
            context.applicationContext,
            RouterEntryPoint::class.java,
        )
        return entry.router()
    }

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String? {
        val name = uri.getQueryParameter(PARAM_NAME) ?: return null
        return FileTypeRegistry.mimeTypeFor(name) ?: "application/octet-stream"
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? = openFile(uri, mode, null)

    override fun openFile(uri: Uri, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        require(mode == "r") { "RemoteStreamProvider supports read-only access (got mode=$mode)" }
        val protocol = uri.getQueryParameter(PARAM_PROTO)?.let { runCatching { Protocol.valueOf(it.uppercase()) }.getOrNull() }
            ?: error("Missing/invalid '$PARAM_PROTO' query parameter on $uri")
        val connectionId = uri.getQueryParameter(PARAM_CONN)
            ?: error("Missing '$PARAM_CONN' query parameter on $uri")
        val path = uri.getQueryParameter(PARAM_PATH)
            ?: error("Missing '$PARAM_PATH' query parameter on $uri")
        val totalSize = uri.getQueryParameter(PARAM_SIZE)?.toLongOrNull() ?: -1L

        val ctx = requireNotNull(context) { "ContentProvider not attached" }
        val router = router(ctx)
        val fileUri = FileUri.Remote(protocol, connectionId, path)

        // Android O+ では ProxyFileDescriptor で seek 対応 PFD を返す。
        // 26 未満は到達しない (minSdk 26)。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val sm = ctx.getSystemService(Context.STORAGE_SERVICE) as StorageManager
            val handler = obtainHandler()
            val callback = RemoteProxyCallback(router, fileUri, totalSize)
            return sm.openProxyFileDescriptor(ParcelFileDescriptor.MODE_READ_ONLY, callback, handler)
        }
        // 互換フォールバック (createPipe; seek 非対応)。
        return openPipeFallback(router, fileUri)
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val cursor = MatrixCursor(cols)
        val name = uri.getQueryParameter(PARAM_NAME) ?: "stream"
        val size = uri.getQueryParameter(PARAM_SIZE)?.toLongOrNull() ?: -1L
        val row: List<Any?> = cols.map { col ->
            when (col) {
                OpenableColumns.DISPLAY_NAME -> name
                OpenableColumns.SIZE -> size
                else -> null
            }
        }
        cursor.addRow(row)
        return cursor
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    /** ProxyFileDescriptorCallback を動かす Handler スレッド。プロセスで 1 本共有する。 */
    private fun obtainHandler(): Handler {
        if (callbackHandler == null) {
            synchronized(this) {
                if (callbackHandler == null) {
                    val ht = HandlerThread("RemoteStreamProvider-Callback").apply { start() }
                    callbackThread = ht
                    callbackHandler = Handler(ht.looper)
                }
            }
        }
        return callbackHandler!!
    }

    /** Pipe 経路 (createPipe + バックグラウンドコピー)。seek 非対応の互換フォールバック。 */
    private fun openPipeFallback(router: StorageProviderRouter, fileUri: FileUri): ParcelFileDescriptor {
        val pipe = ParcelFileDescriptor.createPipe()
        val readSide = pipe[0]
        val writeSide = pipe[1]
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scope.launch {
            ParcelFileDescriptor.AutoCloseOutputStream(writeSide).use { out ->
                runCatching {
                    when (val r = router.openInput(fileUri)) {
                        is Result.Success -> r.value.use { it.copyTo(out, bufferSize = 64 * 1024) }
                        is Result.Failure -> Unit
                    }
                }
            }
        }
        return readSide
    }

    /**
     * onRead(offset, size, data) ごとにストリームから読み取って data に詰める Callback。
     * 連続位置のときは前回のストリームを使い回し、seek (位置不一致) のときだけ開き直す。
     */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.O)
    private class RemoteProxyCallback(
        private val router: StorageProviderRouter,
        private val fileUri: FileUri,
        private val totalSize: Long,
    ) : ProxyFileDescriptorCallback() {

        /** 現在開いているストリーム (連続 read を共有するため)。null は未オープン。 */
        private var stream: InputStream? = null
        /** 次に [stream] から読まれるバイトのファイル先頭からのオフセット。 */
        private var streamPosition: Long = 0L

        override fun onGetSize(): Long {
            if (totalSize >= 0L) return totalSize
            // size 不明: stat で取得を試みる。失敗時は -1 を返すと PFD は size 不明扱い。
            return runCatching {
                runBlocking {
                    val r = router.stat(fileUri)
                    if (r is Result.Success) r.value.size else -1L
                }
            }.getOrDefault(-1L)
        }

        override fun onRead(offset: Long, size: Int, data: ByteArray): Int {
            if (size <= 0) return 0
            try {
                // 必要なら既存ストリームを閉じて新しい位置から開く。
                if (stream == null || offset != streamPosition) {
                    closeStreamQuietly()
                    val opened = runBlocking { router.openInputRange(fileUri, offset) }
                    if (opened !is Result.Success) {
                        throw ErrnoException("openInputRange failed", OsConstants.EIO)
                    }
                    stream = opened.value
                    streamPosition = offset
                }
                // size バイト埋めるよう繰り返し読み (InputStream.read は短く返ることがある)。
                var totalRead = 0
                while (totalRead < size) {
                    val n = stream!!.read(data, totalRead, size - totalRead)
                    if (n < 0) break // EOF
                    totalRead += n
                }
                streamPosition += totalRead
                return totalRead
            } catch (e: ErrnoException) {
                closeStreamQuietly()
                throw e
            } catch (t: Throwable) {
                closeStreamQuietly()
                throw ErrnoException("read failed: ${t.message}", OsConstants.EIO)
            }
        }

        override fun onRelease() {
            closeStreamQuietly()
        }

        private fun closeStreamQuietly() {
            runCatching { stream?.close() }
            stream = null
            streamPosition = 0L
        }
    }

    companion object {
        /** AndroidManifest の `<provider android:authorities="${applicationId}.streaming">` と一致させる。 */
        private const val AUTHORITY_SUFFIX = ".streaming"
        const val PATH = "stream"
        const val PARAM_PROTO = "proto"
        const val PARAM_CONN = "conn"
        const val PARAM_PATH = "path"
        const val PARAM_NAME = "name"
        const val PARAM_SIZE = "size"

        @Volatile private var callbackThread: HandlerThread? = null
        @Volatile private var callbackHandler: Handler? = null

        /** 実行時の applicationId (debug 版は `.debug` 付き) から authority を組み立てる。 */
        fun authority(context: Context): String = context.packageName + AUTHORITY_SUFFIX

        /** [FileUri.Remote] を content URI に変換 (省略可: name / size をクエリパラメータに埋める)。 */
        fun buildUri(context: Context, remote: FileUri.Remote, displayName: String, size: Long? = null): Uri =
            Uri.Builder()
                .scheme("content")
                .authority(authority(context))
                .appendPath(PATH)
                .appendQueryParameter(PARAM_PROTO, remote.protocol.name.lowercase())
                .appendQueryParameter(PARAM_CONN, remote.connectionId)
                .appendQueryParameter(PARAM_PATH, remote.path)
                .appendQueryParameter(PARAM_NAME, displayName)
                .apply { if (size != null && size >= 0) appendQueryParameter(PARAM_SIZE, size.toString()) }
                .build()
    }
}
