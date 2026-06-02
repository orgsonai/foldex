package com.zerotoship.foldex.core.data.log

import android.content.Context
import android.net.Uri
import com.zerotoship.foldex.core.data.repo.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * アプリ全体の実行ログ / エラーログを 1 つのテキストファイルに追記する Singleton。
 *
 * - 保存先: `externalFilesDir/logs/app.log` + ローテで `app.log.1` まで。
 * - 1 ファイル 256KB を超えたら `app.log` → `app.log.1` にリネームし、新規作成。
 * - 行フォーマット: `2026-05-16 12:34:56.789  [LEVEL]  [TAG]  message`。
 *
 * 同期 / サーバ / クラッシュ / 重要な失敗をここに集約し、設定画面からファイルを確認・共有できる。
 */
@Singleton
class AppLogger @Inject constructor(
    @ApplicationContext private val context: Context,
    settingsRepository: SettingsRepository,
) {
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    /** 直近の書き込み時刻 (ms)。Compose 側はこれを観測して画面を再読み込みする。 */
    private val _lastWriteAt = MutableStateFlow(0L)
    val lastWriteAt: StateFlow<Long> = _lastWriteAt.asStateFlow()

    /**
     * 永久ログの追記先 (任意)。設定の URI を購読してキャッシュし、書き込みのたびに
     * このファイルへも追記する。ホットパスで DataStore を読まないよう @Volatile で保持。
     */
    @Volatile
    private var permanentUri: Uri? = null

    init {
        ioScope.launch {
            settingsRepository.settings
                .map { it.permanentLogUri }
                .collect { raw ->
                    permanentUri = raw?.let { runCatching { Uri.parse(it) }.getOrNull() }
                }
        }
    }

    enum class Level { INFO, WARN, ERROR }

    fun info(tag: String, message: String) = write(Level.INFO, tag, message, null)
    fun warn(tag: String, message: String, throwable: Throwable? = null) = write(Level.WARN, tag, message, throwable)
    fun error(tag: String, message: String, throwable: Throwable? = null) = write(Level.ERROR, tag, message, throwable)

    fun logFile(): File = File(logsDir(), "app.log")
    fun rolledFile(): File = File(logsDir(), "app.log.1")

    /** 設定画面のクリアボタン用。両ファイルを空に。 */
    fun clear() {
        ioScope.launch {
            runCatching { logFile().writeText("") }
            runCatching { rolledFile().delete() }
            _lastWriteAt.value = System.currentTimeMillis()
        }
    }

    /** 末尾 [maxLines] 行を新しい順で返す (画面表示用)。 */
    suspend fun tail(maxLines: Int = 1000): List<String> = kotlinx.coroutines.withContext(Dispatchers.IO) {
        val lines = mutableListOf<String>()
        listOfNotNull(rolledFile().takeIf { it.exists() }, logFile().takeIf { it.exists() }).forEach { f ->
            runCatching { f.readLines() }.getOrNull()?.let { lines.addAll(it) }
        }
        if (lines.size > maxLines) lines.subList(lines.size - maxLines, lines.size) else lines
    }

    private fun write(level: Level, tag: String, message: String, throwable: Throwable?) {
        ioScope.launch {
            runCatching {
                val log = logFile()
                if (log.length() > MAX_BYTES) {
                    runCatching { rolledFile().delete() }
                    log.renameTo(rolledFile())
                }
                val stamp = formatter.format(Date())
                val sb = StringBuilder()
                sb.append(stamp).append("  [").append(level.name).append("]  [").append(tag).append("]  ")
                sb.append(message)
                if (throwable != null) {
                    val sw = StringWriter()
                    throwable.printStackTrace(PrintWriter(sw))
                    sb.append('\n').append(sw.toString().trimEnd())
                }
                sb.append('\n')
                val text = sb.toString()
                log.appendText(text)
                // 永久保存先が設定されていれば、同じ 1 行をユーザー指定の .log へも追記。
                // "wa" = 追記モード。ファイルが消された / 権限が切れた場合は黙って諦める。
                permanentUri?.let { uri ->
                    runCatching {
                        context.contentResolver.openOutputStream(uri, "wa")?.use { os ->
                            os.write(text.toByteArray())
                        }
                    }
                }
                _lastWriteAt.value = System.currentTimeMillis()
            }
        }
    }

    private fun logsDir(): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(base, "logs").apply { mkdirs() }
    }

    private companion object {
        const val MAX_BYTES: Long = 256L * 1024
    }
}
