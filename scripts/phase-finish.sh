#!/usr/bin/env bash
# SPDX-License-Identifier: MIT
# Copyright (c) 2026 Zero to Ship

# =========================================================
# Foldex phase-finish.sh
# 使い方: bash scripts/phase-finish.sh P1
# 動作:
#   1. 現在 phase/P<番号>-* ブランチにいることを確認
#   2. 未コミット変更がない・ビルドが通ることを確認 (任意で skip)
#   3. main に --no-ff でマージ
#   4. アノテートタグ v0.<番号>.0-P<番号> を打つ
#   5. push はしない (ユーザーが手動で行う)
# =========================================================
set -euo pipefail

# --- 引数チェック ---
if [ $# -lt 1 ]; then
  cat <<EOF >&2
使い方: $0 <フェーズ番号> [--skip-build]
例:
  $0 P1
  $0 P1 --skip-build
EOF
  exit 1
fi

PHASE_NUM_RAW="$1"
PHASE_NUM="$(echo "$PHASE_NUM_RAW" | tr '[:lower:]' '[:upper:]')"
case "$PHASE_NUM" in
  P*) ;;
  *) PHASE_NUM="P${PHASE_NUM}" ;;
esac

SKIP_BUILD=0
if [ "${2:-}" = "--skip-build" ]; then
  SKIP_BUILD=1
fi

# --- フェーズ → タグ名マッピング ---
case "$PHASE_NUM" in
  P1) TAG="v0.1.0-P1" ;;
  P2) TAG="v0.2.0-P2" ;;
  P3) TAG="v0.3.0-P3" ;;
  P4) TAG="v0.4.0-P4" ;;
  P5) TAG="v0.5.0-P5" ;;
  P6) TAG="v0.6.0-P6" ;;
  P7) TAG="v0.9.0-P7" ;;
  P8) TAG="v1.0.0" ;;
  *)
    echo "未知のフェーズ番号: $PHASE_NUM (P1〜P8 のみ対応)" >&2
    exit 1
    ;;
esac

# --- リポジトリ確認 ---
if ! git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  echo "ここは Git リポジトリではない" >&2
  exit 1
fi

# --- 現在ブランチ確認 ---
CURRENT="$(git branch --show-current)"
EXPECTED_PREFIX="phase/${PHASE_NUM}-"
case "$CURRENT" in
  ${EXPECTED_PREFIX}*) ;;
  *)
    echo "❌ 現在のブランチ ($CURRENT) は ${EXPECTED_PREFIX}* ではない" >&2
    echo "   先に: git switch ${EXPECTED_PREFIX}<名前>" >&2
    exit 1
    ;;
esac

# --- 未コミット変更チェック ---
if [ -n "$(git status --porcelain)" ]; then
  echo "❌ 未コミットの変更がある:" >&2
  git status --short >&2
  exit 1
fi

# --- 既存タグチェック ---
if git rev-parse "${TAG}" >/dev/null 2>&1; then
  echo "❌ タグ ${TAG} は既に存在する" >&2
  exit 1
fi

# --- ビルド確認 ---
if [ "$SKIP_BUILD" -eq 0 ]; then
  if [ -x "./gradlew" ]; then
    echo "→ ビルド確認: ./gradlew clean assembleDebug"
    ./gradlew clean assembleDebug
  else
    echo "(gradlew がまだ無いのでビルドチェックをスキップ)"
  fi
else
  echo "(--skip-build 指定のためビルドチェックをスキップ)"
fi

# --- フェーズサマリの入力 ---
SUMMARY_FILE="$(mktemp)"
cat > "$SUMMARY_FILE" <<EOF
${PHASE_NUM} 完了サマリ

(↑ この上の行を編集してフェーズ達成内容を要約してください。
 1行目はそのままタグメッセージのタイトルになります。)

主な達成項目:
- 

参照:
- docs/PHASES.md (${PHASE_NUM} 章)
- FOLDEX-HANDOFF.md
EOF

EDITOR="${EDITOR:-${VISUAL:-vi}}"
echo ""
echo "→ タグメッセージを編集 (${EDITOR})"
"$EDITOR" "$SUMMARY_FILE"

# 内容が空でないか確認
if [ ! -s "$SUMMARY_FILE" ] || ! grep -q "[^[:space:]]" "$SUMMARY_FILE"; then
  echo "❌ タグメッセージが空のため中止" >&2
  rm -f "$SUMMARY_FILE"
  exit 1
fi

# --- main にマージ ---
echo ""
echo "→ main に切替"
git checkout main

echo "→ ${CURRENT} を --no-ff で main にマージ"
git merge --no-ff "${CURRENT}" -m "merge: ${CURRENT} を main にマージ (${PHASE_NUM} 完了)"

# --- タグ付け ---
echo "→ アノテートタグ ${TAG} を作成"
git tag -a "${TAG}" -F "${SUMMARY_FILE}"

rm -f "$SUMMARY_FILE"

# --- 完了報告 ---
echo ""
echo "✅ ${PHASE_NUM} 完了"
echo "   ブランチ: ${CURRENT} → main にマージ済"
echo "   タグ: ${TAG}"
echo ""
echo "次にやること:"
echo "  - リモートに反映する場合: git push origin main && git push origin ${TAG}"
echo "  - 次フェーズに進む場合:    bash scripts/phase-start.sh P<次番号>"
echo "  - 完了ブランチを削除する場合: git branch -d ${CURRENT}"
