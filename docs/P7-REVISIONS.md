# P7 修正方針メモ (フィードバック反映)

このファイルは P7 (`phase/P7-polish`) 進行中に orgson から出た追加修正依頼を整理したもの。
仕様の真実は `FOLDEX-HANDOFF.md`。確定したら各章へ反映し、ここはチェックリストとして残す。

依頼日: 2026-05-12

### 進捗 (随時更新)
- ✅ A: 一覧の逐次表示 + FastScrollbar (`d13b3d8`)
- ✅ D: FileTypeRegistry / カテゴリ別アイコン・色分け / Coil サムネ (`7701313`)、
  内蔵ビューア (画像/テキスト/音声) + 外部アプリ連携 + APK インストール (`7f1acba`)、
  Markdown/HTML ビューア (`8553916`)、拡張子バッジ + 「ファイルの開き方」設定 (`2ab98a0`)、
  テキスト簡易編集 (`42fe798`)。
  **残りの小ポリッシュ**: 音声アルバムアートのサムネ (Coil 用の MediaMetadataRetriever フェッチャが必要)、動画サムネの確認。
- ✅ C: AppBar を検索+⋮ に整理 (`61b08a7`)
- 🟡 B/E:
  - ✅ E ゴミ箱システム (`39e76ca`): DeleteBehavior(TRASH/PERMANENT/ASK) + TrashRepository + 設定 + TrashScreen + 削除フロー差し替え + 自動削除。
  - ✅ B-3 ジョブ追加 UI の整理 (`2f3b281`): セクション見出し・説明文・例・glob 凡例・スケジュールのプリセットチップ。
  - ✅ B-2 スケジュール拡張 (`6b2c48b`): ScheduleType + SyncSchedule、SyncJobEntity スキーマ拡張 (DB v3→4)、Scheduler を OneTimeWork の自己再スケジュール方式へ、編集画面に時刻/曜日/日/日時のピッカー。
  - ✅ B-4 delete 同期の削除前バックアップ (`912c9d0`): SyncBackupRepository、設定 (世代数/しきい値/超過時の扱い)、SyncEngine→Executor で削除前に退避。バックアップ一覧/復元の UI は後続。
  - ✅ B-1 双方向同期 (`ae5ec6b`): SyncDirection.BIDIRECTIONAL、DiffEngine の双方向判定 (SAME/NEW/MODIFIED/DELETED/NEVER 分類)、ConflictResolver/Executor が勝者で転送方向を決定、SyncEngine は双方向で片側ルート欠如を許容。

**A〜E のすべて (双方向同期含む) 完了。** 小ポリッシュ (音声アルバムアートのサムネ `9bc9683`、削除前バックアップの一覧/復元 UI `9bc9683`) も対応済み。残課題は P7 本来のチェックリスト (アクセシビリティ、エラーメッセージ日本語化、同期途中再開、PDF 内蔵ビューア、テスト配布 APK)。

### 確定した方針 (2026-05-12 ヒアリング)
- B-1 双方向同期: **P7 で実装**。HANDOFF §8-B / PHASES P8 を更新する。
- D 音声プレーヤー: **Media3 / ExoPlayer を依存追加**して簡易内蔵プレーヤーを作る。HANDOFF §10-G / §10-L を更新。
- B-4 バックアップ容量しきい値・確認挙動の設定: **グローバル設定のみ**（ジョブ単位の上書きはしない）。
- 着手順: **A (スクロール/表示性能) → D (内蔵ビューア/サムネ/既定アプリ) → C (AppBar) → B/E (同期拡張・ゴミ箱)** の順で進める。

---

## A. ファイル一覧のスクロール / 表示パフォーマンス

> スクロールがやはり遅い。時間が経つと問題なくなるので読み込みかと思われる。大量にファイルがあるフォルダは表示されるまでに時間がかかる。これも即座に表示されるようにしてほしい。スクロールバーがない。右側に小さく表示させて、右端から左に軽くスライドするとスクロールバーを大きくしてタッチでスライドできるようにしてほしい。

### 方針
- [ ] **初回表示の即時化**: `list()` の結果を一括取得してからまとめて描画する現状をやめ、ストリーム/ページングで「届いた分から即描画」する。
  - `StorageProvider.list()` とは別に `listFlow()`（または既存 API を `Flow<List<FileNode>>` で chunk emit）を検討。少なくとも `storage-local` は段階 emit する。
  - 並べ替え・フォルダ先頭固定は逐次反映でちらつかないよう、一定 chunk ごとに再ソートして差し替え。
- [ ] **スクロールが「時間が経つと直る」原因の特定**: サムネ生成・拡張子バッジ・mtime フォーマット・アイコン解決などを `LazyColumn`/`LazyVerticalGrid` の item composition 内で同期実行していないか確認。
  - サムネは Coil の非同期ロード（プレースホルダ→差し替え）に統一。item 内で同期 I/O / 重い計算をしない。
  - 日付文字列・サイズ文字列は `remember` でメモ化、できれば `FileNode` を UI 用 `FileRow` に一度だけ変換して保持。
  - `key = { it.uriString }` を必ず指定。安定キーがないと再 composition が増える。
  - `@Immutable` / Compose stability（`compose_stability.conf` 既存）を UI モデルに付与。
- [ ] **大量ファイルフォルダ対策**:
  - ソートと「隠しファイル除外」を `Default` ディスパッチャの 1 回の処理にまとめ、メインスレッドでやらない。
  - 1 万件級でも耐えるよう `LazyColumn` の item は完全に軽量化（ネストレイアウト削減）。
- [ ] **スクロールバー（ファストスクローラ）**:
  - 通常時: 右端に細いトラック + つまみを薄く表示（スクロール中のみ濃く、停止後フェードアウト）。
  - インタラクション: 右端付近から左方向にスワイプすると、つまみが拡大して掴める状態になり、ドラッグでファストスクロール。グリッド/リスト両対応。
  - ドラッグ中は現在位置のラベル（先頭文字 or インデックス）をオーバーレイ表示。
  - 自前 Composable（`FastScrollbar`）として `app/ui/components/` に実装。外部ライブラリは入れない方針（要確認なら別途）。

---

## B. 同期エンジン

### B-1. 双方向同期の追加

> 方向だが、双方向同期も追加してほしい。

- [ ] `SyncDirection.BIDIRECTIONAL` を P7 で実装（**HANDOFF §8-B / PHASES P8 では「P8」扱い → P7 に前倒し**）。
  - `SyncJobEntity.direction` に `"bidirectional"` を追加。
  - state DB（`sync_states`）必須。両側の前回 size/mtime を見て「片側のみ変化＝伝播」「両側変化＝競合」「片側消失＝削除伝播（deleteEnabled 時のみ）」を判定。
  - 競合は既存 `ConflictPolicy`（NEWER_WINS デフォルト / LOCAL_WINS / REMOTE_WINS / KEEP_BOTH / SKIP）をそのまま使用。
  - ⚠ HANDOFF / PHASES の「双方向は P8」記述と矛盾するので、確定後に §8-B と PHASES.md を更新。

### B-2. 定期同期のスケジュール指定を拡充

> 定期同期の時間指定だが、毎日何時とか、週どの曜日に何時からとか、日付と時間とかで定期同期も選択肢に入れてほしい。

- [ ] スケジュール種別を追加（現状は `intervalMinutes` の固定間隔のみ）:
  - **間隔** (既存): n 分/時間ごと（WorkManager 最小 15 分制約は維持）。
  - **毎日**: 指定時刻（例 02:00）。
  - **毎週**: 曜日（複数可）＋時刻。
  - **毎月**: 日（例 1 日、月末）＋時刻。
  - **日時指定**: 特定の日付＋時刻に 1 回（または「毎年この日」）。
- [ ] 実装: WorkManager の `OneTimeWorkRequest` を「次回発火時刻」へ `setInitialDelay` で予約し、実行完了後に次回をまた予約する自己再スケジュール方式（`PeriodicWorkRequest` では時刻指定が無理なため）。端末再起動時は `BootReceiver`（既存）で再登録。
- [ ] `SyncJobEntity` スキーマ拡張: `scheduleType`（`interval|daily|weekly|monthly|datetime`）、`scheduleTime`（分単位 of day）、`scheduleDaysOfWeek`（ビットマスク）、`scheduleDayOfMonth`、`scheduleDateTime`（epoch、datetime 用）。`intervalMinutes` は `interval` 種別専用に。Room マイグレーション必要。

### B-3. ジョブ追加 UI のわかりやすさ

> ジョブ追加時の設定だが、下にある三つの入力ボックスに何をいれるのかよく分からない。もうちょっとわかりやすくしてほしい。

