package com.example.data.parser

import com.example.data.model.Channel
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader

enum class StreamFormatKind {
    HLS,
    DASH,
    MPEG_TS,
    PROGRESSIVE_MP4,
    PROGRESSIVE_MKV,
    PROGRESSIVE_WEBM,
    AUTO
}

data class PlaybackEndpoint(
    val url: String,
    val formatKind: StreamFormatKind,
    val isSyntheticFallback: Boolean = false
)

data class StreamRequestInfo(
    val cleanUrl: String,
    val customUserAgent: String? = null,
    val customHeaders: Map<String, String> = emptyMap()
)

object M3uParser {

    private val ATTRIBUTE_REGEX = Regex("([a-zA-Z0-9_-]+)=\"([^\"]*)\"")
    private val NUMBERED_ITEM_REGEX = Regex("^(\\d+)\\.\\s+(.+)$")
    private val XTREAM_SHORT_TS_REGEX =
        Regex("^/([^/]+)/([^/]+)/([^/.]+)\\.(?:ts|mts|m2ts|mpegts|mpeg|mpg|flv|mpd|m3u)$", RegexOption.IGNORE_CASE)
    private val XTREAM_SHORT_NUMERIC_REGEX = Regex("^/([^/]+)/([^/]+)/(\\d+)$")
    private val XTREAM_LIVE_NO_EXT_REGEX =
        Regex("^/live/[^/]+/[^/]+/[^/.]+$", RegexOption.IGNORE_CASE)
    private val FLUSSONIC_TS_PATH_REGEX =
        Regex("/(?:mpegts|ts|live\\.ts)$", RegexOption.IGNORE_CASE)
    private val BROADCAST_EXT_REGEX =
        Regex("\\.(?:ts|mts|m2ts|mpegts|mpeg|mpg|tp|trp|flv|mpd|f4m|m3u|ashx)$", RegexOption.IGNORE_CASE)
    private val SMOOTH_MANIFEST_REGEX =
        Regex("/Manifest$", RegexOption.IGNORE_CASE)
    private val SCRIPT_ENDPOINT_REGEX =
        Regex("\\.(?:php|jsp|asp|aspx|cgi|pl|py)$", RegexOption.IGNORE_CASE)

    /**
     * Cleans HTML-escaped ampersands (`&amp;`) and raw spaces in the URL portion (before any `|` pipe options)
     * so IPTV servers do not reject malformed request URIs with HTTP 400 or HTTP 404.
     */
    fun sanitizeUrlPart(rawUrlPart: String): String {
        val trimmed = rawUrlPart.trim()
        if (trimmed.isEmpty()) return trimmed
        return trimmed
            .replace("&amp;", "&", ignoreCase = true)
            .replace(" ", "%20")
    }

    /**
     * Extracts optional IPTV pipe headers (e.g. `https://host/live.m3u8|User-Agent=Custom&Referer=https://host/`)
     * so HTTP requests never fail with HTTP 400/403/404 due to unparsed pipe suffixes or missing headers.
     */
    fun extractStreamRequestInfo(rawUrl: String): StreamRequestInfo {
        val trimmed = rawUrl.trim()
        val pipeIndex = trimmed.indexOf('|')
        if (pipeIndex == -1) {
            return StreamRequestInfo(cleanUrl = sanitizeUrlPart(trimmed))
        }

        val cleanUrl = sanitizeUrlPart(trimmed.substring(0, pipeIndex))
        val rawOptions = trimmed.substring(pipeIndex + 1).trim()
        if (rawOptions.isEmpty()) {
            return StreamRequestInfo(cleanUrl = cleanUrl)
        }

        var customUserAgent: String? = null
        val headers = linkedMapOf<String, String>()

        for (entry in rawOptions.split('&')) {
            val eqIdx = entry.indexOf('=')
            if (eqIdx <= 0) continue
            val key = entry.substring(0, eqIdx).trim()
            val value = entry.substring(eqIdx + 1).trim()
            if (value.isEmpty()) continue

            when (key.lowercase()) {
                "user-agent", "http-user-agent" -> customUserAgent = value
                "referer", "referrer", "http-referer", "http-referrer" -> headers["Referer"] = value
                "origin", "http-origin" -> headers["Origin"] = value
                "cookie", "http-cookie" -> headers["Cookie"] = value
                "authorization" -> headers["Authorization"] = value
                else -> headers[key] = value
            }
        }

        return StreamRequestInfo(
            cleanUrl = cleanUrl,
            customUserAgent = customUserAgent,
            customHeaders = headers
        )
    }

