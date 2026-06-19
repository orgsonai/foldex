// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.core.model

enum class ServerAuthMode(val wireName: String) {
    ANONYMOUS("anonymous"),
    PASSWORD("password"),
    PUBLIC_KEY("publickey"),
    PASSWORD_OR_PUBLIC_KEY("password_or_publickey"),
    ;

    companion object {
        fun fromWireName(value: String): ServerAuthMode =
            entries.firstOrNull { it.wireName == value }
                ?: error("Unknown ServerAuthMode wire name: $value")
    }
}
