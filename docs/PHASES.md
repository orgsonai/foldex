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
- [x] `core-model`: `FileNode` / `FileUri` / `Permissions` / `NodeType` 定義
- [x] `core-model`: `StorageProvider` インターフェース定義 (`FOLDEX-HANDOFF.md §5-C`)
- [x] `core-common`: `Result<T, E>` 型定義
- [x] `storage-local`: read 系のみ実装 (`stat` / `list` / `openInput`)
- [x] SAF アクセスの最低限のラッパ (`Android/data` 領域も触れる)
- [x] パンくずナビ + ファイル一覧の Compose 画面
- [x] List / Detailed / Grid の3表示モード切替
- [x] `MANAGE_EXTERNAL_STORAGE` 権限要求フロー (任意取得)

### スコープ外
- 書き込み・削除・リネーム (P3)
- リモート (P4 以降)

---

## 3. P3 — ローカル操作 (CRUD)

### 達成条件
- [x] `storage-local`: write 系実装 (`mkdir` / `delete` / `rename` / `copyWithin` / `moveWithin` / `openOutput`)
- [x] 操作モード方式 (X-plore流) のコピー/移動 UI (長押しで選択→Copy/Cut→移動→Paste)
- [x] 削除確認ダイアログ (デフォルトオン)
- [x] アンドゥ Snackbar (5秒) (コピー/移動/リネーム/フォルダ作成)
- [x] キーボードショートカット (`FOLDEX-HANDOFF.md §11-F`) 主要操作 (Ctrl+C/X/V/A/F, Del, F2, Esc)
- [x] 検索 (部分一致 + glob、現在ディレクトリ)
- [x] お気に入り (ピン留め) MVP (SharedPreferences 永続化)

### スコープ外
- リモート (P4)
- ゴミ箱 (P7以降)
- EXIF削除 (P7以降)

---

## 4. P4 — SMB 対応 (1プロトコルで実証)

### 達成条件
- [x] `core-model`: `Connection` sealed class 確定 (Smb / Sftp / Ftp / WebDav)
- [x] `core-data`: Room スキーマ (`connections` + `encrypted_credentials`)
- [x] `core-data`: AES-GCM + AndroidKeyStore で認証情報暗号化
- [x] `storage-smb`: smbj 統合、`StorageProvider` 実装
- [x] 接続設定 UI (追加・編集・削除)
- [x] SMB 経由の閲覧・コピー・移動・削除 (実装済み、実機検証は別途)
- [x] 接続失敗時のエラーハンドリング (SMBApiException → StorageError マッピング)

### スコープ外
- 他プロトコル (P5)
- 同期 (P6)
- APPEND モードの SMB 書き込み (P5/P7 で再検討)

---

## 5. P5 — SFTP / FTP / WebDAV

### 達成条件
- [x] `storage-sftp`: sshj 統合、ホスト鍵フィンガープリント検証
- [x] `storage-ftp`: Apache Commons Net 統合、FTP/FTPS 両対応、平文警告UI
- [x] `storage-webdav`: OkHttp 直叩きで実装 (Sardine-Android は Maven Central 非配布のため代替)
- [x] 各プロトコルの接続設定 UI
- [x] 文字コード判定 (juniversalchardet) — `core-data` に CharsetDetector を用意

### スコープ外
- 自機サーバー (P6)
- 同期 (P6)

---

## 6. P6 — 自機SFTP/FTPサーバー + 同期エンジン (片方向)

