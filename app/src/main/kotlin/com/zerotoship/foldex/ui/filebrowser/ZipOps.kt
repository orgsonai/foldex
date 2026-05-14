package com.zerotoship.foldex.ui.filebrowser

import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.exception.ZipException
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.CompressionLevel
import net.lingala.zip4j.model.enums.EncryptionMethod
import java.io.File

/**
 * ZIP 圧縮 / 解凍 (zip4j)。
 *
 * - パスワードあり = AES-256 (PKCS#5 padding)。zip4j が自動的に AE-2 ヘッダで書く。
 * - パスワードなし = 通常の Deflate ZIP。
 * - 進捗は zip4j の `ProgressMonitor` で参照可。本ヘルパは同期 API のみで、
 *   呼び出し側 (ViewModel) は Dispatchers.IO + viewModelScope.launch でラップする想定。
 */
object ZipOps {

    /**
     * [files] を [destZip] に詰める (既に存在すると上書き)。
     * パスワードを与えると AES-256 で暗号化。
     */
    fun compress(
        files: List<File>,
        destZip: File,
        password: String? = null,
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
        for (f in files) {
            if (!f.exists()) continue
            if (f.isDirectory) zip.addFolder(f, params) else zip.addFile(f, params)
        }
        zip.close()
    }

    /**
     * [src] を [destDir] に展開する。
     * パスワード保護されていてもされていなくても呼び出せる (zip 側で要否を持つ)。
     * パスワード必須なのに password が null/空 のときは [WrongPassword] を投げる。
     */
    fun extract(
        src: File,
        destDir: File,
        password: String? = null,
    ) {
        if (!destDir.exists()) destDir.mkdirs()
        val zip = if (password.isNullOrEmpty()) ZipFile(src) else ZipFile(src, password.toCharArray())
        try {
            // zip4j 2.11: isEncrypted() で要否を判定できる。
            if (zip.isEncrypted && password.isNullOrEmpty()) throw WrongPassword("パスワード必須")
            zip.extractAll(destDir.absolutePath)
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
}