- 該当: `includePatterns` / `excludePatterns` / `maxFileSize` の 3 入力（`sync/edit` 画面）。
- [ ] ラベルと説明文を明確化:
  - 「含めるファイル（glob）」例: `*.jpg, *.png` ／空なら全部。chip 入力 + 「例を見る」ヘルプ。
  - 「除外するファイル（glob）」例: `**/.git/**, *.tmp`。
  - 「これより大きいファイルは同期しない」: 数値 + 単位（MB/GB）ドロップダウン、空＝無制限。スライダ or プリセット（無制限/100MB/1GB/…）。
- [ ] 各フィールドに inline help（`supportingText`）と、glob の簡単な凡例を出すボトムシート。
- [ ] セクション見出しを付ける（「対象フォルダ」「フィルタ」「スケジュール」「競合と削除」「詳細（並列度・リトライ）」）。詳細はデフォルト折りたたみ。

### B-4. delete 同期のバックアップ（世代管理）

> delete 同期を ON にしている場合、削除するファイル・フォルダ構成をアプリ上で 3 世代分くらいバックアップを残しておいてほしい。自分側もリモート側のもどちらも残す。設定で xxxMB 以上の場合はバックアップするか確認 / 無確認でバックアップ / 無確認でバックアップ除外 など、容量と確認を設定可能に。

- [ ] **同期削除前バックアップ**: `deleteEnabled` なジョブで `DeleteLocal` / `DeleteRemote` を実行する直前に、対象をバックアップ領域へ退避してから削除。
  - ローカル側: アプリ専用領域（例 `Android/data/com.zerotoship.foldex/files/sync-trash/<jobId>/<generation>/...`）にフォルダ構成ごと保存。
  - リモート側: リモート上の隠しフォルダ（例 `.foldex-sync-trash/<jobId>/<generation>/...`）へ rename or copy。リモートに書けない場合はローカルへ退避（フォールバック）し、その旨をログ。
  - **世代数 3**（設定可。1〜?）。同期実行ごとに 1 世代繰り上げ、古い世代を削除。
- [ ] **容量しきい値 × 挙動の設定**（ジョブ単位 or グローバル、要確認 → 暫定はジョブ単位、グローバルにデフォルト）:
  - しきい値 `backupThreshold`（MB、未満は常にバックアップ）。
  - しきい値超過時の `backupPolicyOverThreshold`: `ASK`（毎回確認ダイアログ）/ `BACKUP`（無確認でバックアップ）/ `SKIP`（無確認でバックアップせず削除）。
  - バックグラウンド実行（WorkManager）で `ASK` の場合は通知で確認 or 「次回実行まで保留＋通知」。UI が無い時の挙動を要設計。
- [ ] バックアップ領域の閲覧/復元 UI（`sync/history` か専用画面）。「この世代を復元」「破棄」。
- [ ] サイズ集計と上限（サムネキャッシュ同様、設定で上限 → 古い世代から自動削除）。

---

## C. ヘッダー（AppBar）の情報過多

> ヘッダーだが、お気に入りボタン・検索ボタン・表示種類・sync ボタンなど多くて、内部ストレージなどの名前がほとんど見えていない。いずれも見やすく修正してほしい。

- [ ] AppBar の右側アクションを整理:
  - 常時アイコン表示は **検索** と **オーバーフロー(⋮)** の 2 つだけに（HANDOFF §12-B の元設計に戻す）。
  - 表示モード切替・ソート・隠しファイル・お気に入り追加・このフォルダで同期作成 などは ⋮ メニュー or ボトムシートへ集約。
  - 表示モード切替だけは頻度が高いので、⋮ の最上段 or パンくず行右端に小さく置く案も検討。
- [ ] タイトル（「内部ストレージ」「自宅NAS / Public」等）に十分な幅を確保。長い場合は ellipsis、タップでフルパス/接続情報のポップオーバー。
- [ ] 接続中の帯域・状態表示はタイトル下の細い行 or パンくず行に小さく（タイトルを圧迫しない）。
- [ ] FAB（貼り付け/新規作成）と被らないレイアウト確認。

---

## D. ファイル種別ごとの内蔵ビューア / 既定アプリ設定

> txt・画像ファイル・HTML ビューア・mp3 再生などはアプリ内で簡易的に完結させてほしい。サムネイル表示もお願い。apk などはインストールできるように。その他対象外のファイルは外部アプリから開くなど、拡張子によって既定のアプリ選択とか設定からできるように。ゴミ箱システムも追加（後述 E）。

- [ ] **内蔵ビューア**（HANDOFF §10 を一部前倒し / 追加）:
  - 画像: `ImageViewerScreen`（ズーム/回転/スワイプ/EXIF）。
  - テキスト: `TextViewerScreen` + `TextEditorScreen`（簡易編集）。エンコーディング判定（juniversalchardet）。
  - Markdown: `MarkdownViewerScreen`（Markwon）。
  - **HTML ビューア（新規）**: ローカル/リモートの `.html`/`.htm` を `WebView` で簡易表示（JS は既定オフ、相対リソース読み込みは同一ディレクトリ限定）。`app/ui/viewer/HtmlViewerScreen.kt`。
  - **mp3 等の音声再生（新規・方針変更）**: HANDOFF §10-G は「再生は外部のみ」だが、依頼により **簡易内蔵プレーヤー** を追加（`MediaPlayer`/`ExoPlayer` のどちらかで最小実装：再生/一時停止/シーク/曲名・アートワーク表示、バックグラウンド継続は要検討）。`AudioPlayerScreen.kt`。⚠ §10-G / §10-L と矛盾するので確定後に更新。
  - 圧縮: `ArchiveExplorerScreen`（中身プレビュー）。
- [ ] **サムネイル表示**: Coil 3 ベース、メモリ+ディスク 2 層キャッシュ（HANDOFF §10-C）。画像/動画/音声アートワーク/（可能なら）PDF 1 ページ目。リモートは 10MB 制限でフォールバック。グリッド表示で特に効く。A 項のパフォーマンス対策と整合させる（item 内同期ロード禁止）。
- [ ] **APK**: タップ or メニューから `ACTION_VIEW`(`application/vnd.android.package-archive`) でインストール起動（`REQUEST_INSTALL_PACKAGES` 権限、`FileProvider` 経由、リモートは一旦ダウンロード）。
- [ ] **拡張子 → 既定アプリのマッピング設定**:
  - 設定画面に「ファイルの開き方」セクション。拡張子（またはカテゴリ）ごとに「内蔵ビューア / 毎回選択 / 特定の外部アプリ」を選べる。
  - 内部に `OpenWithPreferenceEntity(extOrCategory, mode, externalPackage?)` を持つ（Room or DataStore）。
  - 未設定の拡張子は「内蔵対応があれば内蔵、なければ外部アプリ選択ダイアログ」。
  - ビューア画面に「別のアプリで開く」「既定にする」アクション。

---

## E. ゴミ箱システム（ファイル操作全般）

> ゴミ箱システムも追加してほしい。ON/OFF で、ゴミ箱に入れるか・完全削除か・毎回確認か、設定から選べるように。

- [ ] **削除時の挙動設定**（HANDOFF §11-H「ゴミ箱を使う OFF (P7)」を具体化）:
  - `deleteBehavior`: `TRASH`（ゴミ箱へ移動）/ `PERMANENT`（完全削除）/ `ASK`（毎回ダイアログで選択）。
  - 既存の「削除前の確認 ON/OFF」とは別軸（確認の有無 × 行き先）。
- [ ] **ゴミ箱の実体**:
  - ローカル: アプリ専用領域（例 `Android/data/.../files/trash/`）に、元パス・削除日時のメタ付きで退避。SAF 領域や `MANAGE_EXTERNAL_STORAGE` 配下のファイルも一旦ここへ移動（クロスデバイス時はコピー＋削除）。
  - リモート: 接続/共有のルート直下に `.foldex-trash/` を作って rename。書けない/対応してない場合はゴミ箱無効（＝確認の上で完全削除）か、ローカルへ退避するかを要検討。
- [ ] **ゴミ箱画面**: `files` タブのクイックアクセス or 設定から。一覧（元パス・削除日時・サイズ）、復元、完全削除、空にする。
- [ ] **自動削除**: 「n 日後に自動で空にする」設定（デフォルト 30 日 or 無効、要決定）＋サイズ上限。
- [ ] 同期削除バックアップ（B-4）とは別物だが、退避先ディレクトリ構造・サイズ管理・復元 UI は共通化できると良い（`core-data` に `TrashRepository` を用意し、ゴミ箱と sync-trash の両方が使う）。

---

## F. 実機フィードバック対応（2026-05-13）

実機で触った上での指摘。いずれも UI/パフォーマンス寄りで HANDOFF の確定事項とは矛盾しない（D のサムネ方針のみ縮小）。

