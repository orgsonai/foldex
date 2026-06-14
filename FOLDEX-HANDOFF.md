# Foldex — プロジェクト引き継ぎ資料

## 1. プロジェクト概要

| 項目 | 内容 |
|------|------|
| アプリ名 | **Foldex** |
| 読み | フォルデックス |
| 語源 | folder + ex (= index) |
| 意味付け | フォルダにインデックスを張って、ローカル/リモートを横断的にアクセスできるファイラー |
| コンセプト | 広告なし・ローカルファースト・リモート全部入りのフォルダ管理アプリ |
| ターゲットOS | Android (iOS予定なし) |
| 所属プロジェクト | Zero to Ship |
| 開発言語 | Kotlin + Jetpack Compose |
| パッケージ名 | `com.zerotoship.foldex` |
| ライセンス | MIT (確定) |

### コンセプト補足

ファイルマネージャ + リモートストレージクライアント (SMB/SFTP/FTP/WebDAV) + 自機SFTP/FTPサーバー + 定期同期エンジンを統合した、orgson 自身が日常使いするためのファイラー。
広告ブロック対応・プライバシー重視・既存OSSファイラー (Material Files, Solid Explorer, FolderSync) の良いところを取り、不要な機能 (クラウド連携の押し付け、課金圧、トラッキング等) を排除する。

---

## 2. 機能要件

### 2-A. ローカルファイル管理
- 内部ストレージ・SDカード・USB-OTGの閲覧/操作
- コピー / 移動 / 削除 / リネーム / 新規作成
- 検索 / ソート / フィルタ
- `Android/data` `Android/obb` も SAF経由でアクセス可能

### 2-B. リモートストレージ (クライアント)
- **SMB/CIFS** (smbj使用、SMB2/3対応)
- **SFTP** (sshj使用)
- **FTP / FTPS** (Apache Commons Net使用)
- **WebDAV** (Sardine-Android使用、メンテ状況次第で代替検討)

### 2-C. 自機サーバー機能
- **SFTPサーバー** (Apache MINA SSHD)
- **FTPサーバー** (Apache FtpServer)
- ForegroundService で稼働、通知から停止可能
- 公開フォルダ・認証方式・待受ポートをUIで設定

### 2-D. 定期同期エンジン
- ローカル ↔ リモートの片方向同期 (P6で実装)
- 双方向同期は P7 で対応 (`SyncDirection.BIDIRECTIONAL`)
- glob パターンによる include/exclude フィルタ
- 競合解決ポリシー: NEWER_WINS / LOCAL_WINS / REMOTE_WINS / KEEP_BOTH / SKIP
- WorkManager による定期実行 (15分以上の間隔)
- Wi-Fi限定・充電中限定などの実行条件指定

---

## 3. 技術スタック

| 項目 | 技術 | 選定理由 |
|---|---|---|
| 言語 | Kotlin | Android純正、ネイティブAPI完全アクセス |
| UI | Jetpack Compose | 宣言的UI、Flutter経験者にも親和的 |
| DI | Hilt | Google公式、Composeとの統合最強 |
| ナビゲーション | Navigation Compose (文字列ルーティング) | 標準・無難 |
| データベース | Room | 標準・実績 |
| 設定永続化 | DataStore (Preferences) | SharedPreferencesの後継 |
| 非同期 | Kotlin Coroutines + Flow | Kotlin標準 |
| バックグラウンド | WorkManager | OSとの整合性、定期実行の標準 |
| 時刻型 | kotlinx-datetime (`Instant`) | 型安全 |
| SMB | smbj (`com.hierynomus:smbj:0.14.0`) | SMB2/3対応、Apache 2.0 |
| SFTP クライアント | sshj (`com.hierynomus:sshj:0.39.0`) | 実績豊富 |
| FTP | Apache Commons Net (`commons-net:3.11.1`) | 標準 |
| WebDAV | Sardine-Android (`com.thegrizzlylabs:sardine-android:0.8`) | 採用候補、要メンテ確認 |
| SFTPサーバー | Apache MINA SSHD (`org.apache.sshd:sshd-sftp:2.13.2`) | Java製の定番 |
| FTPサーバー | Apache FtpServer (`org.apache.ftpserver:ftpserver-core:1.2.0`) | 標準 |
| パスワードハッシュ | Argon2id (`de.mkammerer:argon2-jvm:2.11`) | 現代的、推奨アルゴリズム |
| 画像読み込み・サムネ | Coil 3 (`io.coil-kt.coil3:coil-compose:3.0.4`) | Compose統合、Coroutineネイティブ |
| Markdownレンダリング | Markwon (`io.noties.markwon:core`) | Android向け定番、表/タスクリスト対応 |
| エンコーディング判定 | juniversalchardet | SJIS/EUC-JP/UTF-8 自動判定 |
| 圧縮 (汎用) | Apache Commons Compress (`org.apache.commons:commons-compress:1.27.1`) | tar/7z/zip 対応 |
| 圧縮 (XZ用) | XZ for Java (`org.tukaani:xz`) | tar.xz / 7z で必要 |
| 圧縮 (パスワードZIP) | zip4j (`net.lingala.zip4j:zip4j:2.11.5`) | パスワード付きZIP対応 |
| 圧縮 (RAR読み) | junrar (`com.github.junrar:junrar`) | 読み専用、RAR対応 |

---

## 4. アーキテクチャ

### 4-A. モジュール構成 (12モジュール)

```
foldex/
├── settings.gradle.kts
├── build.gradle.kts
├── gradle/libs.versions.toml         # Version Catalog
│
├── app/                              # アプリ本体、Activity、Application、Hilt設定、画面
│
├── core/
│   ├── core-common/                  # Util、独自Result<T,E>型、Coroutine抽象 (純Kotlin)
│   ├── core-model/                   # FileNode, Connection, StorageProvider IF (純Kotlin)
│   └── core-data/                    # Room (DB) + DataStore + AndroidKeyStore暗号化
│
├── storage/
│   ├── storage-local/                # MANAGE_EXTERNAL_STORAGE + SAF を内部で振り分け
│   ├── storage-smb/                  # smbj 実装
│   ├── storage-sftp/                 # sshj 実装
│   ├── storage-ftp/                  # Apache Commons Net 実装
│   └── storage-webdav/               # Sardine-Android 実装
│
├── server/                           # 自機SFTPサーバー + FTPサーバー (1モジュール統合)
│
└── sync/                             # 同期エンジン + WorkManager連携 (1モジュール統合)
```

### 4-B. 依存方向のルール

```
            ┌─────────┐
            │   app   │ ← 全部知ってる、Hiltで束ねる
            └────┬────┘
                 │
   ┌─────────────┼─────────────┬──────────┐
   ▼             ▼             ▼          ▼
storage-*     server         sync     (各機能モジュール)
   │             │             │
   └─────────────┴─────────────┘
                 ▼
           ┌──────────────────────┐
           │ core-data            │
           └──────────┬───────────┘
                      ▼
           ┌──────────────────────┐
           │ core-model           │
           └──────────┬───────────┘
                      ▼
           ┌──────────────────────┐
           │ core-common          │ (純Kotlin、何にも依存しない)
           └──────────────────────┘
```

ルール:
- 依存は上から下のみ
- 横方向 (storage-smb と storage-sftp) は依存しない
- `app` のみ全部を束ねる
- 各 `storage-*` 実装はモジュールごと外せる (SMB対応をやめたい等)

### 4-C. 名前空間

```
com.zerotoship.foldex                  # app
com.zerotoship.foldex.core.common      # core-common
com.zerotoship.foldex.core.model       # core-model
com.zerotoship.foldex.core.data        # core-data
com.zerotoship.foldex.storage.local    # storage-local
com.zerotoship.foldex.storage.smb      # storage-smb
com.zerotoship.foldex.storage.sftp     # storage-sftp
com.zerotoship.foldex.storage.ftp      # storage-ftp
com.zerotoship.foldex.storage.webdav   # storage-webdav
com.zerotoship.foldex.server           # server
com.zerotoship.foldex.sync             # sync
```

---

## 5. ストレージ戦略 (ハイブリッド方式)

### 5-A. アクセス手段の振り分け

| 領域 | アクセス手段 |
|---|---|
| 内部ストレージ・SDカード本体 | **MANAGE_EXTERNAL_STORAGE** (権限ありの場合) / **SAF** (なしの場合) |
| `Android/data`, `Android/obb` | **必ずSAF** (権限の有無に関わらず) |
| アプリ自身のプライベート領域 | `java.io.File` で直接 (権限不要) |
| USB-OTG ストレージ | **SAF** (MANAGE_EXTERNAL_STORAGE では触れない) |

### 5-B. Google Play 審査について

ファイルマネージャは `MANAGE_EXTERNAL_STORAGE` の認められた用途。前例多数 (Material Files, Solid Explorer, X-plore等)。
ただし審査リスクを完全に避けるため、初期は F-Droid または APK直配布を想定。Play配布時は権限要求のタイミングを「全領域モード」のオプトインとし、SAFモードでも基本機能が動くようにする。

### 5-C. ストレージ抽象層 (`StorageProvider` インターフェース)

```kotlin
interface StorageProvider {
    fun canHandle(uri: FileUri): Boolean
    suspend fun connect(): Result<Unit, StorageError>
    suspend fun disconnect()
    suspend fun stat(uri: FileUri): Result<FileNode, StorageError>
    fun list(uri: FileUri, options: ListOptions = ListOptions()): Flow<FileNode>
    suspend fun openInput(uri: FileUri): Result<InputStream, StorageError>
    suspend fun openOutput(uri: FileUri, mode: WriteMode): Result<OutputStream, StorageError>
    suspend fun mkdir(uri: FileUri, recursive: Boolean = false): Result<Unit, StorageError>
    suspend fun delete(uri: FileUri, recursive: Boolean = false): Result<Unit, StorageError>
    suspend fun rename(from: FileUri, to: FileUri): Result<Unit, StorageError>
    suspend fun copyWithin(from: FileUri, to: FileUri, observer: ProgressObserver? = null): Result<Unit, StorageError>
    suspend fun moveWithin(from: FileUri, to: FileUri, observer: ProgressObserver? = null): Result<Unit, StorageError>
}
```