### 達成条件
- [x] `server`: Apache MINA SSHD 統合、SFTP サーバー稼働 (実装済み、実機検証は P7 移行後)
- [x] `server`: Apache FtpServer 統合、FTP/FTPS サーバー稼働 (Explicit FTPS は自己署名証明書を自動生成)
- [x] ForegroundService + 通知から停止可能 (実装済み、実機検証は P7 移行後)
- [x] Argon2id 認証 + Ed25519 ホスト鍵
- [x] Wi-Fi 限定モード (デフォルトオン)
- [x] 接続ログ記録 (デフォルトオン)
- [x] `sync`: DiffEngine / ConflictResolver / Executor / Filter (glob)
- [x] `sync`: WorkManager 連携 (15分以上の間隔)
- [x] 競合解決ポリシー: NEWER_WINS / LOCAL_WINS / REMOTE_WINS / KEEP_BOTH / SKIP
- [x] 片方向同期が動く (双方向は P8) — エンジン/Worker/UI まで実装済み。実機での動作確認は P7 移行後 (UI 整備後)

### スコープ外
- レート制限 / 自動ブロック (P7以降)
- 双方向同期 (P8)

---

## 7. P7 — UI洗練 / エラーハンドリング / テスト配布

> P7 進行中の追加修正依頼は `docs/P7-REVISIONS.md` に整理 (§A〜§G まで)。確定したらここと HANDOFF へ反映する。

### 達成条件
- [x] 設定画面 (`FOLDEX-HANDOFF.md §11-H` フラット構成)
- [x] 動的カラー (Material You) + Forest Green フォールバック
- [x] ダーク/ライト/システム追従
- [x] 拡張子バッジ
- [x] アクセシビリティ最低ライン (TalkBack / 48dp / コントラスト): 操作系 IconButton は全てラベル済み、一覧の行高 56/64dp で 48dp 確保、コントラストは §H で整備。ファイル行のフォルダ種別/選択状態を読み上げ (`feec716`)。※実機 TalkBack での通し確認は残（運用）
- [ ] 同期途中再開 ← **P7 残** (リモートの完全動作を見届けた後に着手)
- [x] エラーメッセージの日本語化 (`StorageError` / `SyncError`): `StorageError.toUserMessage()` を追加し FileBrowser の表示を日本語化 (`7861e51`)。`SyncResult.toSummaryLine` は元から日本語
- [x] ゴミ箱機能 (ゴミ箱へ/完全削除/毎回確認 + ゴミ箱画面)
- [x] PDF 内蔵ビューア (PdfRenderer + LRU ページキャッシュ + 専用スクロールバー)
- [x] テスト配布用 APK ビルド (debug `.debug` 共存 / release 自己署名 / `./gradlew :app:assembleRelease`)

#### P7 で前倒し対応した追加機能 (`docs/P7-REVISIONS.md` §A〜§G)
- [x] 一覧の逐次表示 + ファストスクローラ + プルダウン更新
- [x] 内蔵ビューア (画像/テキスト編集/Markdown/HTML/音声/動画/PDF) + 外部アプリ連携 + APK インストール + サムネ
- [x] **テキストエディタを Sora-editor に置換** (~8MB まで軽快、検索/折返し/行番号/Undo/Redo)
- [x] **動画リモートストリーミング** (`StorageManager.openProxyFileDescriptor` + 各プロバイダ範囲指定 openInput → seek 対応)
- [x] 拡張子→既定アプリ設定 / フォルダ別の表示モード記憶
- [x] AppBar の整理 (検索 + ⋮、ファイル選択モード時の overflow メニュー)
- [x] 同期スケジュール拡張 (毎日/毎週/毎月/日時指定) + 実行状態チップ (RUNNING/ENQUEUED/IDLE)
- [x] 同期ジョブ追加画面の整理
- [x] delete 同期の削除前バックアップ (世代管理 + 容量しきい値設定 + L/R 一括復元)
- [x] **双方向同期 (`SyncDirection.BIDIRECTIONAL`)** ← 元 P8 から前倒し
- [x] **HOME 画面** (組み込みタイル / カスタム / ドラッグ並べ替え / 名前変更 / 非表示 / SAF tree も固定可)
- [x] **App Shortcuts** + ACTION_SEND 共有受信 (`*/*` の単一・複数ファイル)
- [x] **接続編集** (URL ワンライナー入力、ポート空欄許可、SFTP 公開鍵認証 + 鍵生成、SMB 共有名/初期パス分離)
- [x] **接続セッションプール**: 編集後の即時反映 (host/port/共有名/auth 等の signature 比較で再接続)
- [x] **FTP サーバー** を NIO ベースの NativeFileSystemFactory 自前実装に置換 (書き込み失敗の修正)
- [x] **SAF (Termux 等) 完全対応** (`FileUri.Saf.pendingChildName` + DocumentFile.createFile/createDirectory)
- [x] **ZIP** 圧縮/解凍 (zip4j 2.11.5 + AES-256 パスワード)
- [x] **メディア横断ビュー** (画像/動画を MediaStore から横断クエリ、フォルダグルーピング + 選択モード)
- [x] **実行ログ** 機能 (`AppLogger` + 設定→実行ログ、Crash / Server / Sync を集約)
- [x] **キャッシュクリア** (設定の「ストレージ」セクション)
- [x] **アプリアイコン** 差し替え
- [x] **タブ/画面切替の即時化** (NavHost の enter/exit transition を `None` に)
- [x] **DB マイグレーション失敗時の自動復旧** (`deleteDatabase` + 再構築フォールバック)