    /**
     * Converts any IPTV / broadcast stream link (such as .ts, .mpegts, .mpd, .flv, Flussonic /mpegts,
     * RTMP/RTSP/MMS, Xtream live links, Stalker links, or extensionless live paths) into HLS (.m3u8) format.
     */
    fun toHlsStreamUrl(rawUrl: String): String {
        val fullTrimmed = rawUrl.trim()
        if (fullTrimmed.isEmpty()) return fullTrimmed

        val pipeSplit = fullTrimmed.split("|", limit = 2)
        val trimmed = sanitizeUrlPart(pipeSplit[0])
        val pipeSuffix = if (pipeSplit.size > 1 && pipeSplit[1].isNotBlank()) "|${pipeSplit[1].trim()}" else ""
        if (trimmed.isEmpty()) return fullTrimmed

        val lower = trimmed.lowercase()
        if (lower.startsWith("android.resource://") ||
            lower.startsWith("content://") ||
            lower.startsWith("file://")
        ) {
            return trimmed
        }

        // Normalize legacy broadcast protocols (rtmp, rtmps, rtsp, mms, mmsh) to HTTP(S) HLS
        val normalizedSchemeUrl = when {
            lower.startsWith("rtmps://") -> "https://" + trimmed.substring(8)
            lower.startsWith("rtmp://") -> "https://" + trimmed.substring(7)
            lower.startsWith("rtsp://") -> "http://" + trimmed.substring(7)
            lower.startsWith("mmsh://") -> "http://" + trimmed.substring(7)
            lower.startsWith("mms://") -> "http://" + trimmed.substring(6)
            else -> trimmed
        }

        val fragmentSplit = normalizedSchemeUrl.split("#", limit = 2)
        val withoutFragment = fragmentSplit[0]
        val fragment = if (fragmentSplit.size > 1) "#${fragmentSplit[1]}" else ""

        val querySplit = withoutFragment.split("?", limit = 2)
        var baseUrl = querySplit[0]
        var query = if (querySplit.size > 1) querySplit[1] else null

        if (query != null) {
            query = query.replace(
                Regex("(?i)(^|&)(output|extension|format|type|stream_format)=(ts|mpegts|mpd|dash|flv)(?=&|$)"),
                "$1$2=m3u8"
            )
            if (baseUrl.endsWith("live.php", ignoreCase = true) &&
                !query.contains("extension=", ignoreCase = true) &&
                !query.contains("output=", ignoreCase = true)
            ) {
                query += "&extension=m3u8"
            }
        }

        val schemeEnd = baseUrl.indexOf("://")
        if (schemeEnd != -1) {
            val pathStart = baseUrl.indexOf('/', schemeEnd + 3)
            if (pathStart != -1) {
                val origin = baseUrl.substring(0, pathStart)
                val path = baseUrl.substring(pathStart)
                val lowerPath = path.lowercase()

                val isProgressiveVodOrRawPath = lowerPath.endsWith(".mp4") ||
                    lowerPath.endsWith(".m4v") ||
                    lowerPath.endsWith(".mkv") ||
                    lowerPath.endsWith(".avi") ||
                    lowerPath.endsWith(".mov") ||
                    lowerPath.endsWith(".webm") ||
                    lowerPath.contains("/movie/") ||
                    lowerPath.contains("/series/") ||
                    lowerPath.contains("/vod/") ||
                    lowerPath.startsWith("/udp/") ||
                    lowerPath.startsWith("/rtp/")

                if (!isProgressiveVodOrRawPath) {
                    val lastSegment = path.substringAfterLast('/')
                    val convertedPath = when {
                        lowerPath.endsWith(".m3u8") -> path

                        FLUSSONIC_TS_PATH_REGEX.containsMatchIn(path) -> {
                            path.replace(FLUSSONIC_TS_PATH_REGEX, "/index.m3u8")
                        }

                        SMOOTH_MANIFEST_REGEX.containsMatchIn(path) -> {
                            path.replace(SMOOTH_MANIFEST_REGEX, "/index.m3u8")
                        }

                        XTREAM_SHORT_TS_REGEX.matches(path) -> {
                            val match = XTREAM_SHORT_TS_REGEX.matchEntire(path)!!
                            val prefix = match.groupValues[1].lowercase()
                            if (prefix == "live" || prefix == "hls" || prefix == "streams") {
                                path.replace(BROADCAST_EXT_REGEX, ".m3u8")
                            } else {
                                "/live/${match.groupValues[1]}/${match.groupValues[2]}/${match.groupValues[3]}.m3u8"
                            }
                        }

                        XTREAM_SHORT_NUMERIC_REGEX.matches(path) -> {
                            val match = XTREAM_SHORT_NUMERIC_REGEX.matchEntire(path)!!
                            "/live/${match.groupValues[1]}/${match.groupValues[2]}/${match.groupValues[3]}.m3u8"
                        }

                        BROADCAST_EXT_REGEX.containsMatchIn(path) -> {
                            path.replace(BROADCAST_EXT_REGEX, ".m3u8")
                        }

                        XTREAM_LIVE_NO_EXT_REGEX.matches(path) -> {
                            "$path.m3u8"
                        }

                        SCRIPT_ENDPOINT_REGEX.containsMatchIn(lastSegment) -> {
                            path
                        }

                        path.endsWith("/") && path.length > 1 -> {
                            "${path}index.m3u8"
                        }

                        lastSegment.isNotEmpty() && !lastSegment.contains('.') -> {
                            "$path.m3u8"
                        }

                        else -> path
                    }
                    baseUrl = origin + convertedPath
                }
            } else {
                baseUrl = "$baseUrl/index.m3u8"
            }
        }

        return buildString {
            append(baseUrl)
            if (!query.isNullOrEmpty()) {
                append("?")
                append(query)
            }
            append(fragment)
            append(pipeSuffix)
        }
    }