- [x] **設定画面の説明文が「…」で切れて読めない**: `SettingRow` を見直し、チップ群など横幅を食うコントロールはタイトル/説明の下に縦並びで配置（`wide`）。チップは `FlowRow` で折り返し。説明文は `maxLines` 制限を外して全文表示。
- [x] **ファイル一覧のスクロールが重い**: 行のクリック/長押しを安定した関数参照（`viewModel::onItemClick` 等）で渡し、スクロール中の無駄な再コンポーズを抑制。`LazyColumn` の `key` を文字列生成から `FileUri` 自体に変更。サムネの `ImageRequest` を `remember` 化。**一覧のサムネは画像のみ**にして動画/音声アートのメタデータ抽出（重い）を一覧から外した（ビューアでは引き続き表示）。
- [x] **フォルダ階層で左上が「戻る」に変わる → ハンバーガー固定に**: `navigationIcon` から「上へ」分岐を削除。上へ戻る操作はパンくず／端末の戻るボタンで行う（`BackHandler` は据え置き）。
- [x] **テキストエディタ刷新**:
  - 「編集」トグルを廃止し、ローカルのテキストは常時編集可能に。
  - Undo / Redo / 保存 のアイコンボタンを追加（新 `BasicTextField(state)` の `undoState` を利用）。
  - 巨大テキストで固まる問題: 編集は ~512KB までに制限し、それを超えるものは 1 行ずつ遅延描画する閲覧専用モード（`LazyColumn`）にフォールバック。内蔵で開ける上限は 2MB→4MB に拡大。

---

## G. P7 終盤の追加修正 (2026-05-13 〜 2026-05-16)

実機検証を進めながら出てきた追加修正を時系列で記録。§A〜§F より新しい内容はここに追記する。HANDOFF / PHASES の確定事項にぶつかるものはコメントで明示する。

### G-1. 起動時クラッシュ系
- [x] **DB v3→v4 / v4→v5 マイグレーション失敗時の自動復旧** (`CoreDataModule.provideDatabase`): `Room.databaseBuilder().build()` 直後に `openHelper.writableDatabase` を強制オープン。`IllegalStateException` が出たら `context.deleteDatabase(...)` で物理削除→再構築する保険を追加。`fallbackToDestructiveMigration(dropAllTables=true)` でも一部端末・ビルド汚染で発火しないケースに対応。
- [x] **SFTP `IllegalArgumentException("No user home")`** → `FoldexApplication.onCreate` で `System.setProperty("user.home", filesDir.path)` を必ず先に設定。
- [x] **SFTP `KeyExchangeFactories not set`** → BouncyCastle のフル版を `Application.onCreate` で `Security.removeProvider("BC"); Security.addProvider(BouncyCastleProvider())`。`SshServer.setUpDefaultServer()` の各 default が空のときは `ServerBuilder.setUpDefault*(true)` で defensive 再構築。
- [x] **UncaughtExceptionHandler**: スタックに `org.apache.mina|sshd|ftpserver` を含む例外 (FTP の `CoderMalfunctionError` 等) はプロセスを殺さず握り潰す。クラッシュは `externalFilesDir/crash/crash_*.txt` に 5 件ローテで保存。

### G-2. 接続編集 / セッションプール
- [x] **接続編集に「URL から入力」フィールド**: `sftp://user@host:22/path` / `smb://host/share/sub` / `webdavs://...` をパースして各欄に分解。ポート空欄許可 (`portText: String` + `effectivePort()` フォールバック)。
- [x] **SFTP 公開鍵認証**: `SftpAuthMode { PASSWORD, PUBLIC_KEY }`、`SshClientKeyHelper.generate()` で RSA 3072 + PKCS#8 PEM + OpenSSH 公開鍵を生成 (Android 標準 JCA のみ依存)。UI に「鍵を生成」+「公開鍵をコピー」。
- [x] **SMB の「共有名」と「初期パス」を分離**: `Connection.initialPath: String` を追加 (Room v4→v5 destructive)。「共有名」フィールドに `share/sub/path` が入っても自動で先頭セグメント=share、残り=initialPath に分解。
- [x] **編集ダイアログを範囲外タップ/戻るキーで閉じない**: `AlertDialog(properties = DialogProperties(dismissOnClickOutside=false, dismissOnBackPress=false))`。「キャンセル」ボタン経由でのみ閉じる。
- [x] **接続編集の即時反映**: `SmbSessionPool` / `SftpSessionPool` / `FtpClientPool` / `WebDavSessionStore` が `connectionId` だけでキャッシュを返してしまい、共有名や認証情報を変更してもアプリ再起動するまで反映されなかった。各 Holder に「プール時点の Connection 設定」を保持させ、`acquire` 時に signature (host / port / share / username / authMethod / fingerprint / basePath 等) を比較。一致しなければ close → 再接続。

### G-3. FTP まわり
- [x] **FTP 書き込み失敗の診断**: `FoldexFtpDiagnosticFtplet` を新設し、書き込み系コマンド (STOR/APPE/MKD/DELE/RNFR/RNTO) の 5xx 応答を `ServerLogEvent.FILE_OP_FAILED` に流す。`DataConnectionConfigurationFactory.passiveAddress / passiveExternalAddress` を listener と同じ host に明示し、`passivePorts = "30000-30100"` で固定。
- [x] **FTP `NioFileSystemFactory`**: Apache FtpServer のデフォルト `NativeFileSystemFactory` (java.io.File / RandomAccessFile) を自前 NIO ベース実装に置換。`Files.newByteChannel(WRITE, CREATE, TRUNCATE_EXISTING)` を使う `NioFtpFile` + `NioFileSystemView`。
- [x] **FTP `setRestartOffset` で範囲指定 read** (G-9 で追加、ストリーミング seek 用)。

### G-4. ファイルブラウザ
- [x] **タイトル長押し → パスをクリップボードへコピー / タップ → `PathInputDialog` で手動入力**: ローカル `/storage/...` / リモート `scheme://connId/path` / SAF `documentUri` を切り替え。
- [x] **コピー/移動/共有保存の進捗バナー**: `FileBrowserState.opProgress: FileOpProgress?` + `FileOpProgressBanner.kt` (LinearProgressIndicator + 件数 + バイト + 現在ファイル名、80ms スロットル)。
- [x] **DL バナー**: 「ダウンロード中…」snackbar を廃止し、上部に `ActiveDownloadsBanner` を常駐表示。
- [x] **ペーストフッターをコンパクト化** (80dp → 48dp、`Surface + Row`)。
- [x] **PullToRefreshBox** で一覧を引き下げ更新。
- [x] **ソート / 隠しファイル切替** (`KEY_SORT_BY / KEY_SORT_ASC / KEY_SHOW_HIDDEN` に永続化、フォルダごとに表示モードも記憶)。
- [x] **選択モードの overflow**: 共有 / 別アプリで開く / プロパティ / HOME に追加 / ZIP 圧縮 / ZIP 解凍。
- [x] **ファイル作成**: `CreateFolderDialog` → `CreateDialog`、`CreateKind { FOLDER, FILE }` のセグメンテッドボタンで切替。新規ファイル作成後に内蔵対応カテゴリなら自動オープン。
- [x] **コピー貼付の上書き確認**: 衝突時に `PasteOverwriteDialog` でユーザー確認。

### G-5. HOME 画面
- [x] **HOME 画面骨格** (新規モジュール `ui/home/`): 組み込みタイル (Files / Trash / Servers / Sync / Settings / Permissions / SAF / 画像 / 動画) + カスタム (LocalFolder / SafFolder / RemoteConnection)。BottomNav 先頭に追加、startDestination を `home` に変更。
- [x] **タイル長押し → ドラッグで一気に並べ替え**: `sh.calvin.reorderable` を導入。長押しでドラッグ開始 (`longPressDraggableHandle`) → 任意位置でドロップ。タップ = タイル本来の動作。メニュー (改名 / 隠す / 削除) は右上の ⋮ ボタンへ移動。
- [x] **タイル改名**: `HomeShortcutRepository.rename(id, label)` + 組み込みタイル用 `KEY_LABEL_OVERRIDES`。
- [x] **非表示タイルの復元** (HOME 右上の ⋮ から `HiddenShortcutsDialog`)。
- [x] **SAF tree を HOME に固定**: `HomeShortcut.SafFolder`、HOME +ボタンの追加ダイアログを `FlowRow` で「ローカル / SAF / リモート」3 モード対応 (3 つ目が見切れる問題も解決)。
- [x] **画像 / 動画の横断ビュー** (HOME の組み込みタイル → `MediaCollectionScreen`): MediaStore を `DATE_MODIFIED DESC` で横断クエリ、`GridCells.Adaptive(110dp)` で Coil サムネ表示、フォルダグルーピング + 長押し選択 (削除/共有/外部/場所を開く)。

