package com.zerotoship.foldex.ui.sync

import com.zerotoship.foldex.core.model.ConflictPolicy
import com.zerotoship.foldex.core.model.SyncDirection

internal fun directionLabel(direction: SyncDirection): String = when (direction) {
    SyncDirection.TO_REMOTE -> "ローカル → リモート (アップロード)"
    SyncDirection.TO_LOCAL -> "リモート → ローカル (ダウンロード)"
}

internal fun directionShortLabel(direction: SyncDirection): String = when (direction) {
    SyncDirection.TO_REMOTE -> "↑ アップロード"
    SyncDirection.TO_LOCAL -> "↓ ダウンロード"
}

internal fun conflictPolicyLabel(policy: ConflictPolicy): String = when (policy) {
    ConflictPolicy.NEWER_WINS -> "新しい方を採用 (推奨)"
    ConflictPolicy.LOCAL_WINS -> "ローカル優先"
    ConflictPolicy.REMOTE_WINS -> "リモート優先"
    ConflictPolicy.KEEP_BOTH -> "両方残す (片方をリネーム)"
    ConflictPolicy.SKIP -> "スキップして通知"
}

internal fun intervalLabel(minutes: Int): String = when {
    minutes <= 0 -> "手動のみ"
    minutes < 60 -> "${minutes}分ごと"
    minutes % 60 == 0 -> "${minutes / 60}時間ごと"
    else -> "${minutes}分ごと"
}