#### P7 終盤の追加修正 (2026-05-26, `docs/P7-REVISIONS.md` §H)
- [x] **SAF のコピー/切り取り移動** (`LocalStorageProvider` が SAF を弾いていたのを汎用コピーで実装。pending SAF の delete 誤動作も修正)
- [x] **エディタ編集可能上限に「無制限」** + ビューア/エディタをアプリのテーマ設定に追従 + ダークの行番号視認性改善
- [x] **Markdown プレビューに掴めるファストスクロールバー** (`ScrollState` 版を追加)
- [x] **HOME を配色チップ付きタイルに刷新** (機能別の色分け + 意味の合うアイコン)
- [x] **ZIP 中身プレビュー (`ArchiveExplorer`)**: 展開せずフォルダのように閲覧、単一エントリ展開プレビュー、暗号化対応
- [x] **ライト/ダークの配色整備**: 地の面はニュートラルなグレー、緑は primary とアクセント container のみ (従来は primary だけ緑で残り紫系)。動的カラーの既定を OFF にし既定を Forest Green テーマに統一。ステータスバー/ナビバーのアイコン明暗もアプリの実テーマに追従

#### P7 さらに追加修正 (2026-05-31, `docs/P7-REVISIONS.md` §I)
- [x] **親フォルダ消滅バグ修正** (`7405d9f`): フォルダ内の同名フォルダを「同名上書き」で外に出すと宛先が祖先になり、上書き削除で元ごと消えていた致命バグを修正。`FileBrowserViewModel.pathsOverlapUnsafely` で重なりを判定し拒否
- [x] **削除に進捗バナー** (`7405d9f`): `performDelete` から `opProgress` を出す (件数ベース、不確定バー)
- [x] **圧縮・解凍に進捗バナー** (`7405d9f`): `ZipOps` に `ZipProgress` を追加し zip4j `ProgressMonitor` をポーリングしてバイト進捗を吸い出す
- [x] **HOME 起動時の並び替えチラつき解消** (`c6e014d`): `HomeShortcutRepository.cachedShortcuts/cacheShortcuts` で表示中タイル列を同期キャッシュし、`HomeViewModel.shortcuts` の初期値に使用
- [x] **正式リリース署名構成** (`3979e27`): `keystore.properties` (gitignore 済) があれば PKCS12 リリース鍵で署名、無ければ debug 鍵にフォールバック。`apksigner` で `CN=Foldex, O=Zerotoship, C=JP` 署名済 APK を確認
- [x] **GitHub 公開**: `git@github.com:orgsonai/foldex.git` に main + 全 phase ブランチ + 全タグを push

