# Foldex プライバシーポリシー

最終更新: 2026-06-20

このプライバシーポリシーは、Android アプリ **Foldex** (パッケージ名: `com.zerotoship.foldex`) の利用時に取り扱われる情報について説明する。

---

## 1. 結論 (要約)

- Foldex は **ユーザーの個人情報を一切収集・送信しない**。
- アプリの動作はすべて **ユーザーの端末内** で完結する。
- ユーザーが設定したリモート接続 (SMB / SFTP / FTP / WebDAV) との通信は、**ユーザー本人の意思による直接通信**であり、Foldex の運営者が介在しない。
- 解析ツール (Firebase / Google Analytics / Crashlytics 等) は一切組み込まれていない。
- 広告は表示しない。広告 SDK も組み込まれていない。

---

## 2. 取得・保管する情報

### 2-1. アプリが端末内に保管するもの

| 種類 | 保管場所 | 用途 |
|---|---|---|
| 接続設定 (SMB/SFTP/FTP/WebDAV のホスト名・ユーザー名・パスワード等) | 端末内のローカルデータベース (Room) + AndroidKeyStore (AES-GCM 暗号化) | 接続情報の保持 |
| 同期ジョブ設定 | 端末内のローカルデータベース | 同期ジョブの実行 |
| アプリ設定 (テーマ / 表示モード / 既定アプリ等) | 端末内の DataStore | アプリ動作のカスタマイズ |
| 削除前バックアップ / ゴミ箱のファイル | 端末内のキャッシュ領域 | 復元のため |
| エディタ下書き | 端末内のキャッシュ領域 | 編集中の自動保存 |
| 実行ログ / クラッシュログ | 端末内のローカルファイル | デバッグ用 (ユーザーが画面から閲覧・削除可能) |

### 2-2. アプリが外部に送信するもの

**Foldex 運営者へは何も送信しない。** ネットワーク通信が発生するのは以下のみで、いずれも**ユーザーが設定した宛先**への直接通信である。

| 通信先 | 用途 | プロトコル |
|---|---|---|
| ユーザーが追加した SMB サーバー | ファイル閲覧・転送 | SMB2/SMB3 |
| ユーザーが追加した SFTP サーバー | ファイル閲覧・転送 | SSH/SFTP |
| ユーザーが追加した FTP サーバー | ファイル閲覧・転送 | FTP (※暗号化されない) |
| ユーザーが追加した WebDAV サーバー | ファイル閲覧・転送 | HTTP/HTTPS |

これらの宛先はユーザー本人が「接続」画面で登録したものに限られる。アプリ運営者がそれらの宛先を取得・収集することはない。

---

## 3. 権限の用途

Foldex がリクエストする Android 権限とその用途:

| 権限 | 用途 |
|---|---|
| `MANAGE_EXTERNAL_STORAGE` | ファイルマネージャとしての機能 (端末ストレージ内のファイル/フォルダの閲覧・操作) |
| `READ_EXTERNAL_STORAGE` / `WRITE_EXTERNAL_STORAGE` | 旧 API 互換のため (Android 10 以下) |
| `READ_MEDIA_IMAGES` / `READ_MEDIA_VIDEO` | メディア横断ビュー (画像/動画のフォルダ横断表示) |
| `INTERNET` / `ACCESS_NETWORK_STATE` / `ACCESS_WIFI_STATE` | リモート接続 (SMB/SFTP/FTP/WebDAV) と同期 |
| `REQUEST_INSTALL_PACKAGES` | APK ファイル選択時のインストール起動 |
| `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_DATA_SYNC` | 同期ジョブ / 自機 SFTP/FTP サーバーのフォアグラウンド実行 |
| `POST_NOTIFICATIONS` | 同期完了 / 操作完了 / フォアグラウンドサービスの通知 |
| `RECEIVE_BOOT_COMPLETED` | 端末再起動後の定期同期スケジュール復元 |
| `WAKE_LOCK` | 同期ジョブの完走 (Doze モード対策) |

---

## 4. 第三者への提供

第三者にいかなる情報も提供しない。

---

## 5. 解析・広告 SDK

Foldex は以下を**一切組み込んでいない**:

- Firebase Analytics / Crashlytics
- Google Analytics
- AdMob 等の広告 SDK
- その他の解析・追跡・ID 収集 SDK

ネットワークライブラリ (SMB / SFTP / FTP / WebDAV のクライアント実装) は使用しているが、いずれもユーザーが指定した宛先以外への通信は行わない。

---

## 6. 子供の利用について

Foldex は 13 歳未満の児童を対象にしたサービスではない。ただし、Foldex が独自に個人情報を収集することは一切ないため、誰が利用しても情報が外部に渡ることはない。

---

## 7. プライバシーポリシーの変更

本ポリシーが変更された場合、本ファイルの「最終更新」日を更新し、アプリの README 等で告知する。

---

## 8. 連絡先

質問・要望は Foldex の GitHub Issues で受け付ける:

- リポジトリ: https://github.com/orgsonai/foldex
- Issues: https://github.com/orgsonai/foldex/issues
