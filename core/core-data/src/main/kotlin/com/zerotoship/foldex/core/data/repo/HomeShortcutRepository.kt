package com.zerotoship.foldex.core.data.repo

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
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
 * カスタムショートカットの先頭に常時合成する。ユーザは組み込みタイルを削除できない。
 */
@Singleton
class HomeShortcutRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val ds get() = context.homeDataStore

    val customShortcuts: Flow<List<HomeShortcut>> = ds.data.map { p ->
        (p[KEY_ITEMS] ?: emptySet()).mapNotNull(::decode)
    }

    suspend fun addLocalFolder(label: String, path: String) {
        val sc = HomeShortcut.LocalFolder(
            id = UUID.randomUUID().toString(),
            label = label.trim().ifEmpty { path },
            path = path,
        )
        ds.edit { it[KEY_ITEMS] = (it[KEY_ITEMS] ?: emptySet()) + encode(sc) }
    }

    suspend fun addRemoteConnection(label: String, connectionId: String) {
        val sc = HomeShortcut.RemoteConnection(
            id = UUID.randomUUID().toString(),
            label = label.trim().ifEmpty { connectionId },
            connectionId = connectionId,
        )
        ds.edit { it[KEY_ITEMS] = (it[KEY_ITEMS] ?: emptySet()) + encode(sc) }
    }

    suspend fun remove(id: String) {
        ds.edit { prefs ->
            val cur = prefs[KEY_ITEMS] ?: return@edit
            prefs[KEY_ITEMS] = cur.filterNot { decode(it)?.id == id }.toSet()
        }
    }

    private fun encode(sc: HomeShortcut): String = when (sc) {
        is HomeShortcut.LocalFolder -> JSONObject().apply {
            put("kind", "local")
            put("id", sc.id)
            put("label", sc.label)
            put("path", sc.path)
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
            "remote" -> HomeShortcut.RemoteConnection(id, label, o.getString("connectionId"))
            "fn" -> HomeShortcut.Function(id, label, HomeFunction.valueOf(o.getString("fn")))
            else -> null
        }
    }.getOrNull()

    private companion object {
        val KEY_ITEMS = stringSetPreferencesKey("home_shortcuts")
    }
}
