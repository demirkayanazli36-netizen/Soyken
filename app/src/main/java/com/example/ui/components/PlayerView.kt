package com.example.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.net.wifi.WifiManager
import android.view.Gravity
import android.view.KeyEvent
import android.view.TextureView
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.border
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import android.media.MediaCodec
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.hls.DefaultHlsExtractorFactory
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.hls.playlist.DefaultHlsPlaylistTracker
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.AdaptiveTrackSelection
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.extractor.ts.TsExtractor
import androidx.media3.ui.AspectRatioFrameLayout
import com.example.billing.PremiumFeature
import com.example.data.model.Channel
import com.example.data.network.CloudflareDns
import com.example.data.network.CloudflareOkHttpDataSource
import com.example.data.network.StallCapStage
import com.example.data.network.StreamAccelerator
import com.example.data.parser.DemoPlaylists
import com.example.data.parser.M3uParser
import com.example.data.parser.StreamFormatKind
import com.example.ui.theme.LiveRed
import com.example.ui.theme.SoykenGold
import kotlinx.coroutines.delay
import java.net.ConnectException
import java.net.CookieHandler
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLException

private const val UA_IPTV_SMARTERS = "IPTVSmartersPlayer"
private const val UA_TIVIMATE = "TiviMate/4.7.0 (Linux; Android 13)"
private const val UA_VLC = "VLC/3.0.20 LibVLC/3.0.20"
private const val UA_ANDROID_CHROME =
    "Mozilla/5.0 (Linux; Android 14; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

// Remembers HTTPS origins whose TLS cert failed (e.g. raw IP panels like https://79.76.50.223)
// so channel/movie switches skip broken TLS handshakes immediately without delay.
private val SSL_FAILED_ORIGINS: MutableSet<String> = ConcurrentHashMap.newKeySet()

// Process-wide flag flipped if Android's MediaResourceManager ever reclaims a hardware codec
// ("E/MediaCodec: Released by resource manager"), ensuring subsequent sessions immediately
// prioritize contention-free c2.android.* decoders without hitting repeated reclamations.
private val CONTENDED_HW_CODEC_RECLAIMED = AtomicBoolean(false)

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private data class StreamCandidate(
    val url: String,
    val formatKind: StreamFormatKind,
    val userAgent: String,
    val customHeaders: Map<String, String>,
    val isSyntheticFallback: Boolean = false
)

private fun buildStreamCandidates(rawStreamUrl: String): List<StreamCandidate> {
    val requestInfo = M3uParser.extractStreamRequestInfo(rawStreamUrl)
    val endpoints = M3uParser.buildPlaybackEndpoints(rawStreamUrl)
    if (endpoints.isEmpty()) {
        return listOf(
            StreamCandidate(
                url = requestInfo.cleanUrl.ifBlank { rawStreamUrl.trim() },
                formatKind = StreamFormatKind.HLS,
                userAgent = requestInfo.customUserAgent ?: UA_IPTV_SMARTERS,
                customHeaders = requestInfo.customHeaders
            )
        )
    }

    val firstLower = endpoints.first().url.lowercase()
    if (firstLower.startsWith("android.resource://") ||
        firstLower.startsWith("content://") ||
        firstLower.startsWith("file://")
    ) {
        return listOf(
            StreamCandidate(
                url = endpoints.first().url,
                formatKind = endpoints.first().formatKind,
                userAgent = UA_IPTV_SMARTERS,
                customHeaders = emptyMap()
            )
        )
    }

    // Lead with IPTVSmartersPlayer (unless M3U specifies custom #EXTVLCOPT User-Agent) because
    // many XUI.one / Xtream / Nginx panels stealth-block VLC/LibVLC with fake HTTP 404 responses!
    val defaultPrimaryUa = requestInfo.customUserAgent?.takeIf { it.isNotBlank() } ?: UA_IPTV_SMARTERS
    val alternateUserAgents = buildList {
        add(UA_ANDROID_CHROME)
        if (defaultPrimaryUa != UA_IPTV_SMARTERS) add(UA_IPTV_SMARTERS)
        add(UA_TIVIMATE)
        if (defaultPrimaryUa != UA_VLC) add(UA_VLC)
    }.distinct()

    val candidates = mutableListOf<StreamCandidate>()

    // Pass 1: Try all format, protocol, and port endpoints using the primary IPTV User-Agent
    for (endpoint in endpoints) {
        candidates.add(
            StreamCandidate(
                url = endpoint.url,
                formatKind = endpoint.formatKind,
                userAgent = defaultPrimaryUa,
                customHeaders = requestInfo.customHeaders,
                isSyntheticFallback = endpoint.isSyntheticFallback
            )
        )
    }

    // Pass 2: Alternate User-Agents across BOTH https:// and http:// core endpoints (including
    // Referer + Origin headers on Chrome Android for Turkish/global CDN streams that 404/403 without Referer)
    val coreEndpointsForAltUa = (
        endpoints.filter { !it.isSyntheticFallback } +
            endpoints.filter { it.isSyntheticFallback && it.url.startsWith("http://", ignoreCase = true) }
        ).distinctBy { it.url to it.formatKind }.take(10)

    for (ua in alternateUserAgents) {
        for (endpoint in coreEndpointsForAltUa) {
            val headersForUa = if (ua == UA_ANDROID_CHROME && "Referer" !in requestInfo.customHeaders) {
                val origin = StreamProbe.extractOrigin(endpoint.url)
                if (origin != null) {
                    buildMap {
                        put("Referer", "$origin/")
                        put("Origin", origin)
                        putAll(requestInfo.customHeaders)
                    }
                } else {
                    requestInfo.customHeaders
                }
            } else {
                requestInfo.customHeaders
            }
            candidates.add(
                StreamCandidate(
                    url = endpoint.url,
                    formatKind = endpoint.formatKind,
                    userAgent = ua,
                    customHeaders = headersForUa,
                    isSyntheticFallback = endpoint.isSyntheticFallback
                )
            )
        }
    }

    val distinctCandidates = candidates.distinctBy { Triple(it.url, it.formatKind, it.userAgent) }
    return StreamAccelerator.prioritizeCandidates(
        rawChannelUrl = rawStreamUrl,
        candidates = distinctCandidates,
        urlSelector = { it.url },
        formatSelector = { it.formatKind },
        userAgentSelector = { it.userAgent }
    )
}

private fun extractOrigin(url: String): String? {
    val schemeEnd = url.indexOf("://")
    if (schemeEnd == -1) return null
    val pathStart = url.indexOf('/', schemeEnd + 3)
    return if (pathStart == -1) url.lowercase() else url.substring(0, pathStart).lowercase()
}

@OptIn(UnstableApi::class)
private fun Throwable?.findHttpStatusCode(): Int? {
    var cur = this
    while (cur != null) {
        if (cur is HttpDataSource.InvalidResponseCodeException) {
            return cur.responseCode
        }
        cur = cur.cause
    }
    return null
}

private fun Throwable?.hasSslError(): Boolean {
    var cur = this
    while (cur != null) {
        val cls = cur.javaClass.name
        if (cur is SSLException ||
            cls.contains("SSL", ignoreCase = true) ||
            cls.contains("CertPath", ignoreCase = true)
        ) {
            return true
        }
        cur = cur.cause
    }
    return false
}

private fun Throwable?.hasDnsError(): Boolean {
    var cur = this
    while (cur != null) {
        if (cur is UnknownHostException) return true
        cur = cur.cause
    }
    return false
}

private fun Throwable?.hasConnectRefusedError(): Boolean {
    var cur = this
    while (cur != null) {
        if (cur is ConnectException) return true
        cur = cur.cause
    }
    return false
}

private fun Throwable?.hasTimeoutError(): Boolean {
    var cur = this
    while (cur != null) {
        if (cur is SocketTimeoutException) return true
        cur = cur.cause
    }
    return false
}

@OptIn(UnstableApi::class)
internal fun isCodecResourceOrDecoderError(error: PlaybackException): Boolean {
    if (error.errorCode == PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED ||
        error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
        error.errorCode == PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED ||
        error.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED ||
        error.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES
    ) {
        return true
    }
    var cur: Throwable? = error
    while (cur != null) {
        val cls = cur.javaClass.name
        val msg = cur.message.orEmpty()
        if (cur is MediaCodec.CodecException ||
            msg.contains("Released by resource manager", ignoreCase = true) ||
            msg.contains("RESOURCE_RECLAIMED", ignoreCase = true) ||
            cls.contains("MediaCodec", ignoreCase = true) ||
            cls.contains("DecoderInitializationException", ignoreCase = true)
        ) {
            return true
        }
        cur = cur.cause
    }
    return false
}

internal fun isEmulatorOrContendedDecoderName(decoderName: String): Boolean {
    val lower = decoderName.lowercase()
    return lower.contains("goldfish") ||
        lower.contains("ranchu") ||
        lower.contains("cuttlefish") ||
        lower.contains("vsoc") ||
        lower.contains("virtio") ||
        lower.contains("v4l2") ||
        lower.contains("mesa") ||
        lower.contains("swiftshader") ||
        lower.contains("vbox") ||
        lower.contains("redroid") ||
        lower.contains("emulator")
}

internal fun isRunningOnEmulatorDevice(): Boolean {
    val fingerprint = Build.FINGERPRINT.orEmpty().lowercase()
    val hardware = Build.HARDWARE.orEmpty().lowercase()
    val model = Build.MODEL.orEmpty().lowercase()
    val product = Build.PRODUCT.orEmpty().lowercase()
    val device = Build.DEVICE.orEmpty().lowercase()
    val board = Build.BOARD.orEmpty().lowercase()
    val brand = Build.BRAND.orEmpty().lowercase()
    val primaryAbi = Build.SUPPORTED_ABIS?.firstOrNull().orEmpty().lowercase()
    return primaryAbi.contains("x86") ||
        fingerprint.contains("generic") ||
        fingerprint.contains("emulator") ||
        fingerprint.contains("vsoc") ||
        fingerprint.contains("cuttlefish") ||
        fingerprint.contains("cf_") ||
        hardware.contains("goldfish") ||
        hardware.contains("ranchu") ||
        hardware.contains("cuttlefish") ||
        hardware.contains("vsoc") ||
        hardware.contains("virtio") ||
        model.contains("sdk_gphone") ||
        model.contains("emulator") ||
        model.contains("cuttlefish") ||
        model.contains("vsoc") ||
        product.contains("sdk_gphone") ||
        product.contains("emulator") ||
        product.contains("vsoc") ||
        product.contains("cf_") ||
        product.contains("cutf") ||
        product.contains("aosp_x86") ||
        device.contains("vsoc") ||
        device.contains("cf_") ||
        device.contains("cutf") ||
        device.contains("emu") ||
        device.contains("cheets") ||
        board.contains("goldfish") ||
        board.contains("cutf") ||
        board.contains("vsoc") ||
        brand.contains("generic")
}

/**
 * On physical ARM phones/tablets/TVs, keeps true GPU/VPU hardware decoders (`c2.qti.*`,
 * `c2.exynos.*`, `c2.mtk.*`, etc.) at the front for 60fps stutter-free 1080p decoding with
 * `c2.android.*` right behind as automatic fallback. On cloud/Cuttlefish/virtualized environments,
 * single-instance contended codecs (`c2.v4l2.*`, `c2.virtio.*`, `c2.goldfish.*`), or after any
 * `MediaResourceManager` reclamation, places contention-free `c2.android.*` / `OMX.google.*` ahead
 * so SystemUI/WebRTC encoders never trigger `E/MediaCodec: Released by resource manager`.
 */
@OptIn(UnstableApi::class)
internal fun prioritizeContentionFreeVideoDecoders(
    decoders: List<MediaCodecInfo>,
    forcePhysicalDeviceMode: Boolean = false,
    forceSoftwareFirst: Boolean = false
): List<MediaCodecInfo> {
    if (decoders.size <= 1) return decoders
    val swDecoders = decoders.filter { info ->
        info.softwareOnly ||
            info.name.startsWith("c2.android.", ignoreCase = true) ||
            info.name.startsWith("OMX.google.", ignoreCase = true)
    }
    if (swDecoders.isEmpty()) return decoders

    val hwDecoders = decoders.filterNot { info ->
        info.softwareOnly ||
            info.name.startsWith("c2.android.", ignoreCase = true) ||
            info.name.startsWith("OMX.google.", ignoreCase = true)
    }
    if (hwDecoders.isEmpty()) return decoders

    val onEmulator = !forcePhysicalDeviceMode && isRunningOnEmulatorDevice()
    if (forceSoftwareFirst || onEmulator) {
        return swDecoders + hwDecoders
    }

    val physicalHwDecoders = hwDecoders.filterNot { isEmulatorOrContendedDecoderName(it.name) }
    val contendedHwDecoders = hwDecoders.filter { isEmulatorOrContendedDecoderName(it.name) }
    return physicalHwDecoders + swDecoders + contendedHwDecoders
}

