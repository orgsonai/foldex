package com.zerotoship.foldex.core.model

sealed class FileUri {
    data class Local(val absolutePath: String) : FileUri()

    /**
     * SAF (StorageAccessFramework) 経由のドキュメント URI。
     *
     * - [documentUri] は SAF picker で取った tree URI、もしくは tree 内の子の document URI。
     * - [pendingChildName] が非 null のときは「[documentUri] の配下に作る予定の子の名前」を表す。
     *   mkdir / openOutput(CREATE_NEW) はこの name を見て親に createDocument を呼ぶ。
     *   既存ノードへの操作 (read / overwrite / delete) ではこのフィールドは null。
     */
    data class Saf(val documentUri: String, val pendingChildName: String? = null) : FileUri()

    data class Remote(val protocol: Protocol, val connectionId: String, val path: String) : FileUri()

    fun toStorageString(): String = when (this) {
        is Local -> "local://$absolutePath"
        // pendingChildName は揮発情報なので storage 文字列には乗せない (永続化されない子)。
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
