// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.core.model

/**
 * 動画のポップアップ再生の方式。
 *
 * - [SYSTEM]: Android 標準のピクチャー・イン・ピクチャー。許可は要らないが、大きさの上限と
 *   端へ寄る動きは Android が決める。
 * - [FLOATING]: Foldex 自身の小窓。大きさと位置を自由に変えられるが、「他のアプリの上に重ねて表示」の許可が要る。
 */
enum class PopupPlayerMode { SYSTEM, FLOATING }