### G-6. ビューア (画像 / PDF / Markdown / テキスト / 動画)
- [x] **画像スワイプ**: `Modifier.transformable` が 1 指 pan も消費して HorizontalPager のスワイプを阻害する問題。`awaitEachGesture` + `awaitFirstDown(requireUnconsumed=false)` の自前実装で「ポインタが 2 本以上のとき」だけ zoom/pan を消費。1 指は Pager に渡す。
- [x] **PDF**: `produceState` の `awaitDispose` でレンダラインスタンスを正しく close (`DisposableEffect(rendererState) { onDispose { rendererState?.close() } }` が delegate の現値を見るため null→new に切り替わった瞬間 new を即 close するクラッシュを解消)。`PdfBitmapCache` (LRU 12 ページ) + 専用 `EditorScrollbar` (28dp 当たり判定 + ドラッグでスクロール)。
- [x] **テキストエディタを Sora-editor (0.23.5) に置換**: `BasicTextField` を撤廃し `CodeEditor` (AndroidView)。検索 / 折返し / 行番号 / Undo/Redo / 保存 / 貼付 のフッター。`EditorColorScheme` を Material 色から組み立て。編集上限を 8MB まで拡大 + ユーザー設定 (128KB〜8MB) を `editorEditableLimitKb` で導通。
- [x] **エディタ入力ラグ軽減**: `ContentChangeEvent` ハンドラを 150ms debounce + 値変化時のみ Compose state 更新。`setCursorBlinkPeriod(750)` で描画頻度を下げる。
- [x] **エディタフッターを IME 上に**: `AndroidManifest` の `ViewerActivity` に `windowSoftInputMode="adjustResize"` + Column に `imePadding()`。
- [x] **動画**: WMV / `.asf` (VC-1/WMV9) は MediaCodec 非対応なので拡張子で予め判定し外部アプリ案内オーバーレイへ即フォールバック。`Player.Listener.onPlayerError` で再生エラー時も同じオーバーレイ。
- [x] **Markdown**: テーブル / strikethrough / tasklist / linkify プラグイン追加。
- [x] **拡張子なしテキスト自動判定**: `looksLikeText(file)` で先頭 8KB 走査 (NUL バイト / 制御文字 5% 超でバイナリ判定)。LICENSE / Dockerfile / Makefile / shebang スクリプトを内蔵エディタで開けるように。

### G-7. SAF (Termux) 完全対応
- [x] **`FileUri.Saf.pendingChildName` を追加**: 親 URI + これから作る子の名前を擬似 URI で表現。`mkdir` / `openOutput(CREATE_NEW)` は `DocumentFile.fromTreeUri(parent).createDirectory/createFile(name)` で実体生成。
- [x] **`statSaf` を `fromTreeUri + findFile(name)` ベースに**: `fromSingleUri` は wrapper を返すだけで「常に存在」誤判定するバグを解消。
- [x] **`list` を `fromTreeUri` 統一**: `fromSingleUri` ではサブフォルダ listing 不能だった。
- [x] **`StorageProviderRouter.childUri` も SAF 対応**: cross-copy / paste / saveSharedFilesHere / createDialog すべて SAF 宛に動く。
- [x] **`downloadSafToCache` で SAF ファイルを内蔵ビューアで開く / 編集後の書き戻し**: `PendingRemoteEdit` を `PendingEdit(sourceUri, mtimeAtOpen)` に一般化。`ON_RESUME` で `checkPendingUploads` が Remote / SAF 両方を見て `openOutput(OVERWRITE)`。

### G-8. ZIP / 共有受信 / App Shortcuts
- [x] **ZIP 圧縮/解凍** (zip4j 2.11.5): `ZipOps.compress/extract`、AES-256 / PKCS#5 パスワード対応。選択モード overflow から起動。リモート / SAF 宛でも `copyTreeIntoStorage` で書ける。`ZipExtractDialog` は password なしで試行→ `WrongPassword` 例外で再ダイアログ。
- [x] **ACTION_SEND / SEND_MULTIPLE 共有受信**: AndroidManifest に intent-filter (`mimeType="*/*"`)。MainActivity が EXTRA_STREAM から複数 Uri を読み、表示名を `OpenableColumns.DISPLAY_NAME` で問い合わせ、`FileBrowserState.pendingShares` に積んで `ShareReceiveBanner` を表示。「ここに保存」で `openOutput(CREATE_NEW)`、同名は ` (N)` でユニーク化。
- [x] **App Shortcuts** (`res/xml/shortcuts.xml`): Files / Connections / Servers / Trash の 4 つ。MainActivity の launchMode を `singleTask` に、intent extra `foldex.shortcut` を MainScreen が受けて `selectTab(...)`。

### G-9. リモート動画ストリーミング (seek 対応)
- [x] **`RemoteStreamProvider` (ContentProvider)** を新設。authority は `${applicationId}.streaming` (debug/release が共存可能)。
- [x] **第1段階**: `ParcelFileDescriptor.createPipe()` + バックグラウンドで `StorageProviderRouter.openInput()` のストリームを write 側にフィード。再生開始は早いが seek 不可 (ExoPlayer のシークバー操作で停止)。
- [x] **第2段階**: `StorageManager.openProxyFileDescriptor` + `ProxyFileDescriptorCallback` に置換。`onRead(offset, size, data)` ごとに `StorageProvider.openInputRange(uri, offset)` を呼び、連続位置のときは前回ストリームを使い回し、seek 時のみ閉じて再オープン。
- [x] **`StorageProvider.openInputRange(uri, offset)` を追加**:
  - SFTP: `RemoteFile.RemoteFileInputStream(fileOffset)`
  - SMB: `SmbRangeInputStream` (`File.read(buf, fileOffset, ...)` 使用)
  - FTP: `client.setRestartOffset(offset)` + `retrieveFileStream`
  - WebDAV: HTTP `Range` ヘッダ (`206 Partial Content`)、非対応サーバは skip フォールバック
  - 既定実装は `openInput()` + `skip()` (低速だが正しく動く)
- [x] **VideoViewer**: `mediaUri: String?` パラメータを追加。非 null のときは ExoPlayer に直接渡し、ローカルファイルは `file.toURI()` を使う既存経路を維持。

### G-10. その他の細かい改善
- [x] **タブ / 画面遷移の即時化**: `NavHost(enterTransition = { EnterTransition.None }, exitTransition = { ExitTransition.None }, popEnterTransition = ..., popExitTransition = ...)`。フェードを撤廃。
- [x] **同期 "競合" 表記の改善**: `SyncResult.toSummaryLine` を「転送 N (両側更新 M)」に書き換え、`transferredCount = uploaded + downloaded + conflicts` に修正 (競合は失敗ではなく解決済み転送として扱う)。
- [x] **削除バックアップ拡張**: `SyncBackupRepository.restoreLocalFile(overwrite)` + `backupFile()` (リモート復元用ストリーム)。`SyncBackupViewModel.requestBatchRestore` で衝突チェック → ダイアログ → ローカル + リモート同時復元。世代に L/R chip + ファイル詳細表示。
- [x] **同期ジョブの実行中チップ**: `SyncScheduler.observeStatus(jobId): Flow<JobRunStatus>` (WorkInfo タグから判定)。`SyncJobsScreen` の各行に「実行中」緑 / 「キュー中」橙の `JobStatusChip`。実行中は今すぐボタンをスピナーに置換 + 無効化。
- [x] **詳細実行ログ**: `Executor.onAction(message, level)` コールバックを追加し、SyncEngine が各アクションを `AppLogger` に流す。
- [x] **`AppLogger` 基盤**: Singleton、`externalFilesDir/logs/app.log` 256KB / 2 世代ローテ。`info` / `warn` / `error` (例外スタックも記録)。設定 → 実行ログから一覧 / フィルタ / 共有 / 消去。
- [x] **サーバ起動失敗ログ**: `SftpServerManager` / `FtpServerManager` の `start()` Result.Failure 時に `AppLogger.error(...)` を呼ぶ。snackbar を見逃してもログから後追い可能。
- [x] **キャッシュクリア** (設定の「ストレージ」): `cacheDir` + `externalCacheDir` を再帰削除、容量表示。
- [x] **エディタ編集可能上限の設定** (128KB / 256KB / 512KB / 1MB / 2MB / 4MB / 8MB)。
- [x] **アプリアイコン** 差し替え (mipmap-{m,h,xh,xxh,xxxh}dpi)。
- [x] **ContentProvider authority を `${applicationId}.streaming` に**: debug / release 共存対応 (`INSTALL_FAILED_CONFLICTING_PROVIDER` の回避)。

---