internal fun isExplicitLiveEndpointUrl(rawUrl: String): Boolean {
    val clean = M3uParser.extractStreamRequestInfo(rawUrl).cleanUrl
        .lowercase()
        .substringBefore('#')
        .substringBefore('?')
    if (clean.contains("/movie/") ||
        clean.contains("/series/") ||
        clean.endsWith(".mp4") ||
        clean.endsWith(".m4v") ||
        clean.endsWith(".mkv") ||
        clean.endsWith(".webm") ||
        clean.endsWith(".avi") ||
        clean.endsWith(".mov")
    ) {
        return false
    }
    return clean.contains("/live/") ||
        clean.endsWith(".ts") ||
        clean.endsWith("/mpegts") ||
        clean.contains("live.php")
}

/**
 * Tracks 302-redirected HLS media playlist URLs (used by Xtream Codes / XUI.one load balancers
 * whose edge tokens often expire after 60 seconds). When a 60s edge token expires during
 * playlist polling, [LiveTokenRefreshDataSource] transparently re-requests [entryManifestUri]
 * to obtain a fresh 302 edge token without interrupting ExoPlayer or flushing its buffer.
 */
internal class LiveRedirectSession {
    @Volatile
    var entryManifestUri: Uri? = null

    @Volatile
    var pinnedRedirectUri: Uri? = null

    @Volatile
    var activeRedirectUri: Uri? = null

    @Volatile
    var entryAuthQuery: String? = null

    @Volatile
    var manifestOpenedSuccessfully: Boolean = false

    fun resetForManifest(manifestUri: Uri?) {
        entryManifestUri = manifestUri
        pinnedRedirectUri = null
        activeRedirectUri = null
        entryAuthQuery = manifestUri?.encodedQuery?.takeIf { it.isNotBlank() }
        manifestOpenedSuccessfully = false
    }

    fun clearRedirectCache() {
        pinnedRedirectUri = null
        activeRedirectUri = null
    }
}

