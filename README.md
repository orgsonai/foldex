# Foldex

> folder + ex (= index) — フォルダにインデックスを張って、ローカル/リモートを横断的にアクセスできる Android ファイラー。

[Zero to Ship](https://github.com/orgsonai/zero-to-ship) シリーズ第3弾。
広告なし・ローカルファースト・リモート全部入り。自分が日常使いするためのアプリ。

## 特徴 (予定)

- 📁 **ローカルファイル管理** — 内部/SD/USB-OTG、`Android/data` も SAF経由でアクセス
- ☁️ **リモートストレージ** — SMB / SFTP / FTP(S) / WebDAV
- 🖥️ **自機サーバー** — SFTP / FTP サーバーとして公開可能 (Argon2id 認証、Ed25519 ホスト鍵)
- 🔄 **定期同期** — 片方向 → 双方向、競合解決ポリシー選択可
- 🗜️ **圧縮ファイル** — ZIP/7z/tar.xz/RAR(読み)、パスワード付きZIP対応
- 🔒 **プライバシー** — トラッキングなし、認証情報は AndroidKeyStore + AES-GCM 暗号化
- 🎨 **Material You** — 動的カラー対応、日本語UIデフォルト

## 開発状況

現在 **設計完了 → P1 (プロジェクトスケルトン)** 段階。

開発フェーズ詳細は [`docs/PHASES.md`](docs/PHASES.md) を参照。

## 技術スタック

| | |
|---|---|
| 言語 | Kotlin |
| UI | Jetpack Compose + Material 3 |
| アーキ | マルチモジュール (12モジュール) + Hilt |
| DB | Room + DataStore + AndroidKeyStore |

詳細は [`FOLDEX-HANDOFF.md`](FOLDEX-HANDOFF.md)。

## ビルド

```bash
./gradlew assembleDebug
```

## ライセンス

未定 (GPL-3.0 想定)。確定次第 `LICENSE` を追加。

## このリポジトリで Claude Code を使う場合

[`CLAUDE.md`](CLAUDE.md) を最初に読むこと。フェーズ運用とコミット規約が書いてある。