## H. P7 仕上げの追加修正 (2026-05-26)

実機検証後に出た追加依頼。すべて実機確認済み。HANDOFF の確定事項とは矛盾しない (機能追加・不具合修正)。

- [x] **SAF のコピー/切り取り移動ができない問題を修正** (`fcf3be3`): `LocalStorageProvider.copyWithin/moveWithin` が SAF (`FileUri.Saf`) を「Cross-type copy not supported」で弾いていた。SAF↔SAF / SAF↔Local を `openInput/openOutput/mkdir/list/stat` だけで行う汎用コピー (`genericCopy`) を追加し、ディレクトリは実 URI に解決してから再帰。あわせて pendingChildName 付き SAF の `delete` が**親フォルダごと消しかねない**バグも修正 (findFile で子を解決してから削除)。
- [x] **エディタの編集可能上限に「無制限」** (`35c7de4`): 設定チップに「無制限」(`UNLIMITED_EDITABLE_LIMIT_KB`) を追加。選択時は編集ロックと読み込み上限 (MAX_BYTES) の両方を外す。
- [x] **ビューア/エディタをアプリのテーマ設定に追従** (`35c7de4`): `ViewerActivity` が `isSystemInDarkTheme()` 固定で手動のライト/ダーク設定を無視していた。`themeMode` + Material You を設定から解決して `FoldexTheme` に渡す。Sora の `isDark` も実テーマ由来 (surface の luminance) に。
- [x] **ダークモードの行番号が背景に埋もれる問題を修正** (`61eb8d4`): Sora の `LINE_NUMBER_CURRENT` を本文色、スクロール時の行番号バブル (`LINE_NUMBER_PANEL` / `_TEXT`) を primary/onPrimary で高コントラスト化。
- [x] **Markdown プレビューに掴めるファストスクロールバー** (`f634115`): `verticalScroll(ScrollState)` 向けのピクセル比率ベースの `FastScrollbar` を追加し MD プレビューへ設置 (既存の index ベース core には未変更)。
- [x] **HOME のタイルを配色チップ付きデザインに刷新** (`70fe3dc`): 全タイルが同一の灰色カード + primary 一色アイコンで単調だったのを、機能ごとに色分けした角丸アイコンチップ入りタイルへ。配色はテーマ由来 (primary/secondary/tertiary/error/neutral コンテナ) で Material You・ライト/ダークに追従。アイコンも意味の合うものへ (設定=Settings, 権限=Shield, 同期=Sync, サーバ=Dns 等。従来は設定=錠前など誤マッピング)。
- [x] **ZIP を展開せずに中身を閲覧 (ArchiveExplorer)** (`0f72628`): 仕様 §10「中身プレビュー」を実装。`ui/archive/ArchiveExplorer` (zip4j ヘッダ読み + 仮想ツリー + 単一エントリ展開) と `ArchiveExplorerActivity` (パンくず付きで潜れる一覧、ファイルタップで内蔵ビューア/外部表示、暗号化 zip はパスワード要求)。`openFile` が ZIP を `OpenRequest.Archive` 経由で本画面へ振り分け。全展開 (解凍) は従来どおり選択メニューから利用可能。
- [x] **ライト/ダークの配色を整備** (`FoldexTheme.kt`): 当初フォールバックは primary だけ緑で残りが Material 既定の紫系 → 緑×紫がちぐはぐだった。最終形は **地の面 (background / surface / surfaceVariant / surfaceContainer 群) はニュートラルなグレー、緑は primary とアクセント container (HOME タイル等) だけ** に限定 (light/dark とも)。※途中で surface まで緑にしたら「全体的に緑っぽい」と指摘が出たためニュートラルへ戻した経緯あり。あわせて**動的カラーの既定を OFF** にし (`UserSettings.dynamicColor=false` + repo 既定 false)、既定の見た目を Forest Green テーマに統一 (設定でいつでも Material You に切替可)。
- [x] **ステータスバー/ナビバーのアイコン視認性** (`FoldexTheme.kt`): `enableEdgeToEdge()` はシステムの dark/light を基準にアイコン明暗を決めるため、アプリを手動でライトにしても白アイコンのままで時計等が背景と同化していた。`SideEffect` で `WindowCompat` の `isAppearanceLight{Status,Navigation}Bars = !darkTheme` をアプリの実テーマに合わせて設定。

---

## I. P7 仕上げの追加修正 (2026-05-31)

実機検証後の追加依頼 + 正式リリース署名構成 + GitHub 公開。`fix(filebrowser)` `fix(home)` `build` の 3 コミット (`7405d9f` / `c6e014d` / `3979e27`)。

- [x] **親フォルダ消滅バグの修正** (`7405d9f`): `FileBrowserViewModel.executePaste` の上書き処理 (`storage.delete(destUri)` → `move/copy`) が、宛先が元 (`node.uri`) の祖先になっているケースで「元ごと巻き添えで消えて、その後の move/copy が失敗」していた致命バグを修正。再現条件: フォルダ `…/X/A` の中に同名フォルダ `…/X/A/A` があり、内側を切り取って `…/X` で「同名を上書き」貼り付けすると宛先 `…/X/A` が祖先になる。新規ヘルパ `pathsOverlapUnsafely(src, dest)` で「同一」または「どちらかが他方の祖先」を判定し、危険時は削除も移動もせず明示メッセージで拒否 (Local / Remote / SAF 全対応、SAF は docId のパス部で比較)。コピー側も同様にガード。
- [x] **削除に進捗バナー** (`7405d9f`): `performDelete` で `opProgress` を設定し、件数ベースの「削除中…」「ゴミ箱へ移動中…」バナーを表示 (バイト進捗は取れないため不確定バー)。
- [x] **圧縮・解凍に進捗バナー** (`7405d9f`): `ZipOps.compress/extract` に `fun interface ZipProgress` を追加し、zip4j の `runInThread=true` + `ProgressMonitor` ポーリング (60ms) で逐次バイト進捗を吸い出す。`executeZipCompress` / `executeZipExtract` は 80ms スロットルで `opProgress` を更新 (圧縮はファイル単位、解凍はアーカイブ全体)。例外は `pm.exception` 経由で再 throw し既存の `WrongPassword` 検出を保持。
- [x] **HOME 起動時の並び替えチラつき解消** (`c6e014d`): DataStore (非同期) の初期ロード前は `BUILT_IN` 既定順を描き、ロード後に保存順へ並び替わるため Reorderable の配置アニメが走っていた。`HomeShortcutRepository` に SharedPreferences ベースの同期キャッシュ (`cachedShortcuts()` / `cacheShortcuts()`、`foldex_home_cache`) を追加し、`HomeViewModel.shortcuts` の `stateIn` 初期値に使用 + `onEach` で実値到着のたびに更新。前回と変化が無ければ完全に無動作で描ける。
- [x] **正式リリース署名構成** (`3979e27`): リポ直下 `keystore.properties` (gitignore 済み) があれば PKCS12 のリリース鍵 (`release.keystore`、RSA-2048、10000日) で署名し、無いマシンでは debug 鍵にフォールバック。`signingConfigs { create("release") { ... } }` と `release { signingConfig = if (keystorePropsFile.exists()) … else … }` を `app/build.gradle.kts` に追加 (`java.util.Properties` を明示 import)。`release.keystore` / `keystore.properties` は `.gitignore` 既定で除外 (`*.keystore` / `keystore.properties`)。`apksigner verify` で `CN=Foldex, O=Zerotoship, C=JP`、SHA-256 `4F:8C:09:…:00:03` の APK v2 署名を確認済み。
- [x] **GitHub 公開** (リモート: `git@github.com:orgsonai/foldex.git`): main + phase/P1〜P7-polish + v0.1.0-P1〜v0.6.0-P6 のタグ全てを push 済み。鍵 (`release.keystore`) と資格情報 (`keystore.properties`) は gitignore で除外され、リポジトリには含まれない。

## J. P7 仕上げの追加修正 (2026-05-31, 第2回) — フォルダ操作の堅牢化 + ライセンス確定

実機フィードバック (リモート→SAF コピー失敗 / 進捗 / 隠しファイル / 画面OFF / 切り取りの安全性) への対応とライセンス確定。コミット: `f403172` `e0177b5` `7b09ab4` `9fef4df` `3a36115` `dc9948d` `86ff749`。