---

## 6. データモデル (`core-model`)

### 6-A. ファイル系

```kotlin
data class FileNode(
    val uri: FileUri,
    val name: String,
    val type: NodeType,                 // FILE | DIRECTORY | SYMLINK | UNKNOWN
    val size: Long,
    val lastModified: Instant?,         // kotlinx-datetime
    val permissions: Permissions,
    val mimeType: String? = null,
    val isHidden: Boolean = false
)

sealed class FileUri {
    data class Local(val absolutePath: String) : FileUri()
    data class Saf(val documentUri: String) : FileUri()
    data class Remote(val protocol: Protocol, val connectionId: String, val path: String) : FileUri()
}

enum class NodeType { FILE, DIRECTORY, SYMLINK, UNKNOWN }
```

シンボリックリンクは `NodeType.SYMLINK` として残し、`ListOptions` で follow するか否かを制御。

### 6-B. 接続系 (sealed class でプロトコル別)

```kotlin
sealed class Connection {
    abstract val id: String
    abstract val name: String
    abstract val protocol: Protocol
    abstract val host: String
    abstract val port: Int
    abstract val username: String?
    abstract val authMethod: AuthMethod

    data class Smb(...) : Connection()      // share, domain
    data class Sftp(...) : Connection()     // hostKeyFingerprint
    data class Ftp(...) : Connection()      // useTls, passiveMode
    data class WebDav(...) : Connection()   // basePath, useHttps
}

enum class Protocol(val scheme: String, val defaultPort: Int) {
    SMB("smb", 445), SFTP("sftp", 22), FTP("ftp", 21), WEBDAV("webdav", 443)
}

sealed class Credential {
    data class Password(val secret: ByteArray) : Credential()
    data class SshPrivateKey(val keyData: ByteArray, val passphrase: ByteArray?) : Credential()
    object Anonymous : Credential()
}
```

機密データは `String` ではなく `ByteArray` で持つ (JVMの内部キャッシュ・ヒープダンプ対策)。

### 6-C. 操作系

```kotlin
sealed class StorageError(open val message: String, open val cause: Throwable? = null) {
    data class NotConnected(...) : StorageError(...)
    data class AuthenticationFailed(...) : StorageError(...)
    data class HostUnreachable(...) : StorageError(...)
    data class NotFound(val uri: FileUri) : StorageError(...)
    data class AlreadyExists(val uri: FileUri) : StorageError(...)
    data class PermissionDenied(val uri: FileUri) : StorageError(...)
    data class IoError(...) : StorageError(...)
    data class Cancelled(...) : StorageError(...)
    data class ProtocolError(val protocol: Protocol, ...) : StorageError(...)
    data class Unknown(...) : StorageError(...)
}
```

戻り値は **独自定義の `Result<T, E>`** を使用 (Kotlin標準 `Result<T>` ではなく `core-common` に定義)。

---

## 7. 接続情報の保存設計

### 7-A. 二層構造

公開情報と機密情報を分離:

```
┌──────────────────────────────────────────────┐
│ Room DB (core-data)                          │
│  ├─ connections (公開情報のみ)                │
│  │     id, name, protocol, host, port, ...   │
│  │     credentialRef ──┐                     │
│  └────────────────────────┼───────────────────┘
│                           │ (UUIDで参照)        │
│  ┌────────────────────────▼───────────────────┐
│  │ encrypted_credentials                      │
│  │     credentialRef, ciphertext, iv,         │
│  │     keyAlias, ...                          │
│  └────────────────────────────────────────────┘
└──────────────────────────────────────────────┘
                  │
                  │ AES-GCM 復号
                  ▼
┌──────────────────────────────────────────────┐
│ AndroidKeyStore                              │
│  └─ "folderx_master_key_v1" (AES-256)        │
└──────────────────────────────────────────────┘
```

### 7-B. スキーマ

```kotlin
@Entity(tableName = "connections")
data class ConnectionEntity(
    @PrimaryKey val id: String,
    val name: String,
    val protocol: String,                // "smb" | "sftp" | "ftp" | "webdav"
    val host: String,
    val port: Int,
    val username: String?,
    val authMethod: String,              // "password" | "publickey" | "anonymous"

    // プロトコル固有 (使わないものはnull)
    val smbShare: String?,
    val smbDomain: String?,
    val sftpHostKeyFingerprint: String?,
    val webdavBasePath: String?,
    val ftpUseTls: Boolean = false,
    val ftpPassiveMode: Boolean = true,

    val credentialRef: String?,          // -> EncryptedCredentialEntity.id

    val charset: String = "UTF-8",
    val createdAt: Long,
    val updatedAt: Long,
    val lastConnectedAt: Long? = null,
    val sortOrder: Int = 0
)

@Entity(tableName = "encrypted_credentials")
data class EncryptedCredentialEntity(
    @PrimaryKey val id: String,
    val credentialType: String,          // "password" | "ssh_private_key"
    val ciphertext: ByteArray,           // AES-GCM
    val iv: ByteArray,                   // 96bit
    val keyAlias: String,                // 鍵識別 (将来の鍵切替/生体認証対応用)
    val createdAt: Long,
    val updatedAt: Long
)
```

### 7-C. 暗号化方式

- **アルゴリズム**: AES-256-GCM
- **鍵管理**: AndroidKeyStore (端末から出ない)
- **IV**: レコードごとにランダム生成 (96bit、絶対に再利用しない)
- **`keyAlias` カラム**: 将来の鍵ローテーション・生体認証ゲート対応のため、最初から保持

### 7-D. 生体認証ゲート (将来オプション)

スキーマは現時点で対応可能 (`keyAlias` で識別)。コード側の実装は後回し。

```
AndroidKeyStore
├── folderx_master_key_v1            (UserAuth不要、デフォルト)
└── folderx_master_key_protected_v1  (UserAuth必須、保護モード) ← 後で生成
```

レコード単位でどちらの鍵で暗号化されたか判別可能。

### 7-E. SSH秘密鍵の扱い

- DBに **BLOB として暗号化保存** (案1採用)
- 数KB程度のサイズなのでDBで問題なし
- ファイル方式 (DBはパスのみ、ファイル本体は別保存) は採用しない

### 7-F. バックアップ・端末移行

- `android:allowBackup="false"` セット
- `data_extraction_rules.xml` で全領域を exclude (Android 12+対応)
- エクスポート/インポート機能は将来の検討事項 (今は実装しない)

---

## 8. 同期エンジン (`sync`)

### 8-A. 全体フロー

```
[ユーザーが定期同期を作成]
    ↓
SyncJobEntity を core-data に保存
    ↓
SyncScheduler が WorkManager に登録
    ↓
[実行タイミング到来]
    ↓
SyncWorker (CoroutineWorker) 起動
    ↓
SyncEngine.run(jobId)
    ├─ DiffEngine.scan()
    │   ├─ ローカル列挙 (StorageProvider経由)
    │   ├─ リモート列挙 (StorageProvider経由)
    │   ├─ Filter適用 (glob)
    │   ├─ SyncStateRepository から前回状態を取得
    │   └─ SyncAction のリストを生成
    │
    ├─ ConflictResolver.resolve()
    │   └─ Conflict を NEWER_WINS等で解決
    │
    ├─ Executor.execute(actions)
    │   ├─ 並列度3でアクション実行
    │   ├─ 各アクション完了ごとに state 更新
    │   └─ ProgressTracker に進捗通知
    │
    └─ Finalize: lastRunAt 更新、エラーログ記録
```

### 8-B. 同期方向

P6 (初期実装) では片方向のみ:

```
Local ──→ Remote   片方向 (アップロード/ミラー)
Local ←── Remote   片方向 (ダウンロード/ミラー)
```

双方向同期 (bisync) は P7 で実装済み (`SyncDirection.BIDIRECTIONAL`)。前回同期状態 (state DB) から各側の変化を判定する。

### 8-C. 差分検出ロジック

- **同一性判定**: パス + サイズ + mtime
- **mtime精度**: 秒単位に丸める (FTPの精度に合わせる)、UTC正規化
- **ハッシュは使わない** (リモートプロトコルでハッシュが取れないことが多いため)

### 8-D. SyncAction (実行計画)

```kotlin
sealed class SyncAction {
    abstract val path: String
    data class Upload(...) : SyncAction()
    data class Download(...) : SyncAction()
    data class DeleteLocal(...) : SyncAction()
    data class DeleteRemote(...) : SyncAction()
    data class Conflict(..., val resolution: ConflictResolution) : SyncAction()
    data class Skip(..., val reason: SkipReason) : SyncAction()
}
```

### 8-E. state DB (前回同期状態)

```kotlin
@Entity(tableName = "sync_states", primaryKeys = ["jobId", "path"])
data class SyncStateEntity(
    val jobId: String,
    val path: String,
    val localSize: Long?,
    val localMtime: Long?,
    val remoteSize: Long?,
    val remoteMtime: Long?,
    val lastSyncedAt: Long
)
```

`core-data` モジュール内に統合。Room の同じDB内に置く。

state DB がないと「ローカルにあって、リモートにない」が「追加された」のか「削除された」のか判別不能になり、同期事故が起きる。

### 8-F. 競合解決ポリシー

```kotlin
enum class ConflictPolicy {
    NEWER_WINS,    // mtime が新しい方を採用 (デフォルト推奨)
    LOCAL_WINS,    // ローカル優先
    REMOTE_WINS,   // リモート優先
    KEEP_BOTH,     // 両方残す (片方をリネーム)
    SKIP           // スキップしてユーザーに通知
}
```

`MANUAL` モードは実装しない (KEEP_BOTH で代替)。

`KEEP_BOTH` のリネーム規則: `name (conflict YYYY-MM-DD HH-MM-SS).ext` (Dropbox/Syncthing流)。

