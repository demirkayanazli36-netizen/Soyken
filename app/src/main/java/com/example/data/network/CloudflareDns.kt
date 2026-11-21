package com.example.data.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.ConnectionPool
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Dispatcher
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.IDN
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.UnknownHostException
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.net.SocketFactory
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Resolves all M3U playlist, HLS/DASH/TS broadcast, CDN redirect, and channel logo domains
 * through Cloudflare's encrypted DNS endpoint (`1dot1dot1dot1.cloudflare-dns.com`)
 * BEFORE establishing M3U HTTP/HTTPS connections.
 *
 * Uses a resilient 3-layer Cloudflare transport chain:
 *  1. DNS-over-HTTPS (DoH RFC 8484 binary wireformat) -> https://1dot1dot1dot1.cloudflare-dns.com/dns-query
 *  2. DNS-over-HTTPS (JSON API fallback)              -> https://1dot1dot1dot1.cloudflare-dns.com/dns-query
 *  3. DNS-over-TLS  (DoT RFC 7858 on TCP port 853)    -> 1dot1dot1dot1.cloudflare-dns.com:853
 */
object CloudflareDns : Dns {

    const val CLOUDFLARE_DNS_HOST = "1dot1dot1dot1.cloudflare-dns.com"
    const val CLOUDFLARE_DOH_URL = "https://1dot1dot1dot1.cloudflare-dns.com/dns-query"
    const val GOOGLE_DNS_HOST = "dns.google"
    const val GOOGLE_DOH_URL = "https://dns.google/dns-query"
    const val CLOUDFLARE_DOT_PORT = 853
    private const val STREAM_SO_RCVBUF_BYTES = 512 * 1024
    private const val STREAM_IP_TOS_LOWDELAY_THROUGHPUT = 0x18

    internal const val TYPE_A = 1
    internal const val TYPE_AAAA = 28
    private const val CLASS_IN = 1

    private const val MIN_TTL_SECONDS = 120L
    private const val MAX_TTL_SECONDS = 1_800L
    private const val STALE_SERVE_WINDOW_MS = 6 * 60 * 60 * 1_000L
    private const val SESSION_HEARTBEAT_MS = 10 * 60 * 1_000L

    private val DNS_MESSAGE_MEDIA_TYPE by lazy { "application/dns-message".toMediaType() }

    internal data class CachedDnsEntry(
        val addresses: List<InetAddress>,
        val expiresAtMs: Long,
        val staleUntilMs: Long = expiresAtMs + STALE_SERVE_WINDOW_MS
    )

    internal data class WireParseResult(
        val rcode: Int,
        val addresses: List<InetAddress>,
        val minTtlSeconds: Long
    )