- [x] **ライセンス GPL-3.0 確定** (`f403172`): `LICENSE` を GNU GPL-3.0 全文 (gnu.org 原文) に置換。依存 (smbj / Apache Commons / MINA SSHD / Apache FtpServer = Apache-2.0、xz = public domain) はすべて GPL-3.0 互換であることを確認。README / FOLDEX-HANDOFF (§1) / CLAUDE.md の記述も「確定」に更新。
- [x] **リモート→SAF フォルダ貼付の EISDIR 修正** (`e0177b5`): `StorageProviderRouter.crossCopy` が SAF 宛先を扱えていなかった。① `copyFile` が `OVERWRITE` で `openOutput` していたため、SAF の「親 + pendingChildName」擬似 URI で親ディレクトリ自身を `openOutputStream` し `open failed: EISDIR (Is a directory)` を出していた → `CREATE_NEW` に変更。② `copyDirectory` が宛先を実体 URI に解決せず再帰し階層が平坦化していた → `LocalStorageProvider.resolveDestDirectory` を新設し宛先 dir を実 URI に解決してから再帰。単一ファイルの remote→SAF も同根 (OVERWRITE→親EISDIR) で同時に解消。
- [x] **隠しファイル/フォルダの取りこぼし修正** (`e0177b5`): `copyDirectory` が `list(from)` を既定 (`showHidden=false`) で呼び `.zsh` 等が漏れていた → `ListOptions(showHidden=true)`。同プロバイダ内コピー (SMB/SFTP/FTP/WebDAV/Local) は元から raw list で隠しを含むため影響なし。`resolveDir` も `showHidden=true` 化。
- [x] **フォルダ全体の進捗表示** (`9fef4df`): `executePaste` で貼付開始時に各ノードのツリーを実測 (`computeTreeStat`: サイズ/ファイル数/フォルダ数) し、フォルダ全体に対する累積バイトで進捗バーを出す。従来は「1ファイルずつの進捗」でフォルダ完了時刻が読めなかった。ノード境界で確定値へスナップして累積推定のズレを補正。zip 解凍/圧縮は元からアーカイブ/フォルダ全体の進捗。
- [x] **コピー後のデータ一致検証** (`9fef4df`): コピー/移動の成功後に宛先ツリーを再実測し、元の実測値 (サイズ/ファイル数/フォルダ数) と一致するか検証 (`TreeStat` の等価比較)。隠しファイル漏れ・途中失敗・切断による欠損を検出し、不一致はエラー表示。成功時スナックバーに「(検証OK)」。
- [x] **画面OFF/Doze 耐性** (`7b09ab4`): 長時間操作中に画面OFF→Doze で CPU/Wi-Fi が眠り転送がストール/切断していた。`FileOpService` (dataSync 前景サービス + `PARTIAL_WAKE_LOCK` + `WifiLock(FULL_HIGH_PERF)`、安全弁で最長2時間自動解放) を `app/.../fileop/` に追加。`FileBrowserViewModel` が `opProgress` の有無を監視して start/stop し、コピー/移動/解凍/共有保存を一括保護。app manifest に `WAKE_LOCK` / `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_DATA_SYNC` / `POST_NOTIFICATIONS` と service 宣言を追加。`SyncWorker` も `setForeground` で長時間ワーカー化 (約10分上限での打ち切り回避、端末制限で前景化拒否時は通常ワーカーで継続)。
- [x] **切り取りを「コピー→検証→元削除」に変更** (`3a36115`): 従来 `moveWithin` は検証なしでコピー後に元を削除しており欠損時のデータ消失リスクがあった。`executePaste` の Cut を「コピー → 一致検証 → 検証OK確認後に元削除」へ。検証失敗時は元を残し中途半端な宛先のみ掃除。ただし同一ファイルシステム上の Local→Local 移動は atomic rename (バイトコピーが無く欠損し得ない) を使用 (cross-fs/SAF/リモートはコピー経路)。`runPaste` ラッパで成功/失敗/例外いずれでも `opProgress` を畳み、前景サービス+WakeLock を確実に解放 (= コピー/切り取り終了で自動ロック解除)。`onCleared` でも解放。
- [x] **debug ビルドを別名「Foldex (debug)」化** (`dc9948d` / `86ff749`): release (`com.zerotoship.foldex` / Foldex) と区別できるよう、debug は `applicationIdSuffix=".debug"` (既存) に加え `versionNameSuffix="-debug"` と表示名上書きを追加。`src/debug/res/values/strings.xml` だけだと **ja-JP 端末では main の `values-ja` (Foldex) が優先され効かない**ため、`src/debug/res/values-ja/strings.xml` も追加。

### 引き継ぎメモ (2026-05-31 時点)

- **ブランチ**: `phase/P7-polish`。上記 §J の 7 コミットは**ローカルのみ (未 push)**。
- **ビルド確認**: `:app:assembleDebug` / `:app:assembleRelease` ともに成功 (`lintVitalRelease` 通過)。
- **既知の限界 / フォローアップ**:
  - 一致検証は宛先ツリーを再 walk するため、巨大なリモートフォルダではメタデータ列挙の往復コストが増える (バイト再読込はしない)。
  - 前景サービスは Android 14 仕様上、処理中に無音 (IMPORTANCE_LOW) の常駐通知を 1 つ出す。
  - 同一fs の Local→Local 切り取りは atomic rename なので検証をスキップ (rename はデータ欠損し得ないため意図的)。
- **運用インシデント (要再設定)**: 実機検証中、release のインストール失敗 (Play Protect の `VERIFICATION_FAILURE`) を署名衝突と誤認し `adb uninstall com.zerotoship.foldex` を実行 → release のアプリ内データ (接続情報 / サーバー設定 / お気に入り / 同期ジョブ / SAF許可) を消失。`allowBackup=false` のため復旧不可。管理対象のファイル本体は無事。**release は再設定が必要**。debug は別パッケージ (`.debug`) で最初から共存しており衝突は無く、uninstall は不要だった (再発防止: 破壊的操作は事前確認)。
- **実機状態 (Motorola, ja-JP)**: release `com.zerotoship.foldex` (Foldex / 0.1.0) と debug `com.zerotoship.foldex.debug` (Foldex (debug) / 0.1.0-debug) が共存インストール済み。検証用に一時無効化した Play Protect (`verifier_verify_adb_installs`) は有効 (=1) に復帰済み。
- **未実施 / 次の判断**: `git push` (未)、P7 完了タグ付けの可否、実機での「隠しフォルダ込みコピー / 全体進捗 / 画面OFF継続」の最終確認。

## K. P7 残課題の消化 + 進捗のファイル数表示 (2026-05-31, 第3回)

P7 達成条件の残課題 (エラーメッセージ日本語化 / アクセシビリティ) を消化し、フォルダ操作の進捗にファイル数を追加。コミット: `7861e51` `feec716` `137945c` (+ docs)。

- [x] **エラーメッセージの日本語化** (`7861e51`): `StorageError.toUserMessage()` を core-model に追加。`message` (診断用、英語の内部メッセージを含む / AppLogger 用) は温存し、UI 表示はこちらを使う。NotConnected / AuthenticationFailed / HostUnreachable / NotFound / AlreadyExists / PermissionDenied / Cancelled をカテゴリ単位の日本語に。IoError / ProtocolError / Unknown は呼び出し側が既に日本語文面を入れている場合だけ尊重し英語内部メッセージはカテゴリ日本語に丸める。`FileUri.displayName()` を追加 (URI 全体でなく末尾名)。FileBrowserViewModel のスナックバー/エラー表示を `toUserMessage()` へ。同期側 (`SyncResult.toSummaryLine`) は元から日本語のため変更なし。→ PHASES §7 完了に更新。
- [x] **アクセシビリティ最低ライン** (`feec716`): 一覧の `FileLeadingIcon` でフォルダ種別と選択状態を TalkBack 読み上げ (フォルダ→「フォルダ」、選択中→「選択中」、通常ファイルは name + 拡張子バッジで伝わるため null 維持)。操作系 IconButton は調査の結果すべてラベル済み、行高 56/64dp で 48dp タップ領域を確保、コントラストは §H で整備済み。→ PHASES §7 完了に更新。※実機 TalkBack の通し確認は残 (運用)。
- [x] **コピー/切り取り貼り付けの進捗にファイル数** (`137945c`): `FileOpProgress` に `filesTransferred` / `filesTotal` を追加。`executePaste` で `filesTotal = computeTreeStat の files 合計`、ノード完了で `completedFiles` 加算、フォルダ内は observer のファイル境界検出 (初回 / transferred 巻き戻り / total 変化) で `intraFiles` をカウント。バナーは `filesTotal>0` のとき「○○ / ○○ ファイル」を表示 (なければ従来の項目数)。削除/ZIP/保存は `filesTotal=0` で表示は従来どおり。

