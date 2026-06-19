#!/usr/bin/env bash
# SPDX-License-Identifier: MIT
# Copyright (c) 2026 Zero to Ship

# =========================================================
# Foldex collect-knowledge.sh
#   Claude のチャット版 (claude.ai) の「ナレッジ / プロジェクトファイル」へ
#   一括アップロードするために、ソース/ドキュメント類を 1 つのフォルダへ
#   フラットにコピーする。フォルダ階層は持たず、元のパスはファイル名へ
#   エンコードする (例: app/src/main/.../FileBrowserScreen.kt
#                       -> app__src__main__..__FileBrowserScreen.kt)。
#
# 使い方:
#   bash scripts/collect-knowledge.sh            # -> /tmp/foldex-knowledge へ
#   bash scripts/collect-knowledge.sh /path/out  # 出力先を指定
#
# 拾うもの: *.kt *.kts *.md *.toml *.pro / AndroidManifest.xml / proguard*.txt
#           ルート直下の .gitignore .gitattributes .editorconfig
# 除外     : build/ .gradle/ .git/ .idea/ .kotlin/ generated/ など生成物
# =========================================================
set -euo pipefail

# リポジトリルート (このスクリプトの 1 つ上) へ移動
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$REPO_ROOT"

OUT_DIR="${1:-/tmp/foldex-knowledge}"

# 出力先を作り直す
rm -rf "$OUT_DIR"
mkdir -p "$OUT_DIR"

# 除外パターン (パス中にこれらの要素を含むものはスキップ)
PRUNE_DIRS=(build .gradle .git .idea .kotlin node_modules .cxx generated intermediates outputs tmp .fleet)

# find の prune 式を組み立てる
prune_expr=()
for d in "${PRUNE_DIRS[@]}"; do
  prune_expr+=( -path "*/$d" -o -path "*/$d/*" -o )
done
# 末尾の -o を取り除く
unset 'prune_expr[${#prune_expr[@]}-1]'

count=0
while IFS= read -r -d '' f; do
  rel="${f#./}"
  # パス区切りを __ に置換してフラットなファイル名へ
  flat="${rel//\//__}"
  cp "$f" "$OUT_DIR/$flat"
  count=$((count + 1))
done < <(
  find . \
    \( "${prune_expr[@]}" \) -prune -o \
    -type f \
    \( -name '*.kt' -o -name '*.kts' -o -name '*.md' -o -name '*.toml' \
       -o -name '*.pro' -o -name 'AndroidManifest.xml' -o -name 'proguard*.txt' \
       -o -name 'consumer-rules.pro' \) \
    -print0
)

# ルート直下のドット設定ファイル (任意・あれば)
for dot in .gitignore .gitattributes .editorconfig; do
  [ -f "$dot" ] && cp "$dot" "$OUT_DIR/$dot" && count=$((count + 1))
done

echo "コピー完了: $count ファイル -> $OUT_DIR"
echo "（claude.ai のプロジェクト/ナレッジへこのフォルダの中身をまとめてアップロードしてください）"
