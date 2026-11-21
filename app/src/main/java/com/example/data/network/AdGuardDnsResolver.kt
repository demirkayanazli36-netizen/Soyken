package com.example.data.network

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.CookieHandler
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.IDN
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Resolves all M3U playlist, channel stream, redirect, probe, and logo hostnames through
 * AdGuard DNS (`dns.adguard.com`) before establishing M3U connections.
 *
 * Transport priority:
 * 1. DNS-over-HTTPS (RFC 8484) -> `https://dns.adguard.com/dns-query`
 * 2. DNS-over-TLS (RFC 7858)   -> `dns.adguard.com:853`
 * 3. AdGuard UDP DNS           -> `94.140.14.14:53` / `94.140.15.15:53`
 * 4. System DNS safety fallback if the local network completely blocks external DNS
 */
object AdGuardDnsResolver : Dns {

    const val ADGUARD_DNS_HOST = "dns.adguard.com"
    const val ADGUARD_DOH_URL = "https://dns.adguard.com/dns-query"
    private const val ADGUARD_DOT_PORT = 853
    private const val ADGUARD_UDP_PORT = 53

    internal const val RECORD_TYPE_A = 1
    internal const val RECORD_TYPE_AAAA = 28
    private const val DNS_CLASS_IN = 1

    private const val MIN_TTL_MS = 60_000L
    private const val MAX_TTL_MS = 3_600_000L
    private const val WARM_SESSION_TTL_MS = 300_000L

    private val DNS_MESSAGE_MEDIA_TYPE = "application/dns-message".toMediaType()
    private val queryIdCounter = AtomicInteger(1000)

    private val bootstrapIpv4Primary = byteArrayOf(94.toByte(), 140.toByte(), 14.toByte(), 14.toByte())
    private val bootstrapIpv4Secondary = byteArrayOf(94.toByte(), 140.toByte(), 15.toByte(), 15.toByte())

    private val bootstrapIpv6Primary = byteArrayOf(
        0x2a, 0x10, 0x50, 0xc0.toByte(), 0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x0a, 0xd1.toByte(), 0x00, 0xff.toByte()
    )
    private val bootstrapIpv6Secondary = byteArrayOf(
        0x2a, 0x10, 0x50, 0xc0.toByte(), 0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x0a, 0xd2.toByte(), 0x00, 0xff.toByte()
    )

    internal data class DnsCacheEntry(
        val addresses: List<InetAddress>,
        val expiresAtMs: Long
    )

    internal data class ParsedDnsResponse(
        val rcode: Int,
        val addresses: List<InetAddress>,
        val minTtlSeconds: Long
    )

    private val dnsCache = ConcurrentHashMap<String, DnsCacheEntry>()

    @Volatile
    private var lastVerifiedHandshakeMs: Long = 0L

    private val _isDnsConnected = MutableStateFlow(false)
    val isDnsConnected: StateFlow<Boolean> = _isDnsConnected.asStateFlow()

