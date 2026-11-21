package com.example.network

import com.example.data.parser.M3uParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.CookieHandler
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.IDN
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Custom DNS resolver that connects to Cloudflare DNS servers before opening M3U playlist or
 * broadcast stream connections:
 * - Primary DNS (Birincil DNS): 1.1.1.1
 * - Secondary DNS (İkincil DNS): 1.0.0.1
 *
 * Supports both encrypted DNS-over-HTTPS (RFC 8484 wireformat directly to 1.1.1.1 / 1.0.0.1,
 * immune to ISP UDP/53 interception) and standard RFC 1035 UDP/TCP port 53 queries to
 * 1.1.1.1 and 1.0.0.1, with TTL-aware in-memory caching.
 */
object SoykenDnsResolver : Dns {

    const val PRIMARY_DNS = "1.1.1.1"
    const val SECONDARY_DNS = "1.0.0.1"
    const val DNS_PORT = 53

    private val PRIMARY_DNS_BYTES = byteArrayOf(1, 1, 1, 1)
    private val SECONDARY_DNS_BYTES = byteArrayOf(1, 0, 0, 1)

    internal val ORDERED_DNS_SERVERS = listOf(PRIMARY_DNS, SECONDARY_DNS)

    private const val QTYPE_A = 1
    private const val QTYPE_AAAA = 28
    private const val QCLASS_IN = 1
    private const val CONNECTION_TTL_MS = 300_000L // 5 minutes warm session window

    private val DNS_MESSAGE_MEDIA_TYPE = "application/dns-message".toMediaType()
    private val txIdCounter = AtomicInteger((System.nanoTime() and 0x7FFF).toInt())

    private data class CachedDnsEntry(
        val rawAddresses: List<ByteArray>,
        val expiresAtMs: Long
    )

    internal sealed class DnsWireResult {
        data class Success(val addresses: List<InetAddress>, val ttlSeconds: Long) : DnsWireResult()
        data object Truncated : DnsWireResult()
        data object NxDomain : DnsWireResult()
        data class ServerFailure(val rcode: Int) : DnsWireResult()
        data object Invalid : DnsWireResult()
    }

    private val dnsCache = ConcurrentHashMap<String, CachedDnsEntry>()

    @Volatile
    private var lastVerifiedConnectionMs: Long = 0L

    private val handshakeLock = Any()

    private val _activeDnsServer = MutableStateFlow(PRIMARY_DNS)
    val activeDnsServer: StateFlow<String> = _activeDnsServer.asStateFlow()

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

