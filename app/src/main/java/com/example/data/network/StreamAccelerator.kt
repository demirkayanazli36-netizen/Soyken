package com.example.data.network

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import com.example.data.model.Channel
import com.example.data.parser.M3uParser
import com.example.data.parser.StreamFormatKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.Request
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Adaptive bitrate / resolution step-down stages used when a live stream or movie experiences
 * mid-stream rebuffering on slow or fluctuating internet connections.
 */
enum class StallCapStage(
    val maxWidth: Int,
    val maxHeight: Int,
    val maxBitrate: Int,
    val maxFrameRate: Int
) {
    NONE(
        maxWidth = 1920,
        maxHeight = 1080,
        maxBitrate = Int.MAX_VALUE,
        maxFrameRate = 60
    ),
    CAP_720P(
        maxWidth = 1280,
        maxHeight = 720,
        maxBitrate = 3_200_000,
        maxFrameRate = 30
    ),
    CAP_480P(
        maxWidth = 854,
        maxHeight = 480,
        maxBitrate = 1_500_000,
        maxFrameRate = 30
    ),
    CAP_360P(
        maxWidth = 640,
        maxHeight = 360,
        maxBitrate = 850_000,
        maxFrameRate = 30
    );

    fun stepDown(): StallCapStage = when (this) {
        NONE -> CAP_720P
        CAP_720P -> CAP_480P
        CAP_480P -> CAP_360P
        CAP_360P -> CAP_360P
    }
}

/**
 * Remembers the proven working connection profile (scheme, port, stream container format, and
 * User-Agent) for each IPTV server host so subsequent channel switches and movie opens connect
 * on the 1st attempt in milliseconds instead of re-testing incompatible schemes/ports/UAs.
 */
data class ServerSuccessProfile(
    val scheme: String,
    val explicitPort: Int,
    val formatKind: StreamFormatKind,
    val userAgent: String,
    val updatedAtMs: Long = System.currentTimeMillis()
)

/**
 * Multi-layer M3U stream acceleration engine:
 *  1. Persistent 200 MB LRU disk media cache (`SimpleCache` + `CacheDataSource`) for VOD movies &
 *     series so watched/pre-buffered chunks play at 0ms independent of internet speed, while
 *     continuous live TV streams bypass disk writes for zero flash wear & zero I/O latency.
 *  2. Per-host IPTV server profile memory (`ServerSuccessProfile`) for instant 1st-try zapping.
 *  3. Proactive background DNS pre-resolution & HTTP/TLS keep-alive socket pre-connection across
 *     loaded M3U playlists and adjacent (`Next` / `Previous`) channels.
 */
@OptIn(UnstableApi::class)
object StreamAccelerator {

    private const val MEDIA_CACHE_DIR_NAME = "soyken_vod_media_cache"
    private const val MAX_VOD_DISK_CACHE_BYTES = 200L * 1024L * 1024L // 200 MB LRU cache
    private const val MAX_PREWARM_PLAYLIST_HOSTS = 20
    private const val MAX_SOCKET_WARM_ORIGINS = 4
    private const val SOCKET_WARM_COOLDOWN_MS = 90_000L

