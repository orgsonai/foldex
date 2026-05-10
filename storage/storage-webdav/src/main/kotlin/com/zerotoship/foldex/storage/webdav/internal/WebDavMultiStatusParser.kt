package com.zerotoship.foldex.storage.webdav.internal

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * WebDAV PROPFIND の `multistatus` レスポンスを必要なプロパティだけに絞ってパースする。
 * 完全な RFC4918 準拠は目指さず、Foldex が必要な href / resourcetype /
 * getcontentlength / getlastmodified / displayname のみ抽出する。
 */
internal object WebDavMultiStatusParser {

    fun parse(input: InputStream): List<Entry> {
        val parser = XmlPullParserFactory.newInstance().apply {
            isNamespaceAware = true
        }.newPullParser()
        parser.setInput(input, null)
        val entries = mutableListOf<Entry>()
        var event = parser.eventType
        var current: Builder? = null
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "response" -> current = Builder()
                    "href" -> current?.href = parser.nextText().trim()
                    "collection" -> current?.isCollection = true
                    "getcontentlength" -> current?.contentLength = parser.nextText().toLongOrNull()
                    "getlastmodified" -> current?.lastModified = parseHttpDate(parser.nextText())
                    "displayname" -> current?.displayName = parser.nextText().trim().ifBlank { null }
                }
                XmlPullParser.END_TAG -> if (parser.name == "response") {
                    current?.toEntry()?.let(entries::add)
                    current = null
                }
            }
            event = parser.next()
        }
        return entries
    }

    private fun parseHttpDate(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        return HTTP_DATE_FORMATS.firstNotNullOfOrNull { fmt ->
            runCatching {
                val sdf = SimpleDateFormat(fmt, Locale.US)
                sdf.timeZone = TimeZone.getTimeZone("GMT")
                sdf.parse(value)?.time
            }.getOrNull()
        }
    }

    private val HTTP_DATE_FORMATS = listOf(
        "EEE, dd MMM yyyy HH:mm:ss zzz",
        "EEEE, dd-MMM-yy HH:mm:ss zzz",
        "EEE MMM d HH:mm:ss yyyy",
    )

    private class Builder {
        var href: String? = null
        var isCollection: Boolean = false
        var contentLength: Long? = null
        var lastModified: Long? = null
        var displayName: String? = null

        fun toEntry(): Entry? {
            val raw = href ?: return null
            val decodedPath = runCatching { URI(raw).rawPath }.getOrNull() ?: raw
            return Entry(
                rawHref = raw,
                decodedPath = java.net.URLDecoder.decode(decodedPath, Charsets.UTF_8),
                isCollection = isCollection,
                contentLength = if (isCollection) -1L else (contentLength ?: 0L),
                lastModifiedEpochMillis = lastModified,
                displayName = displayName,
            )
        }
    }

    /** PROPFIND 1 件分の要約。 */
    data class Entry(
        val rawHref: String,
        val decodedPath: String,
        val isCollection: Boolean,
        val contentLength: Long,
        val lastModifiedEpochMillis: Long?,
        val displayName: String?,
    )
}
