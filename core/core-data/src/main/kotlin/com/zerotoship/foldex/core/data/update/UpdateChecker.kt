// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.core.data.update

import com.zerotoship.foldex.core.common.AppVersion
import com.zerotoship.foldex.core.data.log.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/** 更新確認の結果。 */
sealed interface UpdateStatus {
    /** まだ確認していない。 */
    data object Idle : UpdateStatus

    /** 問い合わせ中。 */
    data object Checking : UpdateStatus

    /** 今のバージョンが最新だった。 */
    data class UpToDate(val current: String) : UpdateStatus

    /** 新しいバージョンが公開されている。 */
    data class Available(
        val latest: String,
        /** GitHub のリリースページ URL。 */
        val pageUrl: String,
        /** リリース本文 (変更履歴)。空のこともある。 */
        val notes: String,
    ) : UpdateStatus

    /** 確認できなかった (通信エラー・レート制限など)。 */
    data class Failed(val reason: String) : UpdateStatus
}

/**
 * GitHub Releases を見て、新しいバージョンが出ていないかを調べる。
 *
 * **ユーザーが設定画面で明示的に押したときだけ**通信する。自動での定期確認や
 * アプリ内でのダウンロード・インストールは行わない (F-Droid 等での配布を壊さないため、
 * 更新の入手経路はあくまでユーザーが選ぶ)。
 *
 * 依存を増やさないよう、HTTP は `HttpURLConnection`、JSON は Android 同梱の
 * `org.json` を使う。取りに行くのは 1 リクエストだけなので、これで足りる。
 */
@Singleton
class UpdateChecker @Inject constructor(
    private val logger: AppLogger,
) {
    suspend fun check(currentVersionName: String): UpdateStatus = withContext(Dispatchers.IO) {
        try {
            val json = fetchLatestRelease() ?: return@withContext UpdateStatus.Failed("応答を読み取れませんでした")

            // タグは release.yml が "v<versionName>" で打つ。name が空のこともあるので tag_name を優先。
            val tag = json.optString("tag_name").ifBlank { json.optString("name") }
            if (tag.isBlank()) return@withContext UpdateStatus.Failed("最新バージョンを判別できませんでした")

            val pageUrl = json.optString("html_url").ifBlank { RELEASES_PAGE }
            val notes = json.optString("body").trim()

            if (AppVersion.isNewerThan(tag, currentVersionName)) {
                UpdateStatus.Available(latest = tag.removePrefix("v"), pageUrl = pageUrl, notes = notes)
            } else {
                UpdateStatus.UpToDate(currentVersionName)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(TAG, "更新確認に失敗: ${e.javaClass.simpleName}", e)
            UpdateStatus.Failed(describe(e))
        }
    }

    private fun fetchLatestRelease(): JSONObject? {
        val conn = (URL(API_LATEST).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("Accept", "application/vnd.github+json")
            // User-Agent が無いと GitHub API は 403 を返す。
            setRequestProperty("User-Agent", "Foldex")
        }
        try {
            return when (val code = conn.responseCode) {
                HttpURLConnection.HTTP_OK -> JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                HttpURLConnection.HTTP_NOT_FOUND -> throw IllegalStateException("リリースが見つかりません")
                HttpURLConnection.HTTP_FORBIDDEN -> throw IllegalStateException("時間をおいて再試行してください (レート制限)")
                else -> throw IllegalStateException("サーバーが $code を返しました")
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun describe(e: Exception): String = when (e) {
        is java.net.UnknownHostException -> "ネットワークに接続できません"
        is java.net.SocketTimeoutException -> "接続がタイムアウトしました"
        else -> e.message ?: e.javaClass.simpleName
    }

    companion object {
        private const val TAG = "UpdateChecker"
        private const val TIMEOUT_MS = 10_000

        private const val API_LATEST = "https://api.github.com/repos/orgsonai/foldex/releases/latest"

        /** API が使えないときにブラウザで開く先。 */
        const val RELEASES_PAGE = "https://github.com/orgsonai/foldex/releases"
    }
}
