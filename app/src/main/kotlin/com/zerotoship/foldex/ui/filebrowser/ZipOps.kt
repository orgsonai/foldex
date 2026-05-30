package com.zerotoship.foldex.ui.filebrowser

import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.exception.ZipException
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.CompressionLevel
import net.lingala.zip4j.model.enums.EncryptionMethod
import net.lingala.zip4j.progress.ProgressMonitor
import java.io.File

/**
 * ZIP 圧縮 / 解凍 (zip4j)。
 *
 * - パスワードあり = AES-256 (PKCS#5 padding)。zip4j が自動的に AE-2 ヘッダで書く。
 * - パスワードなし = 通常の Deflate ZIP。
 * - 進捗は zip4j の `ProgressMonitor` で参照可。[ZipProgress] を渡すと runInThread + ポーリングで
 *   逐次コールバックする。本ヘルパは同期的にブロックして完了まで待つので、
 *   呼び出し側 (ViewModel) は Dispatchers.IO + viewModelScope.launch でラップする想定。
 */
object ZipOps {

    /** zip4j の進捗を呼び出し側へ渡すコールバック。バイト進捗は [bytesDone]/[bytesTotal] (<=0 は不確定)。 */
    fun interface ZipProgress {
        fun onProgress(index: Int, total: Int, name: String?, bytesDone: Long, bytesTotal: Long)
    }

    /** ProgressMonitor のポーリング間隔 (ミリ秒)。短すぎても UI 更新が間引かれるだけなので緩め。 */
    private const val POLL_MS = 60L

    /**
     * [files] を [destZip] に詰める (既に存在すると上書き)。
     * パスワードを与えると AES-256 で暗号化。
     * [progress] を渡すと 1 件ごとに進捗を通知する (件数 + そのファイルのバイト進捗)。
     */
    fun compress(
        files: List<File>,
        destZip: File,
        password: String? = null,
        progress: ZipProgress? = null,
    ) {
        if (destZip.exists()) destZip.delete()
        val zip = if (password.isNullOrEmpty()) ZipFile(destZip) else ZipFile(destZip, password.toCharArray())
        val params = ZipParameters().apply {
            compressionLevel = CompressionLevel.NORMAL
            if (!password.isNullOrEmpty()) {
                isEncryptFiles = true
                encryptionMethod = EncryptionMethod.AES
                aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
            }
        }
        val targets = files.filter { it.exists() }
        if (progress == null) {
            for (f in targets) {
                if (f.isDirectory) zip.addFolder(f, params) else zip.addFile(f, params)
            }
            zip.close()
            return
        }
        // runInThread = true で addFolder/addFile は即座に戻り、別スレッドで実行される。
        // ProgressMonitor をポーリングして進捗を吸い出す。例外はモニタに溜まるので毎回拾い直す。
        zip.isRunInThread = true
        val pm = zip.progressMonitor
        val total = targets.size
        targets.forEachIndexed { index, f ->
            if (f.isDirectory) zip.addFolder(f, params) else zip.addFile(f, params)
            pollUntilDone(pm) { name, done, totalWork ->
                progress.onProgress(index + 1, total, name ?: f.name, done, totalWork)
            }
            pm.exception?.let { throw it }
        }
        zip.close()
    }

    /**
     * [src] を [destDir] に展開する。
     * パスワード保護されていてもされていなくても呼び出せる (zip 側で要否を持つ)。
     * パスワード必須なのに password が null/空 のときは [WrongPassword] を投げる。
     * [progress] を渡すとアーカイブ全体のバイト進捗を通知する。
     */
    fun extract(
        src: File,
        destDir: File,
        password: String? = null,
        progress: ZipProgress? = null,
    ) {
        if (!destDir.exists()) destDir.mkdirs()
        val zip = if (password.isNullOrEmpty()) ZipFile(src) else ZipFile(src, password.toCharArray())
        try {
            // zip4j 2.11: isEncrypted() で要否を判定できる。
            if (zip.isEncrypted && password.isNullOrEmpty()) throw WrongPassword("パスワード必須")
            if (progress == null) {
                zip.extractAll(destDir.absolutePath)
            } else {
                // runInThread 中の例外は throw されずモニタに溜まるので、完了後に拾って再 throw する。
                zip.isRunInThread = true
                val pm = zip.progressMonitor
                zip.extractAll(destDir.absolutePath)
                pollUntilDone(pm) { name, done, totalWork ->
                    progress.onProgress(1, 1, name, done, totalWork)
                }
                pm.exception?.let { throw it }
            }
        } catch (e: ZipException) {
            // 「Wrong password」系は zip4j が ZipException で投げる (errorCode で識別)。
            val msg = e.message ?: ""
            if (msg.contains("password", ignoreCase = true) || msg.contains("Wrong password", ignoreCase = true)) {
                throw WrongPassword(msg)
            }
            throw e
        } finally {
            runCatching { zip.close() }
        }
    }

    fun isLikelyZip(name: String): Boolean = name.endsWith(".zip", ignoreCase = true)

    /** zip4j の throws を見やすくしたラッパ。UI 側でこの型をキャッチしてパスワード再入力を促す。 */
    class WrongPassword(message: String) : Exception(message)

    /** [pm] が BUSY の間ポーリングして [report] を呼び、完了時に最終値を 1 回通知する。 */
    private inline fun pollUntilDone(
        pm: ProgressMonitor,
        report: (name: String?, done: Long, total: Long) -> Unit,
    ) {
        while (pm.state == ProgressMonitor.State.BUSY) {
            report(pm.fileName, pm.workCompleted, pm.totalWork)
            Thread.sleep(POLL_MS)
        }
        report(pm.fileName, pm.totalWork, pm.totalWork)
    }
}
