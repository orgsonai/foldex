// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.core.data.repo

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.zerotoship.foldex.core.model.home.HomeFunction
import com.zerotoship.foldex.core.model.home.HomeShortcut
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONObject
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private val Context.homeDataStore: DataStore<Preferences> by preferencesDataStore(name = "foldex_home")

/**
 * HOME 画面のショートカットを DataStore (Preferences) で永続化。
 * 各エントリは JSON 文字列として `Set<String>` に格納する。
 *
 * 組み込みタイル (ファイル/ゴミ箱/サーバ等) はストアには持たず、表示時に
 * カスタムショートカットの先頭に常時合成する。組み込みは「削除」できないが、
 * ユーザは [setHidden] で「非表示」にできる ([hiddenIds] に id を入れる)。
 *
 * 並び順は [orderIds] (`` 区切り文字列) に格納し、未登録の id は末尾扱い。
 */
@Singleton
class HomeShortcutRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val ds get() = context.homeDataStore

    // DataStore は同期読み出しができず、起動直後は値が届くまで一瞬遅れる。その間 HOME は
    // 既定順のタイルを描き、直後に保存順へ並び替わるため「並び替えアニメ」がチラつく。
    // それを消すために、最後に表示したタイル列を SharedPreferences (同期) にキャッシュしておき、
    // 起動時の初期値として即座に最終状態を描けるようにする。
    private val cachePrefs = context.getSharedPreferences("foldex_home_cache", Context.MODE_PRIVATE)

    val customShortcuts: Flow<List<HomeShortcut>> = ds.data.map { p ->
        (p[KEY_ITEMS] ?: emptySet()).mapNotNull(::decode)
    }

    /** 非表示にされたタイルの id 集合 (組み込み・カスタム共通)。 */
    val hiddenIds: Flow<Set<String>> = ds.data.map { it[KEY_HIDDEN] ?: emptySet() }

    /** 並び順 (id を `` 区切りで列挙)。未登録 id は表示時に末尾。 */
    val orderIds: Flow<List<String>> = ds.data.map {
        it[KEY_ORDER]?.split('')?.filter { s -> s.isNotEmpty() } ?: emptyList()
    }

    suspend fun addLocalFolder(label: String, path: String) {
        val sc = HomeShortcut.LocalFolder(
            id = UUID.randomUUID().toString(),
            label = label.trim().ifEmpty { path },
            path = path,
        )
        ds.edit {
            it[KEY_ITEMS] = (it[KEY_ITEMS] ?: emptySet()) + encode(sc)
            it[KEY_ORDER] = appendToOrder(it[KEY_ORDER], sc.id)
        }
    }

    suspend fun addSafFolder(label: String, documentUri: String) {
        val sc = HomeShortcut.SafFolder(
            id = UUID.randomUUID().toString(),
            label = label.trim().ifEmpty { documentUri.substringAfterLast('/') },
            documentUri = documentUri,
        )
        ds.edit {
            it[KEY_ITEMS] = (it[KEY_ITEMS] ?: emptySet()) + encode(sc)
            it[KEY_ORDER] = appendToOrder(it[KEY_ORDER], sc.id)
        }
    }

    suspend fun addRemoteConnection(label: String, connectionId: String) {
        val sc = HomeShortcut.RemoteConnection(
            id = UUID.randomUUID().toString(),
            label = label.trim().ifEmpty { connectionId },
            connectionId = connectionId,
        )
        ds.edit {
            it[KEY_ITEMS] = (it[KEY_ITEMS] ?: emptySet()) + encode(sc)
            it[KEY_ORDER] = appendToOrder(it[KEY_ORDER], sc.id)
        }
    }

    /** 既存ショートカットの表示名 (label) を更新する。組み込みタイルも対象。 */
    suspend fun rename(id: String, newLabel: String) {
        val trimmed = newLabel.trim()
        if (trimmed.isEmpty()) return
        // 組み込みタイル: customShortcuts には居ないので別途オーバーライドを保持する。
        // ここでは KEY_LABEL_OVERRIDES に id=label の連結文字列で保存し、
        // 読み出し側 (HomeViewModel) で BUILT_IN にマージする。
        ds.edit { prefs ->
            // カスタム側
            val cur = prefs[KEY_ITEMS] ?: emptySet()
            val updated = cur.map { raw ->
                val sc = decode(raw)
                if (sc != null && sc.id == id) {
                    val renamed = when (sc) {
                        is HomeShortcut.LocalFolder -> sc.copy(label = trimmed)
                        is HomeShortcut.SafFolder -> sc.copy(label = trimmed)
                        is HomeShortcut.RemoteConnection -> sc.copy(label = trimmed)
                        is HomeShortcut.Function -> sc.copy(label = trimmed)
                    }
                    encode(renamed)
                } else raw
            }.toSet()
            prefs[KEY_ITEMS] = updated
            // 組み込みタイル用 label オーバーライド
            val existing = prefs[KEY_LABEL_OVERRIDES] ?: emptySet()
            val withoutOld = existing.filterNot { it.startsWith("$id=") }.toSet()
            prefs[KEY_LABEL_OVERRIDES] = withoutOld + "$id=$trimmed"
        }
    }

    /** 組み込みタイル等の表示名オーバーライド (id -> label)。 */
    val labelOverrides: Flow<Map<String, String>> = ds.data.map { p ->
        (p[KEY_LABEL_OVERRIDES] ?: emptySet()).mapNotNull { entry ->
            val eq = entry.indexOf('=')
            if (eq < 0) null else entry.substring(0, eq) to entry.substring(eq + 1)
        }.toMap()
    }

    suspend fun remove(id: String) {
        ds.edit { prefs ->
            val cur = prefs[KEY_ITEMS] ?: return@edit
            prefs[KEY_ITEMS] = cur.filterNot { decode(it)?.id == id }.toSet()
            // 並び順・非表示集合からも掃除する。
            prefs[KEY_HIDDEN] = (prefs[KEY_HIDDEN] ?: emptySet()) - id
            prefs[KEY_ORDER] = (prefs[KEY_ORDER] ?: "")
                .split('').filter { it.isNotEmpty() && it != id }
                .joinToString("")
        }
    }

    /** id を非表示集合に入れる (true) / 外す (false)。組み込みタイルにも使う。 */
    suspend fun setHidden(id: String, hidden: Boolean) {
        ds.edit {
            val cur = it[KEY_HIDDEN] ?: emptySet()
            it[KEY_HIDDEN] = if (hidden) cur + id else cur - id
        }
    }

    /** 並び順の上書き保存。指定リストに無い id は表示時に末尾に回る。 */
    suspend fun setOrder(ids: List<String>) {
        ds.edit { it[KEY_ORDER] = ids.joinToString("") }
    }

    /** 直近に表示したタイル列のキャッシュ (同期読み出し)。無ければ null。 */
    fun cachedShortcuts(): List<HomeShortcut>? {
        val raw = cachePrefs.getString(KEY_CACHE, null) ?: return null
        return runCatching {
            val arr = org.json.JSONArray(raw)
            (0 until arr.length()).mapNotNull { decode(arr.getString(it)) }
        }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    /** 表示中タイル列を同期キャッシュへ保存する (次回起動の初期値に使う)。 */
    fun cacheShortcuts(list: List<HomeShortcut>) {
        val arr = org.json.JSONArray()
        list.forEach { arr.put(encode(it)) }
        cachePrefs.edit().putString(KEY_CACHE, arr.toString()).apply()
    }

    private fun appendToOrder(current: String?, id: String): String {
        val list = (current ?: "").split('').filter { it.isNotEmpty() }
        if (id in list) return current ?: ""
        return (list + id).joinToString("")
    }

    private fun encode(sc: HomeShortcut): String = when (sc) {
        is HomeShortcut.LocalFolder -> JSONObject().apply {
            put("kind", "local")
            put("id", sc.id)
            put("label", sc.label)
            put("path", sc.path)
        }.toString()
        is HomeShortcut.SafFolder -> JSONObject().apply {
            put("kind", "saf")
            put("id", sc.id)
            put("label", sc.label)
            put("documentUri", sc.documentUri)
        }.toString()
        is HomeShortcut.RemoteConnection -> JSONObject().apply {
            put("kind", "remote")
            put("id", sc.id)
            put("label", sc.label)
            put("connectionId", sc.connectionId)
        }.toString()
        is HomeShortcut.Function -> JSONObject().apply {
            put("kind", "fn")
            put("id", sc.id)
            put("label", sc.label)
            put("fn", sc.kind.name)
        }.toString()
    }

    private fun decode(raw: String): HomeShortcut? = runCatching {
        val o = JSONObject(raw)
        val id = o.getString("id")
        val label = o.getString("label")
        when (o.getString("kind")) {
            "local" -> HomeShortcut.LocalFolder(id, label, o.getString("path"))
            "saf" -> HomeShortcut.SafFolder(id, label, o.getString("documentUri"))
            "remote" -> HomeShortcut.RemoteConnection(id, label, o.getString("connectionId"))
            "fn" -> HomeShortcut.Function(id, label, HomeFunction.valueOf(o.getString("fn")))
            else -> null
        }
    }.getOrNull()

    private companion object {
        val KEY_ITEMS = stringSetPreferencesKey("home_shortcuts")
        val KEY_HIDDEN = stringSetPreferencesKey("home_hidden")
        val KEY_ORDER = stringPreferencesKey("home_order")
        val KEY_LABEL_OVERRIDES = stringSetPreferencesKey("home_label_overrides")
        // SharedPreferences 側の表示キャッシュキー (DataStore とは別ストア)。
        const val KEY_CACHE = "home_visible_cache"
    }
}
