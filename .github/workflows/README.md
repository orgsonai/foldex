# GitHub Actions ワークフロー

## `release.yml` — タグ push でリリース APK を自動ビルド

### 動作

| トリガー | 動作 |
|---|---|
| タグ push (`v*`) | `assembleRelease` → APK 生成 → GitHub Release に APK 添付 + 自動リリースノート |
| 手動 (`workflow_dispatch`) | 指定タグをチェックアウトして APK 生成 (Release は作らない、Artifact のみ) |

リリース判定 (prerelease):
- タグに `-` を含む (例: `v0.9.0-P7`, `v1.0.0-rc1`) → prerelease として公開
- そうでない (例: `v1.0.0`) → 正式リリースとして公開

### 必要な GitHub Secrets

正式署名で配布する場合、以下のシークレットを **Settings → Secrets and variables → Actions → New repository secret** から登録する:

| Secret 名 | 内容 |
|---|---|
| `SIGNING_KEYSTORE_BASE64` | `release.keystore` を `base64 -w0 release.keystore` でエンコードした文字列 |
| `SIGNING_STORE_PASSWORD` | キーストアのパスワード |
| `SIGNING_KEY_ALIAS` | 鍵のエイリアス |
| `SIGNING_KEY_PASSWORD` | 鍵のパスワード |

`SIGNING_KEYSTORE_BASE64` が未設定の場合、ワークフローは警告を出しつつ **debug 鍵にフォールバック**してビルドだけ通る (実用配布には不適、動作確認のみ)。

### 使い方

```bash
# ローカル
git tag v0.2.38
git push origin v0.2.38
# → 自動的にビルド開始、Actions タブで進行確認、完了後 Releases に APK 添付済み
```

GitHub Releases から `foldex-release-*.apk` をダウンロードして配布できる。

### 注意

- `keystore.properties` と `release.keystore` はワークフロー終了時に `Cleanup keystore` ステップで必ず削除される。
- Gradle daemon は `--no-daemon` で無効化 (CI の二重実行回避)。

最終更新: 2026-06-20 (初版、0.2.38 / versionCode 40)