### 8-G. 削除ポリシー

ジョブ単位で `deleteEnabled` を持ち、**デフォルトは false** (削除しない)。
事故防止のため、ユーザーが明示的にオンにしないと削除アクションは生成されない。

### 8-H. フィルタ表現

- **glob パターンのみ** (`*.jpg`, `**/.git/**`, `*.tmp`)
- 正規表現はサポートしない (個人ユーザー向けには glob で十分)
- include/exclude 両方サポート

### 8-I. 並列度・実行制御

| 設定 | デフォルト | 範囲 |
|---|---|---|
| 並列度 | 3 | 1〜8 |
| リトライ回数 | 3 | 0〜10 |
| 中断時の挙動 | やり直し | (将来: 途中再開) |

途中再開 (SFTP `RESTART`、SMB オフセット指定、HTTP `Range`) は P7以降。

### 8-J. SyncJob のスキーマ

```kotlin
@Entity(tableName = "sync_jobs")
data class SyncJobEntity(
    @PrimaryKey val id: String,
    val name: String,
    val enabled: Boolean,

    val localUri: String,                        // FileUri.toStorageString()
    val remoteUri: String,
    val direction: String,                       // "to_remote" | "to_local"

    val conflictPolicy: String,                  // ConflictPolicy.name
    val includePatterns: String,                 // JSON
    val excludePatterns: String,                 // JSON
    val maxFileSize: Long?,

    val intervalMinutes: Int,                    // 0 = 手動のみ
    val requiresWifi: Boolean,
    val requiresCharging: Boolean,
    val requiresBatteryNotLow: Boolean,

    val deleteEnabled: Boolean = false,          // 削除アクションのオン/オフ
    val parallelism: Int = 3,
    val retryCount: Int = 3,

    val createdAt: Long,
    val updatedAt: Long,
    val lastRunAt: Long?,
    val lastRunResult: String?
)
```

### 8-K. WorkManager の制約

- **最小実行間隔: 15分** (Android標準制約)
- 制約条件: `NetworkType.UNMETERED` (Wi-Fi限定) / `requiresCharging` / `requiresBatteryNotLow` / `requiresDeviceIdle`
- 単発実行: `OneTimeWorkRequest` (手動同期用)

### 8-L. モジュール内構造

```
sync/src/main/kotlin/com/zerotoship/foldex/sync/
├── engine/
│   ├── SyncEngine.kt              # ファサード (UIから呼ばれる)
│   ├── DiffEngine.kt              # 差分検出
│   ├── ConflictResolver.kt        # 競合解決
│   ├── Executor.kt                # 転送実行
│   ├── Filter.kt                  # glob パターンマッチ
│   └── ProgressTracker.kt         # 進捗集計
├── scheduler/
│   ├── SyncScheduler.kt           # WorkManager ラッパー
│   ├── SyncWorker.kt              # CoroutineWorker 実装
│   └── ConstraintBuilder.kt       # 制約条件の組み立て
└── model/
    ├── SyncJob.kt
    ├── SyncAction.kt
    ├── SyncProgress.kt
    ├── ConflictPolicy.kt
    └── SyncResult.kt
```

state は `core-data` 内に保存 (上記 8-E)、Repository 経由でアクセス。

---

## 9. 自機サーバー機能 (`server`)

ローカル端末をSFTP/FTPサーバーとして公開し、他のPC・スマホからアクセス可能にする機能。

### 9-A. 全体方針

- **SFTPとFTPは別設定として独立起動** (`ServerConfigEntity` を別レコードで持つ)
- 1サーバーにつき**1つの公開ルート** (将来の複数マウント拡張余地を残す)
- セキュリティ最優先: **意図しない公開を防ぐ** ことを設計の中心に置く

### 9-B. ServerConfigEntity (サーバー設定)

```kotlin
@Entity(tableName = "server_configs")
data class ServerConfigEntity(
    @PrimaryKey val id: String,
    val type: String,                    // "sftp" | "ftp"
    val name: String,
    val enabled: Boolean,                // 自動起動するか (デフォルトfalse)

    val port: Int,                       // SFTP=2022, FTP=2121 (非特権ポート)
    val bindAddress: String,             // "wifi_only" | "0.0.0.0" | 特定IP
    val wifiOnlyMode: Boolean = true,    // Wi-Fi接続時のみ稼働 (デフォルトtrue)

    // 公開設定
    val rootUri: String,                 // FileUri.toStorageString()
    val readOnly: Boolean = false,

    // 認証
    val authMode: String,                // "anonymous" | "password" | "publickey" | "password_or_publickey"
    val username: String?,
    val passwordHashRef: String?,        // -> EncryptedCredentialEntity (Argon2id ハッシュ)
    val authorizedKeysRef: String?,      // -> EncryptedCredentialEntity (公開鍵リスト JSON)

    // SFTP固有
    val hostKeyRef: String?,             // -> EncryptedCredentialEntity (Ed25519秘密鍵)

    // FTP固有
    val ftpsEnabled: Boolean = false,    // Explicit FTPS
    val ftpsTlsCertRef: String?,         // -> EncryptedCredentialEntity (自己署名証明書)

    // ライフサイクル
    val autoStartOnAppLaunch: Boolean = false,    // デフォルトfalse
    val autoStartOnBoot: Boolean = false,         // デフォルトfalse (BootReceiver用)

    val createdAt: Long,
    val updatedAt: Long,
    val lastStartedAt: Long?
)
```

### 9-C. 認証方式

#### SFTPサーバー
- **匿名** (デフォルト無効、有効化時に警告)
- **パスワード認証** (デフォルト)
- **公開鍵認証** (OpenSSH形式の `authorized_keys` 互換)
- **両方併用** (パスワードまたは公開鍵)

#### FTPサーバー
- **匿名**
- **パスワード認証** (デフォルト)
- 公開鍵認証はプロトコル仕様上なし

#### パスワードハッシュ
- **Argon2id** (`de.mkammerer:argon2-jvm`)
- 照合のみで復号不要のため、AndroidKeyStore暗号化は不要 (ハッシュ値そのものを保存)
- ただしDBの一貫性のため `EncryptedCredentialEntity` に格納する形を維持

#### 公開鍵リスト (authorized_keys)
- OpenSSH形式の公開鍵を複数登録可能
- JSONでシリアライズし `EncryptedCredentialEntity` に保存
- UI: テキスト貼り付け / ファイル選択 / 将来QRコード経由

### 9-D. ホスト鍵 (SFTP)

- **初回起動時に Ed25519 鍵を自動生成**
- 秘密鍵は `EncryptedCredentialEntity` に暗号化保存
- フィンガープリント (SHA-256) を**サーバー設定画面で常時表示**
- 再生成機能あり (鍵漏洩時の対応)
- 将来: QRコードでのフィンガープリント表示・読み取り

### 9-E. ネットワーク設計

#### bindAddress の選択肢
| 値 | 動作 |
|---|---|
| `wifi_only` | Wi-Fi接続時のみそのIPで待ち受け、Wi-Fi切断で自動停止 |
| `0.0.0.0` | 全インターフェース (Wi-Fi/モバイル/テザリング) |
| 特定IP | 指定IPのみで待ち受け |

**デフォルトは `wifi_only`** (`wifiOnlyMode = true`)。意図しないモバイル網経由の公開を防ぐ。

#### 警告表示
サーバー起動時、状況に応じて以下の警告を表示:
- 公衆Wi-Fi接続中 → 「公衆Wi-Fiでの起動は推奨しません」
- モバイル網のみ → 「モバイル網経由で外部から到達する可能性があります」
- 平文FTP起動時 → 「通信が暗号化されません、SFTP/FTPSを推奨」

#### ポート番号
- デフォルト: SFTP=**2022**、FTP=**2121** (Android非ルートで1024未満は使えない)
- ユーザーが1024〜65535の範囲で変更可能

### 9-F. ライフサイクル

#### 起動トリガー
1. **UIから手動** (起動ボタン)
2. **アプリ起動時の自動起動** (デフォルトオフ、設定でオン可)
3. **端末再起動後の復帰** (デフォルトオフ、`BootReceiver`は設定オン時のみ動作)

#### 停止トリガー
1. UIから「停止」
2. ForegroundService通知の停止アクション
3. **Wi-Fi切断** (Wi-Fi限定モード時、自動停止)
4. アプリForce Stop (OS判断)

#### ForegroundService
```xml
<service
    android:name=".ServerService"
    android:foregroundServiceType="dataSync"
    android:exported="false" />
```

`dataSync` を指定 (Android 14+ 必須)。`START_STICKY` で OS による再起動も試みる。

### 9-G. 通知UI

```
┌────────────────────────────────────────┐
│ 🔵 Foldex                              │
│ サーバー稼働中                          │
│                                        │
│ SFTP: 192.168.1.20:2022                │
│ FTP:  192.168.1.20:2121                │
│                                        │
│ [停止]  [すべて停止]  [設定]            │
└────────────────────────────────────────┘
```

- IP:ポートを直接表示 (別端末で見ながら入力可能)
- アクションボタンで即時停止可能
- タップでサーバー画面に遷移
- `IMPORTANCE_LOW` で音・バイブなし、`setShowBadge(false)`

### 9-H. 接続ログ

```kotlin
@Entity(tableName = "server_logs")
data class ServerLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val configId: String,
    val event: String,             // "client_connected" | "auth_success" | "auth_failed" | "client_disconnected"
    val clientAddress: String,
    val username: String?,
    val timestamp: Long,
    val details: String?
)
```

- **デフォルトオン** (不正アクセス検知のため)
- UIでログ閲覧可能
- 認証失敗の連続記録は不正アクセスの兆候として注目
- レート制限・自動ブロックは P7以降の機能

### 9-I. セキュリティ補足

#### FTP (平文) の扱い
- 起動時に平文通信である旨を必ず警告
- FTPS (Explicit TLS) を推奨表示
- 削除はしない (古いNAS機器との互換性のため)