    private fun toHttpPortVariant(url: String, port: Int): String? {
        val schemeEnd = url.indexOf("://")
        if (schemeEnd == -1) return null
        val pathStart = url.indexOf('/', schemeEnd + 3)
        val authority = if (pathStart == -1) url.substring(schemeEnd + 3) else url.substring(schemeEnd + 3, pathStart)
        val suffix = if (pathStart == -1) "" else url.substring(pathStart)
        // Only synthesize alternate port when no explicit port was specified in URL
        if (authority.contains(':')) return null
        return "http://$authority:$port$suffix"
    }

    /**
     * Builds an ordered list of playback endpoints with HLS (M3U8) first, followed by HTTP/HTTPS,
     * MPEG-TS (.ts / /mpegts), extensionless, short Xtream, Flussonic alternate manifest names,
     * DASH (.mpd), VOD container variants, and standard Xtream ports (:8080, :8000, :25461)
     * so HTTP 404 responses on any single format or port automatically fail over to the live stream.
     */
    fun buildPlaybackEndpoints(rawUrl: String): List<PlaybackEndpoint> {
        val requestInfo = extractStreamRequestInfo(rawUrl)
        val trimmed = requestInfo.cleanUrl.trim()
        if (trimmed.isEmpty()) return emptyList()

        val lower = trimmed.lowercase()
        if (lower.startsWith("android.resource://") ||
            lower.startsWith("content://") ||
            lower.startsWith("file://")
        ) {
            val kind = if (lower.endsWith(".m3u8")) StreamFormatKind.HLS else StreamFormatKind.AUTO
            return listOf(PlaybackEndpoint(trimmed, kind, isSyntheticFallback = false))
        }

        val cleanBeforeQuery = lower.substringBefore('#').substringBefore('?')
        val isProgressiveVodExt = cleanBeforeQuery.endsWith(".mp4") ||
            cleanBeforeQuery.endsWith(".m4v") ||
            cleanBeforeQuery.endsWith(".mkv") ||
            cleanBeforeQuery.endsWith(".webm") ||
            cleanBeforeQuery.endsWith(".avi") ||
            cleanBeforeQuery.endsWith(".mov")
        val isVodCatalogPath = cleanBeforeQuery.contains("/movie/") ||
            cleanBeforeQuery.contains("/series/") ||
            cleanBeforeQuery.contains("/vod/")

        val endpoints = mutableListOf<PlaybackEndpoint>()

        fun addUnique(url: String, kind: StreamFormatKind, isSynthetic: Boolean = false) {
            if (url.isNotBlank() && endpoints.none { it.url == url && it.formatKind == kind }) {
                endpoints.add(PlaybackEndpoint(url, kind, isSyntheticFallback = isSynthetic))
            }
        }

        fun toHttpIfHttps(url: String): String? {
            return if (url.startsWith("https://", ignoreCase = true)) {
                "http://" + url.substring(8)
            } else {
                null
            }
        }

        fun addPortFallbacks(url: String, kind: StreamFormatKind) {
            for (port in intArrayOf(8080, 8000, 25461)) {
                toHttpPortVariant(url, port)?.let { addUnique(it, kind, isSynthetic = true) }
            }
        }

        if (isProgressiveVodExt || isVodCatalogPath) {
            if (isProgressiveVodExt) {
                // 1. Universal progressive extractor sniffing (MP4, fMP4, MKV, WebM, TS, FLV, AVI)
                addUnique(trimmed, StreamFormatKind.AUTO, isSynthetic = false)
                toHttpIfHttps(trimmed)?.let { addUnique(it, StreamFormatKind.AUTO, isSynthetic = true) }

                // 2. HLS (HlsMediaSource) on exact VOD URL for panels that 302-redirect .mp4 to #EXTM3U
                addUnique(trimmed, StreamFormatKind.HLS, isSynthetic = false)
                toHttpIfHttps(trimmed)?.let { addUnique(it, StreamFormatKind.HLS, isSynthetic = true) }

                // 3. DASH fallback if redirected to .mpd
                addUnique(trimmed, StreamFormatKind.DASH, isSynthetic = true)

                // 4. Standard Xtream HTTP ports (:8080, :8000, :25461)
                addPortFallbacks(trimmed, StreamFormatKind.AUTO)
                addPortFallbacks(trimmed, StreamFormatKind.HLS)

                // 5. Explicit .m3u8 path rewrite last
                val vodHlsUrl = trimmed.replace(
                    Regex("\\.(?:mp4|m4v|mkv|webm|avi|mov)(?=[?#]|$)", RegexOption.IGNORE_CASE),
                    ".m3u8"
                )
                if (vodHlsUrl != trimmed) {
                    addUnique(vodHlsUrl, StreamFormatKind.HLS, isSynthetic = true)
                    toHttpIfHttps(vodHlsUrl)?.let { addUnique(it, StreamFormatKind.HLS, isSynthetic = true) }
                }
            } else {
                // Extensionless or previously .m3u8-rewritten /movie/ or /series/ URL
                val strippedVodBase = trimmed.replace(
                    Regex("\\.(?:m3u8|ts)(?=[?#]|$)", RegexOption.IGNORE_CASE),
                    ""
                )
                val queryIdx = strippedVodBase.indexOf('?').takeIf { it >= 0 } ?: strippedVodBase.length
                val basePart = strippedVodBase.substring(0, queryIdx)
                val suffixPart = strippedVodBase.substring(queryIdx)

                // Try .mp4, .mkv, and extensionless VOD endpoints first before .m3u8
                val mp4Vod = "$basePart.mp4$suffixPart"
                val mkvVod = "$basePart.mkv$suffixPart"
                addUnique(mp4Vod, StreamFormatKind.AUTO, isSynthetic = false)
                toHttpIfHttps(mp4Vod)?.let { addUnique(it, StreamFormatKind.AUTO, isSynthetic = true) }
                addUnique(strippedVodBase, StreamFormatKind.AUTO, isSynthetic = false)
                toHttpIfHttps(strippedVodBase)?.let { addUnique(it, StreamFormatKind.AUTO, isSynthetic = true) }
                addUnique(mkvVod, StreamFormatKind.AUTO, isSynthetic = true)
                toHttpIfHttps(mkvVod)?.let { addUnique(it, StreamFormatKind.AUTO, isSynthetic = true) }
                addUnique("$basePart.m3u8$suffixPart", StreamFormatKind.HLS, isSynthetic = true)
                toHttpIfHttps("$basePart.m3u8$suffixPart")?.let { addUnique(it, StreamFormatKind.HLS, isSynthetic = true) }
                addPortFallbacks(mp4Vod, StreamFormatKind.AUTO)
                addPortFallbacks(strippedVodBase, StreamFormatKind.AUTO)
            }
            return endpoints
        }

        // 1. Primary HLS (M3U8) endpoint + HTTP HLS variant
        val primaryHls = toHlsStreamUrl(trimmed)
        val wasRewrittenToHls = !trimmed.equals(primaryHls, ignoreCase = true)
        addUnique(primaryHls, StreamFormatKind.HLS, isSynthetic = wasRewrittenToHls)
        toHttpIfHttps(primaryHls)?.let { addUnique(it, StreamFormatKind.HLS, isSynthetic = true) }

        // If original URL was explicitly given (e.g. .ts or .mpd or extensionless), include it directly
        if (wasRewrittenToHls) {
            when {
                cleanBeforeQuery.endsWith(".mpd") -> {
                    addUnique(trimmed, StreamFormatKind.DASH, isSynthetic = false)
                    toHttpIfHttps(trimmed)?.let { addUnique(it, StreamFormatKind.DASH, isSynthetic = true) }
                }
                cleanBeforeQuery.endsWith(".ts") ||
                    cleanBeforeQuery.endsWith(".mts") ||
                    cleanBeforeQuery.endsWith(".m2ts") ||
                    cleanBeforeQuery.endsWith(".mpegts") -> {
                    addUnique(trimmed, StreamFormatKind.MPEG_TS, isSynthetic = false)
                    toHttpIfHttps(trimmed)?.let { addUnique(it, StreamFormatKind.MPEG_TS, isSynthetic = true) }
                    addUnique(trimmed, StreamFormatKind.HLS, isSynthetic = true)
                    toHttpIfHttps(trimmed)?.let { addUnique(it, StreamFormatKind.HLS, isSynthetic = true) }
                }
                else -> {
                    addUnique(trimmed, StreamFormatKind.AUTO, isSynthetic = false)
                    toHttpIfHttps(trimmed)?.let { addUnique(it, StreamFormatKind.AUTO, isSynthetic = true) }
                    addUnique(trimmed, StreamFormatKind.HLS, isSynthetic = true)
                }
            }
        }

        val fragmentSplit = primaryHls.split("#", limit = 2)
        val withoutFragment = fragmentSplit[0]
        val fragment = if (fragmentSplit.size > 1) "#${fragmentSplit[1]}" else ""
        val querySplit = withoutFragment.split("?", limit = 2)
        val baseUrl = querySplit[0]
        val query = if (querySplit.size > 1) querySplit[1] else null

        fun rebuildUrl(newBase: String, newQuery: String? = query): String {
            return buildString {
                append(newBase)
                if (!newQuery.isNullOrEmpty()) {
                    append("?")
                    append(newQuery)
                }
                append(fragment)
            }
        }

        val tsQuery = query?.replace(
            Regex("(?i)(^|&)(output|extension|format|type|stream_format)=m3u8(?=&|$)"),
            "$1$2=ts"
        )

        var derivedTsUrl: String? = null
        var derivedNoExtUrl: String? = null

        val schemeEnd = baseUrl.indexOf("://")
        if (schemeEnd != -1) {
            val pathStart = baseUrl.indexOf('/', schemeEnd + 3)
            if (pathStart != -1) {
                val origin = baseUrl.substring(0, pathStart)
                val path = baseUrl.substring(pathStart)
                val lowerPath = path.lowercase()

                when {
                    lowerPath.endsWith("/index.m3u8") -> {
                        val prefixPath = path.dropLast("/index.m3u8".length)
                        val flussonicTs = rebuildUrl("$origin$prefixPath/mpegts", tsQuery)
                        derivedTsUrl = flussonicTs
                        addUnique(flussonicTs, StreamFormatKind.MPEG_TS, isSynthetic = false)
                        toHttpIfHttps(flussonicTs)?.let { addUnique(it, StreamFormatKind.MPEG_TS, isSynthetic = true) }

                        val monoHls = rebuildUrl("$origin$prefixPath/mono.m3u8", query)
                        addUnique(monoHls, StreamFormatKind.HLS, isSynthetic = true)
                        toHttpIfHttps(monoHls)?.let { addUnique(it, StreamFormatKind.HLS, isSynthetic = true) }

                        val playlistHls = rebuildUrl("$origin$prefixPath/playlist.m3u8", query)
                        addUnique(playlistHls, StreamFormatKind.HLS, isSynthetic = true)
                        toHttpIfHttps(playlistHls)?.let { addUnique(it, StreamFormatKind.HLS, isSynthetic = true) }

                        if (prefixPath.isNotEmpty()) {
                            val rawBase = rebuildUrl("$origin$prefixPath", query)
                            derivedNoExtUrl = rawBase
                            addUnique(rawBase, StreamFormatKind.AUTO, isSynthetic = true)
                            toHttpIfHttps(rawBase)?.let { addUnique(it, StreamFormatKind.AUTO, isSynthetic = true) }
                        }
                    }

                    lowerPath.endsWith(".m3u8") -> {
                        val withoutExt = path.dropLast(".m3u8".length)
                        if (!withoutExt.endsWith("/") && withoutExt.isNotEmpty()) {
                            // MPEG-TS (.ts) fallback when .m3u8 returns HTTP 404/403
                            val tsUrl = rebuildUrl("$origin$withoutExt.ts", tsQuery)
                            derivedTsUrl = tsUrl
                            addUnique(tsUrl, StreamFormatKind.MPEG_TS, isSynthetic = false)
                            toHttpIfHttps(tsUrl)?.let { addUnique(it, StreamFormatKind.MPEG_TS, isSynthetic = true) }
                            addUnique(tsUrl, StreamFormatKind.HLS, isSynthetic = true)
                            toHttpIfHttps(tsUrl)?.let { addUnique(it, StreamFormatKind.HLS, isSynthetic = true) }

                            // Extensionless fallback (TVHeadend, Astra, Stalker, raw Xtream)
                            val noExtUrl = rebuildUrl("$origin$withoutExt", query)
                            derivedNoExtUrl = noExtUrl
                            addUnique(noExtUrl, StreamFormatKind.AUTO, isSynthetic = false)
                            toHttpIfHttps(noExtUrl)?.let { addUnique(it, StreamFormatKind.AUTO, isSynthetic = true) }

                            // Short Xtream path fallback (/live/user/pass/id -> /user/pass/id.ts and /user/pass/id)
                            if (withoutExt.startsWith("/live/", ignoreCase = true)) {
                                val shortPath = withoutExt.substring("/live".length)
                                if (shortPath.count { it == '/' } == 3) {
                                    val shortTsUrl = rebuildUrl("$origin$shortPath.ts", tsQuery)
                                    addUnique(shortTsUrl, StreamFormatKind.MPEG_TS, isSynthetic = true)
                                    toHttpIfHttps(shortTsUrl)?.let { addUnique(it, StreamFormatKind.MPEG_TS, isSynthetic = true) }

                                    val shortUrl = rebuildUrl("$origin$shortPath", tsQuery)
                                    addUnique(shortUrl, StreamFormatKind.AUTO, isSynthetic = true)
                                    toHttpIfHttps(shortUrl)?.let { addUnique(it, StreamFormatKind.AUTO, isSynthetic = true) }
                                }
                            }

                            // DASH (.mpd) fallback if stream was originally a DASH manifest rewritten to .m3u8
                            if (lowerPath.contains("manifest") || lowerPath.contains("/dash/")) {
                                val mpdUrl = rebuildUrl("$origin$withoutExt.mpd", query)
                                addUnique(mpdUrl, StreamFormatKind.DASH, isSynthetic = true)
                                toHttpIfHttps(mpdUrl)?.let { addUnique(it, StreamFormatKind.DASH, isSynthetic = true) }
                            }
                        }

                        // Auto-sniffed fallback on primaryHls URL if server streams raw TS bytes on .m3u8
                        addUnique(primaryHls, StreamFormatKind.AUTO, isSynthetic = true)
                    }

                    else -> {
                        if (tsQuery != null && tsQuery != query) {
                            val queryTsUrl = rebuildUrl(baseUrl, tsQuery)
                            derivedTsUrl = queryTsUrl
                            addUnique(queryTsUrl, StreamFormatKind.MPEG_TS, isSynthetic = false)
                            toHttpIfHttps(queryTsUrl)?.let { addUnique(it, StreamFormatKind.MPEG_TS, isSynthetic = true) }
                        }
                        addUnique(primaryHls, StreamFormatKind.AUTO, isSynthetic = true)
                    }
                }
            }
        }

        // Standard Xtream / XUI.one HTTP ports (:8080, :8000, :25461) when port 443 SSL fails
        // and port 80 returns default Nginx 404 Not Found
        addPortFallbacks(primaryHls, StreamFormatKind.HLS)
        derivedTsUrl?.let { addPortFallbacks(it, StreamFormatKind.MPEG_TS) }
        derivedNoExtUrl?.let { addPortFallbacks(it, StreamFormatKind.AUTO) }

        return endpoints
    }

