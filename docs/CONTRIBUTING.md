# CONTRIBUTING — Foldex 開発のお作法

1人開発でも、未来の自分とAIエージェントが読みやすいように規約を決めておく。

---

## 1. コミットメッセージ規約 (Conventional Commits)

### 1-A. フォーマット
```
<type>(<scope>): <短い要約 (50字以内、日本語OK)>

<本文 (なぜ・どう変えたか、必要なら)>

Refs: FOLDEX-HANDOFF.md §<セクション番号>
```

### 1-B. type 一覧

| type | 用途 |
|---|---|
| `feat` | 新機能 |
| `fix` | バグ修正 |
| `refactor` | 機能を変えないコード整理 |
| `perf` | パフォーマンス改善 |
| `style` | フォーマットのみ (ktlint/spotless) |
| `docs` | ドキュメントのみ |
| `test` | テスト追加・修正 |
| `build` | Gradle / Version Catalog / 依存関係 |
| `ci` | CI 設定 |
| `chore` | その他 (リポジトリ雑務) |
| `revert` | リバート |

### 1-C. scope の例
- モジュール名: `core-model`, `storage-smb`, `app`, `server`, `sync`
- 機能名: `auth`, `theme`, `i18n`, `nav`
- インフラ: `gradle`, `repo`, `ci`

### 1-D. 良い例 / 悪い例

✅ 良い例
```
feat(storage-smb): smbj 接続のリトライポリシーを実装

3回まで指数バックオフで再接続する。timeoutEnvelope は接続設定から渡す。
DNS 失敗だけは即時失敗 (リトライしない)。

Refs: FOLDEX-HANDOFF.md §5-C
```

❌ 悪い例 (避ける)
```
WIP
fix typo
update
作業中
```

---

## 2. ブランチ規約

### 2-A. ブランチ種別

| 種別 | 命名 | 用途 |
|---|---|---|
| 安定 | `main` | 各フェーズ完了スナップショット |
| フェーズ | `phase/P<番号>-<名前>` | フェーズ作業の本流 |
| 機能 | `feat/<short>` | フェーズ内の小機能 (任意) |
| 修正 | `fix/<short>` | フェーズ内の小修正 (任意) |
| ホットフィックス | `hotfix/<短い名前>` | リリース後の緊急修正 (P8 以降) |

### 2-B. マージ戦略

- `phase/*` → `main`: **必ず `--no-ff`** (フェーズ境界をマージコミットで残す)
- `feat/*` → `phase/*`: **fast-forward 可** (履歴を綺麗にしたい場合は `--no-ff` でも可)
- `main` への直接コミットは **しない** (フェーズ内ドキュメント微修正だけ例外)

---

## 3. プルリクエスト/レビューのお作法 (1人でも一応)

- 大きな仕様変更を1人で勝手に決めない (一晩寝かせる)
- ADR (Architecture Decision Record) を `docs/ADR/0001-*.md` に書いてから実装するのが望ましい
- Claude Code が変更を提案するときは差分を要約してから実装に入る

---

## 4. テストのお作法

### 4-A. 何をテストするか
| モジュール | テスト方針 |
|---|---|
| `core-common` | 純Kotlin、JVM ユニットテスト必須 |
| `core-model` | 純Kotlin、JVM ユニットテスト必須 |
| `core-data` | Room を Robolectric or Instrumented で |
| `storage-local` | Instrumented (実機/エミュ前提) |
| `storage-smb` 等 | testcontainers でテスト用サーバー (CI で重要) |
| `server` | Instrumented (ポートバインドが必要) |
| `sync` | 純ロジックは JVM、Worker は WorkManager TestKit |
| `app` | UI テストは P7 以降に最小限 |

### 4-B. 命名
- `<対象クラス>Test.kt` (JVM)
- `<対象クラス>InstrumentedTest.kt` (Instrumented)
- テスト関数: `` `動作 — 条件 — 期待` `` のバッククォート関数名OK

例:
```kotlin
@Test
fun `list - 隠しファイルを含めない設定で - 隠しファイルが返らない`() { ... }
```

---

## 5. 依存追加のお作法

新しい依存ライブラリを追加するとき:

1. **`FOLDEX-HANDOFF.md §3` (技術スタック表) に書かれているか確認**
2. 書かれていない場合は **必ず人間に確認**
3. 追加するときは `gradle/libs.versions.toml` に書く (`build.gradle.kts` に直書きしない)
4. ライセンスを確認し、`docs/LICENSES.md` (将来作成) に追記

```toml
# gradle/libs.versions.toml の例
[versions]
smbj = "0.14.0"

[libraries]
smbj = { module = "com.hierynomus:smbj", version.ref = "smbj" }
```

---

## 6. ファイル先頭のヘッダ

- 公開予定ライブラリ部分には SPDX ライセンスヘッダを付ける (P8 でライセンス確定後)
- 内部コードはヘッダなしでよい

---

## 7. シークレット管理

- `local.properties` にシークレットを書く → コミットしない (`.gitignore` 済み)
- 公開できない値は `gradle.properties` に書かず、`local.properties` から `Properties` で読み込む
- CI 用は GitHub Actions Secrets を使う (P8 以降)

---

## 8. レビューポイント (Claude Code が変更を加えたときの自分用チェックリスト)

- [ ] フェーズ範囲内か?
- [ ] 仕様書 (`FOLDEX-HANDOFF.md`) と矛盾していないか?
- [ ] 依存方向ルールを守っているか?
- [ ] 新しい依存ライブラリが入っていないか? (入っていたら確認済みか?)
- [ ] テストはあるか? (該当する場合)
- [ ] コミットメッセージが規約に沿っているか?
- [ ] `build/` などが入っていないか?
- [ ] シークレットが入っていないか?
