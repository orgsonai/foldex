package com.zerotoship.foldex.streaming

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
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
import java.io.IOException

/**
 * リモート (SMB/SFTP/FTP/WebDAV) のファイルを `content://` URI として公開するための ContentProvider。
 *
 * 主な用途: ExoPlayer の MediaItem.fromUri に渡して **全ダウンロードを待たずに動画を再生** すること。
 * `ParcelFileDescriptor.createPipe()` で (read, write) のペアを作り、バックグラウンドで
 * [StorageProviderRouter.openInput] のストリームを write 側に流し込む。read 側を ExoPlayer に返す。
 *
 * URI 形式:
 * ```
 * content://com.zerotoship.foldex.streaming/stream
 *   ?proto=smb|sftp|ftp|webdav
 *   &conn=<connectionId>
 *   &path=<URLエンコードされた絶対パス>
 *   &name=<表示名: 拡張子から MIME を推定>
 *   &size=<bytes (任意・OpenableColumns.SIZE で返す)>
 * ```
 *
 * **制約**: pipe は seek 不可。MP4 の moov が末尾配置のファイルなどはこの実装では再生できない
 * (ExoPlayer が moov を探しに seek するため)。動画形式によっては別アプリへのフォールバックが要る。
 * Range 対応は今後 [StorageProvider] に openInput(offset, length) を追加して対応する想定。
 */
class RemoteStreamProvider : ContentProvider() {

    /** ContentProvider にも DI 注入が要るので EntryPoint 経由で Hilt graph から取り出す。 */
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface RouterEntryPoint {
        fun router(): StorageProviderRouter
    }

    /**
     * Singleton スコープで管理したいが、ContentProvider は process 起動時に Application より先に
     * onCreate されることがある。`router()` はアクセス時に Hilt graph 経由で取得することで、
     * 必要なタイミングで初期化されるようにする。
     */
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

        val ctx = requireNotNull(context) { "ContentProvider not attached" }
        val router = router(ctx)
        val fileUri = FileUri.Remote(protocol, connectionId, path)

        val pipe = ParcelFileDescriptor.createPipe()
        val readSide = pipe[0]
        val writeSide = pipe[1]

        // バックグラウンドで openInput → write 側へ転送。ExoPlayer は read 側を消費する。
        // SupervisorJob で各リクエストを独立。pipe 破断 (= 再生停止) は IOException として握り潰す。
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scope.launch {
            ParcelFileDescriptor.AutoCloseOutputStream(writeSide).use { out ->
                runCatching {
                    when (val r = router.openInput(fileUri)) {
                        is Result.Success -> {
                            r.value.use { input ->
                                val buf = ByteArray(BUFFER_SIZE)
                                while (true) {
                                    if (signal?.isCanceled == true) break
                                    val n = input.read(buf)
                                    if (n < 0) break
                                    out.write(buf, 0, n)
                                }
                            }
                        }
                        is Result.Failure -> {
                            // EOF を流して終了 (ExoPlayer 側は再生エラーに転がす)。
                        }
                    }
                }.onFailure { t ->
                    // pipe broken (ExoPlayer disconnected) や ネットワーク切断は無視。
                    if (t !is IOException) throw t
                }
            }
        }
        return readSide
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        // OpenableColumns 対応 (Intent ACTION_VIEW で外部アプリが size を尋ねるケース等)。
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

    // 書き込み / 行操作はサポートしない。
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        const val AUTHORITY = "com.zerotoship.foldex.streaming"
        const val PATH = "stream"
        const val PARAM_PROTO = "proto"
        const val PARAM_CONN = "conn"
        const val PARAM_PATH = "path"
        const val PARAM_NAME = "name"
        const val PARAM_SIZE = "size"

        private const val BUFFER_SIZE = 64 * 1024

        /** [FileUri.Remote] を content URI に変換 (省略可: name / size をクエリパラメータに埋める)。 */
        fun buildUri(remote: FileUri.Remote, displayName: String, size: Long? = null): Uri =
            Uri.Builder()
                .scheme("content")
                .authority(AUTHORITY)
                .appendPath(PATH)
                .appendQueryParameter(PARAM_PROTO, remote.protocol.name.lowercase())
                .appendQueryParameter(PARAM_CONN, remote.connectionId)
                .appendQueryParameter(PARAM_PATH, remote.path)
                .appendQueryParameter(PARAM_NAME, displayName)
                .apply { if (size != null && size >= 0) appendQueryParameter(PARAM_SIZE, size.toString()) }
                .build()
    }
}