    /**
     * Normalizes M3U playlist download URLs (e.g. Xtream get.php) to request HLS (m3u8) streams.
     */
    fun toHlsPlaylistUrl(rawUrl: String): String {
        val trimmed = sanitizeUrlPart(rawUrl)
        if (trimmed.isEmpty()) return trimmed

        var updated = trimmed.replace(
            Regex("(?i)([?&](?:output|extension|format)=)(?:ts|mpegts|mpd|flv)(?=&|$)"),
            "$1m3u8"
        )
        if (updated.contains("get.php?", ignoreCase = true) &&
            !updated.contains("output=", ignoreCase = true)
        ) {
            updated += "&output=m3u8"
        }
        return updated
    }

    /**
     * Checks whether text content is a direct single-stream HLS M3U8 manifest
     * rather than a multi-channel IPTV playlist.
     */
    fun isDirectHlsManifest(content: String): Boolean {
        val sample = content.take(4096)
        val hasHlsStreamTags = sample.contains("#EXT-X-TARGETDURATION", ignoreCase = true) ||
            sample.contains("#EXT-X-MEDIA-SEQUENCE", ignoreCase = true) ||
            sample.contains("#EXT-X-STREAM-INF", ignoreCase = true)
        val hasIptvChannelTags = sample.contains("group-title=", ignoreCase = true) ||
            sample.contains("tvg-", ignoreCase = true)
        return hasHlsStreamTags && !hasIptvChannelTags
    }

