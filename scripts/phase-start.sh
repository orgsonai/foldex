#!/usr/bin/env bash
# SPDX-License-Identifier: MIT
# Copyright (c) 2026 Zero to Ship

# =========================================================
# Foldex phase-start.sh
# 使い方: bash scripts/phase-start.sh P1
# 動作:
#   1. main を最新化 (リモートがあれば fetch、無ければ何もしない)
#   2. main から phase/P<番号>-<名前> ブランチを切る
#   3. 既に存在する場合はそのブランチに switch するだけ
# =========================================================
set -euo pipefail

# --- 引数チェック ---
if [ $# -lt 1 ]; then
  cat <<EOF >&2
使い方: $0 <フェーズ番号>
例:
  $0 P1
  $0 P2
EOF
  exit 1
fi

PHASE_NUM_RAW="$1"
# 大文字化 + P を補完
PHASE_NUM="$(echo "$PHASE_NUM_RAW" | tr '[:lower:]' '[:upper:]')"
case "$PHASE_NUM" in
  P*) ;;
  *) PHASE_NUM="P${PHASE_NUM}" ;;
esac

# --- フェーズ番号 → 短い名前 マッピング ---
case "$PHASE_NUM" in
  P1) PHASE_NAME="skeleton" ;;
  P2) PHASE_NAME="local-readonly" ;;
  P3) PHASE_NAME="local-crud" ;;
  P4) PHASE_NAME="smb" ;;
  P5) PHASE_NAME="remote" ;;
  P6) PHASE_NAME="server-sync" ;;
  P7) PHASE_NAME="polish" ;;
  P8) PHASE_NAME="release" ;;
  *)
    echo "未知のフェーズ番号: $PHASE_NUM (P1〜P8 のみ対応)" >&2
    exit 1
    ;;
esac

BRANCH="phase/${PHASE_NUM}-${PHASE_NAME}"

# --- リポジトリ確認 ---
if ! git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  echo "ここは Git リポジトリではない" >&2
  exit 1
fi

# --- 未コミット変更の警告 ---
if [ -n "$(git status --porcelain)" ]; then
  echo "⚠️  未コミットの変更がある:"
  git status --short
  echo ""
  read -r -p "このまま続ける? (y/N): " ans
  case "$ans" in
    y|Y|yes|YES) ;;
    *) echo "中止"; exit 1 ;;
  esac
fi

# --- main に切替 ---
echo "→ main にチェックアウト"
git checkout main

# --- リモートがあれば fetch ---
if git remote | grep -q .; then
  echo "→ リモートから fetch"
  git fetch --all --prune || echo "(fetch 失敗、続行)"
fi

# --- 既存ブランチか確認 ---
if git show-ref --verify --quiet "refs/heads/${BRANCH}"; then
  echo "→ 既存ブランチ ${BRANCH} に switch"
  git switch "${BRANCH}"
else
  echo "→ 新規ブランチ ${BRANCH} を main から作成"
  git switch -c "${BRANCH}" main
fi

echo ""
echo "✅ ${PHASE_NUM} 開始: ブランチ = ${BRANCH}"
echo ""
echo "次にやること:"
echo "  1. docs/PHASES.md の ${PHASE_NUM} 達成条件を確認"
echo "  2. 小さくコミットを積む"
echo "  3. 達成条件を満たしたら: bash scripts/phase-finish.sh ${PHASE_NUM}"
