package com.zerotoship.foldex.ui.viewer

import android.content.Context
import java.io.File
import java.security.MessageDigest

/**
 * 内蔵エディタの「未保存の編集 (下書き)」をディスクに退避するストア。
 *
 * 保存ボタンを押す前にアプリがタスクキル / バックグラウンド回収されても編集内容を失わないよう、
 * 入力が落ち着くたび・画面が止まる (ON_STOP) たびに編集中テキストをここへ書き出す。
 * 編集対象ファイルの絶対パスを SHA-256 でキー化し、`cacheDir/editor-drafts/<key>.draft` に
 * UTF-8 で 1 ファイル退避する (本文の文字コードは保存時に元ファイルのものへ戻す)。
 *
 * 下書きは「保存成功」または「破棄」で消す。次回同じファイルを開いたとき、下書きが残っていて
 * かつ現在のファイル内容と異なれば「復元しますか?」を尋ねる入口になる。
 */
internal object EditorDraftStore {

    private fun dir(context: Context): File =
        File(context.cacheDir, "editor-drafts").apply { mkdirs() }

    private fun keyFor(target: File): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(target.absolutePath.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun draftFile(context: Context, target: File): File =
        File(dir(context), "${keyFor(target)}.draft")

    /** 編集中テキストを下書きとして退避する。失敗しても編集体験を壊さないよう握りつぶす。 */
    fun save(context: Context, target: File, content: String) {
        runCatching { draftFile(context, target).writeText(content, Charsets.UTF_8) }
    }

    /** 退避済み下書きを返す。無ければ null。 */
    fun read(context: Context, target: File): String? =
        draftFile(context, target).takeIf { it.exists() }
            ?.let { runCatching { it.readText(Charsets.UTF_8) }.getOrNull() }

    /** 下書きを削除する (保存成功 / 破棄時)。 */
    fun clear(context: Context, target: File) {
        runCatching { draftFile(context, target).delete() }
    }
}