#### FTPS (Explicit TLS)
- **Apache FtpServer 標準対応**
- 自己署名証明書を初回起動時に自動生成、暗号化保存
- 証明書再生成機能あり

### 9-J. モジュール内構造

```
server/src/main/kotlin/com/zerotoship/foldex/server/
├── ServerService.kt                  # ForegroundService エントリ
├── ServerNotificationFactory.kt      # 通知UI構築
├── BootReceiver.kt                   # 端末再起動時の自動復帰 (設定オン時のみ)
├── NetworkBindingResolver.kt         # bindAddress 解決 (Wi-Fi IP取得等)
│
├── sftp/
│   ├── SftpServerManager.kt          # MINA SSHD ラッパー
│   ├── SftpAuthProvider.kt           # パスワード/公開鍵認証
│   ├── SftpFileSystem.kt             # StorageProvider → MINA FileSystem ブリッジ
│   └── HostKeyManager.kt             # ホスト鍵の生成・管理
│
├── ftp/
│   ├── FtpServerManager.kt           # Apache FtpServer ラッパー
│   ├── FtpUserManager.kt             # ユーザー認証
│   ├── FtpFileSystem.kt              # StorageProvider → Apache FileSystem ブリッジ
│   └── FtpsCertManager.kt            # FTPS用TLS証明書管理
│
└── log/
    └── ServerLogger.kt               # 接続ログの記録
```

`SftpFileSystem` / `FtpFileSystem` が要のクラス: それぞれのライブラリの FileSystem抽象を実装し、内部で Foldex の `StorageProvider` を呼び出す。これによりサーバー機能がローカルストレージのSAF/Fileの差を吸収。

### 9-K. 確定事項一覧

| 項目 | 決定 |
|---|---|
| 起動単位 | SFTP/FTP独立、複数同時起動可 |
| デフォルト認証 | パスワード認証 |
| パスワードハッシュ | Argon2id |
| Wi-Fi限定モード | デフォルトオン |
| アプリ起動時自動起動 | デフォルトオフ |
| 端末再起動後復帰 | デフォルトオフ (設定オン時のみ) |
| 接続ログ | デフォルトオン |
| デフォルトポート | SFTP:2022, FTP:2121 |
| FTP平文警告 | 必須表示 |
| FTPS | Explicit TLS、自己署名証明書自動生成 |
| ホスト鍵 | Ed25519、初回自動生成、再生成可 |
| レート制限 | P7以降 (初期はログ記録のみ) |

---

## 10. ファイル種別の扱い

ファイルの種類ごとに、内蔵対応するか外部アプリに任せるかの設計。Foldex のコンセプトに従い、**ファイラーとしての一次操作で必須なものは内蔵、それ以外は外部呼び出し** という方針。

### 10-A. 全体方針

- **内蔵すべき**: 簡易プレビュー、テキスト閲覧・編集、圧縮展開・作成
- **外部に任せる**: 画像編集、動画/音声再生、オフィスドキュメント編集

膨らみすぎないことを最優先。広告まみれZIPアプリへの対抗としての**圧縮ファイル機能**は差別化要素として丁寧に作る。

### 10-B. カテゴリ別の対応方針

| カテゴリ | 例 | アプリ内対応 | 外部呼び出し |
|---|---|---|---|
| 画像 | jpg, png, webp, gif, heic, bmp | サムネ + 簡易ビューア (ズーム/回転/EXIF表示) | 編集は外部 |
| テキスト | txt, log, json, xml, csv, source | 閲覧 + 簡易編集 (検索置換、アンドゥ) | 高機能エディタへの「で開く」 |
| Markdown | md | ソース/プレビュー切替 | — |
| PDF | pdf | アイコン + 外部呼び出し (P7以降に内蔵検討) | 専用アプリ |
| 音声 | mp3, m4a, flac, ogg, wav, opus | サムネ (アルバムアート) + メタデータ | 再生は外部 |
| 動画 | mp4, mkv, webm, mov | サムネ (1フレーム) + メタデータ | 再生は外部 |
| 圧縮 | zip, tar.gz, 7z, rar | 中身プレビュー + 展開 + 作成 | — |
| オフィス | docx, xlsx, pptx, odt | アイコンのみ | 編集は外部 |
| その他 | apk, exe, dmg, iso, 不明 | アイコン | 必要に応じて外部 |

### 10-C. 画像

#### サムネイル
- **Coil 3** (`io.coil-kt.coil3:coil-compose:3.0.4`) を採用
- メモリキャッシュ (LruCache、50枚程度) + ディスクキャッシュ (DiskLruCache) の2層
- キー: `uri.toStorageString() + ":" + size + ":" + lastModified` (lastModified を含めることで自動無効化)

#### リモートサムネ
- 初期版: 完全ダウンロード、ただし**10MB制限**でフォールバック (アイコンのみ)
- P7以降: HTTP Range / SFTP オフセット読みで部分ダウンロード対応検討

#### 簡易ビューア
- ズーム (ピンチ・ダブルタップ)
- 回転 (90度単位)
- 隣の画像へスワイプ
- EXIF表示 (撮影日時、機種、GPS)
- 実装: Coil + `Modifier.transformable` (Compose標準)

#### EXIF削除機能 (P7以降)
- `androidx.exifinterface` で実装
- 設定でオプション提供、**デフォルトオフ**
- 共有/コピー時にGPS座標等を自動削除する設定

### 10-D. テキスト

#### エンコーディング判定
- **juniversalchardet** (`com.googlecode.juniversalchardet:juniversalchardet`) で自動判定
- SJIS/EUC-JP/UTF-8 (BOM付き/なし) 対応
- ユーザーが手動で切替可能 (上部メニュー)

#### ビューア・エディタ
- 等幅フォント (デフォルト)
- 行番号表示 (オプション)
- 折り返し / 折り返しなし切替
- 簡易検索 + 置換
- アンドゥ・リドゥ
- **シンタックスハイライトはなし** (コードエディタを目指さない方針)

#### 編集モード
- デフォルト閲覧、明示的に編集ON
- 変更検知 → 戻る時に保存確認

#### サイズ制限
- 1MB以下: 普通に開く
- 1〜10MB: 警告表示
- 10MB以上: 開けません、外部エディタ推奨 (将来: ヘキサビューア検討)

### 10-E. Markdown

- **Markwon** (Java製、Android向け定番) で内蔵レンダリング
- 表・チェックボックス・コードブロック対応
- ソース表示とプレビュー表示の切替
- portal.html での md 使用経験を活かす

### 10-F. PDF

- 初期版: 外部呼び出しのみ (`Intent.ACTION_VIEW` で `application/pdf`)
- P7以降に内蔵検討 (`pdfium-android` または `androidx.pdf`)
- サムネ (`PdfRenderer`) は P7以降

### 10-G. 音声・動画

- 簡易内蔵プレーヤーを P7 で追加 (Media3/ExoPlayer: 再生/一時停止/シーク/±10秒)。本格再生は引き続き外部アプリ (VLC, MX Player, mpv 等) も選べる。
- サムネは内蔵:
  - 動画: `MediaMetadataRetriever.getFrameAtTime()`
  - 音声: `MediaMetadataRetriever.getEmbeddedPicture()`
- リモート動画のサムネは取得しない (帯域消費が激しいため)
- メタデータ表示 (解像度・コーデック・ビットレート・長さ・アーティスト等) は詳細画面で

### 10-H. 圧縮ファイル (差別化機能)

#### 対応フォーマット

| 形式 | 読み | 作成 | ライブラリ |
|---|---|---|---|
| ZIP | ◎ | ◎ | Java標準 (`java.util.zip`) |
| パスワード付きZIP | ◎ | ◎ | zip4j (`net.lingala.zip4j:zip4j:2.11.5`) |
| tar.gz / tar.bz2 / tar.xz | ◎ | ◎ | Apache Commons Compress (`org.apache.commons:commons-compress:1.27.1`) + `org.tukaani:xz` |
| 7z | ◎ | ◎ | Apache Commons Compress |
| RAR | ◎ (読みのみ) | × | junrar (`com.github.junrar:junrar`) |

#### 中身プレビュー (P7 実装済み: `ui/archive/ArchiveExplorer` + `ArchiveExplorerActivity`)
- ✅ ZIP をタップ → 展開せずに中身一覧を表示 (zip4j のヘッダ読みのみ)
- ✅ 通常のフォルダのように扱う (パンくず + フォルダタップで潜れる)
- ✅ 内部のファイルもプレビュー可能 (タップで単一エントリだけキャッシュ展開 → 内蔵ビューア/外部アプリ)
- ✅ 暗号化 ZIP は開く時にパスワードを要求 (一覧表示自体は不要)
- ⏳ 単一ファイルだけ展開して「コピー」(現状はプレビュー目的の展開のみ。明示コピーは将来)
- ⏳ 7z / tar.xz / RAR は未着手 (ZIP のみ)

#### 実装方針
- `storage-archive` モジュールは作らない (独立Providerにしない)
- 専用クラス `ArchiveExplorer` として実装
- 「他のProviderの中の特別なファイル」として、SMB上でもローカルでも同じUIで扱える

### 10-I. ファイル種別判定

優先順位:
1. **拡張子** (高速、まず最初に試す)
2. **マジックナンバー** (拡張子なし or 偽装の検出、先頭16バイトを読む)
3. **MIMEタイプ** (SAFが返す `mimeType` を信頼)
4. **不明** → 汎用ファイルアイコン

`core-model/filetype/` に `FileTypeRegistry` として静的テーブルを持つ:

```kotlin
enum class Category {
    IMAGE, VIDEO, AUDIO, TEXT, MARKDOWN, PDF, ARCHIVE, OFFICE,
    APK, ISO, BINARY, UNKNOWN
}

object FileTypeRegistry {
    fun categorize(name: String): Category
    fun mimeTypeFor(name: String): String?
}
```

### 10-J. 外部アプリ連携 (Intent)

