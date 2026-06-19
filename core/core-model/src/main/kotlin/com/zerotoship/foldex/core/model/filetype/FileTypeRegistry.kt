package com.zerotoship.foldex.core.model.filetype

/**
 * 拡張子からファイルカテゴリと MIME タイプを引く静的テーブル。
 *
 * 純 Kotlin。Android の `MimeTypeMap` には依存しない (core-model は Android 非依存)。
 * マジックナンバー判定や SAF の mimeType を信頼する処理は上位層 (core-data) で行う。
 */
object FileTypeRegistry {

    private val imageExt = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "avif", "svg", "ico", "tiff", "tif")
    private val videoExt = setOf("mp4", "mkv", "webm", "mov", "avi", "wmv", "flv", "m4v", "3gp", "mpeg", "mpg", "ts")
    private val audioExt = setOf("mp3", "m4a", "aac", "flac", "ogg", "oga", "opus", "wav", "wma", "amr", "mid", "midi")
    private val markdownExt = setOf("md", "markdown", "mkd", "mdown")
    private val htmlExt = setOf("html", "htm", "xhtml")
    private val archiveExt = setOf("zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "tbz2", "xz", "txz", "lz", "lzma", "z", "jar")
    private val officeExt = setOf("doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "odp", "rtf")
    private val isoExt = setOf("iso", "img", "dmg", "vhd", "vhdx")
    private val apkExt = setOf("apk", "apks", "xapk")
    private val binaryExt = setOf("exe", "msi", "bin", "so", "dll", "o", "a", "class", "dex", "deb", "rpm", "appimage")

    // 「拡張子はテキストっぽいが特殊な MIME を持つ/持たないもの」を含むテキスト系。
    private val textExt = setOf(
        "txt", "text", "log", "ini", "cfg", "conf", "properties", "env",
        "json", "json5", "jsonl", "ndjson", "yaml", "yml", "toml",
        "xml", "csv", "tsv",
        "kt", "kts", "java", "scala", "groovy", "gradle",
        "c", "h", "cpp", "cc", "cxx", "hpp", "hh", "m", "mm",
        "py", "pyw", "rb", "rs", "go", "swift", "php", "pl", "pm", "lua", "dart",
        "js", "mjs", "cjs", "jsx", "ts", "tsx", "css", "scss", "sass", "less",
        "sh", "bash", "zsh", "fish", "bat", "cmd", "ps1",
        "sql", "graphql", "gql", "proto",
        "gitignore", "gitattributes", "editorconfig", "dockerfile",
        "srt", "vtt", "ass", "lrc",
        "tex", "bib",
    )

    private val mimeOverrides: Map<String, String> = mapOf(
        // image
        "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "png" to "image/png", "webp" to "image/webp",
        "gif" to "image/gif", "bmp" to "image/bmp", "heic" to "image/heic", "heif" to "image/heif",
        "avif" to "image/avif", "svg" to "image/svg+xml", "ico" to "image/x-icon",
        "tiff" to "image/tiff", "tif" to "image/tiff",
        // video
        "mp4" to "video/mp4", "m4v" to "video/mp4", "mkv" to "video/x-matroska", "webm" to "video/webm",
        "mov" to "video/quicktime", "avi" to "video/x-msvideo", "3gp" to "video/3gpp",
        "mpeg" to "video/mpeg", "mpg" to "video/mpeg", "ts" to "video/mp2t", "wmv" to "video/x-ms-wmv", "flv" to "video/x-flv",
        // audio
        "mp3" to "audio/mpeg", "m4a" to "audio/mp4", "aac" to "audio/aac", "flac" to "audio/flac",
        "ogg" to "audio/ogg", "oga" to "audio/ogg", "opus" to "audio/opus", "wav" to "audio/x-wav",
        "wma" to "audio/x-ms-wma", "amr" to "audio/amr", "mid" to "audio/midi", "midi" to "audio/midi",
        // text / code
        "txt" to "text/plain", "log" to "text/plain", "csv" to "text/csv", "tsv" to "text/tab-separated-values",
        "json" to "application/json", "json5" to "application/json", "jsonl" to "application/json", "ndjson" to "application/json",
        "yaml" to "application/yaml", "yml" to "application/yaml", "toml" to "application/toml",
        "xml" to "text/xml", "css" to "text/css", "js" to "text/javascript", "mjs" to "text/javascript",
        "md" to "text/markdown", "markdown" to "text/markdown",
        "html" to "text/html", "htm" to "text/html", "xhtml" to "application/xhtml+xml",
        "sh" to "application/x-sh", "sql" to "application/sql",
        "srt" to "application/x-subrip", "vtt" to "text/vtt",
        // documents
        "pdf" to "application/pdf",
        "doc" to "application/msword",
        "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "xls" to "application/vnd.ms-excel",
        "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "ppt" to "application/vnd.ms-powerpoint",
        "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "odt" to "application/vnd.oasis.opendocument.text",
        "ods" to "application/vnd.oasis.opendocument.spreadsheet",
        "odp" to "application/vnd.oasis.opendocument.presentation",
        "rtf" to "application/rtf",
        // archive
        "zip" to "application/zip", "jar" to "application/java-archive",
        "rar" to "application/vnd.rar", "7z" to "application/x-7z-compressed",
        "tar" to "application/x-tar", "gz" to "application/gzip", "tgz" to "application/gzip",
        "bz2" to "application/x-bzip2", "xz" to "application/x-xz",
        // packages / images
        "apk" to "application/vnd.android.package-archive",
        "iso" to "application/x-iso9660-image", "dmg" to "application/x-apple-diskimage",
        "exe" to "application/vnd.microsoft.portable-executable", "msi" to "application/x-msi",
        "deb" to "application/vnd.debian.binary-package", "rpm" to "application/x-rpm",
    )

    /** 拡張子部分 (小文字、ドットなし)。ファイル名に拡張子がなければ全体を小文字で返す (例: `dockerfile`)。 */
    private fun ext(name: String): String {
        val base = name.substringAfterLast('/').substringAfterLast('\\')
        val dot = base.lastIndexOf('.')
        return if (dot <= 0 || dot == base.length - 1) base.lowercase() else base.substring(dot + 1).lowercase()
    }

    fun categorize(name: String): Category {
        val e = ext(name)
        // 二重拡張子 (.tar.gz 等) は上で末尾だけ取れているので gz/bz2/xz が archive 扱いになり問題ない。
        return when {
            e in imageExt -> Category.IMAGE
            e in videoExt -> Category.VIDEO
            e in audioExt -> Category.AUDIO
            e in markdownExt -> Category.MARKDOWN
            e in htmlExt -> Category.HTML
            e == "pdf" -> Category.PDF
            e in apkExt -> Category.APK
            e in archiveExt -> Category.ARCHIVE
            e in officeExt -> Category.OFFICE
            e in isoExt -> Category.ISO
            e in textExt -> Category.TEXT
            e in binaryExt -> Category.BINARY
            else -> Category.UNKNOWN
        }
    }

    /** 既知の拡張子なら MIME タイプを返す。未知なら null (上位層で汎用 MIME へフォールバックする)。 */
    fun mimeTypeFor(name: String): String? {
        val e = ext(name)
        mimeOverrides[e]?.let { return it }
        return when (categorize(name)) {
            Category.TEXT -> "text/plain"
            Category.MARKDOWN -> "text/markdown"
            Category.HTML -> "text/html"
            else -> null
        }
    }
}