    /**
     * Bootstrap DNS used exclusively by [dohClient] so connections to `https://1.1.1.1/dns-query`
     * and `https://1.0.0.1/dns-query` use static IP bytes directly without touching ISP DNS.
     */
    private val staticBootstrapDns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            return when (hostname) {
                PRIMARY_DNS -> listOf(InetAddress.getByAddress(PRIMARY_DNS, PRIMARY_DNS_BYTES))
                SECONDARY_DNS -> listOf(InetAddress.getByAddress(SECONDARY_DNS, SECONDARY_DNS_BYTES))
                else -> {
                    parseIpv4Literal(hostname)?.let { bytes ->
                        listOf(InetAddress.getByAddress(hostname, bytes))
                    } ?: Dns.SYSTEM.lookup(hostname)
                }
            }
        }
    }

    private val dohClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dns(staticBootstrapDns)
            .connectTimeout(2_500, TimeUnit.MILLISECONDS)
            .readTimeout(2_500, TimeUnit.MILLISECONDS)
            .writeTimeout(2_500, TimeUnit.MILLISECONDS)
            .callTimeout(3_500, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    /**
     * Creates an [OkHttpClient.Builder] pre-configured with Primary (1.1.1.1) and Secondary (1.0.0.1)
     * DNS resolution and shared IPTV cookie persistence.
     */
    fun newHttpClientBuilder(): OkHttpClient.Builder {
        if (CookieHandler.getDefault() == null) {
            CookieHandler.setDefault(CookieManager(null, CookiePolicy.ACCEPT_ALL))
        }
        return OkHttpClient.Builder()
            .dns(this)
            .cookieJar(sharedCookieJar)
    }

    /**
     * Connects to Primary DNS (1.1.1.1) first, falling back to Secondary DNS (1.0.0.1),
     * before M3U playlist or channel stream connections are established.
     */
    suspend fun ensureConnected(): String = withContext(Dispatchers.IO) {
        ensureConnectedSync()
    }

    fun ensureConnectedSync(): String {
        val now = System.currentTimeMillis()
        if (now - lastVerifiedConnectionMs < CONNECTION_TTL_MS) {
            return _activeDnsServer.value
        }
        synchronized(handshakeLock) {
            val recheckNow = System.currentTimeMillis()
            if (recheckNow - lastVerifiedConnectionMs < CONNECTION_TTL_MS) {
                return _activeDnsServer.value
            }
            for (dnsIp in ORDERED_DNS_SERVERS) {
                if (probeDnsServerReachable(dnsIp)) {
                    _activeDnsServer.value = dnsIp
                    lastVerifiedConnectionMs = System.currentTimeMillis()
                    return dnsIp
                }
            }
            // Even if network is momentarily transitioning, keep Primary 1.1.1.1 selected
            lastVerifiedConnectionMs = System.currentTimeMillis() - (CONNECTION_TTL_MS - 15_000L)
            return _activeDnsServer.value
        }
    }

    /**
     * Connects to 1.1.1.1 / 1.0.0.1 and pre-populates the DNS cache for an M3U playlist or stream URL
     * right before the HTTP connection is opened.
     */
    suspend fun preResolveUrlHost(rawUrl: String) = withContext(Dispatchers.IO) {
        ensureConnectedSync()
        val cleanUrl = M3uParser.extractStreamRequestInfo(rawUrl).cleanUrl
        val host = cleanUrl.toHttpUrlOrNull()?.host ?: extractHostFallback(cleanUrl) ?: return@withContext
        runCatching { lookup(host) }
    }

    private fun extractHostFallback(url: String): String? {
        val schemeIdx = url.indexOf("://")
        if (schemeIdx == -1) return null
        val start = schemeIdx + 3
        val end = url.indexOf('/', start).let { if (it == -1) url.length else it }
        val authority = url.substring(start, end).substringAfterLast('@')
        if (authority.startsWith("[")) {
            return authority.substringBefore(']') + "]"
        }
        return authority.substringBefore(':').takeIf { it.isNotBlank() }
    }

    private fun probeDnsServerReachable(dnsIp: String): Boolean {
        // 1. Try DoH wireformat ping to https://1.1.1.1/dns-query (or https://1.0.0.1/dns-query)
        val dohResult = runCatching {
            queryDoH(dnsIp, "one.one.one.one", "one.one.one.one", QTYPE_A)
        }.getOrNull()
        if (dohResult is DnsWireResult.Success && dohResult.addresses.isNotEmpty()) {
            return true
        }
        // 2. Fallback: standard DNS UDP/TCP port 53 ping to 1.1.1.1:53 (or 1.0.0.1:53)
        val udpResult = runCatching {
            queryUdpOrTcp(dnsIp, "one.one.one.one", "one.one.one.one", QTYPE_A)
        }.getOrNull()
        return udpResult is DnsWireResult.Success && udpResult.addresses.isNotEmpty()
    }

    override fun lookup(hostname: String): List<InetAddress> {
        val trimmed = hostname.trim()
        if (trimmed.isEmpty()) {
            throw UnknownHostException("Boş sunucu adı")
        }

        // Fast-path 1: IPv4 literal (e.g. raw IP IPTV panel 79.76.50.223 or 1.1.1.1)
        parseIpv4Literal(trimmed)?.let { ipBytes ->
            return listOf(InetAddress.getByAddress(trimmed, ipBytes))
        }

        // Fast-path 2: IPv6 literal or local network names
        val unbracketed = trimmed.removePrefix("[").removeSuffix("]")
        if (unbracketed.contains(':') ||
            trimmed.equals("localhost", ignoreCase = true) ||
            trimmed.endsWith(".local", ignoreCase = true) ||
            trimmed.endsWith(".lan", ignoreCase = true)
        ) {
            return Dns.SYSTEM.lookup(trimmed)
        }

        val asciiHost = try {
            IDN.toASCII(trimmed.trimEnd('.')).lowercase()
        } catch (_: Exception) {
            trimmed.trimEnd('.').lowercase()
        }

        val now = System.currentTimeMillis()
        dnsCache[asciiHost]?.let { cached ->
            if (now < cached.expiresAtMs && cached.rawAddresses.isNotEmpty()) {
                return cached.rawAddresses.map { InetAddress.getByAddress(trimmed, it) }
            }
        }

        // Ensure initial DNS connection to 1.1.1.1 -> 1.0.0.1
        ensureConnectedSync()

        var sawAuthoritativeNxDomain = false
        for (dnsIp in ORDERED_DNS_SERVERS) {
            val resolved = resolveAgainstServer(dnsIp, asciiHost, trimmed)
            when {
                resolved != null && resolved.first.isNotEmpty() -> {
                    _activeDnsServer.value = dnsIp
                    lastVerifiedConnectionMs = System.currentTimeMillis()
                    val ttlMs = resolved.second * 1_000L
                    dnsCache[asciiHost] = CachedDnsEntry(
                        rawAddresses = resolved.first.map { it.address },
                        expiresAtMs = System.currentTimeMillis() + ttlMs
                    )
                    return resolved.first
                }
                resolved != null && resolved.second == -1L -> {
                    // Authoritative NXDOMAIN from 1.1.1.1 / 1.0.0.1
                    sawAuthoritativeNxDomain = true
                    break
                }
            }
        }

        if (sawAuthoritativeNxDomain) {
            throw UnknownHostException("Alan adı bulunamadı ($trimmed - DNS ${_activeDnsServer.value})")
        }

        // Serve stale cached entry if available during transient network handoff
        dnsCache[asciiHost]?.takeIf { it.rawAddresses.isNotEmpty() }?.let { stale ->
            return stale.rawAddresses.map { InetAddress.getByAddress(trimmed, it) }
        }

        // Last-resort system resolver fallback (e.g. captive portal or private LAN split-DNS)
        return Dns.SYSTEM.lookup(trimmed)
    }

    /**
     * Queries a single Cloudflare DNS server ([dnsIp] = 1.1.1.1 or 1.0.0.1):
     * 1. Encrypted DoH (`https://<dnsIp>/dns-query`) to prevent ISP UDP/53 spoofing.
     * 2. Standard UDP + TCP port 53 (`<dnsIp>:53`) if HTTPS port 443 to DNS IP is unreachable.
     *
     * @return Pair(addresses, ttlSeconds), Pair(emptyList(), -1L) on authoritative NXDOMAIN, or null on transport failure.
     */
    private fun resolveAgainstServer(
        dnsIp: String,
        asciiHost: String,
        originalHost: String
    ): Pair<List<InetAddress>, Long>? {
        // 1. Try DoH (RFC 8484) directly to 1.1.1.1 / 1.0.0.1
        val dohA = runCatching { queryDoH(dnsIp, asciiHost, originalHost, QTYPE_A) }.getOrNull()
        when (dohA) {
            is DnsWireResult.Success -> {
                if (dohA.addresses.isNotEmpty()) {
                    return dohA.addresses to dohA.ttlSeconds
                }
                // No IPv4 A record -> check IPv6 AAAA record on same server
                val dohAAAA = runCatching { queryDoH(dnsIp, asciiHost, originalHost, QTYPE_AAAA) }.getOrNull()
                if (dohAAAA is DnsWireResult.Success && dohAAAA.addresses.isNotEmpty()) {
                    return dohAAAA.addresses to dohAAAA.ttlSeconds
                }
            }
            is DnsWireResult.NxDomain -> return emptyList<InetAddress>() to -1L
            else -> Unit
        }

        // 2. Fallback: Standard DNS over UDP/TCP port 53 directly to 1.1.1.1:53 / 1.0.0.1:53
        val udpTcpA = runCatching { queryUdpOrTcp(dnsIp, asciiHost, originalHost, QTYPE_A) }.getOrNull()
        when (udpTcpA) {
            is DnsWireResult.Success -> {
                if (udpTcpA.addresses.isNotEmpty()) {
                    return udpTcpA.addresses to udpTcpA.ttlSeconds
                }
                val udpTcpAAAA = runCatching {
                    queryUdpOrTcp(dnsIp, asciiHost, originalHost, QTYPE_AAAA)
                }.getOrNull()
                if (udpTcpAAAA is DnsWireResult.Success && udpTcpAAAA.addresses.isNotEmpty()) {
                    return udpTcpAAAA.addresses to udpTcpAAAA.ttlSeconds
                }
            }
            else -> Unit
        }

        return null
    }

    private fun queryDoH(
        dnsIp: String,
        asciiHost: String,
        originalHost: String,
        queryType: Int
    ): DnsWireResult {
        val wireQuery = buildDnsQueryPacket(asciiHost, queryType, transactionId = 0)
        val request = Request.Builder()
            .url("https://$dnsIp/dns-query")
            .post(wireQuery.toRequestBody(DNS_MESSAGE_MEDIA_TYPE))
            .header("Accept", "application/dns-message")
            .build()

        dohClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("DoH HTTP ${response.code} from $dnsIp")
            }
            val bytes = response.body?.bytes() ?: return DnsWireResult.Invalid
            return parseDnsResponsePacket(
                packet = bytes,
                packetLength = bytes.size,
                expectedTxId = null,
                originalHostname = originalHost
            )
        }
    }

    private fun queryUdpOrTcp(
        dnsIp: String,
        asciiHost: String,
        originalHost: String,
        queryType: Int
    ): DnsWireResult {
        val serverBytes = if (dnsIp == SECONDARY_DNS) SECONDARY_DNS_BYTES else PRIMARY_DNS_BYTES
        val serverAddress = InetSocketAddress(InetAddress.getByAddress(dnsIp, serverBytes), DNS_PORT)
        val txId = txIdCounter.incrementAndGet() and 0xFFFF
        val wireQuery = buildDnsQueryPacket(asciiHost, queryType, txId)

        val udpResult = runCatching {
            DatagramSocket().use { socket ->
                socket.soTimeout = 2_000
                val outPacket = DatagramPacket(wireQuery, wireQuery.size, serverAddress)
                socket.send(outPacket)
                val recvBuf = ByteArray(1500)
                val inPacket = DatagramPacket(recvBuf, recvBuf.size)
                socket.receive(inPacket)
                parseDnsResponsePacket(
                    packet = recvBuf,
                    packetLength = inPacket.length,
                    expectedTxId = txId,
                    originalHostname = originalHost
                )
            }
        }.getOrNull()

        if (udpResult is DnsWireResult.Success || udpResult is DnsWireResult.NxDomain) {
            return udpResult
        }

        // Fallback to DNS-over-TCP (RFC 1035 Section 4.2.2) on port 53 if UDP was truncated or filtered
        return Socket().use { tcpSocket ->
            tcpSocket.soTimeout = 2_500
            tcpSocket.connect(serverAddress, 2_000)
            val out = DataOutputStream(tcpSocket.getOutputStream())
            out.writeShort(wireQuery.size)
            out.write(wireQuery)
            out.flush()

            val input = DataInputStream(tcpSocket.getInputStream())
            val respLen = input.readUnsignedShort()
            if (respLen < 12 || respLen > 65535) {
                return DnsWireResult.Invalid
            }
            val respBytes = ByteArray(respLen)
            input.readFully(respBytes)
            parseDnsResponsePacket(
                packet = respBytes,
                packetLength = respBytes.size,
                expectedTxId = txId,
                originalHostname = originalHost
            )
        }
    }

    internal fun parseIpv4Literal(host: String): ByteArray? {
        var partIndex = 0
        var currentVal = 0
        var hasDigits = false
        val out = ByteArray(4)

        for (i in host.indices) {
            val ch = host[i]
            when {
                ch in '0'..'9' -> {
                    currentVal = currentVal * 10 + (ch - '0')
                    if (currentVal > 255) return null
                    hasDigits = true
                }
                ch == '.' -> {
                    if (!hasDigits || partIndex >= 3) return null
                    out[partIndex++] = currentVal.toByte()
                    currentVal = 0
                    hasDigits = false
                }
                else -> return null
            }
        }
        if (!hasDigits || partIndex != 3) return null
        out[3] = currentVal.toByte()
        return out
    }

    internal fun buildDnsQueryPacket(
        asciiHostname: String,
        queryType: Int,
        transactionId: Int
    ): ByteArray {
        val baos = ByteArrayOutputStream(96)
        val dos = DataOutputStream(baos)

        dos.writeShort(transactionId and 0xFFFF)
        dos.writeShort(0x0100) // Standard recursive query (RD = 1)
        dos.writeShort(1) // QDCOUNT = 1
        dos.writeShort(0) // ANCOUNT = 0
        dos.writeShort(0) // NSCOUNT = 0
        dos.writeShort(0) // ARCOUNT = 0

        val labels = asciiHostname.trimEnd('.').split('.').filter { it.isNotEmpty() }
        for (label in labels) {
            val bytes = label.toByteArray(Charsets.UTF_8)
            val len = bytes.size.coerceAtMost(63)
            dos.writeByte(len)
            dos.write(bytes, 0, len)
        }
        dos.writeByte(0) // Root label terminator

        dos.writeShort(queryType)
        dos.writeShort(QCLASS_IN)
        dos.flush()
        return baos.toByteArray()
    }

    internal fun parseDnsResponsePacket(
        packet: ByteArray,
        packetLength: Int,
        expectedTxId: Int?,
        originalHostname: String
    ): DnsWireResult {
        if (packetLength < 12 || packetLength > packet.size) {
            return DnsWireResult.Invalid
        }

        val txId = readU16(packet, 0)
        if (expectedTxId != null && txId != (expectedTxId and 0xFFFF)) {
            return DnsWireResult.Invalid
        }

        val flags = readU16(packet, 2)
        val isResponse = ((flags ushr 15) and 1) == 1
        if (!isResponse) {
            return DnsWireResult.Invalid
        }

        val isTruncated = ((flags ushr 9) and 1) == 1
        if (isTruncated && expectedTxId != null) {
            return DnsWireResult.Truncated
        }

        val rcode = flags and 0x0F
        if (rcode == 3) {
            return DnsWireResult.NxDomain
        }
        if (rcode != 0) {
            return DnsWireResult.ServerFailure(rcode)
        }

        val qdCount = readU16(packet, 4)
        val anCount = readU16(packet, 6)

        var offset = 12
        for (q in 0 until qdCount) {
            offset = skipDnsName(packet, packetLength, offset)
            if (offset < 0 || offset + 4 > packetLength) {
                return DnsWireResult.Invalid
            }
            offset += 4
        }

        val addresses = mutableListOf<InetAddress>()
        var minTtlSeconds = 300L

        for (a in 0 until anCount) {
            if (offset >= packetLength) break
            offset = skipDnsName(packet, packetLength, offset)
            if (offset < 0 || offset + 10 > packetLength) {
                return DnsWireResult.Invalid
            }

            val rrType = readU16(packet, offset)
            val rrClass = readU16(packet, offset + 2)
            val ttlSeconds = readU32(packet, offset + 4)
            val rdLength = readU16(packet, offset + 8)
            offset += 10

            if (offset + rdLength > packetLength) {
                return DnsWireResult.Invalid
            }

            if (rrClass == QCLASS_IN) {
                if (rrType == QTYPE_A && rdLength == 4) {
                    val ipBytes = packet.copyOfRange(offset, offset + 4)
                    runCatching {
                        addresses.add(InetAddress.getByAddress(originalHostname, ipBytes))
                    }
                    minTtlSeconds = minOf(minTtlSeconds, ttlSeconds)
                } else if (rrType == QTYPE_AAAA && rdLength == 16) {
                    val ipBytes = packet.copyOfRange(offset, offset + 16)
                    runCatching {
                        addresses.add(InetAddress.getByAddress(originalHostname, ipBytes))
                    }
                    minTtlSeconds = minOf(minTtlSeconds, ttlSeconds)
                }
            }

            offset += rdLength
        }

        return DnsWireResult.Success(
            addresses = addresses.distinctBy { it.hostAddress },
            ttlSeconds = minTtlSeconds.coerceIn(60L, 900L)
        )
    }

    private fun skipDnsName(packet: ByteArray, packetLength: Int, startOffset: Int): Int {
        var pos = startOffset
        var steps = 0
        while (pos < packetLength && steps < 128) {
            val len = packet[pos].toInt() and 0xFF
            when {
                len == 0 -> return pos + 1
                (len and 0xC0) == 0xC0 -> {
                    return if (pos + 1 < packetLength) pos + 2 else -1
                }
                len <= 63 -> {
                    pos += 1 + len
                    if (pos > packetLength) return -1
                }
                else -> return -1
            }
            steps++
        }
        return -1
    }

    private fun readU16(buf: ByteArray, offset: Int): Int {
        return ((buf[offset].toInt() and 0xFF) shl 8) or (buf[offset + 1].toInt() and 0xFF)
    }

    private fun readU32(buf: ByteArray, offset: Int): Long {
        return ((buf[offset].toLong() and 0xFFL) shl 24) or
            ((buf[offset + 1].toLong() and 0xFFL) shl 16) or
            ((buf[offset + 2].toLong() and 0xFFL) shl 8) or
            (buf[offset + 3].toLong() and 0xFFL)
    }
}
