// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.core.model

/**
 * Material You (壁紙連動カラー) を使わないときのアプリのアクセント配色。
 *
 * 地の面 (background / surface 群) はどの配色でも共通のニュートラルなグレーで、
 * primary とコンテナ色 (HOME タイルなど) だけがここで切り替わる。
 * 実際の色値は app モジュールの `FoldexTheme` が保持する。
 */
enum class AppColorTheme { GREEN, BLUE, PURPLE, TEAL, ORANGE, ROSE }
