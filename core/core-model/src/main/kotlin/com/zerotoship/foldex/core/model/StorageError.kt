// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.core.model

sealed class StorageError(open val message: String, open val cause: Throwable? = null) {
    data class NotConnected(override val message: String = "Not connected") : StorageError(message)
    data class AuthenticationFailed(override val message: String, override val cause: Throwable? = null) : StorageError(message, cause)
    data class HostUnreachable(val host: String, override val cause: Throwable? = null) : StorageError("Host unreachable: $host", cause)
    data class NotFound(val uri: FileUri) : StorageError("Not found: ${uri.toStorageString()}")
    data class AlreadyExists(val uri: FileUri) : StorageError("Already exists: ${uri.toStorageString()}")
    data class PermissionDenied(val uri: FileUri) : StorageError("Permission denied: ${uri.toStorageString()}")
    data class IoError(override val message: String, override val cause: Throwable? = null) : StorageError(message, cause)
    data class Cancelled(override val message: String = "Cancelled") : StorageError(message)
    data class ProtocolError(val protocol: Protocol, override val message: String, override val cause: Throwable? = null) : StorageError(message, cause)
    data class Unknown(override val message: String, override val cause: Throwable? = null) : StorageError(message, cause)
}

/**
 * UI でユーザーに見せる日本語メッセージ。
 *
 * `message` プロパティは診断用 (英語の技術的内容を含むことがある) で AppLogger 等に流す。
 * 画面に出すときはこちらを使う。動的メッセージ系 (IoError / ProtocolError / Unknown) は、
 * 呼び出し側が既に日本語の文面を入れている場合だけそれを尊重し、英語の内部メッセージは
 * カテゴリ単位の日本語に丸める。
 */
fun StorageError.toUserMessage(): String = when (this) {
    is StorageError.NotConnected -> "サーバーに接続されていません"
    is StorageError.AuthenticationFailed -> "認証に失敗しました。ユーザー名・パスワード・鍵を確認してください"
    is StorageError.HostUnreachable -> "サーバーに接続できませんでした（$host）"
    is StorageError.NotFound -> "見つかりませんでした：${uri.displayName()}"
    is StorageError.AlreadyExists -> "同じ名前のものが既にあります：${uri.displayName()}"
    is StorageError.PermissionDenied -> "アクセスが許可されていません：${uri.displayName()}"
    is StorageError.Cancelled -> "キャンセルしました"
    is StorageError.IoError -> japaneseDetailOrNull() ?: "入出力エラーが発生しました"
    is StorageError.ProtocolError -> japaneseDetailOrNull() ?: "通信エラーが発生しました（${protocol.scheme.uppercase()}）"
    is StorageError.Unknown -> japaneseDetailOrNull() ?: "エラーが発生しました"
}

/** message が日本語 (非 ASCII を含む) ならそのまま使う。英語の内部メッセージは UI に出さない。 */
private fun StorageError.japaneseDetailOrNull(): String? =
    message.takeIf { text -> text.any { it.code > 0x7F } }