@OptIn(UnstableApi::class)
private class LiveTokenRefreshDataSource(
    private val delegate: HttpDataSource,
    private val session: LiveRedirectSession
) : DataSource {

    class Factory(
        private val delegateFactory: HttpDataSource.Factory,
        private val session: LiveRedirectSession
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource {
            return LiveTokenRefreshDataSource(delegateFactory.createDataSource(), session)
        }
    }

    override fun addTransferListener(transferListener: TransferListener) {
        delegate.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val entry = session.entryManifestUri
        val pinned = session.pinnedRedirectUri
        val isPinnedPlaylistPoll = entry != null && pinned != null && dataSpec.uri == pinned

        val effectiveSpec = if (isPinnedPlaylistPoll) {
            val target = session.activeRedirectUri ?: pinned
            if (target != null && target != dataSpec.uri) {
                dataSpec.buildUpon().setUri(target).build()
            } else {
                dataSpec
            }
        } else {
            dataSpec
        }

        return try {
            val length = delegate.open(effectiveSpec)
            val resolved = delegate.uri
            if (entry != null && dataSpec.uri == entry) {
                session.manifestOpenedSuccessfully = true
                if (resolved != null && resolved != entry) {
                    session.pinnedRedirectUri = resolved
                    session.activeRedirectUri = resolved
                    resolved.encodedQuery?.takeIf { it.isNotBlank() }?.let {
                        session.entryAuthQuery = it
                    }
                }
            } else if (isPinnedPlaylistPoll && resolved != null) {
                session.activeRedirectUri = resolved
                resolved.encodedQuery?.takeIf { it.isNotBlank() }?.let {
                    session.entryAuthQuery = it
                }
            }
            length
        } catch (e: HttpDataSource.HttpDataSourceException) {
            if (isPinnedPlaylistPoll && entry != null) {
                runCatching { delegate.close() }
                val refreshSpec = dataSpec.buildUpon().setUri(entry).build()
                val length = delegate.open(refreshSpec)
                val freshResolved = delegate.uri
                if (freshResolved != null && freshResolved != entry) {
                    session.activeRedirectUri = freshResolved
                    freshResolved.encodedQuery?.takeIf { it.isNotBlank() }?.let {
                        session.entryAuthQuery = it
                    }
                }
                length
            } else if (
                e is HttpDataSource.InvalidResponseCodeException &&
                e.responseCode in setOf(401, 403, 404) &&
                entry != null &&
                effectiveSpec.uri != entry &&
                effectiveSpec.uri.encodedQuery.isNullOrEmpty() &&
                !session.entryAuthQuery.isNullOrEmpty() &&
                effectiveSpec.uri.host.equals(
                    (session.activeRedirectUri ?: entry).host,
                    ignoreCase = true
                )
            ) {
                // Rescue child HLS variant playlists / .ts segments on CDNs (Nimble, Wowza, Akamai,
                // Flussonic) that require the parent manifest's ?wmsAuthSign / ?token query string
                runCatching { delegate.close() }
                val authedUri = Uri.parse("${effectiveSpec.uri}?${session.entryAuthQuery}")
                delegate.open(effectiveSpec.buildUpon().setUri(authedUri).build())
            } else {
                throw e
            }
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        return delegate.read(buffer, offset, length)
    }

    override fun getUri(): Uri? = delegate.uri

    override fun getResponseHeaders(): Map<String, List<String>> = delegate.responseHeaders

    override fun close() {
        delegate.close()
    }
}

private class VodProgressSnapshot(
    var positionMs: Long = 0L,
    var durationMs: Long = 0L
)

@OptIn(UnstableApi::class)
@Composable
fun VideoPlayer(
    channel: Channel,
    isFullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
    onClose: () -> Unit,
    onNextChannel: () -> Unit,
    onPreviousChannel: () -> Unit,
    initialPositionMs: Long = 0L,
    onSaveProgress: (positionMs: Long, durationMs: Long, isLive: Boolean) -> Unit = { _, _, _ -> },
    onResetProgress: () -> Unit = {},
    isMiniPlayer: Boolean = false,
    isInPipMode: Boolean = false,
    qualityPreset: VideoQualityPreset = VideoQualityPreset.AUTO,
    onQualityPresetChange: (VideoQualityPreset) -> Unit = {},
    isBackgroundPlayEnabled: Boolean = false,
    onBackgroundPlayChange: (Boolean) -> Unit = {},
    isTurboStreamEnabled: Boolean = false,
    onTurboStreamChange: (Boolean) -> Unit = {},
    onEnterMiniPlayer: () -> Unit = {},
    onExitMiniPlayer: () -> Unit = {},
    onRequestPipMode: () -> Unit = {},
    pipPlayPauseSignal: Int = 0,
    pipDismissSignal: Int = 0,
    onPlayingStateChanged: (Boolean) -> Unit = {},
    onReportIssue: (String?) -> Unit = {},
    isPremium: Boolean = false,
    onOpenPremium: (PremiumFeature) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestOnSaveProgress by rememberUpdatedState(onSaveProgress)
    val latestOnResetProgress by rememberUpdatedState(onResetProgress)
    val latestBackgroundPlayEnabled by rememberUpdatedState(isBackgroundPlayEnabled)
    val latestTurboStreamEnabled by rememberUpdatedState(isTurboStreamEnabled)
    val latestInPipMode by rememberUpdatedState(isInPipMode)
    val latestMiniPlayer by rememberUpdatedState(isMiniPlayer)
    val latestQualityPreset by rememberUpdatedState(qualityPreset)
    val latestOnPlayingStateChanged by rememberUpdatedState(onPlayingStateChanged)

    val isPipSupported = remember(context) {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
    }

    // Ensure HTTP session cookies are retained across HLS redirects and TS segment requests
    remember {
        if (CookieHandler.getDefault() == null) {
            CookieHandler.setDefault(CookieManager(null, CookiePolicy.ACCEPT_ALL))
        }
        true
    }

    val initialStartPosMs = remember(channel.id, channel.streamUrl) {
        initialPositionMs.coerceAtLeast(0L)
    }
    val vodSnapshot = remember(channel.id, channel.streamUrl) {
        VodProgressSnapshot(positionMs = initialStartPosMs, durationMs = 0L)
    }
    var pendingResumeSeekMs by remember(channel.id, channel.streamUrl) {
        mutableLongStateOf(initialStartPosMs)
    }
    var resumeBannerPositionMs by remember(channel.id, channel.streamUrl) {
        mutableLongStateOf(initialStartPosMs)
    }
    var showResumeBanner by remember(channel.id, channel.streamUrl) {
        mutableStateOf(false)
    }

    var isBuffering by remember(channel.id, channel.streamUrl) { mutableStateOf(true) }
    var hasRenderedFirstFrame by remember(channel.id, channel.streamUrl) { mutableStateOf(false) }
    var hasVideoTrack by remember(channel.id, channel.streamUrl) { mutableStateOf(true) }
    var hasError by remember(channel.id, channel.streamUrl) { mutableStateOf(false) }
    var isUnknownHostError by remember(channel.id, channel.streamUrl) { mutableStateOf(false) }
    var isContainerError by remember(channel.id, channel.streamUrl) { mutableStateOf(false) }
    var errorMessage by remember(channel.id, channel.streamUrl) { mutableStateOf<String?>(null) }
    var isPlaying by remember(channel.id, channel.streamUrl) { mutableStateOf(true) }
    var shouldAutoPlay by remember(channel.id, channel.streamUrl) { mutableStateOf(true) }
    var areControlsVisible by remember { mutableStateOf(true) }
    var resizeModeIndex by remember { mutableIntStateOf(0) } // 0: FIT, 1: FILL, 2: ZOOM
    var videoAspectRatio by remember(channel.id, channel.streamUrl) { mutableFloatStateOf(16f / 9f) }

    val streamCandidates = remember(channel.id, channel.streamUrl) {
        buildStreamCandidates(channel.streamUrl)
    }
    val initialCandidateIdx = remember(channel.id, channel.streamUrl, streamCandidates) {
        streamCandidates.indexOfFirst { c ->
            val origin = extractOrigin(c.url)
            origin == null || origin !in SSL_FAILED_ORIGINS
        }.let { if (it >= 0) it else 0 }
    }
    var candidateIndex by remember(channel.id, channel.streamUrl) {
        mutableIntStateOf(initialCandidateIdx)
    }
    var overrideTestUri by remember(channel.id, channel.streamUrl) { mutableStateOf<String?>(null) }
    var probedCandidate by remember(channel.id, channel.streamUrl) { mutableStateOf<StreamCandidate?>(null) }
    var isRunningDeepProbe by remember(channel.id, channel.streamUrl) { mutableStateOf(false) }
    var didRunDeepProbe by remember(channel.id, channel.streamUrl) { mutableStateOf(false) }
    var pendingFinalErrorMessage by remember(channel.id, channel.streamUrl) { mutableStateOf<String?>(null) }

    val failedOrigins = remember(channel.id, channel.streamUrl) { mutableSetOf<String>() }
    val failedHosts = remember(channel.id, channel.streamUrl) { mutableSetOf<String>() }
    val liveHttpOrigins = remember(channel.id, channel.streamUrl) { mutableSetOf<String>() }
    val failedUrlUserAgents = remember(channel.id, channel.streamUrl) {
        mutableSetOf<Pair<String, String>>()
    }
    val failedUrlFormats = remember(channel.id, channel.streamUrl) {
        mutableSetOf<Pair<String, StreamFormatKind>>()
    }
    var lastHttpStatusCode by remember(channel.id, channel.streamUrl) { mutableStateOf<Int?>(null) }

    var lifecycleSessionKey by remember(channel.id, channel.streamUrl) { mutableIntStateOf(0) }
    var stoppedInBackground by remember(channel.id, channel.streamUrl) { mutableStateOf(false) }
    var isVideoDisabledForBackground by remember { mutableStateOf(false) }
    var hasReachedReady by remember(channel.id, channel.streamUrl) { mutableStateOf(false) }
    val hasReachedReadyFlag = remember { AtomicBoolean(false) }
    val preferSoftwareDecoderAfterReclaim = remember {
        AtomicBoolean(CONTENDED_HW_CODEC_RECLAIMED.get())
    }
    val liveRedirectSession = remember { LiveRedirectSession() }
    var didCapTo720p by remember(channel.id, channel.streamUrl) { mutableStateOf(false) }
    var stallCapStage by remember(channel.id, channel.streamUrl) { mutableStateOf(StallCapStage.NONE) }
    var stallRecoveryAttempt by remember(channel.id, channel.streamUrl) { mutableIntStateOf(0) }
    var inPlaceLiveRecoveries by remember(channel.id, channel.streamUrl) { mutableIntStateOf(0) }
    var codecRecoveries by remember(channel.id, channel.streamUrl) { mutableIntStateOf(0) }

    var currentPositionMs by remember(channel.id, channel.streamUrl) {
        mutableLongStateOf(initialStartPosMs)
    }
    var durationMs by remember(channel.id, channel.streamUrl) { mutableLongStateOf(0L) }
    var isSeekable by remember(channel.id, channel.streamUrl) { mutableStateOf(false) }
    var isLiveStream by remember(channel.id, channel.streamUrl) { mutableStateOf(false) }
    var isScrubbing by remember { mutableStateOf(false) }
    var scrubPositionMs by remember { mutableLongStateOf(0L) }
    var showRewindFeedback by remember { mutableStateOf(false) }
    var showForwardFeedback by remember { mutableStateOf(false) }
    var isQualitySheetOpen by remember { mutableStateOf(false) }
    var availableVideoTracks by remember(channel.id, channel.streamUrl) {
        mutableStateOf<List<StreamVideoTrackOption>>(emptyList())
    }
    var selectedStreamTrack by remember(channel.id, channel.streamUrl) {
        mutableStateOf<StreamVideoTrackOption?>(null)
    }
    var activeVideoWidth by remember(channel.id, channel.streamUrl) { mutableIntStateOf(0) }
    var activeVideoHeight by remember(channel.id, channel.streamUrl) { mutableIntStateOf(0) }
    var areMiniControlsVisible by remember { mutableStateOf(true) }

    var textureViewRef by remember { mutableStateOf<TextureView?>(null) }

    val resizeModes = listOf(
        AspectRatioFrameLayout.RESIZE_MODE_FIT,
        AspectRatioFrameLayout.RESIZE_MODE_FILL,
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM
    )
    val resizeLabels = listOf("Uydur (Fit)", "Doldur (Fill)", "Yakınlaştır (Zoom)")

    fun isCandidateEligible(idx: Int): Boolean {
        val c = streamCandidates[idx]
        val origin = extractOrigin(c.url)
        val host = CloudflareDns.extractHostFromUrl(c.url)?.lowercase()
        return (host == null || host !in failedHosts) &&
            (origin == null || (origin !in failedOrigins && origin !in SSL_FAILED_ORIGINS)) &&
            (c.url to c.userAgent) !in failedUrlUserAgents &&
            (c.url to c.formatKind) !in failedUrlFormats
    }

    fun pickNextCandidateIndex(afterIdx: Int, preferLiveOrigin: String? = null): Int? {
        if (preferLiveOrigin != null && preferLiveOrigin in liveHttpOrigins) {
            val currentUa = streamCandidates.getOrNull(afterIdx)?.userAgent
            val sameOriginIdx = ((afterIdx + 1) until streamCandidates.size).firstOrNull { idx ->
                val c = streamCandidates[idx]
                isCandidateEligible(idx) &&
                    extractOrigin(c.url) == preferLiveOrigin &&
                    (currentUa == null || c.userAgent == currentUa)
            }
            if (sameOriginIdx != null) return sameOriginIdx
        }
        return ((afterIdx + 1) until streamCandidates.size).firstOrNull { idx ->
            isCandidateEligible(idx)
        }
    }

    // Fail-fast 0 retries on dead manifests; 4 retries on initial HLS media segments once manifest
    // opened with 200 OK; 8 fast-backoff retries once STATE_READY is reached so brief Wi-Fi/mobile
    // drops recover in 350ms before the 65s deep buffer ever runs dry!
    val loadErrorHandlingPolicy = remember(hasReachedReadyFlag, liveRedirectSession) {
        object : DefaultLoadErrorHandlingPolicy(0) {
            override fun getMinimumLoadableRetryCount(dataType: Int): Int {
                if (hasReachedReadyFlag.get()) return 8
                if (liveRedirectSession.manifestOpenedSuccessfully && dataType == C.DATA_TYPE_MEDIA) {
                    return 4
                }
                return 0
            }

            override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
                val baseDelay = super.getRetryDelayMsFor(loadErrorInfo)
                if (baseDelay == C.TIME_UNSET) return C.TIME_UNSET
                if (hasReachedReadyFlag.get() || liveRedirectSession.manifestOpenedSuccessfully) {
                    return (loadErrorInfo.errorCount * 350L).coerceIn(300L, 1_500L)
                }
                return baseDelay
            }
        }
    }

    // Single shared DataSource & MediaSource factories + single persistent ExoPlayer instance
    // Powered by CloudflareOkHttpDataSource -> CloudflareDns (1dot1dot1dot1.cloudflare-dns.com)
    val httpDataSourceFactory = remember {
        CloudflareOkHttpDataSource.Factory(
            connectTimeoutMs = 4_500L,
            readTimeoutMs = 10_000L
        ).apply {
            setUserAgent(UA_IPTV_SMARTERS)
            setDefaultRequestProperties(
                mapOf(
                    "Accept" to "*/*",
                    "Connection" to "keep-alive"
                )
            )
        }
    }

    val refreshingDataSourceFactory = remember(httpDataSourceFactory, liveRedirectSession) {
        LiveTokenRefreshDataSource.Factory(httpDataSourceFactory, liveRedirectSession)
    }

    // Wraps VOD movie/series requests in a persistent 200 MB LRU SimpleCache on local storage
    // while routing continuous live TV streams directly through zero-copy RAM.
    val smartCachedNetworkFactory = remember(context, refreshingDataSourceFactory) {
        StreamAccelerator.createSmartCacheDataSourceFactory(
            context = context,
            upstreamFactory = refreshingDataSourceFactory,
            isTurboEnabled = { latestTurboStreamEnabled }
        )
    }

    val mediaDataSourceFactory = remember(context, smartCachedNetworkFactory) {
        DefaultDataSource.Factory(context, smartCachedNetworkFactory)
    }

    val tsPayloadFlags = remember {
        DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
            DefaultTsPayloadReaderFactory.FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS
    }

    val hlsMediaSourceFactory = remember(mediaDataSourceFactory, loadErrorHandlingPolicy, tsPayloadFlags) {
        val hlsExtractorFactory = DefaultHlsExtractorFactory(tsPayloadFlags, true)
        HlsMediaSource.Factory(mediaDataSourceFactory)
            .setExtractorFactory(hlsExtractorFactory)
            .setAllowChunklessPreparation(true)
            .setPlaylistTrackerFactory { dataSourceFactory, errorPolicy, parserFactory ->
                DefaultHlsPlaylistTracker(
                    dataSourceFactory,
                    errorPolicy,
                    parserFactory,
                    /* playlistStuckTargetDurationCoefficient = */ 7.5
                )
            }
            .setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
    }

    val dashMediaSourceFactory = remember(mediaDataSourceFactory, loadErrorHandlingPolicy) {
        DashMediaSource.Factory(mediaDataSourceFactory)
            .setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
    }

    val progressiveMediaSourceFactory = remember(mediaDataSourceFactory, loadErrorHandlingPolicy, tsPayloadFlags) {
        val extractorsFactory = DefaultExtractorsFactory()
            .setConstantBitrateSeekingEnabled(true)
            .setTsExtractorFlags(tsPayloadFlags)
            .setTsExtractorTimestampSearchBytes(1500 * TsExtractor.TS_PACKET_SIZE)
        DefaultMediaSourceFactory(context, extractorsFactory)
            .setDataSourceFactory(mediaDataSourceFactory)
            .setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
    }

    val trackSelector = remember {
        val adaptiveTrackSelectionFactory = AdaptiveTrackSelection.Factory(
            /* minDurationForQualityIncreaseMs = */ 4_000,
            /* maxDurationForQualityDecreaseMs = */ 1_500,
            /* minDurationToRetainAfterDiscardMs = */ 6_000,
            /* bandwidthFraction = */ 0.72f
        )
        DefaultTrackSelector(context, adaptiveTrackSelectionFactory).apply {
            setParameters(
                buildUponParameters()
                    .setViewportSizeToPhysicalDisplaySize(context, true)
                    .setMaxVideoSize(1920, 1080)
                    .setExceedRendererCapabilitiesIfNecessary(false)
            )
        }
    }

    // Conservative initial bitrate estimate (~850 Kbps) so multi-bitrate HLS/DASH streams grab a
    // lightweight segment FIRST for ~0.45s instant opening even on weak internet, then smoothly
    // ramp up to HD/Full HD within 4 seconds if bandwidth permits!
    val bandwidthMeter = remember {
        DefaultBandwidthMeter.Builder(context)
            .setInitialBitrateEstimate(850_000L)
            .setResetOnNetworkTypeChange(true)
            .build()
    }

    val exoPlayer = remember {
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 30_000,
                /* maxBufferMs = */ 65_000,
                /* bufferForPlaybackMs = */ 450,
                /* bufferForPlaybackAfterRebufferMs = */ 1_200
            )
            .setTargetBufferBytes(64 * 1024 * 1024)
            .setBackBuffer(
                /* backBufferDurationMs = */ 20_000,
                /* retainBackBufferFromKeyframe = */ true
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val codecSelector = MediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
            val defaultDecoders = MediaCodecSelector.DEFAULT.getDecoderInfos(
                mimeType,
                requiresSecureDecoder,
                requiresTunnelingDecoder
            )
            val forceSw = preferSoftwareDecoderAfterReclaim.get() || CONTENDED_HW_CODEC_RECLAIMED.get()
            if (requiresSecureDecoder || requiresTunnelingDecoder) {
                defaultDecoders
            } else if (MimeTypes.isVideo(mimeType)) {
                prioritizeContentionFreeVideoDecoders(
                    decoders = defaultDecoders,
                    forceSoftwareFirst = forceSw
                )
            } else if (MimeTypes.isAudio(mimeType) && (forceSw || isRunningOnEmulatorDevice())) {
                prioritizeContentionFreeVideoDecoders(
                    decoders = defaultDecoders,
                    forceSoftwareFirst = true
                )
            } else {
                defaultDecoders
            }
        }

        val renderersFactory = DefaultRenderersFactory(context)
            .setMediaCodecSelector(codecSelector)
            .setEnableDecoderFallback(true)
            .setAllowedVideoJoiningTimeMs(5_000L)

        val mediaAudioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()

        ExoPlayer.Builder(context, renderersFactory)
            .setTrackSelector(trackSelector)
            .setBandwidthMeter(bandwidthMeter)
            .setMediaSourceFactory(progressiveMediaSourceFactory)
            .setLoadControl(loadControl)
            .setAudioAttributes(mediaAudioAttributes, /* handleAudioFocus = */ true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
            .apply {
                repeatMode = Player.REPEAT_MODE_OFF
            }
    }

    // Keep Wi-Fi radio in Low-Latency / Full-Throughput mode (disabling 802.11 power-save sleeps)
    // strictly while video playback is active, releasing immediately on pause or exit.
    DisposableEffect(context, isPlaying, isTurboStreamEnabled) {
        val wifiLock = if (isPlaying && isTurboStreamEnabled) {
            runCatching {
                val wifiManager = context.applicationContext
                    .getSystemService(Context.WIFI_SERVICE) as? WifiManager
                val lockMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                } else {
                    @Suppress("DEPRECATION")
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF
                }
                wifiManager?.createWifiLock(lockMode, "SoykenPlay:LowLatencyStreamLock")?.apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }.getOrNull()
        } else {
            null
        }
        onDispose {
            runCatching {
                if (wifiLock?.isHeld == true) {
                    wifiLock.release()
                }
            }
        }
    }

    // Release the single ExoPlayer ONLY when VideoPlayer leaves composition
    DisposableEffect(exoPlayer) {
        onDispose {
            latestOnPlayingStateChanged(false)
            exoPlayer.clearVideoSurface()
            exoPlayer.stop()
            exoPlayer.release()
        }
    }

    fun applyVideoQualitySelection(
        preset: VideoQualityPreset,
        pinnedTrack: StreamVideoTrackOption?,
        force720pCap: Boolean,
        dynamicStallCap: StallCapStage = stallCapStage,
        videoDisabled: Boolean = isVideoDisabledForBackground,
        compactWindow: Boolean = isInPipMode || isMiniPlayer
    ) {
        val builder = trackSelector.buildUponParameters()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, videoDisabled)
            .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
            .setExceedRendererCapabilitiesIfNecessary(false)

        if (videoDisabled) {
            trackSelector.setParameters(builder)
            return
        }

        if (pinnedTrack != null && isPremium) {
            val group = exoPlayer.currentTracks.groups.getOrNull(pinnedTrack.groupIndex)
            if (group != null && group.type == C.TRACK_TYPE_VIDEO && pinnedTrack.trackIndex < group.length) {
                builder
                    .clearViewportSizeConstraints()
                    .setMaxVideoSize(3840, 2160)
                    .setMaxVideoBitrate(Int.MAX_VALUE)
                    .setForceHighestSupportedBitrate(false)
                    .addOverride(
                        TrackSelectionOverride(group.mediaTrackGroup, listOf(pinnedTrack.trackIndex))
                    )
                trackSelector.setParameters(builder)
                return
            }
        }

        val effectivePreset = if (!isPremium && preset == VideoQualityPreset.UHD_4K) {
            VideoQualityPreset.FHD_1080P
        } else {
            preset
        }

        if (effectivePreset == VideoQualityPreset.AUTO) {
            when {
                dynamicStallCap != StallCapStage.NONE -> {
                    builder
                        .setViewportSizeToPhysicalDisplaySize(context, true)
                        .setMaxVideoSize(dynamicStallCap.maxWidth, dynamicStallCap.maxHeight)
                        .setMaxVideoFrameRate(dynamicStallCap.maxFrameRate)
                        .setMaxVideoBitrate(dynamicStallCap.maxBitrate)
                        .setForceHighestSupportedBitrate(false)
                }
                force720pCap || compactWindow -> {
                    builder
                        .setViewportSizeToPhysicalDisplaySize(context, true)
                        .setMaxVideoSize(1280, 720)
                        .setMaxVideoFrameRate(30)
                        .setMaxVideoBitrate(5_500_000)
                        .setForceHighestSupportedBitrate(false)
                }
                else -> {
                    val autoMaxWidth = if (isPremium) 3840 else 1920
                    val autoMaxHeight = if (isPremium) 2160 else 1080
                    builder
                        .setViewportSizeToPhysicalDisplaySize(context, true)
                        .setMaxVideoSize(autoMaxWidth, autoMaxHeight)
                        .setMaxVideoFrameRate(60)
                        .setMaxVideoBitrate(Int.MAX_VALUE)
                        .setForceHighestSupportedBitrate(false)
                }
            }
        } else {
            builder
                .clearViewportSizeConstraints()
                .setMaxVideoSize(effectivePreset.maxWidth, effectivePreset.maxHeight)
                .setMaxVideoFrameRate(60)
                .setMaxVideoBitrate(effectivePreset.maxBitrate)
                .setForceHighestSupportedBitrate(true)
        }
        trackSelector.setParameters(builder)
    }

    LaunchedEffect(
        qualityPreset,
        selectedStreamTrack,
        didCapTo720p,
        stallCapStage,
        isVideoDisabledForBackground,
        isInPipMode,
        isMiniPlayer,
        isPremium
    ) {
        if (!isPremium && selectedStreamTrack != null) {
            selectedStreamTrack = null
        }
        applyVideoQualitySelection(
            preset = qualityPreset,
            pinnedTrack = if (isPremium) selectedStreamTrack else null,
            force720pCap = didCapTo720p,
            dynamicStallCap = stallCapStage,
            videoDisabled = isVideoDisabledForBackground,
            compactWindow = isInPipMode || isMiniPlayer
        )
    }

    fun buildMediaItem(uriString: String, formatKind: StreamFormatKind): MediaItem {
        val uri = Uri.parse(uriString)
        val lower = uriString.lowercase()

        if (lower.startsWith("android.resource://") ||
            lower.startsWith("content://") ||
            lower.startsWith("file://")
        ) {
            return when (formatKind) {
                StreamFormatKind.HLS ->
                    MediaItem.Builder().setUri(uri).setMimeType(MimeTypes.APPLICATION_M3U8).build()
                StreamFormatKind.DASH ->
                    MediaItem.Builder().setUri(uri).setMimeType(MimeTypes.APPLICATION_MPD).build()
                else -> MediaItem.fromUri(uri)
            }
        }

        val mime = when (formatKind) {
            StreamFormatKind.HLS -> MimeTypes.APPLICATION_M3U8
            StreamFormatKind.DASH -> MimeTypes.APPLICATION_MPD
            StreamFormatKind.MPEG_TS -> MimeTypes.VIDEO_MP2T
            StreamFormatKind.PROGRESSIVE_MP4,
            StreamFormatKind.PROGRESSIVE_MKV,
            StreamFormatKind.PROGRESSIVE_WEBM,
            StreamFormatKind.AUTO -> null
        }

        val builder = MediaItem.Builder().setUri(uri)
        if (mime != null) {
            builder.setMimeType(mime)
        }

        if ((formatKind == StreamFormatKind.HLS || formatKind == StreamFormatKind.DASH) &&
            !StreamAccelerator.isCacheableVodUrl(uriString)
        ) {
            val liveConfigBuilder = MediaItem.LiveConfiguration.Builder()
                .setMinPlaybackSpeed(0.96f)
                .setMaxPlaybackSpeed(1.03f)
            if (latestTurboStreamEnabled) {
                // 6-second live safety cushion absorbs brief Wi-Fi/mobile dips without freezing
                liveConfigBuilder
                    .setTargetOffsetMs(6_000L)
                    .setMinOffsetMs(2_500L)
            }
            builder.setLiveConfiguration(liveConfigBuilder.build())
        }

        return builder.build()
    }

    fun isCurrentStreamLiveBroadcast(): Boolean {
        val live = exoPlayer.isCurrentMediaItemLive
        val seekable = exoPlayer.isCurrentMediaItemSeekable
        val rawDur = exoPlayer.duration
        val validDur = if (rawDur > 0 && rawDur != C.TIME_UNSET) rawDur else 0L
        if (live || !seekable || validDur == 0L) return true
        return validDur < 180_000L && isExplicitLiveEndpointUrl(channel.streamUrl)
    }

    fun persistCurrentState() {
        if (hasReachedReady) {
            val seekable = exoPlayer.isCurrentMediaItemSeekable
            val rawDur = exoPlayer.duration
            val validDur = if (rawDur > 0 && rawDur != C.TIME_UNSET) rawDur else 0L

            if (isCurrentStreamLiveBroadcast()) {
                latestOnSaveProgress(0L, 0L, true)
                return
            }

            if (seekable && validDur >= 15_000L && pendingResumeSeekMs == 0L) {
                val pos = exoPlayer.currentPosition.coerceAtLeast(0L)
                vodSnapshot.positionMs = pos
                vodSnapshot.durationMs = validDur
                latestOnSaveProgress(pos, validDur, false)
                return
            }
        }

        if (vodSnapshot.durationMs >= 15_000L && vodSnapshot.positionMs >= 3_000L) {
            latestOnSaveProgress(vodSnapshot.positionMs, vodSnapshot.durationMs, false)
        }
    }

    LaunchedEffect(pipPlayPauseSignal) {
        if (pipPlayPauseSignal > 0) {
            if (exoPlayer.isPlaying) {
                shouldAutoPlay = false
                exoPlayer.pause()
            } else {
                shouldAutoPlay = true
                exoPlayer.play()
            }
        }
    }

    LaunchedEffect(pipDismissSignal) {
        if (pipDismissSignal > 0) {
            persistCurrentState()
            if (vodSnapshot.durationMs >= 15_000L && vodSnapshot.positionMs >= 3_000L) {
                pendingResumeSeekMs = vodSnapshot.positionMs
                resumeBannerPositionMs = vodSnapshot.positionMs
            }
            shouldAutoPlay = false
            stoppedInBackground = true
            isVideoDisabledForBackground = false
            exoPlayer.clearVideoSurface()
            exoPlayer.stop()
        }
    }

    // Deep HTTP/Binary/HTML-Embed stream probe rescue when direct ExoPlayer candidates return 404/403/unsupported
    LaunchedEffect(channel.id, channel.streamUrl, isRunningDeepProbe) {
        if (!isRunningDeepProbe) return@LaunchedEffect
        exoPlayer.stop()
        val probeResult = StreamProbe.resolveFallbackStream(context, channel.streamUrl)
        isRunningDeepProbe = false
        val resolved = probeResult.resolved
        if (resolved != null) {
            hasError = false
            isBuffering = true
            probedCandidate = StreamCandidate(
                url = resolved.url,
                formatKind = resolved.formatKind,
                userAgent = resolved.userAgent,
                customHeaders = resolved.customHeaders,
                isSyntheticFallback = false
            )
        } else {
            val statusCode = probeResult.confirmedHttpStatus ?: lastHttpStatusCode
            hasError = true
            isBuffering = false
            errorMessage = when {
                statusCode == 404 || statusCode == 410 ->
                    "Yayın akışı sunucuda bulunamadı (HTTP $statusCode). Kanal geçici olarak çevrimdışı veya yayın adresi değişmiş olabilir."
                statusCode == 401 || statusCode == 403 ->
                    "Sunucu erişimi reddetti (HTTP $statusCode). Abonelik süresi dolmuş, hesap başka cihazda aktif veya yayın erişime kapalı olabilir."
                else -> pendingFinalErrorMessage ?: "Yayın bağlantısı kurulamadı."
            }
        }
    }

    // Sequentially load new stream / fallback / resume on the SAME ExoPlayer instance
    LaunchedEffect(
        channel.id,
        channel.streamUrl,
        candidateIndex,
        probedCandidate,
        overrideTestUri,
        lifecycleSessionKey,
        isRunningDeepProbe
    ) {
        if (isRunningDeepProbe) return@LaunchedEffect

        hasReachedReady = false
        hasReachedReadyFlag.set(false)
        hasRenderedFirstFrame = false
        isBuffering = true
        hasError = false
        inPlaceLiveRecoveries = 0

        applyVideoQualitySelection(
            preset = qualityPreset,
            pinnedTrack = selectedStreamTrack,
            force720pCap = didCapTo720p
        )

        val activeCandidate = probedCandidate
            ?: streamCandidates.getOrElse(candidateIndex) { streamCandidates.first() }
        val targetUri = overrideTestUri ?: activeCandidate.url
        val targetFormat = if (overrideTestUri != null) StreamFormatKind.AUTO else activeCandidate.formatKind

        if (targetFormat == StreamFormatKind.HLS && overrideTestUri == null) {
            liveRedirectSession.resetForManifest(Uri.parse(targetUri))
        } else {
            liveRedirectSession.resetForManifest(null)
        }

        httpDataSourceFactory.setUserAgent(activeCandidate.userAgent)
        val requestHeaders = buildMap {
            put("Accept", "*/*")
            put("Connection", "keep-alive")
            if (overrideTestUri == null) {
                putAll(activeCandidate.customHeaders)
            }
        }
        httpDataSourceFactory.setDefaultRequestProperties(requestHeaders)

        exoPlayer.stop()
        exoPlayer.clearMediaItems()

        // Non-blocking DNS pre-warm so exoPlayer.prepare() starts immediately with 0ms UI wait
        if (overrideTestUri == null) {
            CloudflareDns.preWarmUrlAsync(targetUri)
        }

        if (!isVideoDisabledForBackground) {
            textureViewRef?.let { tv ->
                exoPlayer.setVideoTextureView(tv)
            }
        }

        val mediaItem = buildMediaItem(targetUri, targetFormat)
        exoPlayer.volume = if (pendingResumeSeekMs >= 3_000L) 0f else 1f
        exoPlayer.playWhenReady = shouldAutoPlay

        when (targetFormat) {
            StreamFormatKind.HLS ->
                exoPlayer.setMediaSource(hlsMediaSourceFactory.createMediaSource(mediaItem))
            StreamFormatKind.DASH ->
                exoPlayer.setMediaSource(dashMediaSourceFactory.createMediaSource(mediaItem))
            else ->
                exoPlayer.setMediaSource(progressiveMediaSourceFactory.createMediaSource(mediaItem))
        }
        exoPlayer.prepare()

        // Preparation Watchdog: prevents infinite loading spinner if an IPTV endpoint accepts the
        // HTTP socket (200 OK) and hangs with 0 HLS segments or 0 media packets.
        val hasMoreCandidates = overrideTestUri == null &&
            probedCandidate == null &&
            pickNextCandidateIndex(candidateIndex) != null
        val watchdogTimeoutMs = if (overrideTestUri != null || probedCandidate != null) {
            14_000L
        } else if (hasMoreCandidates) {
            5_500L
        } else {
            14_000L
        }

        var elapsedMs = 0L
        while (elapsedMs < watchdogTimeoutMs && !hasReachedReady && !hasError && !isRunningDeepProbe) {
            delay(500)
            elapsedMs += 500
            if (elapsedMs == 1_500L && exoPlayer.volume < 1f && pendingResumeSeekMs == 0L) {
                exoPlayer.volume = 1f
            }
        }

        if (!hasReachedReady && !hasError && !isRunningDeepProbe) {
            exoPlayer.volume = 1f
            if (overrideTestUri == null && probedCandidate == null) {
                failedUrlFormats.add(activeCandidate.url to activeCandidate.formatKind)
                val nextIdx = pickNextCandidateIndex(candidateIndex, extractOrigin(activeCandidate.url))
                if (nextIdx != null) {
                    candidateIndex = nextIdx
                    return@LaunchedEffect
                }
                if (!didRunDeepProbe) {
                    didRunDeepProbe = true
                    pendingFinalErrorMessage =
                        "Yayın sunucusu zamanında medya akışı göndermedi. Kanal çevrimdışı veya yoğun olabilir."
                    isRunningDeepProbe = true
                    return@LaunchedEffect
                }
            }
            exoPlayer.stop()
            isBuffering = false
            hasError = true
            errorMessage = "Yayın sunucusu zamanında medya akışı göndermedi. Kanal çevrimdışı veya yoğun olabilir."
        }
    }

    // Lifecycle observer:
    // - In Picture-in-Picture (Activity stays STARTED/PAUSED), keeps both video & audio active.
    // - On true background ON_STOP (no window visible on screen):
    //   * When background play is ON, proactively disables C.TRACK_TYPE_VIDEO and detaches the
    //     TextureView surface so ExoPlayer cleanly releases MediaCodec BEFORE Android's
    //     MediaResourceManager can forcibly kill it ("E/MediaCodec: Released by resource manager"),
    //     keeping background audio completely uninterrupted!
    //   * When background play is OFF, stops network & codec cleanly and resumes on ON_START.
    DisposableEffect(lifecycleOwner, exoPlayer, channel.id, channel.streamUrl) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    persistCurrentState()
                    if (context.findActivity()?.isFinishing == true) {
                        shouldAutoPlay = false
                        isVideoDisabledForBackground = false
                        exoPlayer.clearVideoSurface()
                        exoPlayer.stop()
                    }
                }
                Lifecycle.Event.ON_STOP -> {
                    persistCurrentState()
                    val activelyWantsPlay = exoPlayer.playWhenReady && exoPlayer.playbackState != Player.STATE_ENDED
                    val keepAliveInBackground = (latestBackgroundPlayEnabled || latestInPipMode) && activelyWantsPlay
                    if (!keepAliveInBackground) {
                        if (vodSnapshot.durationMs >= 15_000L && vodSnapshot.positionMs >= 3_000L) {
                            pendingResumeSeekMs = vodSnapshot.positionMs
                            resumeBannerPositionMs = vodSnapshot.positionMs
                        }
                        shouldAutoPlay = activelyWantsPlay
                        stoppedInBackground = true
                        isVideoDisabledForBackground = false
                        exoPlayer.clearVideoSurface()
                        exoPlayer.stop()
                    } else if (!latestInPipMode) {
                        // True invisible background audio mode: release hardware video MediaCodec cleanly
                        isVideoDisabledForBackground = true
                        applyVideoQualitySelection(
                            preset = latestQualityPreset,
                            pinnedTrack = selectedStreamTrack,
                            force720pCap = didCapTo720p,
                            videoDisabled = true,
                            compactWindow = latestMiniPlayer
                        )
                        exoPlayer.clearVideoSurface()
                    }
                }
                Lifecycle.Event.ON_START -> {
                    if (isVideoDisabledForBackground) {
                        isVideoDisabledForBackground = false
                        applyVideoQualitySelection(
                            preset = latestQualityPreset,
                            pinnedTrack = selectedStreamTrack,
                            force720pCap = didCapTo720p,
                            videoDisabled = false,
                            compactWindow = latestInPipMode || latestMiniPlayer
                        )
                        textureViewRef?.let { tv ->
                            exoPlayer.setVideoTextureView(tv)
                        }
                    }
                    if (stoppedInBackground) {
                        stoppedInBackground = false
                        isBuffering = true
                        hasError = false
                        lifecycleSessionKey++
                    } else {
                        textureViewRef?.let { tv ->
                            exoPlayer.setVideoTextureView(tv)
                        }
                    }
                }
                Lifecycle.Event.ON_DESTROY -> {
                    exoPlayer.clearVideoSurface()
                    exoPlayer.stop()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Per-channel Player.Listener so callbacks always bind to current channel's state
    // and save previous channel's final VOD snapshot cleanly on channel switch or close.
    DisposableEffect(exoPlayer, channel.id, channel.streamUrl) {
        val channelSaveCallback = onSaveProgress

        val listener = object : Player.Listener {
            override fun onTracksChanged(tracks: Tracks) {
                if (!tracks.isEmpty) {
                    hasVideoTrack = tracks.containsType(C.TRACK_TYPE_VIDEO)
                    availableVideoTracks = extractAvailableVideoTracks(tracks)
                    if (!hasVideoTrack) {
                        // Audio-only / radio broadcast
                        hasRenderedFirstFrame = true
                    }
                }
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    activeVideoWidth = videoSize.width
                    activeVideoHeight = videoSize.height
                    val ratio = (videoSize.width * videoSize.pixelWidthHeightRatio) / videoSize.height.toFloat()
                    if (ratio.isFinite() && ratio > 0.1f) {
                        videoAspectRatio = ratio
                    }
                }
            }

            override fun onRenderedFirstFrame() {
                hasRenderedFirstFrame = true
                isBuffering = false
                hasError = false
                if (overrideTestUri == null) {
                    val working = probedCandidate
                        ?: streamCandidates.getOrElse(candidateIndex) { streamCandidates.first() }
                    StreamAccelerator.recordWorkingStream(
                        rawChannelUrl = channel.streamUrl,
                        workingCandidateUrl = working.url,
                        formatKind = working.formatKind,
                        userAgent = working.userAgent
                    )
                }
                if (pendingResumeSeekMs == 0L) {
                    exoPlayer.volume = 1f
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_BUFFERING -> {
                        isBuffering = true
                    }
                    Player.STATE_READY -> {
                        hasReachedReady = true
                        hasReachedReadyFlag.set(true)
                        isBuffering = false
                        hasError = false
                        isUnknownHostError = false
                        isContainerError = false
                        errorMessage = null
                        if (overrideTestUri == null) {
                            val working = probedCandidate
                                ?: streamCandidates.getOrElse(candidateIndex) { streamCandidates.first() }
                            StreamAccelerator.recordWorkingStream(
                                rawChannelUrl = channel.streamUrl,
                                workingCandidateUrl = working.url,
                                formatKind = working.formatKind,
                                userAgent = working.userAgent
                            )
                        }

                        val liveBroadcast = isCurrentStreamLiveBroadcast()
                        val seekable = exoPlayer.isCurrentMediaItemSeekable
                        val rawDur = exoPlayer.duration
                        val validDur = if (rawDur > 0 && rawDur != C.TIME_UNSET) rawDur else 0L

                        if (!liveBroadcast && seekable && validDur >= 15_000L) {
                            vodSnapshot.durationMs = validDur
                            if (pendingResumeSeekMs >= 3_000L) {
                                val target = pendingResumeSeekMs.coerceAtMost((validDur - 3_000L).coerceAtLeast(0L))
                                pendingResumeSeekMs = 0L
                                if (target >= 3_000L) {
                                    resumeBannerPositionMs = target
                                    showResumeBanner = target >= 5_000L
                                    exoPlayer.seekTo(target)
                                    currentPositionMs = target
                                    vodSnapshot.positionMs = target
                                }
                            }
                        } else {
                            pendingResumeSeekMs = 0L
                            showResumeBanner = false
                            latestOnSaveProgress(0L, 0L, true)
                        }
                        exoPlayer.volume = 1f
                    }
                    Player.STATE_ENDED -> {
                        exoPlayer.volume = 1f
                        if (overrideTestUri == null && isCurrentStreamLiveBroadcast()) {
                            // Seamless in-place continuation if an IPTV live server closes a chunk
                            // or emits #EXT-X-ENDLIST at ~60s (preserves rendered frame without black flash).
                            liveRedirectSession.clearRedirectCache()
                            hasError = false
                            exoPlayer.seekToDefaultPosition()
                            exoPlayer.prepare()
                            exoPlayer.play()
                        } else {
                            isBuffering = false
                            vodSnapshot.positionMs = 0L
                            latestOnResetProgress()
                        }
                    }
                    Player.STATE_IDLE -> {
                        exoPlayer.volume = 1f
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                exoPlayer.volume = 1f

                if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    liveRedirectSession.clearRedirectCache()
                    hasError = false
                    if (!hasRenderedFirstFrame) {
                        isBuffering = true
                    }
                    exoPlayer.seekToDefaultPosition()
                    exoPlayer.prepare()
                    exoPlayer.play()
                    return
                }

                if (vodSnapshot.durationMs >= 15_000L &&
                    vodSnapshot.positionMs >= 3_000L &&
                    pendingResumeSeekMs == 0L
                ) {
                    pendingResumeSeekMs = vodSnapshot.positionMs
                }

                // Self-heal hardware MediaCodec reclamation ("Released by resource manager") or decoder failures
                if (isCodecResourceOrDecoderError(error)) {
                    preferSoftwareDecoderAfterReclaim.set(true)
                    CONTENDED_HW_CODEC_RECLAIMED.set(true)

                    val isStoppedInBg = !lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) &&
                        !latestInPipMode
                    if (isStoppedInBg) {
                        hasError = false
                        if (latestBackgroundPlayEnabled && shouldAutoPlay) {
                            // Continue seamless audio-only playback without holding a background video MediaCodec
                            isVideoDisabledForBackground = true
                            applyVideoQualitySelection(
                                preset = latestQualityPreset,
                                pinnedTrack = null,
                                force720pCap = true,
                                videoDisabled = true,
                                compactWindow = true
                            )
                            exoPlayer.clearVideoSurface()
                            exoPlayer.stop()
                            if (isCurrentStreamLiveBroadcast()) {
                                exoPlayer.seekToDefaultPosition()
                            } else if (vodSnapshot.positionMs >= 3_000L) {
                                exoPlayer.seekTo(vodSnapshot.positionMs)
                            }
                            exoPlayer.prepare()
                            exoPlayer.play()
                        } else {
                            stoppedInBackground = true
                            exoPlayer.clearVideoSurface()
                            exoPlayer.stop()
                        }
                        return
                    }

                    if (codecRecoveries < 3) {
                        codecRecoveries++
                        didCapTo720p = true
                        selectedStreamTrack = null
                        hasError = false
                        if (!hasRenderedFirstFrame) {
                            isBuffering = true
                        }
                        applyVideoQualitySelection(
                            preset = if (latestQualityPreset == VideoQualityPreset.UHD_4K ||
                                latestQualityPreset == VideoQualityPreset.FHD_1080P
                            ) {
                                VideoQualityPreset.HD_720P
                            } else {
                                latestQualityPreset
                            },
                            pinnedTrack = null,
                            force720pCap = true,
                            videoDisabled = false,
                            compactWindow = true
                        )
                        // Tear down the reclaimed hardware codec cleanly so prepare() re-queries
                        // codecSelector and immediately binds c2.android.* software decoder.
                        exoPlayer.stop()
                        textureViewRef?.let { tv ->
                            exoPlayer.clearVideoTextureView(tv)
                            exoPlayer.setVideoTextureView(tv)
                        }
                        if (isCurrentStreamLiveBroadcast()) {
                            exoPlayer.seekToDefaultPosition()
                        } else if (vodSnapshot.positionMs >= 3_000L) {
                            exoPlayer.seekTo(vodSnapshot.positionMs)
                        }
                        exoPlayer.prepare()
                        exoPlayer.play()
                        return
                    }
                }

                val activeCandidate = probedCandidate
                    ?: streamCandidates.getOrElse(candidateIndex) { streamCandidates.first() }
                val httpStatus = error.findHttpStatusCode()
                if (httpStatus != null) {
                    extractOrigin(activeCandidate.url)?.let { liveHttpOrigins.add(it) }
                    if (lastHttpStatusCode == null || !activeCandidate.isSyntheticFallback ||
                        (lastHttpStatusCode == 404 && httpStatus != 404)
                    ) {
                        lastHttpStatusCode = httpStatus
                    }
                }

                val isContainerOrManifest =
                    error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ||
                        error.errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED ||
                        error.errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED ||
                        error.errorCodeName.contains("CONTAINER_UNSUPPORTED", ignoreCase = true) ||
                        error.errorCodeName.contains("MANIFEST_MALFORMED", ignoreCase = true) ||
                        error.message?.contains("None of the available extractors", ignoreCase = true) == true ||
                        error.message?.contains("#EXTM3U", ignoreCase = true) == true

                val isDnsError = error.hasDnsError()
                val isSslError = error.hasSslError()
                val isConnectRefused = error.hasConnectRefusedError()
                val isTimeout = error.hasTimeoutError() ||
                    error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT

                // Recover from HTTP 416 Range Not Satisfiable on progressive VOD seeks
                if (overrideTestUri == null && httpStatus == 416 && pendingResumeSeekMs > 0L) {
                    pendingResumeSeekMs = 0L
                    vodSnapshot.positionMs = 0L
                    hasError = false
                    isBuffering = true
                    lifecycleSessionKey++
                    return
                }

                // Once a live channel has ALREADY played (hasReachedReady == true), never tear down
                // LaunchedEffect or switch away from the working stream candidate on mid-stream drops;
                // recover seamlessly in-place while keeping the current video frame on screen.
                if (overrideTestUri == null &&
                    hasReachedReady &&
                    isCurrentStreamLiveBroadcast() &&
                    !isContainerOrManifest &&
                    inPlaceLiveRecoveries < 4
                ) {
                    inPlaceLiveRecoveries++
                    liveRedirectSession.clearRedirectCache()
                    hasError = false
                    if (!hasRenderedFirstFrame) {
                        isBuffering = true
                    }
                    exoPlayer.seekToDefaultPosition()
                    exoPlayer.prepare()
                    exoPlayer.play()
                    return
                }

                // Multi-stage automatic failover across HLS, MPEG-TS, DASH, ports, HTTP/HTTPS, and User-Agents
                if (overrideTestUri == null && probedCandidate == null) {
                    val activeOrigin = extractOrigin(activeCandidate.url)
                    val activeHost = CloudflareDns.extractHostFromUrl(activeCandidate.url)?.lowercase()

                    if (isDnsError && activeHost != null) {
                        failedHosts.add(activeHost)
                    } else if (isSslError && activeOrigin != null) {
                        failedOrigins.add(activeOrigin)
                        if (activeOrigin.startsWith("https://")) {
                            SSL_FAILED_ORIGINS.add(activeOrigin)
                        }
                    } else if (isConnectRefused && activeOrigin != null) {
                        failedOrigins.add(activeOrigin)
                    } else if (isTimeout) {
                        failedUrlFormats.add(activeCandidate.url to activeCandidate.formatKind)
                    }

                    if (httpStatus != null) {
                        if (httpStatus in setOf(400, 404, 405, 410) || httpStatus in 500..599) {
                            // Scope 404 to (url, userAgent) so alternate User-Agents + Referer still test
                            // servers that stealth-block specific User-Agents with HTTP 404!
                            failedUrlUserAgents.add(activeCandidate.url to activeCandidate.userAgent)
                        }
                    }

                    if (isContainerOrManifest) {
                        failedUrlFormats.add(activeCandidate.url to activeCandidate.formatKind)
                    }

                    val nextIdx = pickNextCandidateIndex(candidateIndex, activeOrigin)
                    if (nextIdx != null) {
                        hasError = false
                        isBuffering = true
                        candidateIndex = nextIdx
                        return
                    }
                }

                val isNetworkOrDns = isDnsError || isConnectRefused || isTimeout ||
                    error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                    error.errorCodeName.contains("NETWORK", ignoreCase = true)

                val statusCode = httpStatus ?: lastHttpStatusCode
                val computedMessage = when {
                    statusCode == 401 || statusCode == 403 ->
                        "Sunucu erişimi reddetti (HTTP $statusCode). Abonelik süresi dolmuş, hesap başka cihazda aktif veya yayın erişime kapalı olabilir."
                    statusCode == 404 || statusCode == 410 ->
                        "Yayın akışı sunucuda bulunamadı (HTTP $statusCode). Kanal geçici olarak çevrimdışı veya yayın adresi değişmiş olabilir."
                    statusCode == 429 ->
                        "Çok fazla bağlantı isteği (HTTP 429). Lütfen birkaç saniye bekleyip yeniden deneyin."
                    statusCode != null && statusCode in 500..599 ->
                        "Yayın sunucusunda geçici arıza (HTTP $statusCode). Sunucu şu anda yanıt veremiyor."
                    statusCode != null ->
                        "Sunucu yanıt hatası (HTTP $statusCode - ${error.errorCodeName}). Yayın şu anda kullanılamıyor."
                    isContainerOrManifest ->
                        "Yayın formatı desteklenmiyor (${error.errorCodeName}). Kaynak sunucu geçerli bir video akışı döndürmedi."
                    isNetworkOrDns ->
                        "Yayın sunucusuna ulaşılamıyor. İnternet bağlantısı kesilmiş veya yayın çevrimdışı olabilir."
                    else ->
                        "Yayın oynatılamadı (${error.errorCodeName})."
                }

                // Deep StreamProbe Rescue before displaying any error (extracts binary TS/MP4,
                // BOM-prefixed HLS, DASH, or HTML/iframe embedded .m3u8 streams)
                if (overrideTestUri == null && !didRunDeepProbe && !isDnsError) {
                    didRunDeepProbe = true
                    pendingFinalErrorMessage = computedMessage
                    hasError = false
                    isBuffering = true
                    isRunningDeepProbe = true
                    return
                }

                hasError = true
                isBuffering = false
                isContainerError = isContainerOrManifest && lastHttpStatusCode == null
                isUnknownHostError = isNetworkOrDns && lastHttpStatusCode == null
                errorMessage = computedMessage
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
                latestOnPlayingStateChanged(playing)
                if (playing) {
                    shouldAutoPlay = true
                    isBuffering = false
                } else if (hasReachedReady && !exoPlayer.playWhenReady) {
                    shouldAutoPlay = false
                    persistCurrentState()
                }
            }
        }

        exoPlayer.addListener(listener)

        onDispose {
            if (vodSnapshot.durationMs >= 15_000L && vodSnapshot.positionMs >= 3_000L) {
                channelSaveCallback(vodSnapshot.positionMs, vodSnapshot.durationMs, false)
            }
            exoPlayer.removeListener(listener)
        }
    }

    fun seekRelative(deltaMs: Long) {
        val current = exoPlayer.currentPosition
        val dur = exoPlayer.duration
        val target = if (dur > 0 && dur != C.TIME_UNSET) {
            (current + deltaMs).coerceIn(0L, dur)
        } else {
            (current + deltaMs).coerceAtLeast(0L)
        }
        exoPlayer.seekTo(target)
        currentPositionMs = target
        if (dur >= 15_000L && dur != C.TIME_UNSET && !exoPlayer.isCurrentMediaItemLive) {
            vodSnapshot.positionMs = target
            vodSnapshot.durationMs = dur
        }
        persistCurrentState()
        if (deltaMs < 0) {
            showRewindFeedback = true
        } else {
            showForwardFeedback = true
        }
        areControlsVisible = true
    }

    LaunchedEffect(showResumeBanner) {
        if (showResumeBanner) {
            delay(4500)
            showResumeBanner = false
        }
    }

    LaunchedEffect(showRewindFeedback) {
        if (showRewindFeedback) {
            delay(850)
            showRewindFeedback = false
        }
    }

    LaunchedEffect(showForwardFeedback) {
        if (showForwardFeedback) {
            delay(850)
            showForwardFeedback = false
        }
    }

    // Playback telemetry, periodic VOD save, and real-time Frozen-Video Watchdog
    LaunchedEffect(
        exoPlayer,
        channel.id,
        channel.streamUrl,
        candidateIndex,
        probedCandidate,
        overrideTestUri,
        lifecycleSessionKey
    ) {
        var tickCount = 0
        var lastRenderedFrames = -1
        var lastDroppedFrames = 0
        var frozenVideoTicks = 0
        var healthyPlayingTicks = 0
        var midStreamRebufferTicks = 0

        while (true) {
            val state = exoPlayer.playbackState
            val activelyPlaying = exoPlayer.isPlaying && state == Player.STATE_READY

            if (activelyPlaying) {
                isBuffering = false
                midStreamRebufferTicks = 0
                if (!hasRenderedFirstFrame &&
                    exoPlayer.videoSize.width > 0 &&
                    exoPlayer.videoSize.height > 0
                ) {
                    hasRenderedFirstFrame = true
                }
            } else if (state == Player.STATE_BUFFERING && !hasError) {
                isBuffering = true
                if (hasReachedReady && shouldAutoPlay && pendingResumeSeekMs == 0L) {
                    midStreamRebufferTicks++
                    // Proactive anti-freeze step-down: if network stalls >= 2s in AUTO mode,
                    // lower bitrate cap one tier (720p -> 480p -> 360p) so playback resumes immediately!
                    if (midStreamRebufferTicks >= 4 &&
                        latestTurboStreamEnabled &&
                        qualityPreset == VideoQualityPreset.AUTO &&
                        selectedStreamTrack == null &&
                        stallCapStage != StallCapStage.CAP_360P
                    ) {
                        val multiTrackAvailable = exoPlayer.currentTracks.groups
                            .filter { it.type == C.TRACK_TYPE_VIDEO }
                            .sumOf { it.length } > 1
                        if (multiTrackAvailable) {
                            stallCapStage = stallCapStage.stepDown()
                        }
                        midStreamRebufferTicks = 0
                    }
                }
            }

            val dur = exoPlayer.duration
            val validDur = if (dur > 0 && dur != C.TIME_UNSET) dur else 0L
            val liveBroadcast = isCurrentStreamLiveBroadcast()
            durationMs = if (liveBroadcast) 0L else validDur
            isSeekable = exoPlayer.isCurrentMediaItemSeekable
            isLiveStream = liveBroadcast

            if (!isScrubbing && pendingResumeSeekMs == 0L) {
                val pos = exoPlayer.currentPosition.coerceAtLeast(0L)
                currentPositionMs = pos
                if (hasReachedReady && !liveBroadcast && isSeekable && validDur >= 15_000L) {
                    vodSnapshot.positionMs = pos
                    vodSnapshot.durationMs = validDur
                }
            }

            // Real-time Frozen-Video Watchdog: detects when audio is playing (activelyPlaying)
            // while video decoder drops frames heavily or renders 0 new video frames.
            if (activelyPlaying &&
                hasVideoTrack &&
                !isVideoDisabledForBackground &&
                !hasError &&
                pendingResumeSeekMs == 0L
            ) {
                val counters = exoPlayer.videoDecoderCounters
                if (counters != null) {
                    val rendered = counters.renderedOutputBufferCount
                    val dropped = counters.droppedBufferCount

                    if (rendered > 0 && !hasRenderedFirstFrame) {
                        hasRenderedFirstFrame = true
                    }

                    // Only step down adaptive resolution in AUTO mode if multiple video tracks exist
                    if (!didCapTo720p &&
                        qualityPreset == VideoQualityPreset.AUTO &&
                        selectedStreamTrack == null &&
                        (dropped - lastDroppedFrames) >= 24
                    ) {
                        val videoFormatsCount = exoPlayer.currentTracks.groups
                            .filter { it.type == C.TRACK_TYPE_VIDEO }
                            .sumOf { it.length }
                        if (videoFormatsCount > 1) {
                            didCapTo720p = true
                        }
                    }
                    lastDroppedFrames = dropped

                    if (lastRenderedFrames != -1 && rendered == lastRenderedFrames) {
                        frozenVideoTicks++
                        healthyPlayingTicks = 0
                    } else {
                        frozenVideoTicks = 0
                        if (rendered > 0) {
                            healthyPlayingTicks++
                            if (healthyPlayingTicks >= 20) {
                                // 10+ seconds of uninterrupted rendering resets recovery counters
                                inPlaceLiveRecoveries = 0
                                stallRecoveryAttempt = 0
                                codecRecoveries = 0
                            }
                        }
                    }
                    lastRenderedFrames = rendered

                    // Initial black screen (rendered == 0): recover after 4s (8 ticks).
                    // Mid-stream decoder stall (rendered > 0): require 8s (16 ticks) so static TV bumpers never false-trigger!
                    val freezeThresholdTicks = if (rendered == 0) 8 else 16
                    if (frozenVideoTicks >= freezeThresholdTicks) {
                        frozenVideoTicks = 0
                        lastRenderedFrames = -1

                        if (stallRecoveryAttempt == 0) {
                            stallRecoveryAttempt = 1
                            // Flush video renderer & re-bind TextureView surface to re-align A/V clock
                            textureViewRef?.let { tv ->
                                exoPlayer.clearVideoTextureView(tv)
                                exoPlayer.setVideoTextureView(tv)
                            }
                            if (liveBroadcast) {
                                exoPlayer.seekToDefaultPosition()
                            } else {
                                exoPlayer.seekTo(exoPlayer.currentPosition.coerceAtLeast(0L))
                            }
                        } else if (stallRecoveryAttempt == 1 && rendered == 0) {
                            stallRecoveryAttempt = 2
                            // Only do a full session restart if video NEVER rendered a single frame
                            if (vodSnapshot.durationMs >= 15_000L && vodSnapshot.positionMs >= 3_000L) {
                                pendingResumeSeekMs = vodSnapshot.positionMs
                            }
                            lifecycleSessionKey++
                        }
                    }
                }
            } else {
                frozenVideoTicks = 0
                if (activelyPlaying && !hasVideoTrack) {
                    healthyPlayingTicks++
                    if (healthyPlayingTicks >= 20) {
                        inPlaceLiveRecoveries = 0
                    }
                } else {
                    healthyPlayingTicks = 0
                }
            }

            tickCount++
            if (activelyPlaying && !liveBroadcast && tickCount % 4 == 0) {
                persistCurrentState()
            }
            delay(500)
        }
    }

    // Auto-hide controls after 4 seconds
    LaunchedEffect(areControlsVisible, isPlaying, isQualitySheetOpen) {
        if (areControlsVisible && isPlaying && !hasError && !isQualitySheetOpen) {
            delay(4000)
            areControlsVisible = false
        }
    }

    // Auto-hide mini-player overlay after 3.5 seconds while playing
    LaunchedEffect(areMiniControlsVisible, isPlaying, isMiniPlayer) {
        if (isMiniPlayer && areMiniControlsVisible && isPlaying && !hasError) {
            delay(3500)
            areMiniControlsVisible = false
        }
    }

    LaunchedEffect(isMiniPlayer) {
        if (isMiniPlayer) {
            areMiniControlsVisible = true
            isQualitySheetOpen = false
        }
    }

    val activeQualityBadge = remember(qualityPreset, selectedStreamTrack, activeVideoWidth, activeVideoHeight) {
        formatActiveQualityBadge(qualityPreset, selectedStreamTrack, activeVideoWidth, activeVideoHeight)
    }

    val boxModifier = when {
        isInPipMode || isFullscreen -> {
            modifier
                .fillMaxSize()
                .background(Color.Black)
                .testTag(if (isInPipMode) "video_player_pip" else "video_player_fullscreen")
        }
        isMiniPlayer -> {
            modifier
                .aspectRatio(16f / 9f)
                .background(Color.Black)
                .testTag("video_player_mini")
        }
        else -> {
            modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(Color.Black)
                .testTag("video_player_embedded")
        }
    }

    Box(
        modifier = boxModifier
            .onPreviewKeyEvent { keyEvent ->
                if (keyEvent.type != KeyEventType.KeyDown || isInPipMode) {
                    return@onPreviewKeyEvent false
                }
                when (keyEvent.nativeKeyEvent.keyCode) {
                    KeyEvent.KEYCODE_CHANNEL_UP,
                    KeyEvent.KEYCODE_MEDIA_NEXT -> {
                        areControlsVisible = true
                        onNextChannel()
                        true
                    }
                    KeyEvent.KEYCODE_CHANNEL_DOWN,
                    KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                        areControlsVisible = true
                        onPreviousChannel()
                        true
                    }
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                        areControlsVisible = true
                        if (exoPlayer.isPlaying) {
                            shouldAutoPlay = false
                            exoPlayer.pause()
                        } else {
                            shouldAutoPlay = true
                            exoPlayer.play()
                        }
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_CENTER,
                    KeyEvent.KEYCODE_ENTER -> {
                        if (!areControlsVisible && !isMiniPlayer) {
                            areControlsVisible = true
                            true
                        } else {
                            false
                        }
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        if (!areControlsVisible && isSeekable && !isMiniPlayer) {
                            seekRelative(-10_000L)
                            true
                        } else {
                            false
                        }
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        if (!areControlsVisible && isSeekable && !isMiniPlayer) {
                            seekRelative(10_000L)
                            true
                        } else {
                            false
                        }
                    }
                    else -> false
                }
            }
            .pointerInput(exoPlayer, isMiniPlayer, isInPipMode) {
                detectTapGestures(
                    onTap = {
                        if (isInPipMode) return@detectTapGestures
                        if (isMiniPlayer) {
                            areMiniControlsVisible = !areMiniControlsVisible
                        } else {
                            areControlsVisible = !areControlsVisible
                        }
                    },
                    onDoubleTap = { offset ->
                        if (isInPipMode) return@detectTapGestures
                        if (isMiniPlayer) {
                            onExitMiniPlayer()
                            return@detectTapGestures
                        }
                        val w = size.width
                        if (offset.x < w * 0.38f) {
                            seekRelative(-10_000L)
                        } else if (offset.x > w * 0.62f) {
                            seekRelative(10_000L)
                        } else {
                            if (exoPlayer.isPlaying) {
                                shouldAutoPlay = false
                                exoPlayer.pause()
                            } else {
                                shouldAutoPlay = true
                                exoPlayer.play()
                            }
                        }
                    }
                )
            }
    ) {
        // Persistent TextureView bound to the single ExoPlayer instance
        AndroidView(
            factory = { ctx ->
                FrameLayout(ctx).apply {
                    keepScreenOn = true
                    setBackgroundColor(android.graphics.Color.BLACK)
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    val aspectLayout = AspectRatioFrameLayout(ctx).apply {
                        layoutParams = FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            Gravity.CENTER
                        )
                        resizeMode = resizeModes[resizeModeIndex]
                        setAspectRatio(videoAspectRatio)
                    }
                    val textureView = TextureView(ctx).apply {
                        layoutParams = FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            Gravity.CENTER
                        )
                    }
                    aspectLayout.addView(textureView)
                    addView(aspectLayout)
                    textureViewRef = textureView
                    exoPlayer.setVideoTextureView(textureView)
                }
            },
            update = { rootFrame ->
                val aspectLayout = rootFrame.getChildAt(0) as? AspectRatioFrameLayout
                if (aspectLayout != null) {
                    if (aspectLayout.resizeMode != resizeModes[resizeModeIndex]) {
                        aspectLayout.resizeMode = resizeModes[resizeModeIndex]
                    }
                    aspectLayout.setAspectRatio(videoAspectRatio)
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // Shutter mask hides stale frame from previous channel while first video frame loads
        if (!hasRenderedFirstFrame && !hasError) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
            )
        }

        // Loading Spinner
        if (isBuffering && !hasError) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.35f)),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    color = SoykenGold,
                    strokeWidth = 3.dp,
                    modifier = Modifier.size(48.dp)
                )
            }
        }

        // Error Overlay
        if (hasError) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.92f))
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(50.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF2C2515)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = when {
                                isContainerError -> Icons.Default.Extension
                                isUnknownHostError -> Icons.Default.WifiOff
                                else -> Icons.Default.LiveTv
                            },
                            contentDescription = null,
                            tint = SoykenGold,
                            modifier = Modifier.size(28.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        text = when {
                            isContainerError -> "Format Uyumsuzluğu"
                            isUnknownHostError -> "Ağ Bağlantısı Hatası"
                            else -> "Yayın Yüklenemedi"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = errorMessage ?: "Yayın bağlantısı kurulamadı.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.LightGray,
                        textAlign = TextAlign.Center,
                        maxLines = 3,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (isContainerError) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(SoykenGold)
                                    .clickable {
                                        SSL_FAILED_ORIGINS.clear()
                                        failedOrigins.clear()
                                        failedHosts.clear()
                                        liveHttpOrigins.clear()
                                        failedUrlUserAgents.clear()
                                        failedUrlFormats.clear()
                                        lastHttpStatusCode = null
                                        overrideTestUri = null
                                        probedCandidate = null
                                        didRunDeepProbe = false
                                        isRunningDeepProbe = false
                                        candidateIndex = 0
                                        hasError = false
                                        isBuffering = true
                                        shouldAutoPlay = true
                                        stallRecoveryAttempt = 0
                                        lifecycleSessionKey++
                                    }
                                    .padding(horizontal = 12.dp, vertical = 8.dp)
                                    .testTag("force_hls_button"),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "HLS (M3U8) Akışını Yeniden Başlat",
                                    color = Color.Black,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp
                                )
                            }
                        } else {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .clickable {
                                        SSL_FAILED_ORIGINS.clear()
                                        failedOrigins.clear()
                                        failedHosts.clear()
                                        liveHttpOrigins.clear()
                                        failedUrlUserAgents.clear()
                                        failedUrlFormats.clear()
                                        lastHttpStatusCode = null
                                        overrideTestUri = null
                                        probedCandidate = null
                                        didRunDeepProbe = false
                                        isRunningDeepProbe = false
                                        candidateIndex = 0
                                        hasError = false
                                        isBuffering = true
                                        shouldAutoPlay = true
                                        stallRecoveryAttempt = 0
                                        lifecycleSessionKey++
                                    }
                                    .padding(horizontal = 12.dp, vertical = 8.dp)
                                    .testTag("retry_stream_button"),
                                contentAlignment = Alignment.Center
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.Refresh,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "Yeniden Dene",
                                        color = Color.White,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 12.sp
                                    )
                                }
                            }
                        }

                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isContainerError) MaterialTheme.colorScheme.surfaceVariant else SoykenGold)
                                .clickable {
                                    hasError = false
                                    isBuffering = true
                                    shouldAutoPlay = true
                                    lastHttpStatusCode = null
                                    overrideTestUri = DemoPlaylists.getOfflineTestUri(context)
                                    lifecycleSessionKey++
                                }
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                                .testTag("play_offline_test_stream_button"),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.PlayArrow,
                                    contentDescription = null,
                                    tint = if (isContainerError) Color.White else Color.Black,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "Çevrimdışı Test Yayını",
                                    color = if (isContainerError) Color.White else Color.Black,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp
                                )
                            }
                        }

                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color.Black.copy(alpha = 0.72f))
                                .border(1.dp, SoykenGold.copy(alpha = 0.75f), RoundedCornerShape(8.dp))
                                .clickable {
                                    onReportIssue(errorMessage ?: "Yayın bağlantısı kurulamadı.")
                                }
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                                .testTag("error_report_issue_button"),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Outlined.Flag,
                                    contentDescription = null,
                                    tint = SoykenGold,
                                    modifier = Modifier.size(15.dp)
                                )
                                Spacer(modifier = Modifier.width(5.dp))
                                Text(
                                    text = "Sorun Bildir",
                                    color = SoykenGold,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }
                }
            }
        }

        // Resume Playback Notification Pill
        AnimatedVisibility(
            visible = showResumeBanner && !hasError && !isLiveStream && !isMiniPlayer && !isInPipMode,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 52.dp, start = 12.dp, end = 12.dp)
        ) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color.Black.copy(alpha = 0.82f))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .testTag("resume_playback_banner"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Kaldığı yerden devam ediyor • ${formatDuration(resumeBannerPositionMs)}",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = SoykenGold
                )

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(SoykenGold)
                        .clickable {
                            pendingResumeSeekMs = 0L
                            vodSnapshot.positionMs = 0L
                            exoPlayer.seekTo(0L)
                            currentPositionMs = 0L
                            latestOnResetProgress()
                            showResumeBanner = false
                        }
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                        .testTag("restart_from_beginning_button"),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Baştan Başlat",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        ),
                        color = Color.Black
                    )
                }
            }
        }

        // In-App Floating Mini-Player Controls Overlay
        AnimatedVisibility(
            visible = isMiniPlayer && !isInPipMode && !hasError && areMiniControlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Black.copy(alpha = 0.78f),
                                Color.Black.copy(alpha = 0.25f),
                                Color.Black.copy(alpha = 0.82f)
                            )
                        )
                    )
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            ) {
                // Mini Top Bar: Live dot + Channel Name + Expand + Close
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(LiveRed)
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = channel.name,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp
                            ),
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.55f))
                                .clickable { onExitMiniPlayer() }
                                .testTag("mini_expand_button"),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.OpenInFull,
                                contentDescription = "Oynatıcıyı Büyüt",
                                tint = SoykenGold,
                                modifier = Modifier.size(15.dp)
                            )
                        }

                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.55f))
                                .clickable { onClose() }
                                .testTag("mini_close_button"),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Mini Oynatıcıyı Kapat",
                                tint = Color.White,
                                modifier = Modifier.size(15.dp)
                            )
                        }
                    }
                }

                // Mini Center Playback Controls
                Row(
                    modifier = Modifier.align(Alignment.Center),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.5f))
                            .clickable { onPreviousChannel() }
                            .testTag("mini_prev_button"),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.SkipPrevious,
                            contentDescription = "Önceki Kanal",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(SoykenGold)
                            .clickable {
                                if (isPlaying) {
                                    shouldAutoPlay = false
                                    exoPlayer.pause()
                                } else {
                                    shouldAutoPlay = true
                                    exoPlayer.play()
                                }
                            }
                            .testTag("mini_play_pause_button"),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) "Duraklat" else "Oynat",
                            tint = Color.Black,
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.5f))
                            .clickable { onNextChannel() }
                            .testTag("mini_next_button"),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.SkipNext,
                            contentDescription = "Sonraki Kanal",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                // Mini Bottom Row: Active Quality Badge + System PiP Trigger
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(SoykenGold.copy(alpha = 0.22f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = activeQualityBadge,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold
                            ),
                            color = SoykenGold
                        )
                    }

                    if (isPipSupported) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color.Black.copy(alpha = 0.55f))
                                .clickable { onRequestPipMode() }
                                .padding(horizontal = 6.dp, vertical = 3.dp)
                                .testTag("mini_pip_button"),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.OpenInNew,
                                    contentDescription = "Ekran Üstü PiP",
                                    tint = SoykenGold,
                                    modifier = Modifier.size(12.dp)
                                )
                                Spacer(modifier = Modifier.width(3.dp))
                                Text(
                                    text = "PiP",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold
                                    ),
                                    color = SoykenGold
                                )
                            }
                        }
                    }
                }
            }
        }

        // Standard & Fullscreen Overlay Controls
        AnimatedVisibility(
            visible = areControlsVisible && !isMiniPlayer && !isInPipMode,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Black.copy(alpha = 0.75f),
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.85f)
                            )
                        )
                    )
            ) {
                // Top Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(LiveRed)
                                .padding(horizontal = 7.dp, vertical = 3.dp)
                        ) {
                            Text(
                                text = "CANLI",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                ),
                                color = Color.White
                            )
                        }

                        Spacer(modifier = Modifier.width(6.dp))

                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(SoykenGold.copy(alpha = 0.2f))
                                .padding(horizontal = 6.dp, vertical = 3.dp)
                        ) {
                            Text(
                                text = "HLS • M3U8",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold
                                ),
                                color = SoykenGold
                            )
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        Column {
                            Text(
                                text = channel.name,
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = channel.category,
                                style = MaterialTheme.typography.labelSmall,
                                color = SoykenGold
                            )
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = onEnterMiniPlayer,
                            modifier = Modifier.testTag("mini_player_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.PictureInPictureAlt,
                                contentDescription = "Mini Oynatıcıya Küçült",
                                tint = SoykenGold
                            )
                        }

                        if (isPipSupported) {
                            IconButton(
                                onClick = onRequestPipMode,
                                modifier = Modifier.testTag("pip_mode_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.OpenInNew,
                                    contentDescription = "Arka Planda Mini Pencere (PiP)",
                                    tint = Color.White
                                )
                            }
                        }

                        IconButton(
                            onClick = {
                                resizeModeIndex = (resizeModeIndex + 1) % resizeModes.size
                            },
                            modifier = Modifier.testTag("aspect_ratio_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.AspectRatio,
                                contentDescription = "En Boy Oranı: ${resizeLabels[resizeModeIndex]}",
                                tint = Color.White
                            )
                        }

                        IconButton(
                            onClick = onClose,
                            modifier = Modifier.testTag("close_player_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Oynatıcıyı Kapat",
                                tint = Color.White
                            )
                        }
                    }
                }

                // Center Playback Controls
                Row(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    IconButton(
                        onClick = onPreviousChannel,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("prev_channel_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.SkipPrevious,
                            contentDescription = "Önceki Kanal",
                            tint = Color.White,
                            modifier = Modifier.size(28.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    IconButton(
                        onClick = { seekRelative(-10_000L) },
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.45f))
                            .testTag("rewind_10s_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Replay10,
                            contentDescription = "10 Saniye Geri Sar",
                            tint = Color.White,
                            modifier = Modifier.size(28.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(SoykenGold)
                            .clickable {
                                if (isPlaying) {
                                    shouldAutoPlay = false
                                    exoPlayer.pause()
                                } else {
                                    shouldAutoPlay = true
                                    exoPlayer.play()
                                }
                            }
                            .testTag("play_pause_button"),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) "Duraklat" else "Oynat",
                            tint = Color.Black,
                            modifier = Modifier.size(32.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    IconButton(
                        onClick = { seekRelative(10_000L) },
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.45f))
                            .testTag("forward_10s_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Forward10,
                            contentDescription = "10 Saniye İleri Sar",
                            tint = Color.White,
                            modifier = Modifier.size(28.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    IconButton(
                        onClick = onNextChannel,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("next_channel_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.SkipNext,
                            contentDescription = "Sonraki Kanal",
                            tint = Color.White,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }

                // Left Animated Rewind Feedback Badge
                AnimatedVisibility(
                    visible = showRewindFeedback,
                    enter = fadeIn() + scaleIn(),
                    exit = fadeOut() + scaleOut(),
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 28.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.7f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Default.Replay10,
                                contentDescription = null,
                                tint = SoykenGold,
                                modifier = Modifier.size(28.dp)
                            )
                            Text(
                                text = "-10sn",
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                // Right Animated Forward Feedback Badge
                AnimatedVisibility(
                    visible = showForwardFeedback,
                    enter = fadeIn() + scaleIn(),
                    exit = fadeOut() + scaleOut(),
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 28.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.7f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Default.Forward10,
                                contentDescription = null,
                                tint = SoykenGold,
                                modifier = Modifier.size(28.dp)
                            )
                            Text(
                                text = "+10sn",
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                // Bottom Controls
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    if (durationMs > 0L) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = formatDuration(if (isScrubbing) scrubPositionMs else currentPositionMs),
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold
                                ),
                                color = Color.White
                            )

                            Slider(
                                value = (if (isScrubbing) scrubPositionMs else currentPositionMs)
                                    .toFloat()
                                    .coerceIn(0f, durationMs.toFloat()),
                                onValueChange = {
                                    isScrubbing = true
                                    scrubPositionMs = it.toLong()
                                },
                                onValueChangeFinished = {
                                    pendingResumeSeekMs = 0L
                                    exoPlayer.seekTo(scrubPositionMs)
                                    currentPositionMs = scrubPositionMs
                                    if (!isLiveStream && durationMs >= 15_000L) {
                                        vodSnapshot.positionMs = scrubPositionMs
                                        vodSnapshot.durationMs = durationMs
                                    }
                                    isScrubbing = false
                                    persistCurrentState()
                                },
                                valueRange = 0f..durationMs.toFloat(),
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 6.dp)
                                    .testTag("player_timeline_slider"),
                                colors = SliderDefaults.colors(
                                    thumbColor = SoykenGold,
                                    activeTrackColor = SoykenGold,
                                    inactiveTrackColor = Color.White.copy(alpha = 0.28f)
                                )
                            )

                            Text(
                                text = formatDuration(durationMs),
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold
                                ),
                                color = Color.LightGray
                            )
                        }
                    } else {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(7.dp)
                                        .clip(CircleShape)
                                        .background(LiveRed)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "CANLI YAYIN",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold
                                    ),
                                    color = LiveRed
                                )
                            }

                            if (isSeekable) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(Color.White.copy(alpha = 0.2f))
                                            .clickable { seekRelative(-30_000L) }
                                            .padding(horizontal = 8.dp, vertical = 3.dp)
                                            .testTag("live_rewind_30s_button"),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "-30s",
                                            color = Color.White,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }

                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(SoykenGold)
                                            .clickable {
                                                exoPlayer.seekToDefaultPosition()
                                                areControlsVisible = true
                                            }
                                            .padding(horizontal = 8.dp, vertical = 3.dp)
                                            .testTag("jump_to_live_button"),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "Canlı",
                                            color = Color.Black,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Görünüm: ${resizeLabels[resizeModeIndex]}",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.LightGray
                        )

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            // Report Issue (Sorun Bildir) Pill Button
                            Box(
                                modifier = Modifier
                                    .height(34.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color.Black.copy(alpha = 0.62f))
                                    .border(1.dp, SoykenGold.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
                                    .clickable {
                                        areControlsVisible = true
                                        onReportIssue(if (hasError) errorMessage else null)
                                    }
                                    .padding(horizontal = 10.dp)
                                    .testTag("player_report_issue_button"),
                                contentAlignment = Alignment.Center
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Outlined.Flag,
                                        contentDescription = "Sorun Bildir",
                                        tint = SoykenGold,
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "Sorun Bildir",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold
                                        ),
                                        color = SoykenGold
                                    )
                                }
                            }

                            // Video Quality Selector Pill Button
                            Box(
                                modifier = Modifier
                                    .height(34.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color.Black.copy(alpha = 0.62f))
                                    .border(1.dp, SoykenGold.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
                                    .clickable {
                                        isQualitySheetOpen = true
                                        areControlsVisible = true
                                    }
                                    .padding(horizontal = 10.dp)
                                    .testTag("video_quality_button"),
                                contentAlignment = Alignment.Center
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.HighQuality,
                                        contentDescription = "Görüntü Kalitesi Ayarla",
                                        tint = SoykenGold,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(5.dp))
                                    Text(
                                        text = activeQualityBadge,
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold
                                        ),
                                        color = SoykenGold
                                    )
                                }
                            }

                            IconButton(
                                onClick = onToggleFullscreen,
                                modifier = Modifier.testTag("fullscreen_toggle_button")
                            ) {
                                Icon(
                                    imageVector = if (isFullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                                    contentDescription = if (isFullscreen) "Tam Ekrandan Çık" else "Tam Ekran Yap",
                                    tint = SoykenGold
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    VideoQualityBottomSheet(
        isOpen = isQualitySheetOpen && !isInPipMode,
        selectedPreset = qualityPreset,
        selectedStreamTrack = selectedStreamTrack,
        availableStreamTracks = availableVideoTracks,
        activeVideoWidth = activeVideoWidth,
        activeVideoHeight = activeVideoHeight,
        isBackgroundPlayEnabled = isBackgroundPlayEnabled,
        isPipSupported = isPipSupported,
        onSelectPreset = { preset ->
            if (preset == VideoQualityPreset.UHD_4K && !isPremium) {
                onOpenPremium(PremiumFeature.ULTRA_HD_4K)
            } else {
                selectedStreamTrack = null
                didCapTo720p = false
                stallCapStage = StallCapStage.NONE
                onQualityPresetChange(preset)
            }
        },
        onSelectStreamTrack = { track ->
            if (!isPremium) {
                onOpenPremium(PremiumFeature.MANUAL_STREAM_TRACKS)
            } else {
                didCapTo720p = false
                stallCapStage = StallCapStage.NONE
                selectedStreamTrack = track
            }
        },
        onToggleBackgroundPlay = onBackgroundPlayChange,
        onEnterMiniPlayer = onEnterMiniPlayer,
        onEnterPipMode = onRequestPipMode,
        onDismiss = { isQualitySheetOpen = false },
        isTurboStreamEnabled = isTurboStreamEnabled,
        onToggleTurboStream = onTurboStreamChange,
        isPremium = isPremium,
        onOpenPremium = onOpenPremium
    )
}

private fun formatDuration(millis: Long): String {
    if (millis <= 0L) return "00:00"
    val totalSeconds = millis / 1000
    val seconds = totalSeconds % 60
    val minutes = (totalSeconds / 60) % 60
    val hours = totalSeconds / 3600
    return if (hours > 0) {
        String.format("%02d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%02d:%02d", minutes, seconds)
    }
}
