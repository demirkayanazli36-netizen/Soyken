package com.example

import com.example.data.parser.M3uParser
import com.example.data.parser.StreamFormatKind
import com.example.data.repository.IptvRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {
  @Test
  fun addition_isCorrect() {
    assertEquals(4, 2 + 2)
  }

  @Test
  fun testM3uParserBasicContent() {
    val sampleM3u = """
      #EXTM3U
      #EXTINF:-1 tvg-id="TRT1" tvg-name="TRT 1 HD" tvg-logo="https://example.com/trt1.png" group-title="Ulusal",TRT 1 HD
      https://example.com/trt1/stream.m3u8
      #EXTINF:-1 group-title="Spor",Spor TV
      https://example.com/live/ViP/GaraveliForum/39154.ts
    """.trimIndent()

    val channels = M3uParser.parseString(sampleM3u, playlistId = 1L)
    assertEquals(2, channels.size)
    assertEquals("TRT 1 HD", channels[0].name)
    assertEquals("Ulusal", channels[0].category)
    assertEquals("https://example.com/trt1/stream.m3u8", channels[0].streamUrl)
    assertEquals("Spor TV", channels[1].name)
    assertEquals("Spor", channels[1].category)
    assertEquals("https://example.com/live/ViP/GaraveliForum/39154.m3u8", channels[1].streamUrl)
  }

  @Test
  fun testToHlsStreamUrlConversions() {
    assertEquals(
      "https://79.76.50.223/live/ViP/GaraveliForum/39154.m3u8",
      M3uParser.toHlsStreamUrl("https://79.76.50.223/live/ViP/GaraveliForum/39154.ts")
    )
    assertEquals(
      "http://iptv.example.com:8080/live/user/pass/12345.m3u8",
      M3uParser.toHlsStreamUrl("http://iptv.example.com:8080/user/pass/12345")
    )
    assertEquals(
      "http://iptv.example.com:8080/live/user/pass/12345.m3u8?token=abc&output=m3u8",
      M3uParser.toHlsStreamUrl("http://iptv.example.com:8080/live/user/pass/12345.ts?token=abc&output=ts")
    )
    assertEquals(
      "http://iptv.example.com:8080/get.php?username=u&password=p&type=m3u_plus&output=m3u8",
      M3uParser.toHlsPlaylistUrl("http://iptv.example.com:8080/get.php?username=u&password=p&type=m3u_plus&output=ts")
    )
    assertEquals(
      "http://flussonic.example.com/trt1/index.m3u8",
      M3uParser.toHlsStreamUrl("http://flussonic.example.com/trt1/mpegts")
    )
    assertEquals(
      "https://cdn.example.com/live/manifest.m3u8",
      M3uParser.toHlsStreamUrl("https://cdn.example.com/live/manifest.mpd")
    )
    assertEquals(
      "https://live.example.com/app/stream1.m3u8",
      M3uParser.toHlsStreamUrl("rtmp://live.example.com/app/stream1")
    )
    assertEquals(
      "http://live.example.com:554/live/ch1.m3u8",
      M3uParser.toHlsStreamUrl("rtsp://live.example.com:554/live/ch1")
    )
    assertEquals(
      "http://portal.example.com/play/live.php?mac=00:1A:79:00:00:01&stream=555&extension=m3u8",
      M3uParser.toHlsStreamUrl("http://portal.example.com/play/live.php?mac=00:1A:79:00:00:01&stream=555&extension=ts")
    )
    assertEquals(
      "https://cdn.example.com/streams/ulusal_hd.m3u8",
      M3uParser.toHlsStreamUrl("https://cdn.example.com/streams/ulusal_hd")
    )
  }

  @Test
  fun testBuildPlaybackEndpointsHlsFirstWithHttpAndTsFallbacks() {
    val xtreamEndpoints = M3uParser.buildPlaybackEndpoints(
      "https://79.76.50.223/live/ViP/GaraveliForum/39154.m3u8"
    )
    // Primary candidate MUST be HLS (.m3u8)
    assertEquals("https://79.76.50.223/live/ViP/GaraveliForum/39154.m3u8", xtreamEndpoints[0].url)
    assertEquals(StreamFormatKind.HLS, xtreamEndpoints[0].formatKind)
    // Second candidate MUST be HTTP HLS (.m3u8)
    assertEquals("http://79.76.50.223/live/ViP/GaraveliForum/39154.m3u8", xtreamEndpoints[1].url)
    assertEquals(StreamFormatKind.HLS, xtreamEndpoints[1].formatKind)
    // Includes MPEG-TS (.ts) fallback if server returns 404/403 on .m3u8
    assertTrue(
      xtreamEndpoints.any {
        it.url == "https://79.76.50.223/live/ViP/GaraveliForum/39154.ts" &&
          it.formatKind == StreamFormatKind.MPEG_TS
      }
    )
    // Includes short Xtream path fallback (/ViP/GaraveliForum/39154)
    assertTrue(
      xtreamEndpoints.any {
        it.url == "https://79.76.50.223/ViP/GaraveliForum/39154" &&
          it.formatKind == StreamFormatKind.AUTO
      }
    )

    // VOD .mp4 tries universal progressive auto-sniff first, then HLS on the .mp4 URL (for 302 -> #EXTM3U) and .m3u8
    val vodEndpoints = M3uParser.buildPlaybackEndpoints(
      "https://79.76.50.223/movie/ViP/GaraveliForum/21380.mp4"
    )
    assertEquals("https://79.76.50.223/movie/ViP/GaraveliForum/21380.mp4", vodEndpoints[0].url)
    assertEquals(StreamFormatKind.AUTO, vodEndpoints[0].formatKind)
    assertEquals("http://79.76.50.223/movie/ViP/GaraveliForum/21380.mp4", vodEndpoints[1].url)
    assertEquals(StreamFormatKind.AUTO, vodEndpoints[1].formatKind)
    assertTrue(
      vodEndpoints.any {
        it.url == "https://79.76.50.223/movie/ViP/GaraveliForum/21380.mp4" &&
          it.formatKind == StreamFormatKind.HLS
      }
    )
    assertTrue(
      vodEndpoints.any {
        it.url == "http://79.76.50.223/movie/ViP/GaraveliForum/21380.mp4" &&
          it.formatKind == StreamFormatKind.HLS
      }
    )
    assertTrue(
      vodEndpoints.any {
        it.url == "https://79.76.50.223/movie/ViP/GaraveliForum/21380.m3u8" &&
          it.formatKind == StreamFormatKind.HLS
      }
    )
  }

  @Test
  fun testExtVlcOptAndPipeHeaderExtraction() {
    val m3uWithVlcOpts = """
      #EXTM3U
      #EXTINF:-1 group-title="Ulusal",Özel Kanal HD
      #EXTVLCOPT:http-user-agent=CustomIPTV/2.0
      #EXTVLCOPT:http-referrer=https://player.example.com/
      https://cdn.example.com/live/ch99.ts
    """.trimIndent()

    val parsed = M3uParser.parseString(m3uWithVlcOpts, playlistId = 5L)
    assertEquals(1, parsed.size)
    val info = M3uParser.extractStreamRequestInfo(parsed[0].streamUrl)
    assertEquals("https://cdn.example.com/live/ch99.m3u8", info.cleanUrl)
    assertEquals("CustomIPTV/2.0", info.customUserAgent)
    assertEquals("https://player.example.com/", info.customHeaders["Referer"])
  }

  @Test
  fun testReadableTxtDumpParsingToHls() {
    val sampleTxt = """
      M3U DOSYASI – OKUNABİLİR METİN DÖKÜMÜ
      Toplam kayıt: 2

      1. Animal Planet
         Kategori: Yabancı / AMAZER
         Yayın adresi: https://79.76.50.223/live/ViP/GaraveliForum/39155.ts
         Logo adresi: https://amazertv.com/assets/img/channels/channel_animal.png

      2. BBC One HD
         Kategori: Yabancı / AMAZER
         Yayın adresi: https://79.76.50.223/live/ViP/GaraveliForum/39157.ts
         Logo adresi: https://amazertv.com/assets/img/channels/channel_bbconehd.png
    """.trimIndent()

    val channels = M3uParser.parseString(sampleTxt, playlistId = 2L)
    assertEquals(2, channels.size)
    assertEquals("Animal Planet", channels[0].name)
    assertEquals("https://79.76.50.223/live/ViP/GaraveliForum/39155.m3u8", channels[0].streamUrl)
    assertEquals("BBC One HD", channels[1].name)
    assertEquals("https://79.76.50.223/live/ViP/GaraveliForum/39157.m3u8", channels[1].streamUrl)
  }

  @Test
  fun testPlaybackProgressCompletionThresholds() {
    val twoHourMovieMs = 7_200_000L
    // Mid-movie (45 mins) -> not completed, should resume
    assertFalse(IptvRepository.isPlaybackCompleted(2_700_000L, twoHourMovieMs))
    // 98% watched -> completed, resets for next watch
    assertTrue(IptvRepository.isPlaybackCompleted(7_060_000L, twoHourMovieMs))
    // Unknown/live duration (0) -> never treated as completed VOD
    assertFalse(IptvRepository.isPlaybackCompleted(120_000L, 0L))
  }

  @Test
  fun testIsExplicitLiveEndpointClassification() {
    assertTrue(
      com.example.ui.components.isExplicitLiveEndpointUrl(
        "https://79.76.50.223/live/ViP/GaraveliForum/39154.m3u8"
      )
    )
    assertTrue(
      com.example.ui.components.isExplicitLiveEndpointUrl(
        "http://79.76.50.223/live/ViP/GaraveliForum/39154.ts|User-Agent=Custom"
      )
    )
    assertTrue(
      com.example.ui.components.isExplicitLiveEndpointUrl(
        "http://flussonic.example.com/trt1/mpegts"
      )
    )
    assertFalse(
      com.example.ui.components.isExplicitLiveEndpointUrl(
        "https://79.76.50.223/movie/ViP/GaraveliForum/21380.mp4"
      )
    )
    assertFalse(
      com.example.ui.components.isExplicitLiveEndpointUrl(
        "https://79.76.50.223/series/ViP/GaraveliForum/99112.mkv"
      )
    )
  }

  @Test
  fun testVodPathsNeverMangledToM3u8AndIncludeFallbacks() {
    // Extensionless /movie/ and /series/ links must NOT have .m3u8 appended (which triggers HTTP 404 on Xtream)
    assertEquals(
      "https://79.76.50.223/movie/ViP/GaraveliForum/21380",
      M3uParser.toHlsStreamUrl("https://79.76.50.223/movie/ViP/GaraveliForum/21380")
    )
    assertEquals(
      "http://iptv.example.com:8080/series/user/pass/55112",
      M3uParser.toHlsStreamUrl("http://iptv.example.com:8080/series/user/pass/55112")
    )

    // Even if a /movie/ URL was previously stored with .m3u8, buildPlaybackEndpoints must generate .mp4, extensionless, and .mkv fallbacks
    val recoveredMovieEndpoints = M3uParser.buildPlaybackEndpoints(
      "https://79.76.50.223/movie/ViP/GaraveliForum/21380.m3u8"
    )
    assertTrue(
      recoveredMovieEndpoints.any {
        it.url == "https://79.76.50.223/movie/ViP/GaraveliForum/21380.mp4" &&
          it.formatKind == StreamFormatKind.AUTO
      }
    )
    assertTrue(
      recoveredMovieEndpoints.any {
        it.url == "https://79.76.50.223/movie/ViP/GaraveliForum/21380" &&
          it.formatKind == StreamFormatKind.AUTO
      }
    )
    assertTrue(
      recoveredMovieEndpoints.any {
        it.url == "https://79.76.50.223/movie/ViP/GaraveliForum/21380.mkv" &&
          it.formatKind == StreamFormatKind.AUTO
      }
    )
    assertTrue(
      recoveredMovieEndpoints.any {
        it.url == "http://79.76.50.223:8080/movie/ViP/GaraveliForum/21380.mp4"
      }
    )
  }

  @Test
  fun testAlternatePortsAndTsFallbacksForHttp404Recovery() {
    val liveEndpoints = M3uParser.buildPlaybackEndpoints(
      "https://79.76.50.223/live/ViP/GaraveliForum/39154.m3u8"
    )
    // Standard Xtream ports (:8080, :8000, :25461) must be present when port 80/443 returns Nginx default 404
    assertTrue(
      liveEndpoints.any {
        it.url == "http://79.76.50.223:8080/live/ViP/GaraveliForum/39154.m3u8" &&
          it.formatKind == StreamFormatKind.HLS
      }
    )
    assertTrue(
      liveEndpoints.any {
        it.url == "http://79.76.50.223:8000/live/ViP/GaraveliForum/39154.m3u8" &&
          it.formatKind == StreamFormatKind.HLS
      }
    )
    assertTrue(
      liveEndpoints.any {
        it.url == "http://79.76.50.223:25461/live/ViP/GaraveliForum/39154.m3u8" &&
          it.formatKind == StreamFormatKind.HLS
      }
    )
    // Extensionless live path and .ts fallbacks must also be present
    assertTrue(
      liveEndpoints.any {
        it.url == "https://79.76.50.223/live/ViP/GaraveliForum/39154" &&
          it.formatKind == StreamFormatKind.AUTO
      }
    )
    assertTrue(
      liveEndpoints.any {
        it.url == "http://79.76.50.223:8080/live/ViP/GaraveliForum/39154.ts" &&
          it.formatKind == StreamFormatKind.MPEG_TS
      }
    )
  }

  @Test
  fun testStreamProbeEmbeddedStreamExtraction() {
    val stalkerJson = """{"js":{"id":"555","cmd":"ffmpeg https:\/\/cdn.panel.tv:8080\/live\/user\/pass\/777.m3u8?token=xyz"}}"""
    val extracted = com.example.ui.components.StreamProbe.extractEmbeddedStreamFromText(
      stalkerJson,
      "http://portal.panel.tv/portal.php"
    )
    assertEquals(
      "https://cdn.panel.tv:8080/live/user/pass/777.m3u8?token=xyz" to StreamFormatKind.HLS,
      extracted
    )
  }

  @Test
  fun testCloudflareDnsConfigurationAndWireformatParsing() {
    assertEquals(
      "1dot1dot1dot1.cloudflare-dns.com",
      com.example.data.network.CloudflareDns.CLOUDFLARE_DNS_HOST
    )
    assertEquals(
      "https://1dot1dot1dot1.cloudflare-dns.com/dns-query",
      com.example.data.network.CloudflareDns.CLOUDFLARE_DOH_URL
    )
    assertEquals(
      "iptv.panel.tv",
      com.example.data.network.CloudflareDns.extractHostFromUrl("http://iptv.panel.tv:8080/get.php?username=u&password=p")
    )
    assertTrue(com.example.data.network.CloudflareDns.isIpLiteral("79.76.50.223"))
    assertFalse(com.example.data.network.CloudflareDns.isIpLiteral("1dot1dot1dot1.cloudflare-dns.com"))

    // Build RFC 1035 wire query for "iptv.example.com" (A = 1)
    val query = com.example.data.network.CloudflareDns.buildDnsWireQuery(
      "iptv.example.com",
      com.example.data.network.CloudflareDns.TYPE_A
    )
    assertTrue(query.size > 16)

    // Construct a realistic RFC 1035 DNS wire response with 0xC00C compression pointer -> 104.21.15.88 (TTL 300s)
    val responsePacket = java.io.ByteArrayOutputStream().apply {
      // Header: ID=0x0000, Flags=0x8180 (QR=1, RD=1, RA=1, NOERROR), QDCOUNT=1, ANCOUNT=1
      write(byteArrayOf(0x00, 0x00, 0x81.toByte(), 0x80.toByte(), 0x00, 0x01, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00))
      // Question section copied from query (from byte 12 onward)
      write(query, 12, query.size - 12)
      // Answer section: Name pointer 0xC00C, TYPE=A(1), CLASS=IN(1), TTL=300 (0x0000012C), RDLENGTH=4, RDATA=104.21.15.88
      write(
        byteArrayOf(
          0xC0.toByte(), 0x0C,
          0x00, 0x01,
          0x00, 0x01,
          0x00, 0x00, 0x01, 0x2C,
          0x00, 0x04,
          104, 21, 15, 88
        )
      )
    }.toByteArray()

    val parsedWire = com.example.data.network.CloudflareDns.parseDnsWireResponse("iptv.example.com", responsePacket)
    assertEquals(0, parsedWire?.rcode)
    assertEquals(1, parsedWire?.addresses?.size)
    assertEquals("104.21.15.88", parsedWire?.addresses?.first()?.hostAddress)
    assertEquals(300L, parsedWire?.minTtlSeconds)

    // Verify DoH JSON fallback parser
    val sampleDohJson = """{"Status":0,"TC":false,"RD":true,"RA":true,"Answer":[{"name":"iptv.example.com","type":5,"TTL":300,"data":"cdn.example.com."},{"name":"cdn.example.com","type":1,"TTL":180,"data":"172.67.188.42"}]}"""
    val parsedJson = com.example.data.network.CloudflareDns.parseDohJsonResponse("iptv.example.com", sampleDohJson)
    assertEquals(0, parsedJson?.rcode)
    assertEquals(1, parsedJson?.addresses?.size)
    assertEquals("172.67.188.42", parsedJson?.addresses?.first()?.hostAddress)
    assertEquals(180L, parsedJson?.minTtlSeconds)

    // Verify bootstrap resolution for 1dot1dot1dot1.cloudflare-dns.com never touches ISP DNS
    val bootstrapIps = com.example.data.network.CloudflareDns.lookup("1dot1dot1dot1.cloudflare-dns.com")
    assertTrue(bootstrapIps.any { it.hostAddress == "1.1.1.1" })
  }

  @Test
  fun testVideoQualityPresetAndBadgeFormatting() {
    assertEquals(
      com.example.ui.components.VideoQualityPreset.AUTO,
      com.example.ui.components.VideoQualityPreset.fromNameOrDefault(null)
    )
    assertEquals(
      com.example.ui.components.VideoQualityPreset.AUTO,
      com.example.ui.components.VideoQualityPreset.fromNameOrDefault("UNKNOWN_PRESET")
    )
    assertEquals(
      com.example.ui.components.VideoQualityPreset.HD_720P,
      com.example.ui.components.VideoQualityPreset.fromNameOrDefault("HD_720P")
    )
    assertEquals(
      com.example.ui.components.VideoQualityPreset.FHD_1080P,
      com.example.ui.components.VideoQualityPreset.fromNameOrDefault("fhd_1080p")
    )

    assertEquals("4K", com.example.ui.components.resolutionShortLabel(2160))
    assertEquals("1080p", com.example.ui.components.resolutionShortLabel(1080))
    assertEquals("720p", com.example.ui.components.resolutionShortLabel(720))
    assertEquals("480p", com.example.ui.components.resolutionShortLabel(480))
    assertEquals("360p", com.example.ui.components.resolutionShortLabel(360))
    assertEquals("", com.example.ui.components.resolutionShortLabel(0))

    // AUTO preset shows live resolution when known
    assertEquals(
      "AUTO • 1080p",
      com.example.ui.components.formatActiveQualityBadge(
        preset = com.example.ui.components.VideoQualityPreset.AUTO,
        pinnedTrack = null,
        activeVideoWidth = 1920,
        activeVideoHeight = 1080
      )
    )
    assertEquals(
      "AUTO",
      com.example.ui.components.formatActiveQualityBadge(
        preset = com.example.ui.components.VideoQualityPreset.AUTO,
        pinnedTrack = null,
        activeVideoWidth = 0,
        activeVideoHeight = 0
      )
    )

    // Explicit preset badge
    assertEquals(
      "720p",
      com.example.ui.components.formatActiveQualityBadge(
        preset = com.example.ui.components.VideoQualityPreset.HD_720P,
        pinnedTrack = null,
        activeVideoWidth = 1280,
        activeVideoHeight = 720
      )
    )

    // Pinned stream track takes precedence over preset
    val pinned480Track = com.example.ui.components.StreamVideoTrackOption(
      id = "0_2_480_1500000",
      groupIndex = 0,
      trackIndex = 2,
      width = 854,
      height = 480,
      bitrate = 1_500_000,
      frameRate = 25f,
      title = "480p",
      detail = "854×480 • 1.5 Mbps"
    )
    assertEquals(
      "480p",
      com.example.ui.components.formatActiveQualityBadge(
        preset = com.example.ui.components.VideoQualityPreset.AUTO,
        pinnedTrack = pinned480Track,
        activeVideoWidth = 1920,
        activeVideoHeight = 1080
      )
    )
  }

  @Test
  fun testIssueReportSubjectBodyAndDuplicateDetection() {
    assertEquals("polazmaison@gmail.com", com.example.ui.components.SUPPORT_REPORT_EMAIL)

    val ch1 = com.example.data.model.Channel(
      id = 12L,
      playlistId = 1L,
      name = "TRT Spor HD",
      streamUrl = "https://cdn.example.com/live/trtspor.m3u8|User-Agent=Custom",
      category = "Spor",
      channelNumber = 24
    )
    val ch2DuplicateUrl = com.example.data.model.Channel(
      id = 48L,
      playlistId = 1L,
      name = "TRT Spor Yedek",
      streamUrl = "https://cdn.example.com/live/trtspor.m3u8",
      category = "Spor",
      channelNumber = 89
    )
    val ch3Different = com.example.data.model.Channel(
      id = 55L,
      playlistId = 1L,
      name = "Haber Global HD",
      streamUrl = "https://cdn.example.com/live/haberglobal.m3u8",
      category = "Haber",
      channelNumber = 90
    )

    val duplicates = com.example.ui.components.detectDuplicateChannels(
      target = ch1,
      allChannels = listOf(ch1, ch2DuplicateUrl, ch3Different)
    )
    assertEquals(1, duplicates.size)
    assertEquals(89, duplicates[0].channelNumber)

    // Default issue type selects STREAM_NOT_WORKING when playbackError exists, else DUPLICATE_STREAM
    assertEquals(
      com.example.ui.components.StreamIssueType.STREAM_NOT_WORKING,
      com.example.ui.components.resolveDefaultIssueType("HTTP 404 Not Found", duplicates)
    )
    assertEquals(
      com.example.ui.components.StreamIssueType.DUPLICATE_STREAM,
      com.example.ui.components.resolveDefaultIssueType(null, duplicates)
    )

    val subject = com.example.ui.components.buildIssueReportSubject(
      channel = ch1,
      issueType = com.example.ui.components.StreamIssueType.DUPLICATE_STREAM
    )
    assertTrue(subject.contains("Kanal #24"))
    assertTrue(subject.contains("TRT Spor HD"))
    assertTrue(subject.contains("Kopya / Mükerrer Yayın"))

    val body = com.example.ui.components.buildIssueReportBody(
      channel = ch1,
      playlistName = "Ana IPTV Listem",
      issueType = com.example.ui.components.StreamIssueType.DUPLICATE_STREAM,
      userNote = "89 numaralı kanal ile birebir aynı yayın",
      playbackError = null,
      duplicateChannels = duplicates,
      appVersion = "15.0",
      reportedAtFormatted = "2026-11-13 21:30:00"
    )
    assertTrue(body.contains("Kanal Numarası: #24"))
    assertTrue(body.contains("Kanal Adı: TRT Spor HD"))
    assertTrue(body.contains("Kategori: Spor"))
    assertTrue(body.contains("Oynatma Listesi: Ana IPTV Listem"))
    assertTrue(body.contains("https://cdn.example.com/live/trtspor.m3u8"))
    assertFalse(body.contains("|User-Agent=Custom"))
    assertTrue(body.contains("Kanal #89 (TRT Spor Yedek)"))
    assertTrue(body.contains("89 numaralı kanal ile birebir aynı yayın"))
  }

  @Test
  fun testSmartChannelSearchPunctuationTyposAndSimilarOptions() {
    val catalog = listOf(
      com.example.data.model.Channel(
        id = 1L,
        playlistId = 1L,
        name = "Spider-Man: Eve Dönüş Yok (2021) [TR]",
        streamUrl = "https://cdn.example.com/movie/1.mp4",
        category = "Filmler",
        channelNumber = 1
      ),
      com.example.data.model.Channel(
        id = 2L,
        playlistId = 1L,
        name = "TV 8,5 HD",
        streamUrl = "https://cdn.example.com/live/tv85.m3u8",
        category = "Ulusal",
        channelNumber = 8
      ),
      com.example.data.model.Channel(
        id = 3L,
        playlistId = 1L,
        name = "HABERTÜRK HD",
        streamUrl = "https://cdn.example.com/live/haberturk.m3u8",
        category = "Haber",
        channelNumber = 14
      ),
      com.example.data.model.Channel(
        id = 4L,
        playlistId = 1L,
        name = "Kurtlar Vadisi: Pusu",
        streamUrl = "https://cdn.example.com/series/kv.mp4",
        category = "Diziler",
        channelNumber = 22
      ),
      com.example.data.model.Channel(
        id = 5L,
        playlistId = 1L,
        name = "Recep İvedik VI",
        streamUrl = "https://cdn.example.com/movie/recep6.mp4",
        category = "Komedi",
        channelNumber = 30
      ),
      com.example.data.model.Channel(
        id = 6L,
        playlistId = 1L,
        name = "CNN Türk HD",
        streamUrl = "https://cdn.example.com/live/cnnturk.m3u8",
        category = "Haber",
        channelNumber = 15
      ),
      com.example.data.model.Channel(
        id = 7L,
        playlistId = 1L,
        name = "The Lord of the Rings: The Two Towers",
        streamUrl = "https://cdn.example.com/movie/lotr2.mp4",
        category = "Filmler",
        channelNumber = 45
      )
    )

    // 1. Punctuation + Turkish character insensitivity ("Spider-Man: Eve Dönüş Yok" <- "spiderman eve donus")
    val spiderSplit = com.example.data.search.SmartChannelSearch.searchChannels(
      allPlaylistChannels = catalog,
      selectedCategory = "Tümü",
      rawQuery = "spiderman eve donus"
    )
    assertTrue(spiderSplit.directMatches.any { it.id == 1L })

    // 2. Comma/dot/space punctuation insensitivity ("TV 8,5 HD" <- "tv85" and "tv 8.5")
    val tv85Split = com.example.data.search.SmartChannelSearch.searchChannels(
      allPlaylistChannels = catalog,
      selectedCategory = "Tümü",
      rawQuery = "tv85"
    )
    assertTrue(tv85Split.directMatches.any { it.id == 2L })
    val tv85DotSplit = com.example.data.search.SmartChannelSearch.searchChannels(
      allPlaylistChannels = catalog,
      selectedCategory = "Tümü",
      rawQuery = "tv 8.5"
    )
    assertTrue(tv85DotSplit.directMatches.any { it.id == 2L })

    // 3. Typo + uppercase Turkish İ/Ü ("HABERTÜRK HD" <- "habertuk") + sibling similar options ("CNN Türk HD")
    val haberSplit = com.example.data.search.SmartChannelSearch.searchChannels(
      allPlaylistChannels = catalog,
      selectedCategory = "Tümü",
      rawQuery = "habertuk"
    )
    assertTrue(haberSplit.allResults.any { it.id == 3L })
    assertTrue(haberSplit.similarOptions.any { it.id == 6L })

    // 4. Typo + swapped word order ("Kurtlar Vadisi: Pusu" <- "pusu kurlar")
    val kvSplit = com.example.data.search.SmartChannelSearch.searchChannels(
      allPlaylistChannels = catalog,
      selectedCategory = "Tümü",
      rawQuery = "pusu kurlar"
    )
    assertTrue(kvSplit.allResults.any { it.id == 4L })

    // 5. Roman numeral & diacritic normalization ("Recep İvedik VI" <- "recep ivedik 6")
    val recepSplit = com.example.data.search.SmartChannelSearch.searchChannels(
      allPlaylistChannels = catalog,
      selectedCategory = "Tümü",
      rawQuery = "recep ivedik 6"
    )
    assertTrue(recepSplit.directMatches.any { it.id == 5L })

    // 6. Bilingual local alias ("yüzüklerin efendisi" -> "The Lord of the Rings: The Two Towers") + similar film
    val lotrSplit = com.example.data.search.SmartChannelSearch.searchChannels(
      allPlaylistChannels = catalog,
      selectedCategory = "Tümü",
      rawQuery = "yüzüklerin efendisi"
    )
    assertEquals(7L, lotrSplit.directMatches.firstOrNull()?.id)
    assertTrue(lotrSplit.similarOptions.any { it.id == 1L })

    // 7. Genre alias ("film" -> matches "Sinema & Dizi" category) & consonant skeleton ("knl d" -> "Kanal D HD")
    val starterCatalog = listOf(
      com.example.data.model.Channel(
        id = 10L,
        playlistId = 2L,
        name = "Apple TV HLS Multi-Stream",
        streamUrl = "https://example.com/sinema.m3u8",
        category = "Sinema & Dizi",
        channelNumber = 4,
        isFavorite = true
      ),
      com.example.data.model.Channel(
        id = 11L,
        playlistId = 2L,
        name = "Kanal D HD",
        streamUrl = "https://example.com/kanald.m3u8",
        category = "Ulusal",
        channelNumber = 5,
        isFavorite = false
      )
    )
    val filmSplit = com.example.data.search.SmartChannelSearch.searchChannels(
      allPlaylistChannels = starterCatalog,
      selectedCategory = "Tümü",
      rawQuery = "film"
    )
    assertTrue(filmSplit.allResults.any { it.id == 10L })

    val knlDSplit = com.example.data.search.SmartChannelSearch.searchChannels(
      allPlaylistChannels = starterCatalog,
      selectedCategory = "Tümü",
      rawQuery = "knl d"
    )
    assertTrue(knlDSplit.directMatches.any { it.id == 11L })

    // 8. Cross-category fallback finds exact match in another category automatically
    val crossCatSplit = com.example.data.search.SmartChannelSearch.searchChannels(
      allPlaylistChannels = catalog,
      selectedCategory = "Haber",
      rawQuery = "spiderman"
    )
    assertTrue(crossCatSplit.crossCategoryFallbackUsed)
    assertTrue(crossCatSplit.directMatches.any { it.id == 1L })

    // 9. Unmatched query on small starter playlist still offers similar options instead of empty list
    val fallbackSplit = com.example.data.search.SmartChannelSearch.searchChannels(
      allPlaylistChannels = starterCatalog,
      selectedCategory = "Tümü",
      rawQuery = "fox"
    )
    assertTrue(fallbackSplit.allResults.isNotEmpty())
  }

  @Test
  fun testStreamAcceleratorVodCacheAndServerProfilePrioritization() {
    com.example.data.network.StreamAccelerator.clearServerProfilesForTesting()

    // VOD movie & series URLs must be cached on disk, while live broadcasts bypass disk writes
    assertTrue(
      com.example.data.network.StreamAccelerator.isCacheableVodUrl(
        "https://79.76.50.223/movie/ViP/GaraveliForum/21380.mp4"
      )
    )
    assertTrue(
      com.example.data.network.StreamAccelerator.isCacheableVodUrl(
        "http://iptv.panel.tv:8080/series/user/pass/99112.mkv|User-Agent=Custom"
      )
    )
    assertFalse(
      com.example.data.network.StreamAccelerator.isCacheableVodUrl(
        "https://79.76.50.223/live/ViP/GaraveliForum/39154.m3u8"
      )
    )
    assertFalse(
      com.example.data.network.StreamAccelerator.isCacheableVodUrl(
        "http://iptv.panel.tv:8080/live/user/pass/39154.ts"
      )
    )

    // Record working server profile on Channel 1 (e.g. HTTP port 8080 MPEG_TS with Chrome UA)
    com.example.data.network.StreamAccelerator.recordWorkingStream(
      rawChannelUrl = "https://79.76.50.223/live/ViP/GaraveliForum/1001.m3u8",
      workingCandidateUrl = "http://79.76.50.223:8080/live/ViP/GaraveliForum/1001.ts",
      formatKind = StreamFormatKind.MPEG_TS,
      userAgent = "ChromeTestUA"
    )

    data class DummyCand(val url: String, val fmt: StreamFormatKind, val ua: String)

    val ch2Candidates = listOf(
      DummyCand("https://79.76.50.223/live/ViP/GaraveliForum/1002.m3u8", StreamFormatKind.HLS, "IPTVSmartersPlayer"),
      DummyCand("http://79.76.50.223/live/ViP/GaraveliForum/1002.m3u8", StreamFormatKind.HLS, "IPTVSmartersPlayer"),
      DummyCand("http://79.76.50.223:8080/live/ViP/GaraveliForum/1002.ts", StreamFormatKind.MPEG_TS, "ChromeTestUA")
    )

    val prioritized = com.example.data.network.StreamAccelerator.prioritizeCandidates(
      rawChannelUrl = "https://79.76.50.223/live/ViP/GaraveliForum/1002.m3u8",
      candidates = ch2Candidates,
      urlSelector = { it.url },
      formatSelector = { it.fmt },
      userAgentSelector = { it.ua }
    )

    assertEquals("http://79.76.50.223:8080/live/ViP/GaraveliForum/1002.ts", prioritized[0].url)
    assertEquals(StreamFormatKind.MPEG_TS, prioritized[0].fmt)
    assertEquals("ChromeTestUA", prioritized[0].ua)

    com.example.data.network.StreamAccelerator.clearServerProfilesForTesting()

    // Anti-freeze stall step-down ladder progresses cleanly
    var stage = com.example.data.network.StallCapStage.NONE
    stage = stage.stepDown()
    assertEquals(com.example.data.network.StallCapStage.CAP_720P, stage)
    stage = stage.stepDown()
    assertEquals(com.example.data.network.StallCapStage.CAP_480P, stage)
    stage = stage.stepDown()
    assertEquals(com.example.data.network.StallCapStage.CAP_360P, stage)
    assertEquals(com.example.data.network.StallCapStage.CAP_360P, stage.stepDown())
  }

  @Test
  fun testHourMinuteReminderCalculation() {
    val utc = java.util.TimeZone.getTimeZone("UTC")
    assertEquals("08:05", com.example.ui.viewmodel.MainViewModel.formatReminderHourMinute(8, 5))
    assertEquals("23:59", com.example.ui.viewmodel.MainViewModel.formatReminderHourMinute(25, 99))
    assertEquals("ch_42_08_05", com.example.ui.viewmodel.MainViewModel.buildChannelTimeReminderId(42L, 8, 5))

    val baseCal = java.util.Calendar.getInstance(utc).apply {
      set(2026, java.util.Calendar.NOVEMBER, 17, 14, 30, 45)
      set(java.util.Calendar.MILLISECOND, 500)
    }
    val nowMillis = baseCal.timeInMillis

    // Exact current HH:mm (14:30 while clock is 14:30:45) triggers within 2 seconds instead of tomorrow
    val sameMinuteTrigger = com.example.ui.viewmodel.MainViewModel.computeNextReminderTriggerMillis(
      nowMillis = nowMillis,
      hourOfDay = 14,
      minute = 30,
      timeZone = utc
    )
    assertEquals(nowMillis + 2_000L, sameMinuteTrigger)

    // Future HH:mm today (21:15) triggers on the same day at 21:15:00.000
    val todayTrigger = com.example.ui.viewmodel.MainViewModel.computeNextReminderTriggerMillis(
      nowMillis = nowMillis,
      hourOfDay = 21,
      minute = 15,
      timeZone = utc
    )
    val todayResult = java.util.Calendar.getInstance(utc).apply { timeInMillis = todayTrigger }
    assertEquals(17, todayResult.get(java.util.Calendar.DAY_OF_MONTH))
    assertEquals(21, todayResult.get(java.util.Calendar.HOUR_OF_DAY))
    assertEquals(15, todayResult.get(java.util.Calendar.MINUTE))
    assertEquals(0, todayResult.get(java.util.Calendar.SECOND))
    assertEquals(0, todayResult.get(java.util.Calendar.MILLISECOND))

    // Earlier HH:mm than now (09:05 when now is 14:30) rolls over to tomorrow at 09:05:00.000
    val tomorrowTrigger = com.example.ui.viewmodel.MainViewModel.computeNextReminderTriggerMillis(
      nowMillis = nowMillis,
      hourOfDay = 9,
      minute = 5,
      timeZone = utc
    )
    val tomorrowResult = java.util.Calendar.getInstance(utc).apply { timeInMillis = tomorrowTrigger }
    assertEquals(18, tomorrowResult.get(java.util.Calendar.DAY_OF_MONTH))
    assertEquals(9, tomorrowResult.get(java.util.Calendar.HOUR_OF_DAY))
    assertEquals(5, tomorrowResult.get(java.util.Calendar.MINUTE))
    assertEquals(0, tomorrowResult.get(java.util.Calendar.SECOND))
  }
}


