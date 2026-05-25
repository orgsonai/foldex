package com.zerotoship.foldex.core.data.repo

import com.zerotoship.foldex.core.model.DeleteBehavior
import com.zerotoship.foldex.core.model.SyncBackupPolicy
import com.zerotoship.foldex.core.model.ThemeMode

/**
 * ユーザー設定のスナップショット。[SettingsRepository] から `Flow<UserSettings>` として観測する。
 *
 * ここに載るのは「アプリ全体の振る舞いを変える」プレーンな設定値だけ。接続情報やサーバー設定など
 * 機密・大きめのデータは別リポジトリ (Room) で扱う。
 */
data class UserSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /**
     * Material You (端末の壁紙連動カラー)。Android 12 未満では効果なし。
     * 既定は OFF — Foldex 独自の Forest Green テーマを既定の見た目にするため。ON で壁紙連動に切替。
     */
    val dynamicColor: Boolean = false,
    /** ファイル一覧でファイル名の後ろに拡張子バッジを表示するか。 */
    val showExtensionBadge: Boolean = true,
    /** 削除前に確認ダイアログを出すか。 */
    val confirmBeforeDelete: Boolean = true,
    /** アンドゥ Snackbar の表示秒数 (3 / 5 / 10 のいずれか)。 */
    val undoTimeoutSeconds: Int = 5,
    /** 削除操作の既定の行き先 (ゴミ箱へ / 完全削除 / 毎回確認)。 */
    val deleteBehavior: DeleteBehavior = DeleteBehavior.TRASH,
    /** ゴミ箱の自動削除日数 (0 = 無期限)。 */
    val trashRetentionDays: Int = 30,
    /** delete 同期で削除されるファイルをバックアップするか。 */
    val syncDeleteBackup: Boolean = true,
    /** バックアップを残す世代数。 */
    val syncBackupGenerations: Int = 3,
    /** これ以下のサイズ(MB)は常にバックアップ。これより大きいものは [syncBackupPolicyOverThreshold] に従う。 */
    val syncBackupThresholdMb: Int = 50,
    /** しきい値を超えたファイルの扱い。 */
    val syncBackupPolicyOverThreshold: SyncBackupPolicy = SyncBackupPolicy.ASK,
    /**
     * 内蔵テキストエディタで「編集可能」として開く上限 (KB)。
     * これを超えるテキストは閲覧専用 (LazyColumn) で開かれる。既定は 512KB。
     * BasicTextField + Gutter は ~4MB クラスでも動くが、巨大ファイルでは
     * 入力レイテンシが厳しくなる端末があるため、ユーザーが好みで上げ下げできる。
     */
    val editorEditableLimitKb: Int = 512,
)
