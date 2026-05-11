package com.zerotoship.foldex.core.model

sealed class FileUri {
    data class Local(val absolutePath: String) : FileUri()
    data class Saf(val documentUri: String) : FileUri()
    data class Remote(val protocol: Protocol, val connectionId: String, val path: String) : FileUri()

    fun toStorageString(): String = when (this) {
        is Local -> "local://$absolutePath"
        is Saf -> "saf://$documentUri"
        is Remote -> "${protocol.scheme}://$connectionId/$path"
    }

    companion object {
        /**
         * [toStorageString] の逆。`local://...` / `saf://...` / `<scheme>://<connectionId>/<path>` を
         * 解釈する。形式が不正なら [IllegalArgumentException]。
         */
        fun fromStorageString(value: String): FileUri {
            val sep = value.indexOf("://")
            require(sep > 0) { "Unrecognized storage URI: $value" }
            val scheme = value.substring(0, sep)
            val rest = value.substring(sep + 3)
            return when (scheme) {
                "local" -> Local(rest)
                "saf" -> Saf(rest)
                else -> {
                    val protocol = Protocol.entries.firstOrNull { it.scheme == scheme }
                        ?: throw IllegalArgumentException("Unknown storage scheme: $scheme")
                    val slash = rest.indexOf('/')
                    if (slash < 0) {
                        Remote(protocol, rest, "")
                    } else {
                        Remote(protocol, rest.substring(0, slash), rest.substring(slash + 1))
                    }
                }
            }
        }

        fun fromStorageStringOrNull(value: String): FileUri? =
            runCatching { fromStorageString(value) }.getOrNull()
    }
}