    /**
     * Bootstrap DNS used exclusively for connecting to `dns.adguard.com` itself so ISP DNS poisoning
     * on `dns.adguard.com` cannot block the encrypted DoH connection.
     */
    private val bootstrapDns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            if (hostname.equals(ADGUARD_DNS_HOST, ignoreCase = true) ||
                hostname.equals("dns.adguard-dns.com", ignoreCase = true)
            ) {
                val list = ArrayList<InetAddress>(5)
                list.add(InetAddress.getByAddress(hostname, bootstrapIpv4Primary))
                list.add(InetAddress.getByAddress(hostname, bootstrapIpv4Secondary))
                runCatching { Dns.SYSTEM.lookup(hostname) }.getOrNull()?.let { sys ->
                    for (addr in sys) {
                        if (list.none { it.hostAddress == addr.hostAddress }) {
                            list.add(addr)
                        }
                    }
                }
                list.add(InetAddress.getByAddress(hostname, bootstrapIpv6Primary))
                list.add(InetAddress.getByAddress(hostname, bootstrapIpv6Secondary))
                return list
            }
            return Dns.SYSTEM.lookup(hostname)
        }
    }

    private val dohHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dns(bootstrapDns)
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .writeTimeout(4, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    /**
     * Ensures an active connection to `dns.adguard.com` is established before connecting
     * to M3U playlist or broadcast stream URLs. Uses a 5-minute warm session window so rapid
     * channel zapping incurs zero extra latency once connected.
     */
    suspend fun ensureConnected(): Boolean = withContext(Dispatchers.IO) {
        ensureConnectedBlocking()
    }

    fun ensureConnectedBlocking(): Boolean {
        val now = System.currentTimeMillis()
        if (_isDnsConnected.value && (now - lastVerifiedHandshakeMs) < WARM_SESSION_TTL_MS) {
            return true
        }
        return synchronized(this) {
            val recheckNow = System.currentTimeMillis()
            if (_isDnsConnected.value && (recheckNow - lastVerifiedHandshakeMs) < WARM_SESSION_TTL_MS) {
                return@synchronized true
            }
            val connected = runCatching {
                val probe = queryAdGuardTransports(ADGUARD_DNS_HOST, RECORD_TYPE_A)
                probe != null && probe.addresses.isNotEmpty()
            }.getOrDefault(false)
            if (connected) {
                lastVerifiedHandshakeMs = System.currentTimeMillis()
                _isDnsConnected.value = true
            }
            connected
        }
    }

    override fun lookup(hostname: String): List<InetAddress> {
        val trimmed = hostname.trim()
        if (trimmed.isEmpty()) {
            throw UnknownHostException("Boş alan adı")
        }

        // Fast-path IPv4 literal without any system DNS lookup
        parseIpv4Literal(trimmed)?.let { ipv4Addr ->
            return listOf(ipv4Addr)
        }

        // Localhost / mDNS / IPv6 literals delegate directly
        if (trimmed.equals("localhost", ignoreCase = true) ||
            trimmed.endsWith(".local", ignoreCase = true) ||
            trimmed.contains(':')
        ) {
            return Dns.SYSTEM.lookup(trimmed)
        }

        // Self-lookup for dns.adguard.com uses bootstrap addresses directly
        if (trimmed.equals(ADGUARD_DNS_HOST, ignoreCase = true)) {
            return bootstrapDns.lookup(trimmed)
        }

        val asciiHost = try {
            IDN.toASCII(trimmed.trimEnd('.'), IDN.ALLOW_UNASSIGNED).lowercase()
        } catch (_: Exception) {
            trimmed.trimEnd('.').lowercase()
        }

        val now = System.currentTimeMillis()
        dnsCache[asciiHost]?.let { cached ->
            if (now < cached.expiresAtMs && cached.addresses.isNotEmpty()) {
                return cached.addresses
            }
        }

        // Resolve via dns.adguard.com (DoH -> DoT -> AdGuard UDP)
        val ipv4Result = queryAdGuardTransports(asciiHost, RECORD_TYPE_A)
        val resolvedAddresses = ArrayList<InetAddress>(6)
        var minTtlSec = 300L

        if (ipv4Result != null && ipv4Result.addresses.isNotEmpty()) {
            for (addr in ipv4Result.addresses) {
                resolvedAddresses.add(InetAddress.getByAddress(trimmed, addr.address))
            }
            minTtlSec = ipv4Result.minTtlSeconds
        } else {
            // Try IPv6 (AAAA) if domain has no IPv4 A record (unless NXDOMAIN was authoritative)
            val isAuthoritativeNxDomain = ipv4Result?.rcode == 3
            if (!isAuthoritativeNxDomain) {
                val ipv6Result = queryAdGuardTransports(asciiHost, RECORD_TYPE_AAAA)
                if (ipv6Result != null && ipv6Result.addresses.isNotEmpty()) {
                    for (addr in ipv6Result.addresses) {
                        resolvedAddresses.add(InetAddress.getByAddress(trimmed, addr.address))
                    }
                    minTtlSec = ipv6Result.minTtlSeconds
                }
            }
        }

        if (resolvedAddresses.isNotEmpty()) {
            val ttlMs = (minTtlSec * 1000L).coerceIn(MIN_TTL_MS, MAX_TTL_MS)
            dnsCache[asciiHost] = DnsCacheEntry(
                addresses = resolvedAddresses,
                expiresAtMs = System.currentTimeMillis() + ttlMs
            )
            lastVerifiedHandshakeMs = System.currentTimeMillis()
            _isDnsConnected.value = true
            return resolvedAddresses
        }

        // If AdGuard explicitly returned NXDOMAIN (rcode == 3), fail fast without hanging
        if (ipv4Result?.rcode == 3) {
            throw UnknownHostException("$ADGUARD_DNS_HOST alan adını bulamadı (NXDOMAIN): $trimmed")
        }

        // Safety fallback to system DNS only when external DNS transports are unreachable
        return Dns.SYSTEM.lookup(trimmed)
    }

    private fun queryAdGuardTransports(asciiHost: String, recordType: Int): ParsedDnsResponse? {
        queryDoH(asciiHost, recordType)?.let { return it }
        queryDoT(asciiHost, recordType)?.let { return it }
        queryUdp(asciiHost, recordType)?.let { return it }
        return null
    }

    private fun queryDoH(asciiHost: String, recordType: Int): ParsedDnsResponse? {
        val wireQuery = buildDnsQueryPacket(asciiHost, recordType, queryId = 0)
        val encodedDnsParam = Base64.encodeToString(
            wireQuery,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )

        // Try RFC 8484 GET first (HTTP/2 cache-friendly on dns.adguard.com)
        val getReq = Request.Builder()
            .url("$ADGUARD_DOH_URL?dns=$encodedDnsParam")
            .header("Accept", "application/dns-message")
            .get()
            .build()

        runCatching {
            dohHttpClient.newCall(getReq).execute().use { resp ->
                if (resp.isSuccessful) {
                    val bytes = resp.body?.bytes()
                    if (bytes != null && bytes.size >= 12) {
                        return parseDnsResponsePacket(bytes, asciiHost)
                    }
                }
            }
        }

        // Fallback to RFC 8484 POST
        val postReq = Request.Builder()
            .url(ADGUARD_DOH_URL)
            .header("Accept", "application/dns-message")
            .post(wireQuery.toRequestBody(DNS_MESSAGE_MEDIA_TYPE))
            .build()

        return runCatching {
            dohHttpClient.newCall(postReq).execute().use { resp ->
                if (resp.isSuccessful) {
                    val bytes = resp.body?.bytes()
                    if (bytes != null && bytes.size >= 12) {
                        parseDnsResponsePacket(bytes, asciiHost)
                    } else {
                        null
                    }
                } else {
                    null
                }
            }
        }.getOrNull()
    }

    private fun queryDoT(asciiHost: String, recordType: Int): ParsedDnsResponse? {
        val queryId = queryIdCounter.incrementAndGet() and 0xFFFF
        val wireQuery = buildDnsQueryPacket(asciiHost, recordType, queryId = queryId)
        val sslFactory = SSLSocketFactory.getDefault() as? SSLSocketFactory ?: return null

        for (ipBytes in listOf(bootstrapIpv4Primary, bootstrapIpv4Secondary)) {
            val result = runCatching {
                val targetAddr = InetAddress.getByAddress(ADGUARD_DNS_HOST, ipBytes)
                val rawSocket = javax.net.SocketFactory.getDefault().createSocket()
                rawSocket.connect(InetSocketAddress(targetAddr, ADGUARD_DOT_PORT), 3_000)
                rawSocket.soTimeout = 3_500

                (sslFactory.createSocket(rawSocket, ADGUARD_DNS_HOST, ADGUARD_DOT_PORT, true) as SSLSocket).use { sslSocket ->
                    val params = sslSocket.sslParameters
                    params.serverNames = listOf(SNIHostName(ADGUARD_DNS_HOST))
                    sslSocket.sslParameters = params
                    sslSocket.startHandshake()

                    val verified = HttpsURLConnection.getDefaultHostnameVerifier()
                        .verify(ADGUARD_DNS_HOST, sslSocket.session)
                    if (!verified) {
                        throw SecurityException("DoT sertifika doğrulaması başarısız: $ADGUARD_DNS_HOST")
                    }

                    val out = DataOutputStream(sslSocket.outputStream)
                    out.writeShort(wireQuery.size)
                    out.write(wireQuery)
                    out.flush()

                    val input = DataInputStream(sslSocket.inputStream)
                    val respLen = input.readUnsignedShort()
                    if (respLen in 12..4096) {
                        val respBytes = ByteArray(respLen)
                        input.readFully(respBytes)
                        parseDnsResponsePacket(respBytes, asciiHost)
                    } else {
                        null
                    }
                }
            }.getOrNull()

            if (result != null) return result
        }
        return null
    }

    private fun queryUdp(asciiHost: String, recordType: Int): ParsedDnsResponse? {
        val queryId = queryIdCounter.incrementAndGet() and 0xFFFF
        val wireQuery = buildDnsQueryPacket(asciiHost, recordType, queryId = queryId)

        for (ipBytes in listOf(bootstrapIpv4Primary, bootstrapIpv4Secondary)) {
            val result = runCatching {
                DatagramSocket().use { socket ->
                    socket.soTimeout = 2_500
                    val target = InetAddress.getByAddress(ADGUARD_DNS_HOST, ipBytes)
                    val sendPacket = DatagramPacket(wireQuery, wireQuery.size, target, ADGUARD_UDP_PORT)
                    socket.send(sendPacket)

                    val recvBuf = ByteArray(1500)
                    val recvPacket = DatagramPacket(recvBuf, recvBuf.size)
                    socket.receive(recvPacket)
                    if (recvPacket.length >= 12) {
                        val respBytes = recvBuf.copyOf(recvPacket.length)
                        val respId = ((respBytes[0].toInt() and 0xFF) shl 8) or (respBytes[1].toInt() and 0xFF)
                        if (respId == queryId) {
                            parseDnsResponsePacket(respBytes, asciiHost)
                        } else {
                            null
                        }
                    } else {
                        null
                    }
                }
            }.getOrNull()

            if (result != null) return result
        }
        return null
    }

    internal fun parseIpv4Literal(host: String): InetAddress? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        val bytes = ByteArray(4)
        for (i in 0..3) {
            val p = parts[i]
            if (p.isEmpty() || p.length > 3 || !p.all { it in '0'..'9' }) return null
            val num = p.toIntOrNull() ?: return null
            if (num !in 0..255) return null
            bytes[i] = num.toByte()
        }
        return InetAddress.getByAddress(host, bytes)
    }

    /**
     * Builds a standard RFC 1035 DNS wire-format query packet.
     */
    internal fun buildDnsQueryPacket(hostname: String, recordType: Int, queryId: Int = 0): ByteArray {
        val cleanHost = hostname.trim().trimEnd('.')
        val out = ByteArrayOutputStream(64)

        // 12-byte RFC 1035 Header
        out.write((queryId ushr 8) and 0xFF)
        out.write(queryId and 0xFF)
        // Flags: Standard Query with Recursion Desired (0x0100)
        out.write(0x01)
        out.write(0x00)
        // QDCOUNT = 1
        out.write(0x00)
        out.write(0x01)
        // ANCOUNT, NSCOUNT, ARCOUNT = 0
        repeat(6) { out.write(0x00) }

        // QNAME labels
        for (label in cleanHost.split('.')) {
            val labelBytes = label.toByteArray(Charsets.US_ASCII)
            val len = labelBytes.size.coerceAtMost(63)
            out.write(len)
            out.write(labelBytes, 0, len)
        }
        out.write(0x00) // Root terminator

        // QTYPE
        out.write((recordType ushr 8) and 0xFF)
        out.write(recordType and 0xFF)
        // QCLASS = IN (1)
        out.write(0x00)
        out.write(DNS_CLASS_IN)

        return out.toByteArray()
    }

    /**
     * Parses an RFC 1035 DNS wire-format response packet, extracting A (IPv4) and AAAA (IPv6)
     * answer records and minimum TTL.
     */
    internal fun parseDnsResponsePacket(packet: ByteArray, requestedHost: String): ParsedDnsResponse? {
        if (packet.size < 12) return null

        val flags = ((packet[2].toInt() and 0xFF) shl 8) or (packet[3].toInt() and 0xFF)
        val isResponse = ((flags ushr 15) and 0x01) == 1
        if (!isResponse) return null

        val rcode = flags and 0x0F
        val qdCount = ((packet[4].toInt() and 0xFF) shl 8) or (packet[5].toInt() and 0xFF)
        val anCount = ((packet[6].toInt() and 0xFF) shl 8) or (packet[7].toInt() and 0xFF)

        var offset = 12

        // Skip Question section
        repeat(qdCount) {
            offset = skipDnsName(packet, offset)
            if (offset < 0 || offset + 4 > packet.size) return null
            offset += 4 // QTYPE + QCLASS
        }

        val addresses = ArrayList<InetAddress>(anCount.coerceAtMost(16))
        var minTtl = Long.MAX_VALUE

        repeat(anCount) {
            offset = skipDnsName(packet, offset)
            if (offset < 0 || offset + 10 > packet.size) {
                return ParsedDnsResponse(
                    rcode = rcode,
                    addresses = addresses,
                    minTtlSeconds = if (minTtl == Long.MAX_VALUE) 300L else minTtl
                )
            }

            val type = ((packet[offset].toInt() and 0xFF) shl 8) or (packet[offset + 1].toInt() and 0xFF)
            val clazz = ((packet[offset + 2].toInt() and 0xFF) shl 8) or (packet[offset + 3].toInt() and 0xFF)
            val ttl = ((packet[offset + 4].toLong() and 0xFF) shl 24) or
                ((packet[offset + 5].toLong() and 0xFF) shl 16) or
                ((packet[offset + 6].toLong() and 0xFF) shl 8) or
                (packet[offset + 7].toLong() and 0xFF)
            val rdLength = ((packet[offset + 8].toInt() and 0xFF) shl 8) or (packet[offset + 9].toInt() and 0xFF)
            offset += 10

            if (rdLength < 0 || offset + rdLength > packet.size) {
                return ParsedDnsResponse(
                    rcode = rcode,
                    addresses = addresses,
                    minTtlSeconds = if (minTtl == Long.MAX_VALUE) 300L else minTtl
                )
            }

            if (clazz == DNS_CLASS_IN) {
                when {
                    type == RECORD_TYPE_A && rdLength == 4 -> {
                        val ipBytes = packet.copyOfRange(offset, offset + 4)
                        runCatching { InetAddress.getByAddress(requestedHost, ipBytes) }
                            .getOrNull()
                            ?.let { addr ->
                                if (addresses.none { it.hostAddress == addr.hostAddress }) {
                                    addresses.add(addr)
                                }
                                if (ttl < minTtl) minTtl = ttl
                            }
                    }

                    type == RECORD_TYPE_AAAA && rdLength == 16 -> {
                        val ipBytes = packet.copyOfRange(offset, offset + 16)
                        runCatching { InetAddress.getByAddress(requestedHost, ipBytes) }
                            .getOrNull()
                            ?.let { addr ->
                                if (addresses.none { it.hostAddress == addr.hostAddress }) {
                                    addresses.add(addr)
                                }
                                if (ttl < minTtl) minTtl = ttl
                            }
                    }
                }
            }

            offset += rdLength
        }

        return ParsedDnsResponse(
            rcode = rcode,
            addresses = addresses,
            minTtlSeconds = if (minTtl == Long.MAX_VALUE) 300L else minTtl.coerceAtLeast(30L)
        )
    }

    private fun skipDnsName(packet: ByteArray, startOffset: Int): Int {
        var pos = startOffset
        var steps = 0
        while (pos < packet.size && steps < 128) {
            val len = packet[pos].toInt() and 0xFF
            when {
                len == 0 -> return pos + 1
                (len and 0xC0) == 0xC0 -> {
                    return if (pos + 1 < packet.size) pos + 2 else -1
                }
                else -> {
                    pos += 1 + len
                    if (pos > packet.size) return -1
                }
            }
            steps++
        }
        return -1
    }
}