    fun parse(inputStream: InputStream, playlistId: Long): List<Channel> {
        val reader = BufferedReader(InputStreamReader(inputStream))
        return parseReader(reader, playlistId)
    }

    fun parseString(content: String, playlistId: Long): List<Channel> {
        val reader = BufferedReader(content.reader())
        return parseReader(reader, playlistId)
    }

    private fun parseReader(reader: BufferedReader, playlistId: Long): List<Channel> {
        val channels = mutableListOf<Channel>()
        var currentTvgId: String? = null
        var currentTvgName: String? = null
        var currentLogo: String? = null
        var currentGroup: String? = null
        var currentTitle: String? = null
        var currentVlcUserAgent: String? = null
        var currentVlcReferer: String? = null
        var currentVlcOrigin: String? = null
        var channelIndex = 1

        // State for readable .txt dump format (e.g. "1. Kanal\n Kategori: ...\n Yayın adresi: ...")
        var inTextDumpBlock = false
        var txtTitle: String? = null
        var txtCategory: String? = null
        var txtStreamUrl: String? = null
        var txtLogoUrl: String? = null

        fun flushTextDumpBlock() {
            val url = txtStreamUrl?.takeIf { it.isNotBlank() }
            if (inTextDumpBlock && url != null) {
                channels.add(
                    Channel(
                        playlistId = playlistId,
                        name = txtTitle?.takeIf { it.isNotBlank() } ?: "Kanal $channelIndex",
                        streamUrl = toHlsStreamUrl(url),
                        logoUrl = txtLogoUrl?.takeIf { it.isNotBlank() },
                        category = txtCategory?.takeIf { it.isNotBlank() } ?: "Genel",
                        tvgId = null,
                        channelNumber = channelIndex++
                    )
                )
            }
            inTextDumpBlock = false
            txtTitle = null
            txtCategory = null
            txtStreamUrl = null
            txtLogoUrl = null
        }

        var line: String? = reader.readLine()
        while (line != null) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                line = reader.readLine()
                continue
            }

