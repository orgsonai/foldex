# CLAUDE.md — Foldex プロジェクトルール

このファイルは Claude Code がこのリポジトリで作業するときに最初に読むルールブックである。
仕様の真実 (single source of truth) は `FOLDEX-HANDOFF.md`。本ファイルは「Claude Code がどう振る舞うか」だけを書く。

---

## 0. 絶対ルール (これだけは破らない)

1. **`FOLDEX-HANDOFF.md` の確定事項に逆らわない**
   矛盾しそうな実装をする前に、必ず該当セクションを引用してユーザーに確認する。
2. **フェーズを跨ぐ変更を一気に行わない**
   現フェーズの目標 (`docs/PHASES.md`) を超える実装は、勝手に始めない。
3. **コミットメッセージは Conventional Commits + 日本語OK**
   `feat:` / `fix:` / `refactor:` / `docs:` / `chore:` / `test:` / `build:` / `ci:` を必ず先頭に付ける。
4. **`git push` は明示指示があるまで絶対に行わない**
   ローカルコミットまでは自動。リモート反映は手動承認。
5. **秘匿情報をリポジトリに入れない**
   署名鍵 (`*.jks`, `*.keystore`)、`local.properties`、`gradle.properties` のシークレット類は `.gitignore` 必須。

---

## 1. プロジェクト概要 (要約)

| 項目 | 値 |
|---|---|
| アプリ名 | Foldex |
| パッケージ | `com.zerotoship.foldex` |
| 言語 / UI | Kotlin + Jetpack Compose |
| アーキ | マルチモジュール (12モジュール) |
| DI | Hilt |
| DB | Room + DataStore + AndroidKeyStore (AES-GCM) |
| 最低SDK | minSdk 26 想定 (要確認) / targetSdk 最新 |
| ライセンス | 未定 (GPL-3.0 想定) |
| シリーズ | Zero to Ship |

詳細は `FOLDEX-HANDOFF.md` を見ること。本ファイル内に重複させない。

---

## 2. ディレクトリ構造 (期待値)

```
foldex/
├── CLAUDE.md                  # 本ファイル (Claude Code 用ルール)
├── FOLDEX-HANDOFF.md          # 仕様書 (生きた設計ドキュメント)
├── README.md                  # 公開向け概要
├── LICENSE                    # 確定後に追加
├── .claude/
│   └── settings.json          # Claude Code 設定
├── .editorconfig
├── .gitignore
├── .gitattributes
├── docs/
│   ├── PHASES.md              # フェーズ毎の Git 運用
│   ├── CONTRIBUTING.md        # コミット/ブランチ規約
│   └── ADR/                   # Architecture Decision Records (任意)
├── scripts/
│   ├── phase-start.sh         # フェーズ開始 (ブランチ作成)
│   └── phase-finish.sh        # フェーズ完了 (タグ付け & main へ反映)
├── gradle/
│   └── libs.versions.toml     # Version Catalog
├── settings.gradle.kts
├── build.gradle.kts
├── app/
├── core/
│   ├── core-common/
│   ├── core-model/
│   └── core-data/
├── storage/
│   ├── storage-local/
│   ├── storage-smb/
│   ├── storage-sftp/
│   ├── storage-ftp/
│   └── storage-webdav/
├── server/
└── sync/
```

P1 の段階で全12モジュールの骨格を空でもよいので作る (他フェーズの作業を阻害しないため)。

---

## 3. コーディング規約