#### P7 仕上げ第2回 — フォルダ操作の堅牢化 + ライセンス確定 (2026-05-31, `docs/P7-REVISIONS.md` §J)
- [x] **ライセンス GPL-3.0 確定** (`f403172`): `LICENSE` を GPL-3.0 全文に置換。依存は全て GPL-3.0 互換。README / HANDOFF §1 / CLAUDE も更新 (※当初 P8 予定を前倒し)
- [x] **リモート→SAF コピーの EISDIR 修正** (`e0177b5`): `StorageProviderRouter` の跨プロバイダコピーで SAF 宛先を `CREATE_NEW` + 実体 URI 解決 (`LocalStorageProvider.resolveDestDirectory`) に修正
- [x] **隠しファイル/フォルダの取りこぼし修正** (`e0177b5`): `copyDirectory` の `list` を `showHidden=true` に
- [x] **フォルダ全体の進捗 + コピー後の一致検証** (`9fef4df`): `computeTreeStat` でツリー実測し全体進捗を表示、コピー後に宛先ツリーと突き合わせ検証 (サイズ/ファイル数/フォルダ数)
- [x] **画面OFF/Doze 耐性** (`7b09ab4`): `FileOpService` (dataSync 前景 + WakeLock + WifiLock) を追加し paste/delete/zip/共有保存を保護、`SyncWorker` も `setForeground` 長時間化
- [x] **切り取りを「コピー→検証→元削除」に変更** (`3a36115`): 検証OK後にのみ元削除 (同一fs Local→Local は atomic rename)。`runPaste`/`onCleared` で WakeLock を確実解放
- [x] **debug 別名「Foldex (debug)」化** (`dc9948d` / `86ff749`): `versionNameSuffix` + `src/debug/res` の `app_name` 上書き (ja-JP 用に `values-ja` も)

#### P7 仕上げ第5回 — 実機FB + 完了通知 (2026-06-01〜06-02, `docs/P7-REVISIONS.md` §M)
- [x] **foldex-patch 適用** (`708102a`): 画像スライドの順序を画面表示順に統一 + 画像以外を排除、SD/OTG を `StorageManager` 経由で直接アクセス候補に、同期実行を `runCatching` で暫定保護
- [x] **ZIP 解凍の高速化 + 展開進捗** (`3aa17e7`): ローカル展開先は zip4j で直接展開しキャッシュ経由コピーを廃止 (I/O 約半分)、SAF/リモートはコピー工程に総バイト+ファイル数進捗を追加
- [x] **Android 14 同期クラッシュの根治** (`0ce3e68`): WorkManager の `SystemForegroundService` に `foregroundServiceType="dataSync"` をマニフェスト merge で付与 (型 0x1 が宣言 0x0 の部分集合でないクラッシュ)。`runCatching` では捕捉できない coroutine 外の `startForeground` が原因
- [x] **操作完了のシステム通知** (`cbd2ab3`): 共通 `OpCompletionNotifier` (core-data) でコピー・移動 / 解凍 / 同期の完了を通知。設定に「通知」セクションを新設し 3 トグルで個別 ON/OFF (既定 ON)
- [x] **バージョン** (`28945fc` + docs): `0.1.0` → `0.2.0` (完了通知で minor) → `0.2.1` (docs 区切りで patch)

#### P7 仕上げ第6回 — アイコン刷新 / 並び替え / ログ強化 / 定期同期の定刻化 / ナビ改善 (2026-06-02〜06-03, `docs/P7-REVISIONS.md` §N)
- [x] **アイコン刷新 / 並び替え / ログ強化 / SMB パス統合** (`5ecfaad`, 0.2.2): 接続/同期のドラッグ並び替え、永久ログ、SMB の共有名+初期パス統合
- [x] **時刻指定の定期同期をアプリ未起動でも実行** (`50e7544`, 0.2.3): `AlarmManager.setAndAllowWhileIdle` ベースに変更し Doze でも定刻発火
- [x] **保存後に一覧へ戻る / ローカルフォルダをブラウズ選択** (`39a204f`, 0.2.4): 同期ジョブの保存遷移を即時化、フォルダブラウザに切替
- [x] **次回時刻表示 / 通知許可バナー / 詳細ログ / 永久ログ拡張子修正** (`353b9c0`, 0.2.5)
- [x] **リモートの戻る挙動の試行と撤回** (`add1461` → `2048ef6`, 0.2.6〜0.2.10): 接続経由でのリモート閲覧時の戻り先を接続一覧に — 4 段階で再修正したが共有 `FileBrowserViewModel` 由来の副作用を抑えられず 0.2.10 で機能撤去・元の挙動に復帰。**教訓は §N に記録**

