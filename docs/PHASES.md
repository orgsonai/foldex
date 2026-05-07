# Foldex 開発フェーズ — 達成条件と Git 運用

このファイルは「いま何をやっているか」「次に進む条件は何か」「Git でどう区切るか」のルールブック。
仕様の詳細は `FOLDEX-HANDOFF.md §13` を参照。本ファイルは運用面のみ。

---

## 0. フェーズ運用の基本

| | |
|---|---|
| 区切り単位 | フェーズ (P1〜P8) |
| ブランチ | `phase/P<番号>-<短い名前>` |
| マージ | `main` に **`--no-ff`** で常にマージコミットを残す |
| タグ | `v0.<番号>.0-P<番号>` (例: `v0.1.0-P1`) |
| 完了判断 | 本ファイルのチェックリストを全て満たし、ユーザーが OK を出した時点 |

### ブランチ命名
- `phase/P1-skeleton`
- `phase/P2-local-readonly`
- `phase/P3-local-crud`
- `phase/P4-smb`
- `phase/P5-remote`
- `phase/P6-server-sync`
- `phase/P7-polish`
- `phase/P8-release`

フェーズ内で複数の小機能を並行する場合は `feat/<short>` を `phase/P*` から切る。

### タグ命名
| フェーズ完了 | タグ |
|---|---|
| P1 | `v0.1.0-P1` |
| P2 | `v0.2.0-P2` |
| P3 | `v0.3.0-P3` |
| P4 | `v0.4.0-P4` |
| P5 | `v0.5.0-P5` |
| P6 | `v0.6.0-P6` |
| P7 | `v0.9.0-P7` (RC 相当) |
| P8 | `v1.0.0` (初回正式リリース) |

タグ付け時はアノテートタグ (`git tag -a`) を使い、メッセージにフェーズ達成サマリを書く。

---

## 1. P1 — プロジェクトスケルトン

### 達成条件
- [x] ルートの `settings.gradle.kts` / `build.gradle.kts` が用意されている
- [x] `gradle/libs.versions.toml` (Version Catalog) が用意されている
- [x] 12モジュールのディレクトリと最小 `build.gradle.kts` が存在する
  - [x] `app`
  - [x] `core/core-common`
  - [x] `core/core-model`
  - [x] `core/core-data`
  - [x] `storage/storage-local`
  - [x] `storage/storage-smb`
  - [x] `storage/storage-sftp`
  - [x] `storage/storage-ftp`
  - [x] `storage/storage-webdav`
  - [x] `server`
  - [x] `sync`
- [x] Hilt の最低限のセットアップ (Application クラス + `@HiltAndroidApp`)
- [x] 何も表示しない MainActivity (Compose) が起動する
- [x] `./gradlew assembleDebug` が成功する
- [x] 依存方向ルール (`CLAUDE.md §4-A`) が守られている (横方向依存なし)

### スコープ外 (やらない)
- 実機能の実装 (P2 以降)
- リモートストレージライブラリの統合
- UI の作り込み

### 想定コミット例
```
chore(repo): リポジトリ初期化、.gitignore/.editorconfig 整備
build(gradle): Version Catalog (libs.versions.toml) 初期化
build(modules): 12モジュールのスケルトン作成
feat(app): Hilt + 空の MainActivity
docs: P1 達成サマリ
```

---

## 2. P2 — ローカルファイルブラウザ (read-only)

### 達成条件
- [ ] `core-model`: `FileNode` / `FileUri` / `Permissions` / `NodeType` 定義
- [ ] `core-model`: `StorageProvider` インターフェース定義 (`FOLDEX-HANDOFF.md §5-C`)
- [ ] `core-common`: `Result<T, E>` 型定義
- [ ] `storage-local`: read 系のみ実装 (`stat` / `list` / `openInput`)
- [ ] SAF アクセスの最低限のラッパ (`Android/data` 領域も触れる)
- [ ] パンくずナビ + ファイル一覧の Compose 画面
- [ ] List / Detailed / Grid の3表示モード切替
- [ ] `MANAGE_EXTERNAL_STORAGE` 権限要求フロー (任意取得)

### スコープ外
- 書き込み・削除・リネーム (P3)
- リモート (P4 以降)

---

## 3. P3 — ローカル操作 (CRUD)

