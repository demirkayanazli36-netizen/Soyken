package com.example.ui.components

import android.content.Context
import android.net.Uri
import com.example.data.network.CloudflareDns
import com.example.data.parser.M3uParser
import com.example.data.parser.StreamFormatKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.URI
import java.util.concurrent.TimeUnit

internal data class ResolvedStreamCandidate(
    val url: String,
    val formatKind: StreamFormatKind,
    val userAgent: String,
    val customHeaders: Map<String, String>
)

internal data class ProbeResolutionResult(
    val resolved: ResolvedStreamCandidate? = null,
    val confirmedHttpStatus: Int? = null
)

object StreamProbe {

    const val UA_SMARTERS = "IPTVSmartersPlayer"
    const val UA_VLC = "VLC/3.0.20 LibVLC/3.0.20"
    const val UA_CHROME_ANDROID =
        "Mozilla/5.0 (Linux; Android 14; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    val UA_DALVIK: String =
        System.getProperty("http.agent")?.takeIf { it.isNotBlank() }
            ?: "Dalvik/2.1.0 (Linux; U; Android 14; Pixel 8 Build/UQ1A.240205.004)"

    private val DIRECT_M3U8_REGEX =
        Regex("""https?://[^\s"'<>\\]+\.m3u8[^\s"'<>\\]*""", RegexOption.IGNORE_CASE)
    private val DIRECT_MPD_REGEX =
        Regex("""https?://[^\s"'<>\\]+\.mpd[^\s"'<>\\]*""", RegexOption.IGNORE_CASE)
    private val DIRECT_VOD_REGEX =
        Regex("""https?://[^\s"'<>\\]+\.(?:mp4|mkv|webm|m4v)(?:[?#][^\s"'<>\\]*)?""", RegexOption.IGNORE_CASE)
    private val PLAYER_SOURCE_PROP_REGEX =
        Regex("""(?:file|source|src|stream|streamUrl|hls|url)\s*[:=]\s*["'](https?://[^"'\s<>]+)["']""", RegexOption.IGNORE_CASE)
    private val IFRAME_SRC_REGEX =
        Regex("""<iframe[^>]+src\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val HLS_URI_ATTR_REGEX =
        Regex("""URI="([^"]+)"""")

    private val probeClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dns(CloudflareDns)
            .cookieJar(CloudflareDns.sharedCookieJar)
            .connectionPool(CloudflareDns.sharedConnectionPool)
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(false)
            .build()
    }

    fun extractOrigin(url: String): String? {
        val schemeEnd = url.indexOf("://")
        if (schemeEnd == -1) return null
        val pathStart = url.indexOf('/', schemeEnd + 3)
        return if (pathStart == -1) url else url.substring(0, pathStart)
    }

    private fun unescapeWebString(raw: String): String {
        return raw
            .replace("""\/""", "/")
            .replace("""\u0026""", "&", ignoreCase = true)
            .replace("""\u003d""", "=", ignoreCase = true)
            .replace("&amp;", "&", ignoreCase = true)
    }

    /**
     * Extracts embedded HLS (.m3u8), DASH (.mpd), or progressive (.mp4/.mkv) stream URLs
     * from HTML player wrappers (JWPlayer, PlayerJS, Clappr, HTML5 video) or JSON responses.
     */
    fun extractEmbeddedStreamFromText(
        rawText: String,
        baseUrl: String
    ): Pair<String, StreamFormatKind>? {
        if (rawText.isBlank()) return null
        val unescaped = unescapeWebString(rawText)

        DIRECT_M3U8_REGEX.find(unescaped)?.value?.let { found ->
            return M3uParser.sanitizeUrlPart(found.trimEnd(',', ';', ')')) to StreamFormatKind.HLS
        }

        DIRECT_MPD_REGEX.find(unescaped)?.value?.let { found ->
            return M3uParser.sanitizeUrlPart(found.trimEnd(',', ';', ')')) to StreamFormatKind.DASH
        }

        DIRECT_VOD_REGEX.find(unescaped)?.value?.let { found ->
            val cleaned = M3uParser.sanitizeUrlPart(found.trimEnd(',', ';', ')'))
            val lower = cleaned.lowercase()
            if (!lower.endsWith(".jpg") && !lower.endsWith(".png") && !lower.endsWith(".js")) {
                return cleaned to StreamFormatKind.AUTO
            }
        }

        for (match in PLAYER_SOURCE_PROP_REGEX.findAll(unescaped)) {
            val candidate = M3uParser.sanitizeUrlPart(match.groupValues[1])
            val lower = candidate.lowercase()
            if (lower.endsWith(".jpg") || lower.endsWith(".png") || lower.endsWith(".css") ||
                lower.endsWith(".js") || lower.endsWith(".svg") || lower.endsWith(".ico")
            ) {
                continue
            }
            val resolved = resolveRelativeUrl(baseUrl, candidate) ?: continue
            val kind = when {
                lower.contains(".m3u8") -> StreamFormatKind.HLS
                lower.contains(".mpd") -> StreamFormatKind.DASH
                else -> StreamFormatKind.AUTO
            }
            return resolved to kind
        }

        return null
    }

    fun extractIframeSrcFromHtml(rawHtml: String, baseUrl: String): String? {
        val unescaped = unescapeWebString(rawHtml)
        val match = IFRAME_SRC_REGEX.find(unescaped) ?: return null
        val src = match.groupValues[1].trim()
        if (src.isEmpty() || src.startsWith("javascript:", ignoreCase = true) || src.startsWith("about:", ignoreCase = true)) {
            return null
        }
        return resolveRelativeUrl(baseUrl, src)
    }

    fun resolveRelativeUrl(baseUrl: String, ref: String): String? {
        val trimmedRef = M3uParser.sanitizeUrlPart(ref)
        if (trimmedRef.startsWith("http://", ignoreCase = true) ||
            trimmedRef.startsWith("https://", ignoreCase = true)
        ) {
            return trimmedRef
        }
        return try {
            val okBase = baseUrl.toHttpUrlOrNull()
            if (okBase != null) {
                okBase.resolve(trimmedRef)?.toString()
            } else {
                URI(baseUrl).resolve(trimmedRef).toString()
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Strips leading whitespace/BOM before `#EXTM3U` and rewrites relative segment/variant URIs
     * to absolute URLs against `baseManifestUrl`.
     */
    fun rewriteHlsManifestToAbsolute(manifestContent: String, baseManifestUrl: String): String {
        val idx = manifestContent.indexOf("#EXTM3U", ignoreCase = true)
        val cleanStart = if (idx >= 0) manifestContent.substring(idx) else manifestContent
        val lines = cleanStart.lines()
        return lines.joinToString("\n") { line ->
            val t = line.trim()
            when {
                t.isEmpty() -> ""
                t.startsWith("#") -> {
                    HLS_URI_ATTR_REGEX.replace(t) { m ->
                        val resolved = resolveRelativeUrl(baseManifestUrl, m.groupValues[1]) ?: m.groupValues[1]
                        """URI="$resolved""""
                    }
                }
                else -> resolveRelativeUrl(baseManifestUrl, t) ?: t
            }
        }
    }

    fun detectBinaryFormat(bytes: ByteArray, length: Int): StreamFormatKind? {
        if (length < 4) return null

        // MPEG-TS sync byte 0x47 ('G') every 188 bytes
        if (bytes[0] == 0x47.toByte() && (length < 189 || bytes[188] == 0x47.toByte())) {
            return StreamFormatKind.MPEG_TS
        }
        for (offset in 0 until minOf(length - 188, 376)) {
            if (bytes[offset] == 0x47.toByte() && bytes[offset + 188] == 0x47.toByte()) {
                return StreamFormatKind.MPEG_TS
            }
        }

        // ISO-BMFF / MP4 ('ftyp', 'moov', 'styp', 'mdat' at offset 4..7)
        if (length >= 8) {
            val box = String(bytes, 4, 4, Charsets.ISO_8859_1)
            if (box == "ftyp" || box == "moov" || box == "styp" || box == "mdat" || box == "free") {
                return StreamFormatKind.AUTO
            }
        }

        // Matroska / WebM EBML header: 1A 45 DF A3
        if (bytes[0] == 0x1A.toByte() && bytes[1] == 0x45.toByte() &&
            bytes[2] == 0xDF.toByte() && bytes[3] == 0xA3.toByte()
        ) {
            return StreamFormatKind.AUTO
        }

        // FLV ('FLV') or RIFF/AVI ('RIFF') or Ogg ('OggS') or ID3 ('ID3')
        val magic3 = String(bytes, 0, 3, Charsets.ISO_8859_1)
        val magic4 = String(bytes, 0, 4, Charsets.ISO_8859_1)
        if (magic3 == "FLV" || magic3 == "ID3" || magic4 == "RIFF" || magic4 == "OggS") {
            return StreamFormatKind.AUTO
        }

        return null
    }

    internal suspend fun resolveFallbackStream(
        context: Context,
        rawStreamUrl: String
    ): ProbeResolutionResult = withContext(Dispatchers.IO) {
        val reqInfo = M3uParser.extractStreamRequestInfo(rawStreamUrl)
        val endpoints = M3uParser.buildPlaybackEndpoints(rawStreamUrl)
        if (endpoints.isEmpty()) return@withContext ProbeResolutionResult()

        // Connect to 1dot1dot1dot1.cloudflare-dns.com first before probing M3U endpoints
        CloudflareDns.ensureConnectedForUrl(reqInfo.cleanUrl)

        // Probe distinct candidate URLs, prioritizing non-synthetic URLs first across schemes and ports
        val orderedProbeUrls = (
            endpoints.filter { !it.isSyntheticFallback }.map { it.url to false } +
                endpoints.filter { it.isSyntheticFallback }.map { it.url to true }
            ).distinctBy { it.first }.take(12)

        val userAgentProfiles = buildList {
            reqInfo.customUserAgent?.takeIf { it.isNotBlank() }?.let { add(it to false) }
            add(UA_SMARTERS to false)
            add("TiviMate/4.7.0 (Linux; Android 13)" to false)
            add(UA_CHROME_ANDROID to true) // Includes Referer + Origin
            add(UA_VLC to false)
            add(UA_DALVIK to false)
        }.distinctBy { it.first }

        var bestPrimaryStatus: Int? = null
        val deadOrigins = HashSet<String>()

        for ((probeUrl, isSynthetic) in orderedProbeUrls) {
            val origin = extractOrigin(probeUrl)
            if (origin != null && origin in deadOrigins) continue
            var sawOriginFailure = false

            for ((ua, addBrowserReferer) in userAgentProfiles) {
                if (sawOriginFailure) break

                val headers = LinkedHashMap<String, String>()
                headers["Accept"] = "*/*"
                if (addBrowserReferer && origin != null) {
                    headers["Referer"] = "$origin/"
                    headers["Origin"] = origin
                }
                headers.putAll(reqInfo.customHeaders)

                val requestBuilder = Request.Builder()
                    .url(probeUrl)
                    .header("User-Agent", ua)
                for ((k, v) in headers) {
                    requestBuilder.header(k, v)
                }

                try {
                    probeClient.newCall(requestBuilder.build()).execute().use { resp ->
                        val code = resp.code
                        if (!isSynthetic) {
                            if (bestPrimaryStatus == null || (bestPrimaryStatus == 404 && code != 404)) {
                                bestPrimaryStatus = code
                            }
                        }

                        if (!resp.isSuccessful) {
                            return@use
                        }

                        val finalUrl = resp.request.url.toString()
                        val finalOrigin = extractOrigin(finalUrl) ?: origin
                        val resolvedHeaders = LinkedHashMap(headers)
                        if (finalOrigin != null && "Referer" !in resolvedHeaders && addBrowserReferer) {
                            resolvedHeaders["Referer"] = "$finalOrigin/"
                        }

                        val stream = resp.body?.byteStream() ?: return@use
                        val buffer = ByteArray(24 * 1024)
                        var totalRead = 0
                        while (totalRead < buffer.size) {
                            val n = stream.read(buffer, totalRead, buffer.size - totalRead)
                            if (n <= 0) break
                            totalRead += n
                            // 4 KB is sufficient if binary sync bytes are already clear
                            if (totalRead >= 4096 && detectBinaryFormat(buffer, totalRead) != null) {
                                break
                            }
                        }

                        if (totalRead <= 0) return@use

                        // 1. Check binary container signatures first
                        val binaryFormat = detectBinaryFormat(buffer, totalRead)
                        if (binaryFormat != null) {
                            return@withContext ProbeResolutionResult(
                                resolved = ResolvedStreamCandidate(
                                    url = finalUrl,
                                    formatKind = binaryFormat,
                                    userAgent = ua,
                                    customHeaders = resolvedHeaders
                                ),
                                confirmedHttpStatus = 200
                            )
                        }

                        val textSample = String(buffer, 0, totalRead, Charsets.UTF_8)
                        val trimmedSample = textSample.trimStart('\uFEFF', ' ', '\t', '\r', '\n')

                        // 2. Check HLS (#EXTM3U) manifest (even with Content-Type: text/html or leading whitespace)
                        if (trimmedSample.startsWith("#EXTM3U", ignoreCase = true)) {
                            val hasLeadingNoise = !textSample.startsWith("#EXTM3U", ignoreCase = true) &&
                                !textSample.startsWith("\uFEFF#EXTM3U", ignoreCase = true)
                            val isMasterOrVod = trimmedSample.contains("#EXT-X-STREAM-INF", ignoreCase = true) ||
                                trimmedSample.contains("#EXT-X-ENDLIST", ignoreCase = true)

                            if (hasLeadingNoise && isMasterOrVod && totalRead < buffer.size) {
                                val rewritten = rewriteHlsManifestToAbsolute(textSample, finalUrl)
                                val cacheFile = File(context.cacheDir, "soyken_resolved_stream.m3u8")
                                cacheFile.writeText(rewritten, Charsets.UTF_8)
                                return@withContext ProbeResolutionResult(
                                    resolved = ResolvedStreamCandidate(
                                        url = Uri.fromFile(cacheFile).toString(),
                                        formatKind = StreamFormatKind.HLS,
                                        userAgent = ua,
                                        customHeaders = resolvedHeaders
                                    ),
                                    confirmedHttpStatus = 200
                                )
                            }

                            return@withContext ProbeResolutionResult(
                                resolved = ResolvedStreamCandidate(
                                    url = finalUrl,
                                    formatKind = StreamFormatKind.HLS,
                                    userAgent = ua,
                                    customHeaders = resolvedHeaders
                                ),
                                confirmedHttpStatus = 200
                            )
                        }

                        // 3. Check MPEG-DASH (<MPD) manifest
                        if ((trimmedSample.startsWith("<?xml", ignoreCase = true) ||
                                trimmedSample.startsWith("<MPD", ignoreCase = true)) &&
                            trimmedSample.contains("<MPD", ignoreCase = true)
                        ) {
                            return@withContext ProbeResolutionResult(
                                resolved = ResolvedStreamCandidate(
                                    url = finalUrl,
                                    formatKind = StreamFormatKind.DASH,
                                    userAgent = ua,
                                    customHeaders = resolvedHeaders
                                ),
                                confirmedHttpStatus = 200
                            )
                        }

                        // 4. Check HTML / JSON wrapper for embedded .m3u8, .mpd, or .mp4 stream URL
                        extractEmbeddedStreamFromText(textSample, finalUrl)?.let { (embeddedUrl, embeddedKind) ->
                            val embedHeaders = LinkedHashMap(resolvedHeaders)
                            if (finalOrigin != null) {
                                embedHeaders["Referer"] = "$finalOrigin/"
                                embedHeaders["Origin"] = finalOrigin
                            }
                            return@withContext ProbeResolutionResult(
                                resolved = ResolvedStreamCandidate(
                                    url = embeddedUrl,
                                    formatKind = embeddedKind,
                                    userAgent = UA_CHROME_ANDROID,
                                    customHeaders = embedHeaders
                                ),
                                confirmedHttpStatus = 200
                            )
                        }

                        // 5. Follow 1-hop <iframe src="..."> player embed if present
                        val iframeUrl = extractIframeSrcFromHtml(textSample, finalUrl)
                        if (iframeUrl != null) {
                            val iframeReq = Request.Builder()
                                .url(iframeUrl)
                                .header("User-Agent", UA_CHROME_ANDROID)
                                .header("Accept", "*/*")
                                .apply {
                                    if (finalOrigin != null) header("Referer", finalUrl)
                                }
                                .build()
                            runCatching {
                                probeClient.newCall(iframeReq).execute().use { iframeResp ->
                                    if (iframeResp.isSuccessful) {
                                        val iframeBody = iframeResp.body?.string().orEmpty()
                                        val iframeFinalUrl = iframeResp.request.url.toString()
                                        extractEmbeddedStreamFromText(iframeBody, iframeFinalUrl)?.let { (embUrl, embKind) ->
                                            val iframeOrigin = extractOrigin(iframeFinalUrl) ?: finalOrigin
                                            val embHeaders = LinkedHashMap<String, String>()
                                            embHeaders["Accept"] = "*/*"
                                            if (iframeOrigin != null) {
                                                embHeaders["Referer"] = "$iframeOrigin/"
                                                embHeaders["Origin"] = iframeOrigin
                                            }
                                            return@withContext ProbeResolutionResult(
                                                resolved = ResolvedStreamCandidate(
                                                    url = embUrl,
                                                    formatKind = embKind,
                                                    userAgent = UA_CHROME_ANDROID,
                                                    customHeaders = embHeaders
                                                ),
                                                confirmedHttpStatus = 200
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    val cls = e.javaClass.name
                    if (cls.contains("SSL", ignoreCase = true) ||
                        cls.contains("Cert", ignoreCase = true) ||
                        e is java.net.ConnectException ||
                        e is java.net.UnknownHostException
                    ) {
                        sawOriginFailure = true
                        if (origin != null) {
                            deadOrigins.add(origin)
                        }
                    }
                }
            }
        }

        ProbeResolutionResult(
            resolved = null,
            confirmedHttpStatus = bestPrimaryStatus
        )
    }
}