### 3-A. Kotlin
- 公式 [Kotlin Coding Conventions](https://kotlinlang.org/docs/coding-conventions.html) に準拠
- `ktlint` で自動整形 (Spotless 経由で `./gradlew spotlessApply`)
- `detekt` で静的解析 (`./gradlew detekt`)
- 命名規則:
  - クラス: `PascalCase`
  - 関数・変数: `camelCase`
  - 定数: `SCREAMING_SNAKE_CASE`
  - パッケージ: 全て小文字、ドット区切り

### 3-B. Compose
- Composable は **PascalCase** (例: `FileListItem`)
- Stateless と Stateful を分離。Stateful 側は ViewModel から hoisting 経由で受ける。
- `Modifier` は引数の最後の任意パラメータ (`modifier: Modifier = Modifier`)
- プレビューは `@Preview(showBackground = true)` を最低1つ

### 3-C. コメント
- 日本語OK (公開ライブラリ部分のみ英語)
- 「なぜ」を書く。「何を」はコードで読めるべき。
- TODO は `// TODO(orgson): ...` または GitHub Issue 番号付き

### 3-D. ファイル分割
- 1ファイル1クラスを基本とする (関連 sealed クラス/enum はまとめてOK)
- `internal` を積極的に使い、モジュール公開APIを最小化

---

## 4. アーキテクチャ厳守事項

### 4-A. 依存方向
```
app → storage-* / server / sync → core-data → core-model → core-common
```
- 横方向 (`storage-smb` ↔ `storage-sftp` 等) の依存は **禁止**
- `core-common` は **純Kotlin** (Android依存禁止)
- `core-model` も **純Kotlin** が望ましい (`kotlinx-datetime` のみ可)
- Android API は `core-data` 以下に閉じ込める

新しいモジュール間依存を導入する前に、必ず人間に確認すること。

### 4-B. StorageProvider
- 全ストレージ実装は `core-model` の `StorageProvider` インターフェースを実装
- 実装はファクトリ経由で `app` モジュールから注入 (Hilt Multibinding 想定)
- ストレージ実装内部で他のストレージ実装を import しない

### 4-C. Result 型
- 例外を投げない。`core-common` の `Result<T, E : StorageError>` で返す
- Java の `kotlin.Result` ではなく独自定義を使う (sealed class 想定)
- coroutine の `CancellationException` だけは再throwする

---

## 5. Claude Code の作業ループ

### 5-A. 何かを実装する前に必ず確認すること
1. 今は何フェーズか? (`git branch --show-current` または `docs/PHASES.md`)
2. その作業はそのフェーズの範囲内か?
3. `FOLDEX-HANDOFF.md` の該当セクションは何を言っているか?
4. 既存コードに同じ責務のクラスがないか? (`rg` で検索)

### 5-B. 実装中の流儀
- **小さくコミット**: 1つの論理単位で1コミット
- **コミット前に必ずビルド or テスト実行**
  - 単発: `./gradlew :module:assembleDebug`
  - 全体: `./gradlew assembleDebug`
- **失敗したコミットを残さない**: 失敗してたら `--amend` か `git reset --soft HEAD~1` で直す
- **生成物をコミットしない**: `build/`, `.gradle/`, `local.properties` などは絶対に入れない

### 5-C. 実装後の流儀
- 動作確認できた時点で `docs/PHASES.md` の該当チェックリストにチェックを入れる
- フェーズ完了の判断はユーザーが行う。Claude は「P○ の達成条件を全て満たしました、完了タグ付けますか?」と確認する。

---

## 6. Git 運用 (要点)

詳細は `docs/PHASES.md` と `docs/CONTRIBUTING.md` を参照。要点だけここに:

### 6-A. ブランチ戦略
- `main` — 各フェーズ完了スナップショット (タグ付き)
- `phase/P1-skeleton` — フェーズ作業ブランチ
- `feat/<short-name>` — フェーズ内の小さな機能ブランチ (任意)

### 6-B. フェーズ運用
1. フェーズ開始: `bash scripts/phase-start.sh P1` で `phase/P1-skeleton` 作成
2. フェーズ中: 小さなコミットを積む
3. フェーズ完了: `bash scripts/phase-finish.sh P1` で `main` にマージ + `v0.1.0-P1` タグ

### 6-C. コミットメッセージ
```
<type>(<scope>): <短い要約>

<本文 (必要なら)>

Refs: FOLDEX-HANDOFF.md §<セクション番号>
```

例:
```
feat(core-model): StorageProvider インターフェースを定義

list/stat/openInput など最小12メソッドを定義。
Result<T, StorageError> を返すように統一。

Refs: FOLDEX-HANDOFF.md §5-C
```

---

## 7. ビルド & テスト

### 7-A. よく使うコマンド
| 目的 | コマンド |
|---|---|
| 全体ビルド | `./gradlew assembleDebug` |
| 単一モジュール | `./gradlew :core:core-model:assemble` |
| ユニットテスト | `./gradlew test` |
| Instrumented test | `./gradlew connectedAndroidTest` |
| 静的解析 | `./gradlew detekt` |
| フォーマット | `./gradlew spotlessApply` |
| クリーン | `./gradlew clean` |

### 7-B. AGP / Kotlin
- AGP: 9.1.1 系 (Kotlin 内蔵) を想定
- KSP2: `2.2.10-2.0.2` 想定
- 詳細は `gradle/libs.versions.toml` を真とする

### 7-C. テスト方針
- `core-common` / `core-model` は **JVM ユニットテスト必須**
- `core-data` は Robolectric or Instrumented で Room を回す
- `storage-*` 各実装はモック or テスト用サーバー (testcontainers) で
- UI は P7 まで広範囲には書かない (重要な ViewModel ロジックのみ)

---

## 8. やってはいけないことリスト

- ❌ 仕様書 (`FOLDEX-HANDOFF.md`) の確定事項に反する実装を勝手に始める
- ❌ フェーズを越境した実装 (例: P2 で SMB を入れ始める)
- ❌ `git push` を勝手に実行する
- ❌ ライセンス確定前に `LICENSE` ファイルを書く (空のプレースホルダのみOK)
- ❌ Google Play 署名鍵などのシークレットをコミットする
- ❌ 生成物 (`build/`, `.gradle/`) をコミットする
- ❌ コミットメッセージに `WIP` / `fix typo` を量産する (squash前提でも避ける)
- ❌ 大きな依存ライブラリを仕様書に書かれていないものから勝手に導入する
- ❌ Compose と View System を混在させる (やむを得ない場合は事前確認)
- ❌ `rclone` を組み込む (仕様書で明示的に排除)

---

## 9. 連絡 / 確認のしかた

不明点が出たときは、勝手に推測で書かず、以下のテンプレで相談する:

```
[確認] FOLDEX-HANDOFF.md §<番号> について
- 現状の理解: <Claude の解釈>
- 不明点: <何が分からないか>
- 案A: <選択肢A とトレードオフ>
- 案B: <選択肢B とトレードオフ>
どちらで進めますか?
```

---

## 10. このファイル自体の更新

- `CLAUDE.md` の更新は `docs:` プレフィックスでコミット
- 大きなルール変更は別ブランチで PR レビュー (1人開発でも一度立ち止まる)
- 仕様書 (`FOLDEX-HANDOFF.md`) と矛盾しないこと

---

最終更新: 2026-05-07 (初版)