### 達成条件
- [ ] `storage-local`: write 系実装 (`mkdir` / `delete` / `rename` / `copyWithin` / `moveWithin` / `openOutput`)
- [ ] 操作モード方式 (X-plore流) のコピー/移動 UI
- [ ] 削除確認ダイアログ (デフォルトオン)
- [ ] アンドゥ Snackbar (5秒)
- [ ] キーボードショートカット (`FOLDEX-HANDOFF.md §11-F`) 主要操作
- [ ] 検索 (部分一致 + glob、再帰可)
- [ ] お気に入り (ピン留め) MVP

### スコープ外
- リモート (P4)
- ゴミ箱 (P7以降)
- EXIF削除 (P7以降)

---

## 4. P4 — SMB 対応 (1プロトコルで実証)

### 達成条件
- [ ] `core-model`: `Connection` sealed class 確定
- [ ] `core-data`: Room スキーマ (`connections` + `encrypted_credentials`)
- [ ] `core-data`: AES-GCM + AndroidKeyStore で認証情報暗号化
- [ ] `storage-smb`: smbj 統合、`StorageProvider` 実装
- [ ] 接続設定 UI (追加・編集・削除)
- [ ] SMB 経由の閲覧・コピー・移動・削除が動く
- [ ] 接続失敗時のエラーハンドリング

### スコープ外
- 他プロトコル (P5)
- 同期 (P6)

---

## 5. P5 — SFTP / FTP / WebDAV

### 達成条件
- [ ] `storage-sftp`: sshj 統合、ホスト鍵フィンガープリント検証
- [ ] `storage-ftp`: Apache Commons Net 統合、FTP/FTPS 両対応、平文警告UI
- [ ] `storage-webdav`: Sardine-Android 統合 (代替検討含む)
- [ ] 各プロトコルの接続設定 UI
- [ ] 文字コード判定 (juniversalchardet) — FTP の SJIS 対応など

### スコープ外
- 自機サーバー (P6)
- 同期 (P6)

---

## 6. P6 — 自機SFTP/FTPサーバー + 同期エンジン (片方向)

### 達成条件
- [ ] `server`: Apache MINA SSHD 統合、SFTP サーバー稼働
- [ ] `server`: Apache FtpServer 統合、FTP/FTPS サーバー稼働
- [ ] ForegroundService + 通知から停止可能
- [ ] Argon2id 認証 + Ed25519 ホスト鍵
- [ ] Wi-Fi 限定モード (デフォルトオン)
- [ ] 接続ログ記録 (デフォルトオン)
- [ ] `sync`: DiffEngine / ConflictResolver / Executor / Filter (glob)
- [ ] `sync`: WorkManager 連携 (15分以上の間隔)
- [ ] 競合解決ポリシー: NEWER_WINS / LOCAL_WINS / REMOTE_WINS / KEEP_BOTH / SKIP
- [ ] 片方向同期が動く (双方向は P8)

### スコープ外
- レート制限 / 自動ブロック (P7以降)
- 双方向同期 (P8)

---

## 7. P7 — UI洗練 / エラーハンドリング / テスト配布

### 達成条件
- [ ] 設定画面 (`FOLDEX-HANDOFF.md §11-H` フラット構成)
- [ ] 動的カラー (Material You) + Forest Green フォールバック
- [ ] ダーク/ライト/システム追従
- [ ] 拡張子バッジ
- [ ] アクセシビリティ最低ライン (TalkBack / 48dp / コントラスト)
- [ ] 同期途中再開
- [ ] エラーメッセージの日本語化
- [ ] ゴミ箱機能 (任意)
- [ ] PDF内蔵ビューア (任意)
- [ ] テスト配布用 APK ビルド (内輪向け)

### スコープ外
- 双方向同期 (P8)
- F-Droid / Play 配布 (P8)

---

## 8. P8 — 双方向同期 / 配布

### 達成条件
- [ ] 双方向同期 (`SyncDirection.BIDIRECTIONAL`)
- [ ] エクスポート/インポート (要設計)
- [ ] F-Droid 用 metadata
- [ ] Reproducible Build
- [ ] LICENSE 確定 + ヘッダ付与
- [ ] プライバシーポリシー (Play 向けに準備)
- [ ] GitHub リリースワークフロー
- [ ] `v1.0.0` タグ

---

## 付録 — フェーズ完了の儀式

1. 達成条件のチェックリストを全部 ✅ にする
2. `./gradlew clean assembleDebug detekt` が通ることを確認
3. このファイル末尾に「P○ 完了サマリ」セクションを追記 (任意)
4. `bash scripts/phase-finish.sh P<番号>` を実行
5. push はユーザーが手動で行う

---

最終更新: 2026-05-07 (初版)