#### P7 仕上げ第7回 — エディタ堅牢化 / 同期の Doze 対応 / 詳細表示デフォルト化 (2026-06-03〜06-04, `docs/P7-REVISIONS.md` §O)
- [x] **エディタ体感改善** (`c6346ba` 0.2.11 → `86f20a3` 0.2.12 → `34552f3` 0.2.13): カーソルアニメ無効化で入力ラグ解消、左端まで選択可能、長押し→そのままドラッグで範囲選択、ハンドル拡大、入力経路の軽量化
- [x] **文字コード誤判定の根治** (`34552f3`): ASCII 多めの UTF-8 を US-ASCII と誤判定 → 全体検査で UTF-8 を優先する判定に変更。保存時の文字化け/欠落を防止
- [x] **定期同期の Doze 実行 / 双方向ループ修正 / 隠しファイル同期** (`aef5d2a`): INTERVAL も AlarmManager + expedited に統一、`UNCHANGED` 判定追加で SMB 等の mtime 再付与による再転送ループを解消、`showHidden=true` で隠しファイルも同期対象
- [x] **アイコン file5 / スプラッシュ背景調整** (`15d9fca`, 0.2.25): file5 ベースで再生成、Android 12+ スプラッシュ背景を `#1E1F24` に統一
- [x] **詳細表示デフォルト化 + 時刻表示** (`c53afae`, 0.2.27): フォルダの既定表示モードを `LIST → DETAILED` に、`yyyy-MM-dd HH:mm` で更新日時を表示
- [x] **同期キュー滞留の解除策** (`c53afae`, 0.2.27): 状態別ボタン (IDLE/ENQUEUED/RUNNING) で手動キャンセル可能に。設定で「キュー待ちの自動解除」をオン/オフ + 解除時間 (1/5/10/30/60 分) を選択可
- [x] **内蔵エディタの下書き自動保存** (`c53afae`, 0.2.27): `EditorDraftStore` で `cacheDir/editor-drafts/<sha256>.draft` にデバウンス保存、`ON_STOP` でフラッシュ、起動時に復元ダイアログ
- [x] **アイコン微縮小** (`c53afae`, 0.2.27): ランチャー前景を 56dp → 52dp に

#### P7 仕上げ第8回 — エディタ描画クラッシュ / SMB 接続復旧 / 同期ログ整理 (2026-06-08, `docs/P7-REVISIONS.md` §P)
- [x] **エディタの RenderNode クラッシュ回避** (`b4a0970` 0.2.28): `isHardwareAcceleratedDrawAllowed=false` + `cacheRenderNodeForLongLines=false` で `Current state = RESET, new state = FLUSHED` を踏まなくする (※第9回 §Q-1 でビュー全体をソフトウェアレイヤー固定にして再発も封じた)
- [x] **SMB 死セッションの追い出し** (`f3b1055` 0.2.29): プール再利用判定の厳格化 + エラー時に当該接続だけ `release` して自動再接続。アプリ再起動不要に
- [x] **同期ログをスキップ排除 + 方向ヘッダー化** (`26c0f22` 0.2.30): `── 同期開始: <方向> ──` + per-file 転送/エラーのみ
- [x] **フォルダ名タップでパス手動入力ジャンプ**: タイトルタップで `showPathInput()` (実装済み)