    private val acceleratorScope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.IO) }

    private val serverProfiles = ConcurrentHashMap<String, ServerSuccessProfile>()
    private val warmedSocketOrigins = ConcurrentHashMap<String, Long>()

    @Volatile
    private var sharedVodCache: Cache? = null
    private val cacheInitLock = Any()

    private val preConnectClient by lazy {
        CloudflareDns.createHttpClient(
            connectTimeoutMs = 2_500L,
            readTimeoutMs = 2_500L
        )
    }

    /**
     * Returns true when [rawUrl] points to a VOD movie/series file whose byte ranges should be
     * persisted on disk so seeking, rewinding, and re-watching require zero internet bandwidth.
     * Continuous live broadcasts (`/live/`, `.ts`, `.m3u8`, `live.php`) return false so disk is
     * never thrashed by endless live packets.
     */
    fun isCacheableVodUrl(rawUrl: String): Boolean {
        val clean = M3uParser.extractStreamRequestInfo(rawUrl).cleanUrl
            .lowercase()
            .substringBefore('#')
            .substringBefore('?')
        if (clean.isBlank()) return false
        if (clean.startsWith("android.resource://") ||
            clean.startsWith("content://") ||
            clean.startsWith("file://")
        ) {
            return false
        }
        if (clean.contains("/live/") ||
            clean.endsWith(".ts") ||
            clean.endsWith(".m3u8") ||
            clean.endsWith(".mpd") ||
            clean.endsWith("/mpegts") ||
            clean.contains("live.php")
        ) {
            return false
        }
        return clean.contains("/movie/") ||
            clean.contains("/series/") ||
            clean.contains("/vod/") ||
            clean.endsWith(".mp4") ||
            clean.endsWith(".m4v") ||
            clean.endsWith(".mkv") ||
            clean.endsWith(".webm") ||
            clean.endsWith(".avi") ||
            clean.endsWith(".mov")
    }

    private fun getOrCreateVodCache(context: Context): Cache? {
        val existing = sharedVodCache
        if (existing != null) return existing
        synchronized(cacheInitLock) {
            sharedVodCache?.let { return it }
            return runCatching {
                val appCtx = context.applicationContext
                val cacheDir = File(appCtx.cacheDir, MEDIA_CACHE_DIR_NAME).apply { mkdirs() }
                val evictor = LeastRecentlyUsedCacheEvictor(MAX_VOD_DISK_CACHE_BYTES)
                val dbProvider = StandaloneDatabaseProvider(appCtx)
                SimpleCache(cacheDir, evictor, dbProvider).also { created ->
                    sharedVodCache = created
                }
            }.getOrNull()
        }
    }

    /**
     * Creates a [DataSource.Factory] that serves & caches VOD movie/series byte chunks from local
     * flash storage when [isTurboEnabled] is true, while routing live TV directly through RAM.
     */
    fun createSmartCacheDataSourceFactory(
        context: Context,
        upstreamFactory: DataSource.Factory,
        isTurboEnabled: () -> Boolean
    ): DataSource.Factory {
        val appContext = context.applicationContext
        return DataSource.Factory {
            val rawUpstream = upstreamFactory.createDataSource()
            val vodCache = if (isTurboEnabled()) getOrCreateVodCache(appContext) else null
            val vodCacheSource = if (vodCache != null) {
                CacheDataSource.Factory()
                    .setCache(vodCache)
                    .setUpstreamDataSourceFactory(upstreamFactory)
                    .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
                    .createDataSource()
            } else {
                null
            }
            RoutingMediaDataSource(
                liveUpstream = rawUpstream,
                vodCacheSource = vodCacheSource,
                isTurboEnabled = isTurboEnabled
            )
        }
    }

    private class RoutingMediaDataSource(
        private val liveUpstream: DataSource,
        private val vodCacheSource: DataSource?,
        private val isTurboEnabled: () -> Boolean
    ) : DataSource {
        private var activeSource: DataSource? = null

        override fun addTransferListener(transferListener: TransferListener) {
            liveUpstream.addTransferListener(transferListener)
            vodCacheSource?.addTransferListener(transferListener)
        }

        override fun open(dataSpec: DataSpec): Long {
            val useDiskCache = vodCacheSource != null &&
                isTurboEnabled() &&
                isCacheableVodUrl(dataSpec.uri.toString())
            val chosen = if (useDiskCache) vodCacheSource!! else liveUpstream
            activeSource = chosen
            return chosen.open(dataSpec)
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            return (activeSource ?: liveUpstream).read(buffer, offset, length)
        }

        override fun getUri(): Uri? = (activeSource ?: liveUpstream).uri

        override fun getResponseHeaders(): Map<String, List<String>> {
            return (activeSource ?: liveUpstream).responseHeaders
        }

        override fun close() {
            val target = activeSource
            activeSource = null
            target?.close()
        }
    }

    internal fun buildProfileKey(rawUrl: String): String? {
        val clean = M3uParser.extractStreamRequestInfo(rawUrl).cleanUrl
        val host = CloudflareDns.extractHostFromUrl(clean)?.lowercase() ?: return null
        val kindTag = if (isCacheableVodUrl(clean)) "vod" else "live"
        return "$host|$kindTag"
    }

    internal fun extractSchemeAndPort(url: String): Pair<String, Int>? {
        val schemeIdx = url.indexOf("://")
        if (schemeIdx <= 0) return null
        val scheme = url.substring(0, schemeIdx).lowercase()
        val afterScheme = url.substring(schemeIdx + 3)
        val authority = afterScheme.substringBefore('/').substringBefore('?').substringBefore('#')
        val hostPort = authority.substringAfterLast('@')
        val explicitPort = if (hostPort.startsWith("[")) {
            val closeIdx = hostPort.indexOf(']')
            if (closeIdx > 0 && closeIdx + 1 < hostPort.length && hostPort[closeIdx + 1] == ':') {
                hostPort.substring(closeIdx + 2).toIntOrNull() ?: -1
            } else {
                -1
            }
        } else if (hostPort.contains(':')) {
            hostPort.substringAfterLast(':').toIntOrNull() ?: -1
        } else {
            -1
        }
        return scheme to explicitPort
    }

    /**
     * Records a proven working candidate profile for the server host so subsequent channels or
     * movies on the same IPTV server skip failing schemes/ports/formats/User-Agents immediately.
     */
    fun recordWorkingStream(
        rawChannelUrl: String,
        workingCandidateUrl: String,
        formatKind: StreamFormatKind,
        userAgent: String
    ) {
        val key = buildProfileKey(rawChannelUrl) ?: return
        val (scheme, port) = extractSchemeAndPort(workingCandidateUrl) ?: return
        serverProfiles[key] = ServerSuccessProfile(
            scheme = scheme,
            explicitPort = port,
            formatKind = formatKind,
            userAgent = userAgent,
            updatedAtMs = System.currentTimeMillis()
        )
    }

    fun getServerProfile(rawChannelUrl: String): ServerSuccessProfile? {
        val key = buildProfileKey(rawChannelUrl) ?: return null
        return serverProfiles[key]
    }

    fun clearServerProfilesForTesting() {
        serverProfiles.clear()
    }

    /**
     * Reorders candidate items using any previously verified [ServerSuccessProfile] for the same
     * IPTV panel while preserving deterministic fallback order for all remaining candidates.
     */
    fun <T> prioritizeCandidates(
        rawChannelUrl: String,
        candidates: List<T>,
        urlSelector: (T) -> String,
        formatSelector: (T) -> StreamFormatKind,
        userAgentSelector: (T) -> String
    ): List<T> {
        if (candidates.size <= 1) return candidates
        val profile = getServerProfile(rawChannelUrl) ?: return candidates

        return candidates.sortedByDescending { candidate ->
            val url = urlSelector(candidate)
            val (scheme, port) = extractSchemeAndPort(url) ?: ("" to -1)
            val format = formatSelector(candidate)
            val ua = userAgentSelector(candidate)

            var bonus = 0
            if (scheme == profile.scheme) bonus += 4
            if (port == profile.explicitPort) bonus += 4
            if (format == profile.formatKind) bonus += 3
            if (ua == profile.userAgent) bonus += 2
            bonus
        }
    }

    /**
     * Pre-resolves unique stream domains into [CloudflareDns] and warms keep-alive TCP/TLS sockets
     * in [CloudflareDns.sharedConnectionPool] so tapping any channel starts with 0ms DNS & warm TCP/TLS.
     */
    fun preWarmPlaylistAsync(channels: List<Channel>) {
        if (channels.isEmpty()) return
        acceleratorScope.launch {
            val sampleUrls = LinkedHashSet<String>()
            for (ch in channels) {
                val clean = M3uParser.extractStreamRequestInfo(ch.streamUrl).cleanUrl
                if (clean.startsWith("http://", ignoreCase = true) ||
                    clean.startsWith("https://", ignoreCase = true)
                ) {
                    sampleUrls.add(clean)
                    if (sampleUrls.size >= 80) break
                }
            }

            val uniqueHosts = LinkedHashSet<String>()
            val uniqueOrigins = LinkedHashSet<String>()
            for (url in sampleUrls) {
                CloudflareDns.extractHostFromUrl(url)?.let { host ->
                    if (uniqueHosts.size < MAX_PREWARM_PLAYLIST_HOSTS) {
                        uniqueHosts.add(host)
                    }
                }
                extractOriginPrefix(url)?.let { origin ->
                    if (uniqueOrigins.size < MAX_SOCKET_WARM_ORIGINS) {
                        uniqueOrigins.add(origin)
                    }
                }
            }

            // 1. Pre-resolve DNS in parallel for unique playlist domains
            for (host in uniqueHosts) {
                if (!CloudflareDns.isIpLiteral(host) && !CloudflareDns.isHostCached(host)) {
                    launch {
                        runCatching { CloudflareDns.lookup(host) }
                    }
                }
            }

            // 2. Warm HTTP/TLS keep-alive sockets in sharedConnectionPool for top origins
            for (origin in uniqueOrigins) {
                launch {
                    warmOriginSocketIfNeeded(origin)
                }
            }
        }
    }

    /**
     * Pre-warms DNS and keep-alive TCP/TLS connections for a target channel and its adjacent
     * (`Next` / `Previous`) channels so zapping is instantaneous.
     */
    fun preWarmUrlsAsync(urls: List<String>) {
        if (urls.isEmpty()) return
        acceleratorScope.launch {
            for (raw in urls.distinct().take(4)) {
                val clean = M3uParser.extractStreamRequestInfo(raw).cleanUrl
                if (!clean.startsWith("http://", ignoreCase = true) &&
                    !clean.startsWith("https://", ignoreCase = true)
                ) {
                    continue
                }
                launch {
                    runCatching { CloudflareDns.ensureConnectedForUrl(clean) }
                    extractOriginPrefix(clean)?.let { origin ->
                        warmOriginSocketIfNeeded(origin)
                    }
                }
            }
        }
    }

    private fun warmOriginSocketIfNeeded(origin: String) {
        val key = origin.lowercase()
        val now = System.currentTimeMillis()
        val lastWarm = warmedSocketOrigins[key] ?: 0L
        if (now - lastWarm < SOCKET_WARM_COOLDOWN_MS) return
        warmedSocketOrigins[key] = now

        runCatching {
            val req = Request.Builder()
                .url("$origin/")
                .head()
                .header("User-Agent", "IPTVSmartersPlayer")
                .header("Connection", "keep-alive")
                .build()
            preConnectClient.newCall(req).execute().close()
        }
    }

    private fun extractOriginPrefix(url: String): String? {
        val schemeIdx = url.indexOf("://")
        if (schemeIdx == -1) return null
        val pathIdx = url.indexOf('/', schemeIdx + 3)
        return if (pathIdx == -1) url else url.substring(0, pathIdx)
    }
}