### 引き継ぎメモ (第3回追記)
- **未 push が増加**: §J の 7 + §K の 4 コミット (`7861e51` `6cd3cb3` `feec716` `5301dcf` `316b1fe` `137945c` + 本追記) がローカルのみ。
- **P7 達成条件の残り**: 「同期途中再開」のみ (リモートの完全動作を実機で見届けた後に着手の前提)。他 (アクセシビリティ / エラー日本語化) は §K で完了。
- **ファイル数進捗の限界**: フォルダ内のファイル数はバイト境界からの推定なので、空ファイル (0 バイトで observer が発火しない) は途中カウントに乗らないことがある。ノード完了時に確定値 (`completedFiles`) へスナップするので最終値は正しい。

## L. ライセンスを GPL-3.0 → MIT に変更 (2026-05-31, 第4回)

§J で GPL-3.0 に確定していたが、自由度重視の方針転換で **MIT** に変更。依存ライブラリは全て Apache-2.0 / public domain で MIT と互換のため問題なし。あわせてアプリ内にライセンス画面を新設した。

- [x] **`LICENSE` を MIT 全文に差し替え** (GPL-3.0 全文 → MIT、Copyright (c) 2026 Zero to Ship)。
- [x] **ドキュメントの記述を MIT に更新**: `README.md` のライセンス節 / `FOLDEX-HANDOFF.md` §1 / `CLAUDE.md` §1 の表を「MIT (確定)」に。
- [x] **アプリ内ライセンス画面を新設** (`ui/settings/LicensesScreen.kt`): 設定 → 「ライセンス」をタップで開く。本アプリ本体 (MIT) + 依存ライブラリ (smbj / Apache Commons Net / MINA SSHD / Apache FtpServer = Apache-2.0、XZ for Java = public domain) を一覧表示し、タップで全文ダイアログ。全文は `assets/licenses/MIT.txt` / `Apache-2.0.txt` から読み込む。`MainScreen` に `settings/licenses` ルートを追加。設定の「ライセンス」行は固定文字列 `"GPL-3.0 (予定)"` → `"MIT © 2026 Zero to Ship"` + タップ遷移に変更。
- 注: §J 行・PHASES §7/§8 の GPL 記述は当時の履歴としてそのまま残す (本 §L が後続の確定)。各ソースへの SPDX ヘッダ付与は引き続き P8。

## M. 実機フィードバック対応 + 完了通知 (2026-06-01〜06-02, 第5回)

実機 (Motorola, ja-JP, Android 14) で release 版を使い込んでの追加依頼。パッチ適用 → 解凍高速化 →
Android 14 同期クラッシュの根治 → 完了通知の追加、の順で対応。コミット: `708102a` `3aa17e7`
`0ce3e68` `cbd2ab3` `28945fc` (+ 本 docs 更新でバージョンを 0.2.1 に)。

- [x] **foldex-patch の適用** (`708102a`): 別セッションで `foldex-patch/` に置かれていた 5 ファイルの修正を本体へ反映。
  - 画像スライドの順序を画面表示順 (`MediaCollectionScreen` の `visible: List<MediaItem>`) に統一し、画像以外の混入を排除 (旧: `File.parentFile.listFiles()` の FS 順 + `isFile` のみ)。
  - SD/OTG を `StorageManager.storageVolumes` (API 30+) で確実検出し `/storage/XXXX-XXXX` を直接アクセス候補に (FileBrowser QuickAccess / HOME 候補 / 同期のローカルフォルダ GUI ピッカー)。SAF より大幅に高速。`canRead()` 判定を外し権限未付与でも候補に出す。
  - 同期実行 (`SyncJobsViewModel.runNow/setEnabled`) を `runCatching` で保護 (※無音クラッシュの**暫定**防御。根治は下の `0ce3e68`)。
- [x] **ZIP 解凍の高速化 + 展開進捗** (`3aa17e7`): 旧 `executeZipExtract` はどんな展開先でも「キャッシュへ全展開 → 保存先へ全コピー」と全データを 2 回書いており、ローカル展開が遅く、後半の「展開中…」が無進捗で長時間ハングして見えていた。
  - **ローカル展開先** (`FileUri.Local`) は zip4j で展開先フォルダへ**直接展開**し、コピー工程を廃止 (I/O 約半分、最後まで解凍バイト進捗が出続ける)。
  - **SAF/リモート展開先**はキャッシュ経由を維持しつつ、コピー工程に `measureTree` で測った総バイト+総ファイル数ベースの進捗 (`filesTransferred/Total`) を追加。
  - `finishZipExtract` / `measureTree` を切り出して成功・失敗の後始末を共通化。`copyTreeIntoStorage` に per-file コールバックを追加。
- [x] **Android 14 で同期がクラッシュする問題を根治** (`0ce3e68`): `SyncWorker` は WorkManager の `SystemForegroundService` 経由で `dataSync` 型の前景サービスとして起動するが、このサービスは WorkManager ライブラリ側マニフェストで `foregroundServiceType` が未宣言 (=0)。Android 14 (API 34) は `setForeground` で要求する型 (0x1) がマニフェスト宣言の部分集合でないと、メインスレッドの `SystemForegroundService.startForeground` が `IllegalArgumentException` で即クラッシュする (`0x1 is not a subset of 0x0`)。実際の `startForeground` は Worker の coroutine 外で起きるため `runCatching` では捕捉できなかった。→ app マニフェストで `SystemForegroundService` に `android:foregroundServiceType="dataSync"` を `tools:node="merge"` で上書き宣言 (必要権限 FOREGROUND_SERVICE / FOREGROUND_SERVICE_DATA_SYNC は §J で宣言済み)。マージ後マニフェストに型付与を確認。
- [x] **操作完了のシステム通知 + 設定で個別 ON/OFF** (`cbd2ab3`): 長時間操作が終わったとき、画面を離れていても気づけるように完了をシステム通知する。設定画面に「通知」セクションを新設し 3 トグルで個別制御 (**コピー・移動 / 解凍 / 同期**、既定すべて ON)。
  - core-data: `UserSettings` に `notifyOnFileOpComplete/Extract/Sync` を追加、`SettingsRepository` に DataStore キーと setter。
  - core-data: 完了通知の共通ユーティリティ `notify/OpCompletionNotifier` を新設 (チャンネル `foldex_op_done`、タップでアプリ起動、POST_NOTIFICATIONS 未許可でも例外を投げず無表示)。app (`FileBrowserViewModel` の貼付/解凍完了) と sync (`SyncWorker` の最終結果) の両方から呼ぶ。同期はリトライ予定時は通知しない (最終結果のみ)。
- [x] **バージョン** (`28945fc` + 本 docs): `0.1.0` (code 1) → `0.2.0` (code 2、完了通知の追加に合わせ minor) → `0.2.1` (code 3、本 docs 更新の区切りで patch)。

### 引き継ぎメモ (第5回追記)
- **未 push が増加**: §J の 7 + §K の 4 + §M の 5 コミット (`708102a` `3aa17e7` `0ce3e68` `cbd2ab3` `28945fc` + 本 docs + バージョン 0.2.1) がローカルのみ。→ 本 §M 完了後に push 予定。
- **実機状態 (Motorola, ja-JP, Android 14)**: release `com.zerotoship.foldex` を 0.2.1 で再インストール済み。同期クラッシュは解消を実機で確認。完了通知は POST_NOTIFICATIONS が許可なら表示 (起動時の許可ダイアログ導線は未実装 — 必要なら次回)。
- **P7 達成条件の残り**: 「同期途中再開」のみ (§K から変わらず)。

## N. 実機フィードバック対応 第6回 (2026-06-02〜06-03) — アイコン刷新 / 並び替え / ログ強化 / 定期同期の定刻化 / ナビ改善

実機 (Motorola moto g66j 5G, ja-JP, Android 14) で使い込んでの追加依頼を 6 リリース (0.2.2〜0.2.7) に分けて対応。
各コミットは本回新設の「コミット前にバージョンを上げる」ルールに従い versionName/Code を更新済み。

- [x] **CLAUDE ルール追加** (`d3cf078`): 実コード変更コミットの前に `app/build.gradle.kts` の versionName/Code を上げる運用を foldex `CLAUDE.md` §0 に追加 (docs のみの変更は対象外)。
- [x] **アイコン刷新 / 並び替え / ログ強化 / SMB パス統合** (`5ecfaad`, 0.2.2):
  - アプリアイコンを新画像で全密度 (mdpi〜xxxhdpi) の `ic_launcher` / `_round` / `_foreground` に再生成。adaptive icon 構成は維持。
  - **接続タブ / 同期タブのエントリーをドラッグで並び替え**可能に (既存の `sh.calvin.reorderable` を流用)。接続は既存 `sortOrder` 列を使用。同期は `SyncJobEntity.sortOrder` を新設し **Room v5→v6** (destructive migration)。`observeAll` を `sortOrder ASC, updatedAt DESC` に変更。編集保存で順序が消えないよう `SyncJobRepository.upsert` で既存 sortOrder を引き継ぐ。
  - 実行ログ画面に**コピーボタン** + **永久ログ** (ユーザーが手動作成した `.log` へ書き込みのたび累計追記)。保存先 URI は `UserSettings.permanentLogUri` (DataStore) に保持し `AppLogger` が @Volatile でキャッシュして追記。
  - SMB の「共有名」+「初期パス」を**「パス」1欄に統合**し未記入も許可 (先頭セグメントを share、残りを initialPath に分解。分解ロジックは元々 `saveSmb` にあったものを UI 側で集約)。
