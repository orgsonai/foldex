// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.core.data.repo

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** 拡張子ごとの「開き方」。 */
enum class OpenWithMode {
    /** 既定の振る舞い (内蔵対応があれば内蔵、なければ外部アプリ選択)。 */
    DEFAULT,

    /** 内蔵ビューアで開く (内蔵非対応なら外部アプリ選択にフォールバック)。 */
    BUILTIN,

    /** 毎回アプリ選択ダイアログを出す。 */
    ASK,

    /** 外部アプリで開く (OS の既定アプリに任せる)。 */
    EXTERNAL,
}

private val Context.openWithDataStore: DataStore<Preferences> by preferencesDataStore(name = "foldex_openwith")

/**
 * 拡張子 → [OpenWithMode] の対応を永続化するリポジトリ。設定画面から編集する。
 * 未登録の拡張子は [OpenWithMode.DEFAULT] 扱い。
 */
@Singleton
class OpenWithRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val ds get() = context.openWithDataStore

    val overrides: Flow<Map<String, OpenWithMode>> = ds.data.map { p ->
        buildMap {
            p.asMap().forEach { (key, value) ->
                val name = key.name
                if (!name.startsWith(PREFIX)) return@forEach
                val mode = (value as? String)?.let { runCatching { OpenWithMode.valueOf(it) }.getOrNull() }
                    ?: return@forEach
                put(name.removePrefix(PREFIX), mode)
            }
        }
    }

    suspend fun set(extension: String, mode: OpenWithMode) {
        val ext = extension.trim().removePrefix(".").lowercase()
        if (ext.isEmpty()) return
        val key = stringPreferencesKey(PREFIX + ext)
        ds.edit { prefs ->
            if (mode == OpenWithMode.DEFAULT) prefs.remove(key) else prefs[key] = mode.name
        }
    }

    private companion object {
        const val PREFIX = "openwith."
    }
}