            if (trimmed.startsWith("#EXTINF", ignoreCase = true)) {
                flushTextDumpBlock()

                currentTvgId = null
                currentTvgName = null
                currentLogo = null
                currentGroup = null
                currentTitle = null
                currentVlcUserAgent = null
                currentVlcReferer = null
                currentVlcOrigin = null

                val matches = ATTRIBUTE_REGEX.findAll(trimmed)
                for (match in matches) {
                    val key = match.groupValues[1].lowercase()
                    val value = match.groupValues[2]
                    when (key) {
                        "tvg-id" -> currentTvgId = value
                        "tvg-name" -> currentTvgName = value
                        "tvg-logo" -> currentLogo = value
                        "group-title" -> currentGroup = value
                        "user-agent", "http-user-agent" -> if (value.isNotBlank()) currentVlcUserAgent = value
                        "http-referrer", "http-referer" -> if (value.isNotBlank()) currentVlcReferer = value
                    }
                }

                val commaIndex = trimmed.indexOf(',')
                if (commaIndex != -1 && commaIndex < trimmed.length - 1) {
                    currentTitle = trimmed.substring(commaIndex + 1).trim()
                }
            } else if (trimmed.startsWith("#EXTVLCOPT:", ignoreCase = true)) {
                val opt = trimmed.substringAfter(":").trim()
                val eqIndex = opt.indexOf('=')
                if (eqIndex > 0) {
                    val optKey = opt.substring(0, eqIndex).trim().lowercase()
                    val optVal = opt.substring(eqIndex + 1).trim()
                    if (optVal.isNotEmpty()) {
                        when (optKey) {
                            "http-user-agent" -> currentVlcUserAgent = optVal
                            "http-referrer", "http-referer" -> currentVlcReferer = optVal
                            "http-origin" -> currentVlcOrigin = optVal
                        }
                    }
                }
            } else if (trimmed.startsWith("Kategori:", ignoreCase = true)) {
                txtCategory = trimmed.substringAfter(":").trim()
            } else if (trimmed.startsWith("Yayın adresi:", ignoreCase = true) ||
                trimmed.startsWith("Yayin adresi:", ignoreCase = true)
            ) {
                txtStreamUrl = trimmed.substringAfter(":").trim()
                inTextDumpBlock = true
            } else if (trimmed.startsWith("Logo adresi:", ignoreCase = true)) {
                txtLogoUrl = trimmed.substringAfter(":").trim()
            } else if (!trimmed.startsWith("#")) {
                val numberedMatch = NUMBERED_ITEM_REGEX.matchEntire(trimmed)
                if (numberedMatch != null && !trimmed.contains("://")) {
                    flushTextDumpBlock()
                    inTextDumpBlock = true
                    txtTitle = numberedMatch.groupValues[2].trim()
                } else if (trimmed.contains("://")) {
                    flushTextDumpBlock()

                    var hlsStreamUrl = toHlsStreamUrl(trimmed)
                    if (!hlsStreamUrl.contains('|')) {
                        val pipeOpts = mutableListOf<String>()
                        currentVlcUserAgent?.let { pipeOpts.add("User-Agent=$it") }
                        currentVlcReferer?.let { pipeOpts.add("Referer=$it") }
                        currentVlcOrigin?.let { pipeOpts.add("Origin=$it") }
                        if (pipeOpts.isNotEmpty()) {
                            hlsStreamUrl = "$hlsStreamUrl|${pipeOpts.joinToString("&")}"
                        }
                    }
                    val finalName = currentTitle?.takeIf { it.isNotBlank() }
                        ?: currentTvgName?.takeIf { it.isNotBlank() }
                        ?: "Kanal $channelIndex"
                    val finalCategory = currentGroup?.takeIf { it.isNotBlank() } ?: "Genel"

                    channels.add(
                        Channel(
                            playlistId = playlistId,
                            name = finalName,
                            streamUrl = hlsStreamUrl,
                            logoUrl = currentLogo,
                            category = finalCategory,
                            tvgId = currentTvgId,
                            channelNumber = channelIndex++
                        )
                    )

                    currentTvgId = null
                    currentTvgName = null
                    currentLogo = null
                    currentGroup = null
                    currentTitle = null
                    currentVlcUserAgent = null
                    currentVlcReferer = null
                    currentVlcOrigin = null
                }
            }

            line = reader.readLine()
        }

        flushTextDumpBlock()
        return channels
    }
}