- [x] **時刻指定の定期同期をアプリ未起動でも実行** (`50e7544`, 0.2.3): 遅延付き WorkManager (`setInitialDelay`) は Doze 中に次のメンテ枠まで延期され「定刻に動かない / アプリを開くまでキュー中」になっていた。→ DAILY/WEEKLY/MONTHLY/DATETIME を **AlarmManager `setAndAllowWhileIdle`** (特別権限なし) で起こす方式に変更。
  - `SyncAlarmReceiver` (アラーム発火→即時 WorkManager 実行 + 繰り返しは次回再登録)、`SyncBootReceiver` (再起動後にアラーム再登録、`RECEIVE_BOOT_COMPLETED`) を新設。INTERVAL は従来の `PeriodicWorkRequest` のまま。
  - ユーザー選択: アラームは「Doze 対応・標準アラーム (setAndAllowWhileIdle、数分の誤差あり)」、Wi-Fi 必須の既定は **ON のまま**。
- [x] **保存後に一覧へ自動で戻る / 接続の長押し編集を廃止 / ローカルフォルダをブラウズ選択** (`39a204f`, 0.2.4):
  - 同期ジョブ保存後、サスペンドする `snackbar.showSnackbar` を待たず即 `onSaved()` で一覧へ戻る。
  - 接続リストの行の長押しを「編集」から「ドラッグ並び替え」に変更 (タップ=開く、編集は鉛筆ボタン)。並び替えのつもりで編集画面に飛ぶ問題を解消。
  - 同期のローカルフォルダ選択を固定候補からフォルダブラウザに変更 (ショートカットでジャンプ→サブフォルダを掘り下げ→「このフォルダを選択」。全ファイルアクセス権限前提で `java.io.File` 探索)。
- [x] **次回時刻表示 / 通知許可バナー / 詳細ログ / 永久ログ拡張子修正** (`353b9c0`, 0.2.5):
  - 同期一覧に「次回」実行予定時刻を表示 (`SyncLabels.nextRunLabel`。時刻指定は正確、INTERVAL は前回+間隔の「目安」)。
  - 同期画面に**通知 (POST_NOTIFICATIONS) 未許可時の案内バナー** (「許可する」=ランタイム要求 / 「設定を開く」=アプリ通知設定。ON_RESUME で再判定)。§M で「未実装」とした起動時許可導線の代替。
  - 詳細同期ログを日本語化 + 新規/更新を区別 (`アップロード(新規/更新)` / `ダウンロード(新規/更新)` / `削除(ローカル/リモート)` / `失敗(種類): パス — 理由`、サイズは KB/MB)。新規/更新は転送先に既存パスがあるかで判定。実行ごとに `── 同期を開始しました ──` の区切りも記録。
  - 永久ログ作成の MIME を `text/plain` → `application/octet-stream` に変更し、SAF が `.txt` を補って `foldex.log.txt` になる二重拡張子を回避。
- [x] **リモートの戻るで HOME の前に接続一覧へ** (`add1461`→`be2ac80`→`e5ab221`→`b13bff6`, 0.2.6→0.2.7→0.2.8→0.2.9): リモートを開いている最上位で戻ったら HOME ではなく接続一覧へ戻す。
  - 0.2.6: 「接続タブから開いたか」を `MainScreen` の `remember` フラグで持たせたが、**アクティビティ再生成で初期値に戻り**依然 HOME へ。
  - 0.2.7: フラグを `rememberSaveable` 化したが**それでも HOME へ** (実機で再現)。
  - 0.2.8: フラグを廃止し `MainScreen` 親 BackHandler で `browserViewModel.state` の根が Remote かを判定したが**まだ HOME へ**。→ 親 BackHandler がリモート最上位で発火していない (子の FileBrowser BackHandler が消費している) と判断。
  - 0.2.9: 判定と遷移を `FileBrowserScreen` の BackHandler 側へ移動 (`isRemoteSession` で有効化し最上位で `onExitRemote()`)。だが **browserViewModel が共有のため、リモートを開いた後はローカルのファイルタブで戻っても breadcrumbs 根が Remote のままで接続経由になる副作用**が発生。
  - **0.2.10 (`2048ef6`): 機能を全撤去しユーザー依頼で元の挙動に戻した**。`FileBrowserScreen`/`MainScreen` を 0.2.5 相当へ復元 (戻るは HOME に集約)。← 現行。
    - 教訓: ファイルブラウザは**単一の共有 `FileBrowserViewModel`** でローカルもリモートも表示するため、「いまリモートか」を戻り先判定に使うと、リモート閲覧後にローカルへ切り替えても状態が残り誤爆する。再挑戦するなら「接続から開いたセッションか」を**リモートを閉じた時点でクリアする**設計 (例: ローカルを開いたら接続フラグを必ず false に) か、接続用に別 NavBackStackEntry を積む設計が要る。

### 引き継ぎメモ (第6回追記)
- **push**: §M までの未 push 分を含め、本 §N の 11 コミット (`d3cf078` `5ecfaad` `50e7544` `39a204f` `353b9c0` `add1461` `be2ac80` `e5ab221` `b13bff6` + docs 2 件) を `origin/phase/P7-polish` へ push。
- **DB 破壊的マイグレーション注意**: §N で `sync_jobs` に列追加 → **Room v6**。本リポは `fallbackToDestructiveMigration(dropAllTables=true)` なので、0.2.2 以降への更新で**既存の接続/認証情報/同期ジョブ/サーバー設定が全消去**される (v4→v5 と同じ挙動)。実ユーザーのデータ保持が必要になったら正式マイグレーションへ切替を検討 (P8 候補)。
- **実機状態 (Motorola moto g66j 5G, ja-JP, Android 14)**: release `com.zerotoship.foldex` を 0.2.10 で再インストール済み。リモート→接続の戻り機能は撤去 (戻るは元どおり HOME)。
- **未解決・要実機確認**: ① AlarmManager 定期同期の発火 (Doze 下) は実機未確認。一部 OEM の省電力で殺される場合あり (電池最適化除外の案内は未実装)。② 永久ログは既存 `.log.txt` には影響しないため作り直しが必要。③ リモートの戻り先は 0.2.6〜0.2.8 で直らず、0.2.9 で FileBrowser 側処理に変更 — 実機での再確認待ち。もし 0.2.9 でもダメなら、リモート最上位で `state.canGoUp`/`breadcrumbs` が想定と異なる (根が Remote でない/サイズが1でない) 可能性を疑う。
- **作業ツリーの未追跡変更**: ルートの `foldex_icon.png` (未使用の旧アイコン元画像) が削除済みだが本セッションの commit には含めていない (削除者不明のため保留)。
- **P7 達成条件の残り**: 「同期途中再開」のみ (§K から変わらず)。

---

## 影響範囲・要確認まとめ

| # | 内容 | HANDOFF/PHASES との整合 | 要確認 |
|---|---|---|---|
| B-1 | 双方向同期を P7 へ | §8-B / PHASES P8 と矛盾（前倒し） | P7 で実装してよいか / P8 据え置きか |
| B-4 | 同期削除バックアップ・容量しきい値設定 | §8-G を拡張 | しきい値設定はジョブ単位 / グローバル / 両方？ バックグラウンド時の `ASK` 挙動 |
| C | AppBar 整理 | §12-B の元設計に近い | 表示モード切替をどこに残すか |
| D | 音声の内蔵プレーヤー | §10-G「外部のみ」と矛盾 | 簡易内蔵でよいか（ExoPlayer 依存追加の可否） |
| D | HTML 内蔵ビューア | §10 に項目なし（追加） | WebView 使用の可否（JS 既定オフ前提） |
| D | 拡張子別 既定アプリ設定 | §10-J を拡張 | 粒度は拡張子単位 / カテゴリ単位 / 両方？ |
| E | ゴミ箱 | §11-H「P7（任意）」を必須化・具体化 | リモートでゴミ箱不可時の挙動 / 自動削除日数のデフォルト |
| Room スキーマ | SyncJob 拡張・OpenWith・Trash メタ | マイグレーション必要 | — |

> このメモの各項目が確定したら、`FOLDEX-HANDOFF.md` の該当章と `docs/PHASES.md` の P7（必要なら P8）チェックリストに反映し、ここはリンクだけ残す。