#### ローカルファイルを外部で開く
- **`FileProvider` 経由で URI を作成** (Android 7+ 必須)
- `MANAGE_EXTERNAL_STORAGE` 持っていても、外部アプリ向けは `content://` 形式
- `Intent.ACTION_VIEW` / `Intent.ACTION_EDIT` / `Intent.ACTION_SEND`

#### リモートファイルを外部で開く
- 一度ローカルキャッシュにダウンロード
- ダウンロード完了通知
- ローカルパスを `FileProvider` で URI化して Intent
- **外部アプリでの編集は反映されない** (制約として割り切る、編集を反映したい場合は内蔵エディタを推奨)

### 10-K. モジュール配置

```
core-model/
└── filetype/
    ├── FileTypeRegistry.kt          # 拡張子→カテゴリ判定
    └── Category.kt

core-data/cache/
└── ThumbnailCache.kt                # メモリ+ディスク2層

app/ui/viewer/
├── ImageViewerScreen.kt
├── TextViewerScreen.kt
├── TextEditorScreen.kt
├── MarkdownViewerScreen.kt
└── ArchiveExplorerScreen.kt
```

初期は `app` 内に置き、肥大化したら個別モジュール化を検討。

### 10-L. 確定事項一覧

| 項目 | 決定 |
|---|---|
| 動画プレーヤー | Media3 ExoPlayer で内蔵 (P7)。**リモートも `StorageManager.openProxyFileDescriptor` + 範囲指定 `openInput` で seek 対応ストリーミング**。WMV/.asf は外部アプリ案内 |
| PDFビューア | PdfRenderer で内蔵 (P7、LRU ページキャッシュ + 専用スクロールバー) |
| テキストエディタ | **Sora-editor (Canvas 描画 + 仮想化、`sora-editor:0.23.5`)** に置換 (P7 後期)。検索/折返し/行番号/Undo/Redo、編集上限はユーザー設定 (128KB〜8MB / 無制限)。配色はアプリのテーマ設定 (システム/ライト/ダーク) に追従、ダークの行番号視認性も調整済み |
| Markdownプレビュー | Markwon で内蔵 (tables/strikethrough/tasklist/linkify プラグイン)。プレビューにも掴めるファストスクロールバー |
| 圧縮ファイル | ZIP の圧縮/解凍 + **中身プレビュー (展開せずフォルダのように閲覧、`ArchiveExplorerActivity`)** を P7 で実装 (zip4j 2.11.5、AES-256 パスワード対応)。**解凍はローカル展開先なら zip4j で展開先へ直接展開** (キャッシュ経由の二重 I/O を廃止して高速化、最後までバイト進捗を表示。SAF/リモート宛のみキャッシュ経由 + コピー工程にファイル数進捗)。7z/tar.xz/RAR は未着手 |
| RAR | 未着手 (将来 junrar を検討) |
| パスワード付きZIP | zip4j で対応 (AES-256 / PKCS#5) |
| EXIF削除 | オプション提供、デフォルトオフ (P7以降) |
| エンコーディング判定 | juniversalchardet 自動判定 |
| 画像サムネ | Coil 3 |
| 動画/音声サムネ | MediaMetadataRetriever |
| サムネキャッシュ | メモリ + ディスクの2層、lastModified込みのキー |
| リモート画像サムネ | 完全DL (10MB制限)、P7以降に部分DL検討 |

---

## 11. テーマ・アクセシビリティ

Foldex はファイラーアプリなので、ファイル一覧をストレスなく読めることを最優先。Material 3 の標準に乗りつつ、orgson が日常使いするための小さな工夫を入れる方向。

### 11-A. 全体方針

- **Material 3 (Material You) を採用**
- **動的カラーはデフォルトオフ / 既定は Forest Green テーマ** (設定でオンにすると Android 12+ のシステム壁紙色に追従)
- **過剰なデザイン主張は避ける** (ファイル一覧の読みやすさが最優先)
- **アクセシビリティは Android 標準を守る** (TalkBack、48dp、コントラスト)

### 11-B. カラースキーム

#### テーマモード
| モード | 動作 |
|---|---|
| システム追従 | Android のダーク/ライト設定に追従 (デフォルト) |
| 常にライト | 強制ライト |
| 常にダーク | 強制ダーク |
| 時刻ベース (将来) | 日没〜日の出はダーク |

DataStore で `themeMode` を保存。

#### 動的カラー (Material You)
- Android 12+ で壁紙からシステム色を抽出
- **デフォルトオフ** (Foldex 独自の Forest Green テーマを既定の見た目にするため)。設定でオンにすると壁紙連動
- オフ時 (既定) は Foldex 独自カラーを使用

#### Foldex 独自カラー
- **Forest Green** (`#2E7D32`) をアクセントの基調
- `FoldexTheme.kt` に light / dark のフルパレットを定義。**地の面 (background / surface /
  surfaceVariant / surfaceContainer 群) はニュートラルなグレー、緑は primary とアクセントの
  container (HOME タイル等) にだけ使う**。
  - 以前は primary だけ緑で残りが Material 既定の紫系 → 緑×紫がちぐはぐだった。
  - その後 surface まで緑にしたら「全体的に緑っぽい」となったため、地の面はニュートラルへ戻した
    (= 現在の方針)。
- Zero to Ship のアクセントカラーと整合

#### ダーク/ライトの注意点
- ファイル一覧の読みやすさ (行間、コントラスト)
- アイコンの視認性 (拡張子別の色分けが沈まないよう調整)
- **システムバーのアイコン明暗はアプリの実テーマに追従** (`FoldexTheme` の `SideEffect` で
  `WindowCompat.isAppearanceLight{Status,Navigation}Bars = !darkTheme`)。`enableEdgeToEdge()` は
  システムの dark/light 基準なので、手動でライト/ダークを選ぶと時計等が背景と同化する問題への対処。
- ダーク時の画像サムネに薄い枠線

### 11-C. タイポグラフィ

#### フォント
- **デフォルト**: システムデフォルト (端末の日本語フォント、多くは Noto Sans CJK)
- **等幅フォント**: コード/テキストエディタ専用
- **カスタムフォント追加**: 将来オプション

#### サイズスケール
Material 3 の Typography Scale (Display/Headline/Title/Body/Label) を標準採用。
**ファイル名表示は `bodyMedium`** (14sp) を基準。

#### ユーザー設定
- コンパクト (情報密度高)
- 標準 (デフォルト)
- 大きめ (見やすさ重視)

### 11-D. アイコン

#### システムアイコン
- Material Symbols (`androidx.compose.material.icons.extended`) を使用
- `Icons.Outlined.*` をベース
- アクションボタンは `Icons.Filled.*`

#### ファイル種別アイコンの色分け

| カテゴリ | 色 (推奨) | アイコン |
|---|---|---|
| フォルダ | プライマリ色 | `Icons.Outlined.Folder` |
| 画像 | 緑系 | `Icons.Outlined.Image` |
| 動画 | 紫系 | `Icons.Outlined.Movie` |
| 音声 | 橙系 | `Icons.Outlined.MusicNote` |
| テキスト | 青系 | `Icons.Outlined.Description` |
| Markdown | 青系 | `Icons.Outlined.Article` |
| PDF | 赤系 | `Icons.Outlined.PictureAsPdf` |
| 圧縮 | 黄系 | `Icons.Outlined.FolderZip` |
| その他 | グレー | `Icons.Outlined.InsertDriveFile` |

ダーク/ライトで彩度を微調整。

#### 拡張子バッジ
- アイコンの上に小さく拡張子テキストを表示
- **デフォルトオン**
- `.docx` と `.doc` のような細かい区別が一目で分かる

#### アプリアイコン
- MVP段階では Android デフォルトアイコンで進める
- 完成度が見えてから Foldex のメタファー (folder + index = タブ付きフォルダ等) を反映してデザイン

### 11-E. アクセシビリティ

#### 必須対応項目
1. **TalkBack 対応**
   - 全Composableに `contentDescription` を付ける
   - 装飾アイコンは `contentDescription = null`
   - 重要な状態変化を `LiveRegion` で通知

2. **タッチターゲットサイズ**
   - 最低 48dp × 48dp (Material 3 標準)

3. **コントラスト比**
   - 通常テキスト: 4.5:1 以上
   - 大文字テキスト: 3:1 以上
   - Material 3 デフォルトカラー組み合わせは自動的に満たす

4. **フォントサイズ追従**
   - システム設定 (端末の文字サイズ) に追従

#### 動作の安全性
- **削除確認ダイアログ**: デフォルトオン、設定でスキップ可能
- **危険操作の色とアイコン**: 上書き保存・永久削除は赤系 + Warning アイコン
- **アンドゥ機構**: コピー/移動/削除直後に Snackbar で「元に戻す」を5秒表示
- **ゴミ箱機能**: P7 で実装。設定で「ゴミ箱へ / 完全削除 / 毎回確認」を選択、ゴミ箱画面で復元・完全削除・空にする。ローカルファイルのみ対象 (リモートは完全削除)。

#### 視覚補助
- ハイコントラストモード: システム設定追従
- 色覚多様性対応: 色だけで情報を伝えない (アイコン形状でも区別)

### 11-F. キーボードショートカット (物理キーボード対応)

`onKeyEvent` で Compose 上でキャプチャ。**初期実装に含める**。

| ショートカット | 動作 |
|---|---|
| Ctrl+C | コピー |
| Ctrl+V | 貼付 |
| Ctrl+X | カット |
| Ctrl+A | 全選択 |
| Ctrl+F | 検索 |
| Delete | 削除 |
| F2 | リネーム |
| Esc | 選択解除/モーダル閉じる |

### 11-G. 国際化 (i18n)

- **日本語をデフォルト** (`values-ja/`)
- **英語をフォールバック** (`values/`)
- 将来的に他言語追加可能な構造

```
app/src/main/res/
├── values/
│   └── strings.xml         # 英語 (デフォルト・フォールバック)
├── values-ja/
│   └── strings.xml         # 日本語
└── values-night/
    └── colors.xml          # ダークモード専用カラー (必要に応じて)
```

### 11-H. 設定画面

**フラット構成** (1画面に全項目、スクロール 1〜2 画面分)。

```
┌──────────────────────────────────────┐
│ 設定                                  │
├──────────────────────────────────────┤
│ 表示                                  │
│  ├ テーマモード         システム追従 ▼ │
│  ├ Material You         ON           │
│  ├ アクセントカラー      Forest Green ▼│
│  ├ フォントサイズ       標準 ▼        │
│  └ 拡張子バッジ         ON           │
│                                      │
│ 動作                                  │
│  ├ 削除前の確認         ON           │
│  ├ 削除の行き先         ゴミ箱へ ▼   │
│  └ アンドゥの表示時間    5秒 ▼        │
│                                      │
│ 通知                                  │
│  ├ コピー・移動の完了を通知  ON         │
│  ├ 解凍の完了を通知       ON           │
│  └ 同期の完了を通知       ON           │
│                                      │
│ アクセシビリティ                       │
│  ├ ハイコントラスト      システム追従   │
│  └ TalkBack最適化       ON           │
│                                      │
│ プライバシー                           │
│  ├ 共有時にEXIF削除      OFF (P7)     │
│  ├ 接続情報のロック       OFF          │
│  └ 接続ログ             ON           │
│                                      │
│ ストレージ                             │
│  ├ サムネキャッシュ削除                 │
│  ├ キャッシュサイズ上限   100MB ▼      │
│  └ ダウンロードキャッシュ削除           │
│                                      │
│ 詳細                                  │
│  ├ バージョン           1.0.0        │
│  ├ ライセンス                         │
│  └ ソースコード         GitHubへ →   │
└──────────────────────────────────────┘
```

### 11-I. テーマ実装の構造

```
app/src/main/kotlin/com/zerotoship/foldex/ui/theme/
├── FoldexTheme.kt              # MaterialTheme ラッパー
├── Color.kt                    # ダーク/ライトのColorScheme定義
├── Typography.kt               # フォント・サイズ定義
├── Shape.kt                    # 角丸等のShape定義
└── PreferenceKeys.kt           # DataStoreキー定義
```

```kotlin
class ThemePreferences @Inject constructor(
    private val dataStore: DataStore<Preferences>
) {
    val themeMode: Flow<ThemeMode>
    val dynamicColor: Flow<Boolean>
    val accentColor: Flow<AccentColor>
    val fontScale: Flow<Float>
    suspend fun setThemeMode(mode: ThemeMode)
    // ...
}

enum class ThemeMode { SYSTEM, LIGHT, DARK }
enum class AccentColor(val seedColor: Color) {
    FOREST_GREEN(Color(0xFF2E7D32)),
    TEAL(Color(0xFF00897B)),
    INDIGO(Color(0xFF3F51B5)),
    CUSTOM(Color.Unspecified)
}
```

### 11-J. 確定事項一覧

| 項目 | 決定 |
|---|---|
| UIフレームワーク | Material 3 |
| 動的カラー | デフォルトオン |
| 独自カラー | Forest Green (`#2E7D32`) |
| テーマモード | システム追従/ライト/ダーク (時刻ベースは将来) |
| フォント | システム + 等幅 (エディタ用) |
| フォントサイズ | コンパクト/標準/大きめ |
| アイコン | Material Symbols Outlined、カテゴリ別色分け |
| 拡張子バッジ | デフォルトオン |
| TalkBack対応 | 必須 |
| タッチターゲット | 最低48dp |
| 削除確認 | デフォルトオン |
| アンドゥ | Snackbar 5秒 |
| ゴミ箱 | P7 で実装 (ゴミ箱へ / 完全削除 / 毎回確認 + ゴミ箱画面) |
| キーボードショートカット | 主要操作を初期から実装 |
| 言語 | 日本語デフォルト + 英語フォールバック |
| 設定画面階層 | フラット |
| アプリアイコン | MVP後に詰める |

---

## 12. UI設計

ファイラーとしての日常使いを最優先した画面設計。Material 3 標準に乗りつつ、Material Files / X-plore / ファイルマネージャ+ などで評価の高いインタラクションを取り入れる。

### 12-A. 全体構成

#### トップレベル: 5タブ構成

下部固定 NavigationBar で5タブを常時表示:

| タブ | 役割 |
|---|---|
| 📁 ファイル | ファイルブラウザ (メイン)、ローカル+リモート横断 |
| 🔌 接続 | リモート接続の管理 (SMB/SFTP/FTP/WebDAV) |
| 📡 サーバー | 自機SFTP/FTPサーバーの起動・停止・設定 |
| 🔄 同期 | 同期ジョブの一覧・編集・実行履歴 |
| ⚙ 設定 | アプリ設定 (フラット構成) |

#### 画面遷移グラフ

```
[起動]
  ↓
[NavigationBar 付き Scaffold]
├── files                  (ファイルブラウザ)
│   ├── files/{path}       (サブフォルダ)
│   ├── files/viewer       (画像/テキスト/Markdown ビューア)
│   ├── files/editor       (テキストエディタ)
│   └── files/archive      (圧縮ファイル中身プレビュー)
│
├── connections            (接続一覧)
│   ├── connections/edit
│   └── connections/test
│
├── server                 (サーバー一覧)
│   ├── server/edit
│   └── server/log         (接続ログ閲覧)
│
├── sync                   (同期ジョブ一覧)
│   ├── sync/edit
│   ├── sync/run/{id}      (実行中の進捗)
│   └── sync/history/{id}
│
└── settings
    ├── settings/about
    ├── settings/license
    └── settings/storage
```

### 12-B. ファイルブラウザ (メイン画面)

#### レイアウト
```
┌──────────────────────────────────────┐
│ ☰  /SD/Documents          🔍 ⋮       │  ← AppBar
├──────────────────────────────────────┤
│ Documents > Reports > 2026          │  ← パンくず
├──────────────────────────────────────┤
│ 📁 backup                  3項目     │
│ 📁 archive                 12項目    │
│ 📄 summary.md           24KB 5/7     │
│ 🖼️ chart.png            120KB 5/6    │  ← ファイル一覧
│ 🗜️ data.zip             1.2MB 4/30   │
│                              [📤+]   │  ← FAB
└──────────────────────────────────────┘
```

#### 表示モード (フォルダごとに記憶)

3モード × Gridサイズ3段階 = 5パターン:

| モード | 用途 |
|---|---|
| List | 1行に1ファイル、情報密度高 (デフォルト) |
| Detailed List | 列で整理、タブレット・横画面向け |
| Grid (小/中/大) | サムネ重視、画像/動画フォルダで便利 |

#### フォルダごとの記憶

ファイルマネージャ+ 流の挙動:

```kotlin
@Entity(tableName = "folder_view_preferences")
data class FolderViewPreferenceEntity(
    @PrimaryKey val pathKey: String,        // FileUri ベースのキー
    val viewMode: String,                   // "list" | "detailed" | "grid_small" | "grid_medium" | "grid_large"
    val sortKey: String,                    // "name" | "size" | "modified" | "type"
    val sortOrder: String,                  // "asc" | "desc"
    val showHidden: Boolean,
    val foldersFirst: Boolean,
    val updatedAt: Long
)
```

検索順 (フォールバック):
1. このフォルダの設定 (完全一致)
2. 親フォルダの設定 (継承)
3. グローバル設定 (アプリ設定でのデフォルト)
4. システムデフォルト (List + 名前昇順)

これにより、DCIM配下を一度Gridにすれば配下フォルダも継承される。
「このフォルダの設定をリセット」メニュー、「全フォルダの設定をリセット」(設定画面) を提供。

#### ソート・フィルタ

AppBar右上の⋮メニューから:
- 隠しファイル表示 (チェックボックス)
- ソート: 名前 / サイズ / 更新日時 / 種類 × 昇順/降順
- 表示: List / Detailed / Grid (小/中/大)
- フォルダを先頭に固定

#### パンくずリスト
- 長いパスは省略表示 (`/storage/.../Documents/Reports`)
- 各セグメントタップで遡る
- 横スクロール可能
- 長押しで「パスをコピー」

#### リモート接続中の表示
AppBar に接続状態と帯域:
```
🔌 自宅NAS / Public
●接続中  256KB/s
```
切断時:
```
🔌 自宅NAS / Public
●切断  [再接続]
```

### 12-C. 操作体系

#### タップ・長押し
| 操作 | 動作 |
|---|---|
| ファイルタップ | 開く (内蔵対応は内蔵ビューア、他は外部Intent) |
| フォルダタップ | 中に入る |
| 長押し | **即・選択モード突入** (Material Files流) |
| 長押しドラッグ | 範囲選択 (連続選択) |
| 選択モード中タップ | 選択トグル |
| 戻るボタン (選択モード) | 選択解除 |

#### 選択モード時の AppBar
```
┌──────────────────────────────────────┐
│ ✕  3 項目選択中           ⋯         │
├──────────────────────────────────────┤
│ 📋 コピー / ✂️ 移動 / 🗑️ 削除 / 📤 共有│  ← アクションバー
└──────────────────────────────────────┘
```

#### アクション
**主要 (常時表示)**: コピー / 移動 / 削除 / 共有
**オーバーフロー (⋯)**: リネーム (1件のみ) / 複製 / プロパティ / 圧縮 / ハッシュ計算 (MD5/SHA-256) / アクセス権変更 (ローカルのみ) / 全選択 / 選択反転

#### コピー・移動の操作モード方式

X-plore / Material Files流のフロー (クリップボード方式ではない):

```
1. ファイルを選択
2. 「コピー」または「移動」ボタンをタップ
3. AppBarが「コピー先を選択」「キャンセル」に変化
4. 別フォルダ・別タブ・リモートに移動可能
5. 「ここに貼付」FAB が表示される
6. 貼付タップ → 進捗表示 → 完了
```

クリップボード方式と違い、他アプリに切替えても操作が消えない。

#### お気に入り (ピン留め、MVP搭載)

頻繁に使うフォルダ・接続をホームに固定:
- ファイルタブのトップに「ピン留め」セクション
- 各フォルダ/接続を長押し → 「ピン留め」
- ホームから1タップでアクセス

### 12-D. ナビゲーション

#### 戻るボタンの優先順位

```
1. モーダル/ダイアログが開いている → 閉じる
2. 選択モード中 → 選択解除
3. 検索モード中 → 検索クリア
4. サブフォルダにいる → 親フォルダへ
5. ルートフォルダ → タブ履歴を辿る、または終了確認
```

#### 「上へ」ボタン
**設置しない**。パンくずリストのタップで遡れるので冗長。

#### 横画面・タブレット対応
- スマホ縦・横: 単一ペイン (FAB位置は横画面で調整)
- タブレット 2ペイン (左:フォルダツリー、右:ファイル一覧): **P7以降**

### 12-E. 特殊な画面

#### 進捗表示
ファイル転送・同期実行中:
```
┌──────────────────────────────────────┐
│ 転送中...                            │
│ chart.png  (3/12 完了)               │
│ ████████░░░░░░░░░ 45%               │
│ 256 KB / 568 KB  ・ 1.2 MB/s         │
│ 残り 9 件  ・ 推定 25秒              │
│ [一時停止]  [キャンセル]              │
└──────────────────────────────────────┘
```
- フルスクリーン
- バックグラウンドに移行可能 (通知に格下げ)
- 同期実行中も同様の画面

#### エラー表示

| 種類 | UI |
|---|---|
| 接続失敗 | Snackbar (再試行ボタン付き) |
| 認証失敗 | Dialog (パスワード再入力誘導) |
| 同期競合 | Dialog (解決方法を選択) |
| 容量不足 | Snackbar |
| 一般I/Oエラー | Snackbar |

#### 検索

AppBar の🔍タップで検索モード:
```
┌──────────────────────────────────────┐
│ ✕  *.jpg              [現在のフォルダ ▼]│
├──────────────────────────────────────┤
│ 一致: 23件                           │
│ 🖼️ chart.jpg                         │
│ 📁 photos/                           │
│   🖼️ 2026-05-01.jpg                  │
└──────────────────────────────────────┘
```

検索範囲: 現在のフォルダのみ / 現在のフォルダ + サブフォルダ (再帰) / アプリ全体 (将来、インデックス機能と組合せ)
検索方法: 部分一致 (デフォルト) / glob (`*.jpg`) / 正規表現 (将来)

### 12-F. ファイル種別ごとのタップ動作

10章 (ファイル種別の扱い) と連動:

| 種別 | タップ動作 |
|---|---|
| 画像 | 内蔵ImageViewerScreen |
| テキスト | 内蔵TextViewerScreen (編集ボタンでEditorに切替) |
| Markdown | 内蔵MarkdownViewerScreen (ソース/プレビュー切替) |
| 圧縮 | 内蔵ArchiveExplorerScreen (中身を通常フォルダのように) |
| PDF | 外部Intent (P7以降に内蔵検討) |
| 動画/音声 | 外部Intent |
| オフィス | 外部Intent |
| 不明 | 外部Intentダイアログ (アプリ選択) |

### 12-G. 確定事項一覧

| 項目 | 決定 |
|---|---|
| ナビゲーション | 下部固定NavigationBar (5タブ) |
| 表示モード | List/Detailed/Grid、フォルダごとに記憶 |
| 長押し | 即・選択モード |
| コピー/移動 | 操作モード方式 |
| お気に入り | ピン留め機能、MVP搭載 |
| 「上へ」ボタン | 設置しない |
| タブレット対応 | P7以降 |
| ファイルタップ | 内蔵可は内蔵、他は外部Intent |
| 進捗表示 | フルスクリーン、バックグラウンド可 |
| エラー表示 | Snackbar (一時) と Dialog (重大) |
| 検索 | 部分一致 + glob。現在フォルダのみ / サブフォルダ含む再帰 (P7 §Q 実装) |
| 使用量分析 | gdu 風のフォルダ別サイズ (ドリルダウン + バー、P7 §Q 実装) |

---

## 13. 開発ロードマップ

| フェーズ | 内容 | 状況 |
|---|---|---|
| **P1** | プロジェクトスケルトン、build.gradle.kts、Hilt設定、最低限のActivity | ✅ 完了 |
| **P2** | ローカルファイルブラウザ (read-only)、SAF対応 | ✅ 完了 |
| **P3** | ローカル操作 (CRUD)、権限ハンドリング、StorageProvider抽象 | ✅ 完了 |
| **P4** | SMB対応 (smbj統合) — 1プロトコルで実証 | ✅ 完了 |
| **P5** | SFTP / FTP / WebDAV を順次追加 | ✅ 完了 |
| **P6** | 自機SFTP/FTPサーバー (ForegroundService、Argon2id認証、ホスト鍵生成) + 同期エンジン (片方向) | ✅ 完了 |
| **P7** | UI洗練、エラーハンドリング、テスト配布 + P8 から前倒し (双方向同期 / リモート動画ストリーミング / HOME 画面 / Sora エディタ / ZIP) | 🚧 大半完了。残: アクセシビリティ、エラーメッセージ日本語化、同期途中再開 |
| **P8** | エクスポート/インポート、F-Droid/Play配布、`v1.0.0` | ⏳ 未着手 |

各フェーズは1〜3週間程度を想定。スマホポチポチ開発のペース。

P7 で前倒し実装したものを含む詳細な進捗は `docs/PHASES.md` §7 と `docs/P7-REVISIONS.md` を参照。

---

## 14. 確定済み設計判断 (一覧)

| 項目 | 決定 |
|---|---|
| アプリ名 | Foldex (folder + ex / index) |
| パッケージベース | `com.zerotoship.foldex` |
| 言語 / UI | Kotlin + Jetpack Compose |
| リモート方式 | 個別ライブラリ束ね (rclone不採用) |
| 自機サーバー | Apache MINA SSHD + Apache FtpServer |
| ストレージ戦略 | ハイブリッド (MANAGE_EXTERNAL_STORAGE + SAF) |
| `Android/data` | サポート (SAFラッパー最初から) |
| モジュール粒度 | 12モジュール |
| ローカルストレージ | 1モジュールに統合 (内部でFile/SAF振り分け) |
| DI | Hilt |
| ナビゲーション | Navigation Compose (文字列ルーティング) |
| Result型 | 独自定義 `Result<T, E>` を core-common に |
| シンボリックリンク | SYMLINK タイプを残し、ListOptionsで制御 |
| 時刻型 | `Instant` (kotlinx-datetime) |
| Connection表現 | sealed class でプロトコル別 |
| 接続情報DB | `connections` + `encrypted_credentials` の二層 |
| 暗号化方式 | AES-GCM + AndroidKeyStore (256bit) |
| 生体認証ゲート | 設定可、`keyAlias` で識別 (将来実装) |
| SSH秘密鍵 | DBにBLOB暗号化保存 |
| インポート機能 | 見送り (将来Roomマイグレーション) |
| バックアップ | `allowBackup=false` のみ |
| 双方向同期 | P7 で実装 (`SyncDirection.BIDIRECTIONAL`) |
| 削除前バックアップ | 世代管理 + 容量しきい値設定 + ローカル/リモート一括復元 (P7 実装)。復元は実行前にファイル一覧をプレビューして可否確認 (P7 §Q) |
| 同期の実行直列化 | 全ジョブを `SyncEngine` 全体ミューテックス + `WorkManager` 一意名 `KEEP` で 1 本ずつ実行 (P7 §Q、二重実行による「転送0なのに転送済み」を解消) |
| 同期実行状態の可視化 | `JobStatusChip` (RUNNING/ENQUEUED/IDLE) + `AppLogger` 経由の詳細ログ (サマリ末尾にスキップ数/合計件数, P7 §Q) |
| 削除アクション | デフォルトオフ (オプトイン) |
| state DB | `core-data` に統合 |
| フィルタ | glob パターンのみ |
| 競合解決 MANUAL | 実装しない (KEEP_BOTH で代替) |
| 並列度 | デフォルト3、設定で1〜8 |
| サーバー起動単位 | SFTP/FTP独立、複数同時起動可 |
| サーバーデフォルト認証 | パスワード認証 |
| パスワードハッシュ | Argon2id |
| Wi-Fi限定モード | デフォルトオン |
| サーバー自動起動 (アプリ起動時) | デフォルトオフ |
| サーバー自動復帰 (端末再起動後) | デフォルトオフ (設定オン時のみ) |
| 接続ログ記録 | デフォルトオン |
| デフォルトポート | SFTP=2022, FTP=2121 |
| FTPS | Explicit TLS、自己署名証明書自動生成 |
| ホスト鍵 (SFTP) | Ed25519、初回自動生成、再生成可 |
| 動画プレーヤー | Media3 ExoPlayer で内蔵 (P7)。**リモートも `StorageManager.openProxyFileDescriptor` + 範囲指定 openInput で seek 対応ストリーミング** (P7 後期)。WMV/.asf は外部アプリ案内 |
| PDFビューア | PdfRenderer で内蔵 (P7、LRU ページキャッシュ + 専用スクロールバー) |
| テキストエディタ | **Sora-editor (Canvas 描画 + 仮想化、`sora-editor:0.23.5`)** に置換 (P7 後期、~8MB まで軽快)。検索/折返し/行番号/Undo/Redo、編集上限はユーザー設定 (128KB〜8MB / 無制限)。配色はアプリのテーマ設定に追従、ダークの行番号視認性も調整済み |
| Markdownプレビュー | Markwon で内蔵 (P7、tables/strikethrough/tasklist/linkify プラグイン)。プレビューにも掴めるファストスクロールバー |
| 圧縮ファイル | ZIP の圧縮 + 解凍 + **中身プレビュー (展開せず閲覧、`ArchiveExplorerActivity`)** を P7 で実装 (zip4j 2.11.5)。7z/tar.xz/RAR は未着手 |
| RAR | 未着手 (将来 junrar を検討) |
| パスワード付きZIP | zip4j で対応 (AES-256 / PKCS#5) |
| EXIF削除 | オプション提供、デフォルトオフ (P7以降) |
| エンコーディング判定 | juniversalchardet 自動判定 |
| 画像サムネ | Coil 3 |
| 動画/音声サムネ | MediaMetadataRetriever |
| サムネキャッシュ | メモリ + ディスクの2層 |
| UIフレームワーク | Material 3 |
| 動的カラー | **デフォルトオフ** (Android 12+ で設定オン時のみ壁紙連動)。既定は Forest Green テーマ |
| 独自カラー | Forest Green (`#2E7D32`) アクセント。地の面はニュートラルなグレー、緑は primary とアクセント container のみ (`FoldexTheme.kt` の light/dark パレット) |
| テーマモード | システム追従/ライト/ダーク |
| 拡張子バッジ | デフォルトオン |
| 削除確認ダイアログ | デフォルトオン |
| アンドゥ | Snackbar 5秒 |
| ゴミ箱 | P7 で実装 (ゴミ箱へ / 完全削除 / 毎回確認 + ゴミ箱画面) |
| キーボードショートカット | 主要操作を初期から実装 |
| 言語 | 日本語デフォルト + 英語フォールバック |
| 設定画面階層 | フラット (1画面) |
| ナビゲーション | 下部固定NavigationBar (5タブ) |
| 表示モード | List/Detailed/Grid、フォルダごとに記憶 |
| 長押し | 即・選択モード (Material Files流)。HOME タイルだけは長押し=ドラッグ並べ替え |
| コピー/移動 | 操作モード方式 (X-plore流) + 進捗バナー (件数 + バイト + 80ms スロットル) |
| お気に入り (ピン留め) | HOME 画面に統合 (LocalFolder / SafFolder / RemoteConnection の 3 種類のタイル、ドラッグで並べ替え可能) |
| 「上へ」ボタン | 設置しない (パンくずで代替) |
| タブレット2ペイン | P7以降 |
| ファイルタップ | 内蔵可は内蔵、他は外部Intent |
| 検索 | 部分一致 + glob。検索バーの「サブフォルダ」トグルで現在フォルダのみ ⇄ 再帰検索 (P7 §Q 実装、最大5000件・キャンセル可) |
| 使用量分析 | メニュー「使用量を分析」で gdu 風のフォルダ別サイズ表示 (P7 §Q 実装)。直下を配下込み合計サイズで降順 + 占有率バー、フォルダタップで潜れる。ローカル/リモート/SAF 対応、進捗 + 中断可 |
| HOME 画面 | 起動時の最初の画面 (P7 後期実装)、ドラッグ並べ替え (`sh.calvin.reorderable`)、非表示・改名、画像/動画の横断ビュータイル付き |
| 接続編集 | URL ワンライナー入力対応、ポート空欄許可、SFTP 公開鍵認証 (鍵生成内蔵)、SMB 共有名/初期パス分離。編集後はセッションプール (signature 比較) で即時反映 |
| App Shortcuts | Files / Connections / Servers / Trash (静的)。ACTION_SEND 共有受信対応 |
| 実行ログ | `AppLogger` で Crash / Server / Sync を集約 (256KB × 2 世代ローテ)。設定→実行ログから確認・共有・消去 |
| ContentProvider | `${applicationId}.fileprovider` (外部アプリへのファイル受け渡し) + `${applicationId}.streaming` (リモート動画の seek 対応ストリーミング) |

---

## 15. 残っている設計論点

P7 終盤での消化状況を ✅ / ⏳ で示す。

1. **サーバー機能の拡張 (P7以降)**
   - ⏳ レート制限・自動ブロック (fail2ban相当)
   - ⏳ QRコードでホスト鍵フィンガープリント表示・読み取り
   - ⏳ 複数マウント (公開ルートを複数登録)
   - ⏳ WebDAV サーバー機能 (要件外だが将来検討)
   - ✅ サーバ起動失敗の可視化 (`AppLogger.error("Server/SFTP"|"Server/FTP", ...)`)
   - ✅ FTP サーバの書き込み信頼性向上 (NIO `NativeFileSystemFactory` 自前実装 / PASV ポート固定)

2. **ファイル操作の高度な機能 (P7以降)**
   - ✅ ゴミ箱機能 (P7、TRASH/PERMANENT/ASK + 自動削除 + 一覧画面)
   - ⏳ EXIF削除オプション (共有時の自動GPS削除)
   - ✅ PDF内蔵ビューア (P7、PdfRenderer + LRU ページキャッシュ + 専用スクロールバー)
   - ⏳ リモート画像の部分ダウンロードサムネ
   - ✅ ZIP 圧縮/解凍 (P7、AES-256 パスワード対応) + 中身プレビュー (展開せず閲覧、`ArchiveExplorerActivity`)
   - ✅ 拡張子なしテキストの自動判定で内蔵エディタ開く (P7)

3. **タブレット・大画面対応 (P7以降)**
   - ⏳ 2ペインレイアウト (フォルダツリー + ファイル一覧)
   - ⏳ NavigationRail (横画面・タブレットでの代替ナビ)
   - ⏳ ドラッグ&ドロップ操作 (HOME タイルのドラッグ並べ替えのみ実装、ファイル間 D&D は未対応)

4. **配布関連 (P8以降)**
   - ⏳ F-Droid 用ビルド設定 (Reproducible Build対応)
   - ⏳ Google Play 審査用 デモ動画・プライバシーポリシー
   - ⏳ GitHub リリースワークフロー
   - ✅ release 自己署名 (`isMinifyEnabled=false`、24MB) で内輪配布可
   - ✅ **正式リリース署名構成** (`keystore.properties` 経由 / PKCS12 / RSA-2048): リポ直下に `keystore.properties` (gitignore 済み) があれば本番鍵 (`release.keystore`) で署名、無いマシンでは debug 鍵にフォールバック。`apksigner verify --print-certs` で APK v2 署名を確認可
   - ✅ debug `.debug` applicationIdSuffix で release と共存可能
   - ✅ GitHub 公開: `git@github.com:orgsonai/foldex.git` (main + phase/P1〜P7-polish + v0.1.0-P1〜v0.6.0-P6)

5. **P7 で消化された設計論点** (HANDOFF / PHASES に反映済み)
   - ✅ HOME 画面 (元々想定外、P7 で追加)
   - ✅ 動画リモートストリーミング (元々「外部呼び出しのみ」だったが seek 対応で内蔵)
   - ✅ テキストエディタ刷新 (BasicTextField → Sora editor、~8MB まで軽快)
   - ✅ 接続編集の即時反映 (セッションプールの signature 比較)
   - ✅ SAF (Termux 等) 完全対応 (`pendingChildName` + DocumentFile)
   - ✅ DB マイグレーション失敗の自動復旧

6. **次フェーズ (P8) で扱う残課題**
   - ⏳ アクセシビリティ最低ライン (TalkBack ラベル / 48dp タップ領域 / コントラスト 4.5:1)
   - ⏳ エラーメッセージ完全日本語化 (`StorageError` / `SyncError` の `message`)
   - ⏳ 同期途中再開 (リモートが完全動作してから着手)
   - ⏳ `ServerService.foregroundServiceType` (DATA_SYNC は Android 14+ で 6h 制限 / SPECIAL_USE 化検討)
   - ⏳ FTP の `CoderMalfunctionError` (日本語フォルダ名 CWD で発火) の根治: 現在は `UncaughtExceptionHandler` で握り潰し中。Apache FtpServer の encoder 差し替えが必要

---

## 16. メモ・参考実装

### 参考にしたOSSアプリ
- **Material Files** — UIの参考、Kotlin/Compose製、SMB/FTP対応
- **Solid Explorer** — 商用だが機能の充実度が参考に
- **Round Sync (旧RCX)** — rclone同梱型の対極の例、こちらは採用しない方式
- **FolderSync** — 同期機能の枯れた仕様の参考
- **PrimitiveFTPd** — Android端末をSFTP/FTPサーバー化する定番OSS

### 配布戦略
- 初期: 自分用 + 直接APK配布 (Vivaldi等から)
- 中期: テスター集めて F-Droid 配布
- 長期: 需要次第で Google Play (要審査クリア)

### 関連プロジェクト (Zero to Ship 内)
- **Kuraudo (蔵人)** — Flutter製パスワードマネージャー (前作)
- **webcal (仮)** — Kotlin製ブラウザアプリ
- **portal.html** — オフライン個人ナレッジポータル

Foldex は Zero to Ship シリーズ初の本格 Kotlin/Compose アプリ。Kuraudo は Flutter製だったので、技術スタック上の連続性は無いが、同シリーズの「広告なし・ローカルファースト・必要なものを必要な分だけ」という思想を継承する。

---

## 17. 文体・コーディング規約

### Kotlin スタイル
- 公式 Kotlin Coding Conventions に準拠
- `ktlint` で自動整形
- `detekt` で静的解析

### コメント
- 日本語OK (公開ライブラリ部分は英語)
- 「なぜ」を書く、「何を」はコードで読めるはず
- TODO/FIXME はGitHub Issueと紐付け

### 命名
- クラス: PascalCase
- 関数・変数: camelCase
- 定数: SCREAMING_SNAKE_CASE
- パッケージ: 小文字、ドット区切り

### コミットメッセージ
- Conventional Commits 推奨 (`feat:`, `fix:`, `refactor:` 等)
- 日本語OK

---

## 18. 引き継ぎ用メモ

このファイルは **Foldex の生きた仕様書** として運用する。
新しい設計判断が確定したら、該当セクションに追記または更新。
将来的な変更履歴は Git の履歴で追えるので、ファイル内に「いつ変えた」は書かない。

最終更新: 2026-06-02 (§10 ZIP 解凍の高速化 + §設定に「通知」セクションを追記。実機FB対応・Android 14 同期クラッシュ根治・操作完了のシステム通知は `docs/P7-REVISIONS.md` §M を参照。バージョン 0.2.1)