#### P7 仕上げ第9回 — エディタ確実起動 / 同期の二重実行根絶 / 削除復元プレビュー / 再帰検索 / 使用量分析 (2026-06-14, `docs/P7-REVISIONS.md` §Q)
- [x] **エディタを確実に開く** (`66cada5` 0.2.31): `setLayerType(LAYER_TYPE_SOFTWARE)` でビュー全体をソフトウェア描画にし、RenderNode 状態機械を構造的に回避 (§P-1 後の稀な再発を封じる)
- [x] **削除バックアップ復元をプレビュー+確認式に** (`66cada5` 0.2.31): 復元前に必ず一覧 (ローカル/リモート別) を出して可否を尋ねる。チップを「ローカル N件 / リモート N件」に明示化
- [x] **同期の二重実行を根絶** (`66cada5` 0.2.31 + `16e98fb` 0.2.33): 実行を一意名 `sync-run-…`+`KEEP` に統合 (途中キャンセル廃止) + `SyncEngine` 全体ミューテックスで全ジョブ直列化。2 ジョブ同時刻のレースで起きていた「転送0なのに転送済み」を解消
- [x] **同期ログにスキップ数・処理総数** (`66cada5` 0.2.31): サマリ末尾に `スキップ N / 合計 M 件`
- [x] **空ファイルを内蔵エディタで開く** (`6b8cd0b` 0.2.32): 0 バイト+不明拡張子を TEXT 扱いに
- [x] **サブフォルダ含む再帰検索** (`c9c4f57` 0.2.34): 検索バーの「サブフォルダ」トグルで現在フォルダ配下を再帰走査 (HANDOFF §12 検索の再帰を実装)
- [x] **使用量分析 (gdu 風・ドリルダウン)** (`b23afe6` 0.2.35): メニュー「使用量を分析」→フォルダ別合計サイズをバー付きで大きい順表示・潜れる・リモート可・中断可
> 全 5 コミット push 済み (`phase/P7-polish`)。リリースビルド緑。実機確認は §Q 引き継ぎメモ参照。

### スコープ外
- F-Droid / Play 配布 (P8)
- ~~LICENSE 確定 (P8)~~ → §J で前倒し確定 (GPL-3.0) → **§L で MIT に変更**。残りは各ソースへの SPDX ヘッダ付与 (P8)

---

## 8. P8 — 配布

### 達成条件
- [x] 双方向同期 (`SyncDirection.BIDIRECTIONAL`) — P7 で前倒し実装済み
- [x] 動画リモートストリーミング — P7 で前倒し実装済み (ProxyFileDescriptor + range openInput)
- [x] HOME 画面・ドラッグ並べ替え — P7 で前倒し実装済み
- [ ] エクスポート/インポート (要設計)
- [ ] F-Droid 用 metadata
- [ ] Reproducible Build
- [x] LICENSE 確定 (P7 §J で GPL-3.0 → §L で MIT に変更) / [ ] 各ソースへの SPDX ヘッダ付与
- [ ] プライバシーポリシー (Play 向けに準備)
- [ ] GitHub リリースワークフロー
- [ ] `v1.0.0` タグ
- [ ] P7 残課題の消化 (同期途中再開のみ。アクセシビリティ / エラーメッセージ日本語化は P7 で完了)

---

## 付録 — フェーズ完了の儀式

1. 達成条件のチェックリストを全部 ✅ にする
2. `./gradlew clean assembleDebug detekt` が通ることを確認
3. このファイル末尾に「P○ 完了サマリ」セクションを追記 (任意)
4. `bash scripts/phase-finish.sh P<番号>` を実行
5. push はユーザーが手動で行う

---

最終更新: 2026-06-04 (P7 仕上げ第7回 §O: エディタ堅牢化 (文字コード/長押しドラッグ/カーソルラグ) + 定期同期の Doze 実行 + 双方向ループ修正 + 詳細表示デフォルト化 + 同期キュー解除 + エディタ下書き保存。バージョン 0.2.27 / versionCode 29)