/**
 * Provides shared OkHttpClient instances pre-configured to route all DNS resolution through
 * `dns.adguard.com` ([AdGuardDnsResolver]) and share session cookies across playlist downloads,
 * ExoPlayer HLS/TS/MP4 playback, stream fallback probing, and channel logos.
 */
object AdGuardNetworkClient {

    val sharedCookieJar: CookieJar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            val handler = CookieHandler.getDefault()
                ?: CookieManager(null, CookiePolicy.ACCEPT_ALL).also { CookieHandler.setDefault(it) }
            runCatching {
                handler.put(url.toUri(), mapOf("Set-Cookie" to cookies.map { it.toString() }))
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val handler = CookieHandler.getDefault() ?: return emptyList()
            return runCatching {
                val cookieHeaders = handler.get(url.toUri(), emptyMap())["Cookie"].orEmpty()
                cookieHeaders.flatMap { headerLine ->
                    headerLine.split(';').mapNotNull { part ->
                        val eq = part.indexOf('=')
                        if (eq > 0) {
                            Cookie.Builder()
                                .name(part.substring(0, eq).trim())
                                .value(part.substring(eq + 1).trim())
                                .domain(url.host)
                                .build()
                        } else {
                            null
                        }
                    }
                }
            }.getOrDefault(emptyList())
        }
    }

    val playlistHttpClient: OkHttpClient by lazy {
        ensureCookieManagerInitialized()
        OkHttpClient.Builder()
            .dns(AdGuardDnsResolver)
            .cookieJar(sharedCookieJar)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    val streamingHttpClient: OkHttpClient by lazy {
        ensureCookieManagerInitialized()
        OkHttpClient.Builder()
            .dns(AdGuardDnsResolver)
            .cookieJar(sharedCookieJar)
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }

    val probeHttpClient: OkHttpClient by lazy {
        ensureCookieManagerInitialized()
        OkHttpClient.Builder()
            .dns(AdGuardDnsResolver)
            .cookieJar(sharedCookieJar)
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(false)
            .build()
    }

    val imageHttpClient: OkHttpClient by lazy {
        ensureCookieManagerInitialized()
        OkHttpClient.Builder()
            .dns(AdGuardDnsResolver)
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    private fun ensureCookieManagerInitialized() {
        if (CookieHandler.getDefault() == null) {
            CookieHandler.setDefault(CookieManager(null, CookiePolicy.ACCEPT_ALL))
        }
    }
}
