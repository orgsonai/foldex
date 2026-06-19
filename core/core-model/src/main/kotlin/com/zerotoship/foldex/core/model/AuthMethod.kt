// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.core.model

enum class AuthMethod(val wireName: String) {
    PASSWORD("password"),
    PUBLIC_KEY("publickey"),
    ANONYMOUS("anonymous"),
    ;

    companion object {
        fun fromWireName(value: String): AuthMethod =
            entries.firstOrNull { it.wireName == value }
                ?: error("Unknown AuthMethod wire name: $value")
    }
}
