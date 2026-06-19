# Foldex

> folder + ex (= index) — フォルダにインデックスを張って、ローカル/リモートを横断的にアクセスできる Android ファイラー。

[Zero to Ship](https://github.com/orgsonai/zero-to-ship) シリーズ第3弾。
広告なし・ローカルファースト・リモート全部入り。自分が日常使いするためのアプリ。

リポジトリ: <https://github.com/orgsonai/foldex>

```bash
git clone git@github.com:orgsonai/foldex.git
# または HTTPS
git clone https://github.com/orgsonai/foldex.git
```

## 特徴

### ファイル管理
- 📁 **ローカル** — 内部ストレージ / SD / USB-OTG、`Android/data` も SAF 経由でアクセス。Termux のホームなど SAF tree も HOME に固定可
- ☁️ **リモート** — SMB / SFTP / FTP(S) / WebDAV
- 🏠 **HOME 画面** — よく使うフォルダ・リモート接続・機能をタイル化。長押しでドラッグ並べ替え、改名、非表示
- 🔍 **検索 / ソート / 隠しファイル切替** — フォルダごとに記憶
- 🗜️ **ZIP** — 圧縮 / 解凍 (AES-256 パスワード対応、ローカルは直接展開で高速・進捗表示)
- 🔄 **共有受信** — 他アプリの「共有」から複数ファイルを受け取って保存

### 内蔵ビューア
- 🖼️ **画像** — Coil + ピンチズーム + スワイプで前後の画像へ
- 📄 **PDF** — ページキャッシュ + スクロールバー
- 📝 **テキストエディタ** — Sora-editor (Canvas 描画、~8MB まで軽快、検索/折返し/行番号/Undo/Redo)
- 📐 **Markdown / HTML** — ソース ⇄ プレビュー切替 (Markwon + テーブル)
- 🎬 **動画** — ExoPlayer + **リモートストリーミング (seek 対応)**
- 🎵 **音声** — Media3 内蔵プレーヤー

### リモート
- 🖥️ **自機サーバー** — SFTP / FTP サーバーとして公開可能 (Argon2id 認証、Ed25519 ホスト鍵、FoldexFtpUserManager)
- 🔑 **SFTP 公開鍵認証** — クライアント鍵を端末内で生成、サーバー側 `authorized_keys` 用に公開鍵をコピー
- 🚀 **動画リモートストリーミング** — `StorageManager.openProxyFileDescriptor` 経由で全 DL を待たず再生・シークバーで任意位置から再生
- 🔄 **定期同期** — 双方向対応、競合解決ポリシー選択可、削除前バックアップ (世代管理)、実行状態チップ + 詳細実行ログ

### システム
- 🔒 **プライバシー** — トラッキングなし、認証情報は AndroidKeyStore + AES-GCM 暗号化
- 🎨 **Material You** — 動的カラー対応、日本語UIデフォルト
- 🔔 **App Shortcuts** — ランチャー長押しから Files / Connections / Servers / Trash へ
- 🔕 **完了通知** — コピー・移動 / 解凍 / 同期 の完了をシステム通知 (設定で個別 ON/OFF)
- 📋 **実行ログ** — クラッシュ・サーバ起動失敗・同期サマリを集約 (設定→実行ログから確認・共有)

## 開発状況

現在 **P7 (UI 洗練) 終盤**。

- ✅ P1〜P6: 完了 (スケルトン / ローカル read-only / CRUD / SMB / SFTP-FTP-WebDAV / 自機サーバー + 同期エンジン)
- 🚧 P7: 大半完了。アクセシビリティ (TalkBack/48dp/コントラスト) とエラーメッセージ日本語化は対応済み。残: 同期途中再開
- ⏳ P8: F-Droid 用 metadata / Reproducible Build / LICENSE 確定 / プライバシーポリシー / 初回正式リリース (`v1.0.0`)

開発フェーズ詳細は [`docs/PHASES.md`](docs/PHASES.md)、P7 で前倒し対応した内容は [`docs/P7-REVISIONS.md`](docs/P7-REVISIONS.md) を参照。

## 技術スタック

| | |
|---|---|
| 言語 | Kotlin |
| UI | Jetpack Compose + Material 3 |
| アーキ | マルチモジュール (12モジュール) + Hilt |
| DB | Room (v5) + DataStore + AndroidKeyStore |
| エディタ | sora-editor (Canvas 描画 + 仮想化) |
| 動画 | Media3 ExoPlayer (+ 自前 ContentProvider で seek 可能なリモートストリーミング) |
| サムネ / 画像 | Coil 3 |
| Markdown | Markwon (テーブル / strikethrough / tasklist / linkify) |
| SMB | smbj |
| SFTP | sshj |
| FTP | Apache Commons Net |
| WebDAV | OkHttp 直叩き |
| SFTP/FTP サーバー | Apache MINA SSHD / Apache FtpServer (NIO ベース FileSystemFactory) |
| 圧縮 | zip4j (AES-256 暗号化) |
| 暗号 | BouncyCastle (フル版・Application で `Security.addProvider`) |
| 文字コード判定 | juniversalchardet |
| ドラッグ並べ替え | sh.calvin.reorderable |

詳細仕様は [`FOLDEX-HANDOFF.md`](FOLDEX-HANDOFF.md)。

## ビルド

```bash
# debug (applicationIdSuffix=.debug、release と共存可)
./gradlew :app:assembleDebug

# release (リポ直下 keystore.properties があれば正式鍵、無ければ debug 鍵にフォールバック)
./gradlew :app:assembleRelease

# 出力
# app/build/outputs/apk/{debug,release}/app-{debug,release}.apk
```

正式署名で配布する場合は `keystore.properties` (gitignore 済み) を以下の形式で用意する:
```
storeFile=release.keystore
storePassword=...
keyAlias=...
keyPassword=...
```
鍵ファイル本体 (`*.jks` / `*.keystore`) と `keystore.properties` は `.gitignore` で除外されるためコミットされない。署名確認は `apksigner verify --print-certs <apk>`。

## ライセンス

[MIT](LICENSE) — Copyright (c) 2026 Zero to Ship。依存ライブラリ (smbj / Apache Commons / MINA SSHD / Apache FtpServer = Apache-2.0、xz = public domain) はいずれも MIT と互換。

## プライバシー

Foldex は個人情報を一切収集・送信しない。動作はすべて端末内で完結し、ネットワーク通信はユーザーが設定した SMB/SFTP/FTP/WebDAV 接続先との直接通信のみ。広告 SDK や解析 SDK は組み込んでいない。詳細は [`docs/PRIVACY.md`](docs/PRIVACY.md)。

## このリポジトリで Claude Code を使う場合

[`CLAUDE.md`](CLAUDE.md) を最初に読むこと。フェーズ運用とコミット規約が書いてある。