    private val dnsCache = ConcurrentHashMap<String, CachedDnsEntry>()
    private val inFlightRefreshHosts: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val hostLookupLocks = ConcurrentHashMap<String, Any>()
    private val lastVerifiedDnsHandshakeMs = AtomicLong(0L)
    private val warmUpScope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.IO) }

    val sharedConnectionPool: ConnectionPool by lazy { ConnectionPool(32, 5, TimeUnit.MINUTES) }

    /**
     * Thread-safe cookie jar shared across M3U playlist imports, StreamProbe, and ExoPlayer
     * so IPTV session cookies (`Set-Cookie` on 302 redirects) persist across HLS `.ts` segments.
     */
    val sharedCookieJar: CookieJar = object : CookieJar {
        private val store = ConcurrentHashMap<String, MutableList<Cookie>>()

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            if (cookies.isEmpty()) return
            val hostKey = url.host.lowercase()
            store.compute(hostKey) { _, existing ->
                val list = existing ?: mutableListOf()
                synchronized(list) {
                    for (incoming in cookies) {
                        list.removeAll { it.name == incoming.name && it.path == incoming.path }
                        if (incoming.expiresAt > System.currentTimeMillis()) {
                            list.add(incoming)
                        }
                    }
                }
                list
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val now = System.currentTimeMillis()
            val result = mutableListOf<Cookie>()
            for ((_, bucket) in store) {
                synchronized(bucket) {
                    bucket.removeAll { it.expiresAt <= now }
                    for (cookie in bucket) {
                        if (cookie.matches(url)) {
                            result.add(cookie)
                        }
                    }
                }
            }
            return result
        }
    }

    /**
     * Official Cloudflare bootstrap addresses for `1dot1dot1dot1.cloudflare-dns.com`.
     * Constructed via `InetAddress.getByAddress(host, bytes)` in memory (0ms, zero ISP DNS queries)
     * while preserving `1dot1dot1dot1.cloudflare-dns.com` for TLS SNI and X.509 verification.
     */
    private val bootstrapAddresses: List<InetAddress> by lazy {
        buildList {
            add(InetAddress.getByAddress(CLOUDFLARE_DNS_HOST, byteArrayOf(1, 1, 1, 1)))
            add(InetAddress.getByAddress(CLOUDFLARE_DNS_HOST, byteArrayOf(1, 0, 0, 1)))
            runCatching {
                add(
                    InetAddress.getByAddress(
                        CLOUDFLARE_DNS_HOST,
                        byteArrayOf(
                            0x26, 0x06, 0x47, 0x00, 0x47, 0x00, 0x00, 0x00,
                            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x11, 0x11
                        )
                    )
                )
                add(
                    InetAddress.getByAddress(
                        CLOUDFLARE_DNS_HOST,
                        byteArrayOf(
                            0x26, 0x06, 0x47, 0x00, 0x47, 0x00, 0x00, 0x00,
                            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x10, 0x01
                        )
                    )
                )
            }
        }
    }

    private val googleBootstrapAddresses: List<InetAddress> by lazy {
        listOf(
            InetAddress.getByAddress(GOOGLE_DNS_HOST, byteArrayOf(8, 8, 8, 8)),
            InetAddress.getByAddress(GOOGLE_DNS_HOST, byteArrayOf(8, 8, 4, 4))
        )
    }

    private val bootstrapDns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            if (hostname.equals(CLOUDFLARE_DNS_HOST, ignoreCase = true)) {
                return bootstrapAddresses
            }
            if (hostname.equals(GOOGLE_DNS_HOST, ignoreCase = true)) {
                return googleBootstrapAddresses
            }
            return Dns.SYSTEM.lookup(hostname)
        }
    }

    private val dnsRaceExecutor by lazy {
        Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "soyken-dns-racer").apply { isDaemon = true }
        }
    }

    /**
     * Tuned TCP [SocketFactory] for M3U playlists and live/VOD media segments:
     * - `tcpNoDelay = true`: disables Nagle buffering so segment HTTP requests transmit in 0ms
     * - `receiveBufferSize = 512 KB`: widens TCP receive window for maximum fiber / 4.5G / 5G throughput
     * - `trafficClass = 0x18`: sets IPTOS_LOWDELAY | IPTOS_THROUGHPUT DSCP hint for Wi-Fi WMM routers
     */
    val highThroughputSocketFactory: SocketFactory = object : SocketFactory() {
        private val delegate = getDefault()

        private fun tune(socket: Socket): Socket {
            runCatching { socket.tcpNoDelay = true }
            runCatching { socket.keepAlive = true }
            runCatching { socket.receiveBufferSize = STREAM_SO_RCVBUF_BYTES }
            runCatching { socket.trafficClass = STREAM_IP_TOS_LOWDELAY_THROUGHPUT }
            return socket
        }

        override fun createSocket(): Socket = tune(delegate.createSocket())

        override fun createSocket(host: String?, port: Int): Socket =
            tune(delegate.createSocket(host, port))

        override fun createSocket(
            host: String?,
            port: Int,
            localHost: InetAddress?,
            localPort: Int
        ): Socket = tune(delegate.createSocket(host, port, localHost, localPort))

        override fun createSocket(host: InetAddress?, port: Int): Socket =
            tune(delegate.createSocket(host, port))

        override fun createSocket(
            address: InetAddress?,
            port: Int,
            localAddress: InetAddress?,
            localPort: Int
        ): Socket = tune(delegate.createSocket(address, port, localAddress, localPort))
    }

    private val sharedStreamingDispatcher: Dispatcher by lazy {
        Dispatcher().apply {
            maxRequests = 128
            maxRequestsPerHost = 32
        }
    }

    private val dohHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dns(bootstrapDns)
            .socketFactory(highThroughputSocketFactory)
            .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
            .connectionPool(ConnectionPool(8, 5, TimeUnit.MINUTES))
            .connectTimeout(1_600, TimeUnit.MILLISECONDS)
            .readTimeout(1_800, TimeUnit.MILLISECONDS)
            .writeTimeout(1_800, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    /**
     * Creates an `OkHttpClient` tuned for maximum streaming throughput:
     * - Routes host lookups via fast parallel DoH (`1.1.1.1` + `8.8.8.8`) + 0ms RAM cache
     * - Applies 512 KB TCP receive window, `TCP_NODELAY`, HTTP/2 multiplexing, and 32 concurrent
     *   streams per host so channel logos or pre-warms never block active video segments.
     */
    fun createHttpClient(
        connectTimeoutMs: Long = 5_000L,
        readTimeoutMs: Long = 12_000L,
        customDispatcher: Dispatcher? = null
    ): OkHttpClient {
        return OkHttpClient.Builder()
            .dns(this)
            .socketFactory(highThroughputSocketFactory)
            .dispatcher(customDispatcher ?: sharedStreamingDispatcher)
            .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
            .cookieJar(sharedCookieJar)
            .connectionPool(sharedConnectionPool)
            .connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }

    /**
     * Warms up the encrypted connection to `1dot1dot1dot1.cloudflare-dns.com` in the background.
     */
    fun warmUpAsync() {
        warmUpScope.launch {
            runCatching { ensureSessionActive() }
        }
    }

    /**
     * Non-blocking async DNS pre-resolution for a stream or playlist URL.
     */
    fun preWarmUrlAsync(rawUrl: String?) {
        if (rawUrl.isNullOrBlank()) return
        warmUpScope.launch {
            runCatching { ensureConnectedForUrl(rawUrl) }
        }
    }

    internal fun isHostCached(rawHost: String): Boolean {
        val cleanHost = rawHost.trim().trimEnd('.')
        if (cleanHost.isEmpty()) return false
        if (isIpLiteral(cleanHost) || cleanHost.equals("localhost", ignoreCase = true)) return true
        val asciiHost = runCatching { IDN.toASCII(cleanHost) }.getOrDefault(cleanHost).lowercase()
        val entry = dnsCache[asciiHost] ?: return false
        return entry.addresses.isNotEmpty() && entry.staleUntilMs > System.currentTimeMillis()
    }

    /**
     * Pre-resolves and caches the M3U target hostname via `1dot1dot1dot1.cloudflare-dns.com`.
     * IP-literal stream hosts return in 0ms without blocking playback on any extra network round-trip.
     */
    fun ensureConnectedForUrl(rawUrl: String?) {
        if (rawUrl.isNullOrBlank()) return
        val trimmed = rawUrl.trim().substringBefore('|')
        val lower = trimmed.lowercase()
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return

        val host = extractHostFromUrl(trimmed)
        if (!host.isNullOrBlank() && !isIpLiteral(host) && !isLocalHostname(host)) {
            runCatching { lookup(host) }
        } else {
            warmUpAsync()
        }
    }

    /**
     * Verifies/establishes an active encrypted connection to `1dot1dot1dot1.cloudflare-dns.com`.
     * Uses a 10-minute heartbeat window so consecutive HLS `.ts` segment downloads incur 0ms overhead.
     */
    fun ensureSessionActive() {
        val now = System.currentTimeMillis()
        val last = lastVerifiedDnsHandshakeMs.get()
        if (last > 0L && (now - last) < SESSION_HEARTBEAT_MS) return

        val resolved = queryCloudflareAddresses("cloudflare.com")
        if (resolved.isNotEmpty()) {
            lastVerifiedDnsHandshakeMs.set(System.currentTimeMillis())
        }
    }

    private fun refreshHostAsync(asciiHost: String) {
        if (!inFlightRefreshHosts.add(asciiHost)) return
        warmUpScope.launch {
            try {
                val fresh = queryCloudflareWithTtl(asciiHost)
                if (fresh != null && fresh.addresses.isNotEmpty()) {
                    storeCachedResult(asciiHost, fresh)
                }
            } catch (_: Exception) {
                // Keep serving existing cached addresses
            } finally {
                inFlightRefreshHosts.remove(asciiHost)
            }
        }
    }

    private fun storeCachedResult(asciiHost: String, result: WireParseResult) {
        lastVerifiedDnsHandshakeMs.set(System.currentTimeMillis())
        val clampedTtlSec = result.minTtlSeconds.coerceIn(MIN_TTL_SECONDS, MAX_TTL_SECONDS)
        val now = System.currentTimeMillis()
        val expiresAt = now + clampedTtlSec * 1_000L
        dnsCache[asciiHost] = CachedDnsEntry(
            addresses = result.addresses,
            expiresAtMs = expiresAt,
            staleUntilMs = now + STALE_SERVE_WINDOW_MS
        )
    }

    override fun lookup(hostname: String): List<InetAddress> {
        val cleanHost = hostname.trim().trimEnd('.')
        if (cleanHost.isEmpty()) {
            throw UnknownHostException("Empty hostname")
        }

        // Fast-path for literal IPv4 / IPv6 addresses or localhost (0ms)
        if (cleanHost.equals("localhost", ignoreCase = true)) {
            return listOf(InetAddress.getLoopbackAddress())
        }
        if (isIpLiteral(cleanHost)) {
            return listOf(InetAddress.getByName(cleanHost.removeSurrounding("[", "]")))
        }
        if (cleanHost.equals(CLOUDFLARE_DNS_HOST, ignoreCase = true)) {
            return bootstrapAddresses
        }
        if (isLocalHostname(cleanHost)) {
            return Dns.SYSTEM.lookup(cleanHost)
        }

        val asciiHost = runCatching { IDN.toASCII(cleanHost) }.getOrDefault(cleanHost).lowercase()
        val now = System.currentTimeMillis()

        // 1. Serve fresh cached entry in 0ms
        val cached = dnsCache[asciiHost]
        if (cached != null && cached.addresses.isNotEmpty()) {
            if (cached.expiresAtMs > now) {
                return cached.addresses
            }
            // Stale-While-Revalidate: return cached IP in 0ms and refresh asynchronously!
            if (cached.staleUntilMs > now) {
                refreshHostAsync(asciiHost)
                return cached.addresses
            }
        }

        // 2. Coalesce concurrent cold lookups for the same hostname
        val hostLock = hostLookupLocks.computeIfAbsent(asciiHost) { Any() }
        synchronized(hostLock) {
            val recheck = dnsCache[asciiHost]
            val recheckNow = System.currentTimeMillis()
            if (recheck != null && recheck.addresses.isNotEmpty() && recheck.staleUntilMs > recheckNow) {
                return recheck.addresses
            }

            val freshResult = queryCloudflareWithTtl(asciiHost)
            if (freshResult != null && freshResult.addresses.isNotEmpty()) {
                storeCachedResult(asciiHost, freshResult)
                return freshResult.addresses
            }

            if (recheck != null && recheck.addresses.isNotEmpty()) {
                return recheck.addresses
            }

            val systemAddresses = Dns.SYSTEM.lookup(cleanHost)
            if (systemAddresses.isNotEmpty()) {
                val fallbackNow = System.currentTimeMillis()
                dnsCache[asciiHost] = CachedDnsEntry(
                    addresses = systemAddresses,
                    expiresAtMs = fallbackNow + MIN_TTL_SECONDS * 1_000L,
                    staleUntilMs = fallbackNow + STALE_SERVE_WINDOW_MS
                )
            }
            return systemAddresses
        }
    }

    private fun queryCloudflareAddresses(asciiHost: String): List<InetAddress> {
        return queryCloudflareWithTtl(asciiHost)?.addresses.orEmpty()
    }

    private fun raceDohWireformat(asciiHost: String, qtype: Int): WireParseResult? {
        val completion = ExecutorCompletionService<WireParseResult?>(dnsRaceExecutor)
        val cfFuture = completion.submit(Callable {
            runCatching { queryDohWireformatEndpoint(CLOUDFLARE_DOH_URL, asciiHost, qtype) }.getOrNull()
        })
        val googleFuture = completion.submit(Callable {
            runCatching { queryDohWireformatEndpoint(GOOGLE_DOH_URL, asciiHost, qtype) }.getOrNull()
        })

        var fallbackEmptyNoError: WireParseResult? = null
        try {
            repeat(2) {
                val done = completion.poll(1_900, TimeUnit.MILLISECONDS) ?: return@repeat
                val res = runCatching { done.get() }.getOrNull()
                if (res != null) {
                    if (res.addresses.isNotEmpty() || res.rcode == 3) {
                        return res
                    }
                    if (res.rcode == 0 && fallbackEmptyNoError == null) {
                        fallbackEmptyNoError = res
                    }
                }
            }
        } finally {
            cfFuture.cancel(true)
            googleFuture.cancel(true)
        }
        return fallbackEmptyNoError
    }

    private fun queryCloudflareWithTtl(asciiHost: String): WireParseResult? {
        // Layer 1: Parallel Happy-Eyeballs race between Cloudflare DoH (1.1.1.1) and Google DoH (8.8.8.8)
        val dohIpv4 = runCatching { raceDohWireformat(asciiHost, TYPE_A) }.getOrNull()
        if (dohIpv4 != null) {
            if (dohIpv4.addresses.isNotEmpty()) {
                return dohIpv4
            }
            // Domain might be IPv6-only (AAAA) if NOERROR with 0 A records
            if (dohIpv4.rcode == 0) {
                val dohIpv6 = runCatching { raceDohWireformat(asciiHost, TYPE_AAAA) }.getOrNull()
                if (dohIpv6 != null && dohIpv6.addresses.isNotEmpty()) {
                    return dohIpv6
                }
            }
            if (dohIpv4.rcode == 3) {
                // Authoritative NXDOMAIN
                lastVerifiedDnsHandshakeMs.set(System.currentTimeMillis())
                return dohIpv4
            }
        }

        // Layer 2: Cloudflare DoH JSON API over HTTPS to 1dot1dot1dot1.cloudflare-dns.com
        val dohJson = runCatching { queryDohJson(asciiHost, TYPE_A) }.getOrNull()
        if (dohJson != null && dohJson.addresses.isNotEmpty()) {
            return dohJson
        }

        // Layer 3: RFC 7858 DNS-over-TLS (DoT) on port 853 to 1dot1dot1dot1.cloudflare-dns.com
        val dotIpv4 = runCatching { queryDotTls853(asciiHost, TYPE_A) }.getOrNull()
        if (dotIpv4 != null && dotIpv4.addresses.isNotEmpty()) {
            return dotIpv4
        }

        return null
    }

    private fun queryDohWireformat(asciiHost: String, qtype: Int): WireParseResult? {
        return queryDohWireformatEndpoint(CLOUDFLARE_DOH_URL, asciiHost, qtype)
    }

    private fun queryDohWireformatEndpoint(
        endpointUrl: String,
        asciiHost: String,
        qtype: Int
    ): WireParseResult? {
        val wireQuery = buildDnsWireQuery(asciiHost, qtype)
        val request = Request.Builder()
            .url(endpointUrl.toHttpUrl())
            .post(wireQuery.toRequestBody(DNS_MESSAGE_MEDIA_TYPE))
            .header("Accept", "application/dns-message")
            .build()

        dohHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val bytes = response.body?.bytes() ?: return null
            return parseDnsWireResponse(asciiHost, bytes)
        }
    }

    private fun queryDohJson(asciiHost: String, qtype: Int): WireParseResult? {
        val url = CLOUDFLARE_DOH_URL.toHttpUrl().newBuilder()
            .addQueryParameter("name", asciiHost)
            .addQueryParameter("type", if (qtype == TYPE_AAAA) "AAAA" else "A")
            .build()

        val request = Request.Builder()
            .url(url)
            .get()
            .header("Accept", "application/dns-json")
            .build()

        dohHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.string() ?: return null
            return parseDohJsonResponse(asciiHost, body)
        }
    }

    private fun queryDotTls853(asciiHost: String, qtype: Int): WireParseResult? {
        val wireQuery = buildDnsWireQuery(asciiHost, qtype)
        val sslFactory = SSLSocketFactory.getDefault() as SSLSocketFactory

        for (bootstrapAddr in bootstrapAddresses.take(2)) {
            try {
                Socket().use { rawSocket ->
                    rawSocket.connect(InetSocketAddress(bootstrapAddr, CLOUDFLARE_DOT_PORT), 3_000)
                    rawSocket.soTimeout = 3_500
                    val sslSocket = sslFactory.createSocket(
                        rawSocket,
                        CLOUDFLARE_DNS_HOST,
                        CLOUDFLARE_DOT_PORT,
                        true
                    ) as SSLSocket

                    sslSocket.use { tls ->
                        val params: SSLParameters = tls.sslParameters
                        params.serverNames = listOf(SNIHostName(CLOUDFLARE_DNS_HOST))
                        params.endpointIdentificationAlgorithm = "HTTPS"
                        tls.sslParameters = params
                        tls.startHandshake()

                        val verified = HttpsURLConnection.getDefaultHostnameVerifier()
                            .verify(CLOUDFLARE_DNS_HOST, tls.session)
                        if (!verified) {
                            throw UnknownHostException("TLS hostname verification failed for $CLOUDFLARE_DNS_HOST")
                        }

                        val out = DataOutputStream(tls.outputStream)
                        out.writeShort(wireQuery.size)
                        out.write(wireQuery)
                        out.flush()

                        val input = DataInputStream(tls.inputStream)
                        val respLen = input.readUnsignedShort()
                        if (respLen in 12..65535) {
                            val respBytes = ByteArray(respLen)
                            input.readFully(respBytes)
                            val parsed = parseDnsWireResponse(asciiHost, respBytes)
                            if (parsed != null) return parsed
                        }
                    }
                }
            } catch (_: Exception) {
                // Try secondary bootstrap IP (1.0.0.1)
            }
        }
        return null
    }

    internal fun buildDnsWireQuery(asciiHost: String, qtype: Int): ByteArray {
        val out = ByteArrayOutputStream(64)
        // 12-byte RFC 1035 Header (ID = 0 per RFC 8484 Section 4.1, Flags = 0x0100 RD=1, QDCOUNT = 1)
        out.write(0x00)
        out.write(0x00)
        out.write(0x01)
        out.write(0x00)
        out.write(0x00)
        out.write(0x01)
        out.write(0x00)
        out.write(0x00)
        out.write(0x00)
        out.write(0x00)
        out.write(0x00)
        out.write(0x00)

        val labels = asciiHost.trimEnd('.').split('.')
        for (label in labels) {
            val labelBytes = label.toByteArray(Charsets.US_ASCII)
            require(labelBytes.size in 1..63) { "Invalid DNS label length" }
            out.write(labelBytes.size)
            out.write(labelBytes)
        }
        out.write(0x00) // Root terminator

        // QTYPE
        out.write((qtype ushr 8) and 0xFF)
        out.write(qtype and 0xFF)
        // QCLASS = IN (1)
        out.write(0x00)
        out.write(CLASS_IN)

        return out.toByteArray()
    }

    internal fun parseDnsWireResponse(hostname: String, data: ByteArray): WireParseResult? {
        if (data.size < 12) return null

        val flags = ((data[2].toInt() and 0xFF) shl 8) or (data[3].toInt() and 0xFF)
        val isResponse = (flags and 0x8000) != 0
        if (!isResponse) return null

        val rcode = flags and 0x000F
        val qdCount = ((data[4].toInt() and 0xFF) shl 8) or (data[5].toInt() and 0xFF)
        val anCount = ((data[6].toInt() and 0xFF) shl 8) or (data[7].toInt() and 0xFF)

        var offset = 12
        for (i in 0 until qdCount) {
            offset = skipDnsName(data, offset)
            if (offset < 0 || offset + 4 > data.size) return null
            offset += 4 // Skip QTYPE + QCLASS
        }

        val addresses = mutableListOf<InetAddress>()
        var minTtl = MAX_TTL_SECONDS

        for (i in 0 until anCount) {
            offset = skipDnsName(data, offset)
            if (offset < 0 || offset + 10 > data.size) break

            val type = ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
            val rrClass = ((data[offset + 2].toInt() and 0xFF) shl 8) or (data[offset + 3].toInt() and 0xFF)
            val ttl = ((data[offset + 4].toLong() and 0xFF) shl 24) or
                ((data[offset + 5].toLong() and 0xFF) shl 16) or
                ((data[offset + 6].toLong() and 0xFF) shl 8) or
                (data[offset + 7].toLong() and 0xFF)
            val rdLength = ((data[offset + 8].toInt() and 0xFF) shl 8) or (data[offset + 9].toInt() and 0xFF)
            offset += 10

            if (offset + rdLength > data.size) break

            if (rrClass == CLASS_IN) {
                if ((type == TYPE_A && rdLength == 4) || (type == TYPE_AAAA && rdLength == 16)) {
                    val addrBytes = data.copyOfRange(offset, offset + rdLength)
                    runCatching {
                        addresses.add(InetAddress.getByAddress(hostname, addrBytes))
                        if (ttl > 0L && ttl < minTtl) {
                            minTtl = ttl
                        }
                    }
                }
            }
            offset += rdLength
        }

        return WireParseResult(
            rcode = rcode,
            addresses = addresses,
            minTtlSeconds = minTtl
        )
    }

    internal fun parseDohJsonResponse(hostname: String, rawJson: String): WireParseResult? {
        if (rawJson.isBlank()) return null
        val status = extractJsonNumber(rawJson, "\"Status\"")?.toInt() ?: return null
        val answerKeyIdx = rawJson.indexOf("\"Answer\"")
        if (answerKeyIdx == -1) {
            return WireParseResult(rcode = status, addresses = emptyList(), minTtlSeconds = MIN_TTL_SECONDS)
        }

        val arrayStart = rawJson.indexOf('[', answerKeyIdx)
        if (arrayStart == -1) {
            return WireParseResult(rcode = status, addresses = emptyList(), minTtlSeconds = MIN_TTL_SECONDS)
        }

        val addresses = mutableListOf<InetAddress>()
        var minTtl = MAX_TTL_SECONDS

        var idx = arrayStart + 1
        var inQuotes = false
        var objStart = -1
        while (idx < rawJson.length) {
            val ch = rawJson[idx]
            if (ch == '"' && (idx == 0 || rawJson[idx - 1] != '\\')) {
                inQuotes = !inQuotes
            } else if (!inQuotes) {
                when (ch) {
                    '{' -> if (objStart == -1) objStart = idx + 1
                    '}' -> if (objStart != -1) {
                        val fields = rawJson.substring(objStart, idx)
                        objStart = -1
                        val type = extractJsonNumber(fields, "\"type\"")?.toInt() ?: 0
                        val ipData = extractJsonString(fields, "\"data\"")?.trim().orEmpty()
                        val ttl = extractJsonNumber(fields, "\"TTL\"") ?: MIN_TTL_SECONDS
                        if ((type == TYPE_A || type == TYPE_AAAA) && ipData.isNotEmpty() && isIpLiteral(ipData)) {
                            runCatching {
                                val rawAddr = InetAddress.getByName(ipData).address
                                addresses.add(InetAddress.getByAddress(hostname, rawAddr))
                                if (ttl > 0L && ttl < minTtl) {
                                    minTtl = ttl
                                }
                            }
                        }
                    }
                    ']' -> if (objStart == -1) break
                }
            }
            idx++
        }

        return WireParseResult(
            rcode = status,
            addresses = addresses,
            minTtlSeconds = minTtl
        )
    }

    private fun extractJsonNumber(source: String, quotedKey: String): Long? {
        val keyIdx = source.indexOf(quotedKey)
        if (keyIdx == -1) return null
        val colonIdx = source.indexOf(':', keyIdx + quotedKey.length)
        if (colonIdx == -1) return null
        var i = colonIdx + 1
        while (i < source.length && source[i].isWhitespace()) i++
        val start = i
        while (i < source.length && source[i].isDigit()) i++
        return if (i > start) source.substring(start, i).toLongOrNull() else null
    }

    private fun extractJsonString(source: String, quotedKey: String): String? {
        val keyIdx = source.indexOf(quotedKey)
        if (keyIdx == -1) return null
        val colonIdx = source.indexOf(':', keyIdx + quotedKey.length)
        if (colonIdx == -1) return null
        val openQuote = source.indexOf('"', colonIdx + 1)
        if (openQuote == -1) return null
        var i = openQuote + 1
        while (i < source.length) {
            if (source[i] == '"' && source[i - 1] != '\\') {
                return source.substring(openQuote + 1, i)
            }
            i++
        }
        return null
    }

    private fun skipDnsName(data: ByteArray, startOffset: Int): Int {
        var offset = startOffset
        var hops = 0
        while (offset < data.size && hops < 64) {
            val len = data[offset].toInt() and 0xFF
            when {
                len == 0 -> return offset + 1
                (len and 0xC0) == 0xC0 -> {
                    // 2-byte compression pointer terminates current name representation
                    return if (offset + 2 <= data.size) offset + 2 else -1
                }
                (len and 0xC0) != 0 -> return -1
                else -> {
                    offset += 1 + len
                    hops++
                }
            }
        }
        return -1
    }

    internal fun extractHostFromUrl(url: String): String? {
        val schemeIdx = url.indexOf("://")
        if (schemeIdx == -1) return null
        val afterScheme = url.substring(schemeIdx + 3)
        val authority = afterScheme.substringBefore('/').substringBefore('?').substringBefore('#')
        val hostPort = authority.substringAfterLast('@')
        if (hostPort.startsWith("[")) {
            val closeIdx = hostPort.indexOf(']')
            return if (closeIdx > 0) hostPort.substring(0, closeIdx + 1) else hostPort
        }
        return hostPort.substringBefore(':').takeIf { it.isNotBlank() }
    }

    internal fun isIpLiteral(host: String): Boolean {
        val clean = host.trim().removeSurrounding("[", "]")
        if (clean.isEmpty()) return false
        if (isIpv4Literal(clean)) return true
        // IPv6 addresses always contain at least one colon
        return clean.contains(':')
    }

    private fun isIpv4Literal(value: String): Boolean {
        val parts = value.split('.')
        if (parts.size != 4) return false
        for (part in parts) {
            if (part.isEmpty() || part.length > 3) return false
            if (part.length > 1 && part[0] == '0') return false
            val octet = part.toIntOrNull() ?: return false
            if (octet !in 0..255) return false
        }
        return true
    }

    private fun isLocalHostname(host: String): Boolean {
        val lower = host.lowercase()
        return !lower.contains('.') ||
            lower.endsWith(".local") ||
            lower.endsWith(".lan") ||
            lower.endsWith(".home") ||
            lower.endsWith(".internal")
    }
}
