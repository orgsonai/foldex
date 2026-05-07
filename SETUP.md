# Foldex リポジトリ セットアップ手順

このパッケージを Claude Code で動かすまでの手順。

---

## 0. 前提

- Git がインストール済み
- `gh` (GitHub CLI) は任意
- Claude Code CLI が使える状態
- ローカルに Foldex 用のディレクトリを作る場所がある

---

## 1. 配置するファイル一覧

このセットアップで生成されたファイルツリーは以下:

```
foldex/
├── CLAUDE.md
├── FOLDEX-HANDOFF.md            ← 既存 (ナレッジから配置)
├── README.md
├── LICENSE                       (プレースホルダ)
├── .claude/
│   └── settings.json
├── .editorconfig
├── .gitignore
├── .gitattributes
├── docs/
│   ├── PHASES.md
│   ├── CONTRIBUTING.md
│   └── ADR/
│       └── README.md
└── scripts/
    ├── phase-start.sh
    └── phase-finish.sh
```

`FOLDEX-HANDOFF.md` は既存のものをそのままリポジトリのルートに配置する。
このセットアップの zip を展開した後、`FOLDEX-HANDOFF.md` を手で置けばOK。

---

## 2. 初期セットアップ手順

```bash
# ① ディレクトリを作って移動
mkdir -p ~/projects/foldex
cd ~/projects/foldex

# ② このセットアップの中身を全部展開してこのディレクトリに置く
#    (zip を解凍するなり cp -r するなりして配置)

# ③ FOLDEX-HANDOFF.md を配置 (既存仕様書をコピー)
#    例: cp /path/to/FOLDEX-HANDOFF.md .

# ④ Git 初期化
git init -b main

# ⑤ ユーザー設定 (まだなら)
git config user.name  "orgson"
git config user.email "your-email@example.com"

# ⑥ 初回コミット (P1 開始前のスナップショット)
git add .
git commit -m "chore(repo): リポジトリ初期化、CLAUDE.md / docs / scripts 整備

Foldex の初期セットアップ。
- CLAUDE.md (Claude Code 用ルール)
- .claude/settings.json (権限設定)
- docs/PHASES.md (フェーズ運用ルール)
- docs/CONTRIBUTING.md (コミット規約)
- scripts/phase-{start,finish}.sh

Refs: FOLDEX-HANDOFF.md"

# ⑦ リモート追加 (任意、push はしない方針なのでまだ)
# git remote add origin git@github.com:orgsonai/foldex.git
```

---

## 3. P1 を開始する

```bash
# P1 ブランチを切る
bash scripts/phase-start.sh P1

# Claude Code を起動 (このディレクトリで)
claude

# Claude Code に最初に投げる指示の例:
#   "CLAUDE.md と FOLDEX-HANDOFF.md と docs/PHASES.md を読んで、
#    P1 (プロジェクトスケルトン) の達成条件を順に潰してください。
#    1ステップごとに小さくコミットを積んで、各ステップが終わるたびに
#    docs/PHASES.md のチェックリストにチェックを入れてください。"
```

---

## 4. フェーズ完了

```bash
# 達成条件を全部満たしたら
bash scripts/phase-finish.sh P1

# このスクリプトは以下を行う:
# 1. 未コミット変更チェック
# 2. ./gradlew clean assembleDebug でビルド確認
# 3. タグメッセージをエディタで編集
# 4. main に --no-ff でマージ
# 5. v0.1.0-P1 アノテートタグ作成
# (push はしない)

# リモートがある場合、最後に手動で:
# git push origin main
# git push origin v0.1.0-P1
```

---

## 5. 各フェーズの目安

| フェーズ | タグ | 想定期間 |
|---|---|---|
| P1 スケルトン | v0.1.0-P1 | 1週間 |
| P2 ローカル read-only | v0.2.0-P2 | 1〜2週間 |
| P3 ローカル CRUD | v0.3.0-P3 | 1〜2週間 |
| P4 SMB | v0.4.0-P4 | 2週間 |
| P5 SFTP/FTP/WebDAV | v0.5.0-P5 | 2〜3週間 |
| P6 サーバー + 同期 | v0.6.0-P6 | 3週間 |
| P7 UI洗練 | v0.9.0-P7 | 2週間 |
| P8 双方向同期 + 配布 | v1.0.0 | 2週間 |

---

## 6. トラブルシュート

### `phase-finish.sh` がビルドで落ちる
- ビルドエラーを直してから再実行する
- 緊急避難として `--skip-build` 指定可:
  `bash scripts/phase-finish.sh P1 --skip-build`

### Claude Code が指示外のことをしようとする
- `CLAUDE.md §0` の絶対ルールに違反しているはず
- 「CLAUDE.md §0 に従って一旦止まって」と指示する

### `git push` をしてしまった
- `.claude/settings.json` の `deny` リストに `git push` を入れているので、
  Claude Code 経由では発生しないはず
- 手動で間違えた場合は `git revert` で対応 (force push は推奨しない)

---

最終更新: 2026-05-07 (初版)
