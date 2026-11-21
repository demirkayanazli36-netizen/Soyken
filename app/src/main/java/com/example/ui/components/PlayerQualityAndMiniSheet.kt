package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Tracks
import com.example.billing.PremiumFeature
import com.example.ui.theme.SoykenGold
import com.example.ui.tv.soykenTvFocus
import kotlin.math.roundToInt

enum class VideoQualityPreset(
    val title: String,
    val subtitle: String,
    val badge: String,
    val maxWidth: Int,
    val maxHeight: Int,
    val maxBitrate: Int
) {
    AUTO(
        title = "Otomatik (Önerilen)",
        subtitle = "Bağlantı hızına göre en yüksek akıcı kaliteyi seçer",
        badge = "AUTO",
        maxWidth = 3840,
        maxHeight = 2160,
        maxBitrate = Int.MAX_VALUE
    ),
    UHD_4K(
        title = "4K Ultra HD (2160p)",
        subtitle = "Ultra yüksek çözünürlük destekleyen yayınlar için",
        badge = "4K",
        maxWidth = 3840,
        maxHeight = 2160,
        maxBitrate = Int.MAX_VALUE
    ),
    FHD_1080P(
        title = "1080p Full HD",
        subtitle = "Keskin görüntü ve dengeli veri kullanımı",
        badge = "1080p",
        maxWidth = 1920,
        maxHeight = 1080,
        maxBitrate = 12_000_000
    ),
    HD_720P(
        title = "720p HD",
        subtitle = "Orta hızdaki bağlantılar için kararlı HD akış",
        badge = "720p",
        maxWidth = 1280,
        maxHeight = 720,
        maxBitrate = 5_500_000
    ),
    SD_480P(
        title = "480p SD",
        subtitle = "Mobil veri tasarrufu ve takılmasız oynatma",
        badge = "480p",
        maxWidth = 854,
        maxHeight = 480,
        maxBitrate = 2_200_000
    ),
    LOW_360P(
        title = "360p Düşük (Veri Tasarrufu)",
        subtitle = "Zayıf internet bağlantılarında kesintisiz yayın",
        badge = "360p",
        maxWidth = 640,
        maxHeight = 360,
        maxBitrate = 1_100_000
    );

    companion object {
        fun fromNameOrDefault(raw: String?): VideoQualityPreset {
            if (raw.isNullOrBlank()) return AUTO
            return entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: AUTO
        }
    }
}

data class StreamVideoTrackOption(
    val id: String,
    val groupIndex: Int,
    val trackIndex: Int,
    val width: Int,
    val height: Int,
    val bitrate: Int,
    val frameRate: Float,
    val title: String,
    val detail: String
)

fun resolutionShortLabel(height: Int): String {
    return when {
        height >= 2160 -> "4K"
        height >= 1440 -> "1440p"
        height >= 1080 -> "1080p"
        height >= 720 -> "720p"
        height >= 540 -> "576p"
        height >= 480 -> "480p"
        height >= 360 -> "360p"
        height > 0 -> "${height}p"
        else -> ""
    }
}

fun formatActiveQualityBadge(
    preset: VideoQualityPreset,
    pinnedTrack: StreamVideoTrackOption?,
    activeVideoWidth: Int,
    activeVideoHeight: Int
): String {
    if (pinnedTrack != null) {
        val shortRes = resolutionShortLabel(pinnedTrack.height)
        return if (shortRes.isNotEmpty()) shortRes else pinnedTrack.title
    }
    val liveRes = resolutionShortLabel(activeVideoHeight)
    return if (preset == VideoQualityPreset.AUTO) {
        if (liveRes.isNotEmpty()) "AUTO • $liveRes" else "AUTO"
    } else {
        if (liveRes.isNotEmpty() && activeVideoHeight <= preset.maxHeight) {
            liveRes
        } else {
            preset.badge
        }
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
fun extractAvailableVideoTracks(tracks: Tracks): List<StreamVideoTrackOption> {
    val discovered = mutableListOf<StreamVideoTrackOption>()
    tracks.groups.forEachIndexed { groupIdx, group ->
        if (group.type != C.TRACK_TYPE_VIDEO) return@forEachIndexed
        for (trackIdx in 0 until group.length) {
            if (!group.isTrackSupported(trackIdx)) continue
            val format = group.getTrackFormat(trackIdx)
            val width = if (format.width != Format.NO_VALUE && format.width > 0) format.width else 0
            val height = if (format.height != Format.NO_VALUE && format.height > 0) format.height else 0
            val bitrate = if (format.bitrate != Format.NO_VALUE && format.bitrate > 0) format.bitrate else 0
            val fps = if (format.frameRate != Format.NO_VALUE.toFloat() && format.frameRate > 0f) {
                format.frameRate
            } else {
                0f
            }
            if (height <= 0 && bitrate <= 0) continue

            val title = when {
                height >= 2160 -> "4K Ultra HD (${height}p)"
                height >= 1440 -> "2K QHD (${height}p)"
                height >= 1080 -> "Full HD (${height}p)"
                height >= 720 -> "HD (${height}p)"
                height > 0 -> "${height}p"
                else -> "${(bitrate / 1000).coerceAtLeast(1)} Kbps"
            }

            val detailParts = buildList {
                if (width > 0 && height > 0) add("${width}×${height}")
                if (fps >= 45f) add("${fps.roundToInt()} FPS")
                if (bitrate > 0) {
                    val mbps = bitrate / 1_000_000.0
                    if (mbps >= 1.0) {
                        val rounded = (mbps * 10.0).roundToInt() / 10.0
                        add("$rounded Mbps")
                    } else {
                        add("${bitrate / 1000} Kbps")
                    }
                }
            }

            discovered.add(
                StreamVideoTrackOption(
                    id = "${groupIdx}_${trackIdx}_${height}_$bitrate",
                    groupIndex = groupIdx,
                    trackIndex = trackIdx,
                    width = width,
                    height = height,
                    bitrate = bitrate,
                    frameRate = fps,
                    title = title,
                    detail = detailParts.joinToString(" • ")
                )
            )
        }
    }

    return discovered
        .sortedWith(compareByDescending<StreamVideoTrackOption> { it.height }.thenByDescending { it.bitrate })
        .distinctBy { "${it.height}_${it.bitrate / 250_000}" }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoQualityBottomSheet(
    isOpen: Boolean,
    selectedPreset: VideoQualityPreset,
    selectedStreamTrack: StreamVideoTrackOption?,
    availableStreamTracks: List<StreamVideoTrackOption>,
    activeVideoWidth: Int,
    activeVideoHeight: Int,
    isBackgroundPlayEnabled: Boolean,
    isPipSupported: Boolean,
    onSelectPreset: (VideoQualityPreset) -> Unit,
    onSelectStreamTrack: (StreamVideoTrackOption) -> Unit,
    onToggleBackgroundPlay: (Boolean) -> Unit,
    onEnterMiniPlayer: () -> Unit,
    onEnterPipMode: () -> Unit,
    onDismiss: () -> Unit,
    isTurboStreamEnabled: Boolean = false,
    onToggleTurboStream: (Boolean) -> Unit = {},
    isPremium: Boolean = false,
    onOpenPremium: (PremiumFeature) -> Unit = {}
) {
    if (!isOpen) return

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .testTag("video_quality_sheet")
        ) {
            // Sheet Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(SoykenGold.copy(alpha = 0.16f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Tune,
                            contentDescription = null,
                            tint = SoykenGold,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "Görüntü Kalitesi & Mini Oynatıcı",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        val activeText = if (activeVideoWidth > 0 && activeVideoHeight > 0) {
                            "Aktif Çözünürlük: ${activeVideoWidth}×${activeVideoHeight} (${resolutionShortLabel(activeVideoHeight)})"
                        } else {
                            "Adaptif HLS / M3U8 Akış Kontrolü"
                        }
                        Text(
                            text = activeText,
                            style = MaterialTheme.typography.labelSmall,
                            color = SoykenGold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Quick Mini-Player & Background Section
            Text(
                text = "ARKA PLAN & MİNİ OYNATMA",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f))
                        .border(1.dp, SoykenGold.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
                        .soykenTvFocus(RoundedCornerShape(12.dp))
                        .clickable {
                            onDismiss()
                            if (isPremium) {
                                onEnterMiniPlayer()
                            } else {
                                onOpenPremium(PremiumFeature.BACKGROUND_MINI_PIP)
                            }
                        }
                        .padding(horizontal = 12.dp, vertical = 12.dp)
                        .testTag("sheet_enter_mini_player_button")
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.PictureInPictureAlt,
                            contentDescription = null,
                            tint = SoykenGold,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "Küçük Pencere",
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                if (!isPremium) {
                                    Spacer(modifier = Modifier.width(5.dp))
                                    QualityProBadge()
                                }
                            }
                            Text(
                                text = "Kanal gezerken izle",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                if (isPipSupported) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f))
                            .border(1.dp, SoykenGold.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
                            .soykenTvFocus(RoundedCornerShape(12.dp))
                            .clickable {
                                onDismiss()
                                if (isPremium) {
                                    onEnterPipMode()
                                } else {
                                    onOpenPremium(PremiumFeature.BACKGROUND_MINI_PIP)
                                }
                            }
                            .padding(horizontal = 12.dp, vertical = 12.dp)
                            .testTag("sheet_enter_pip_button")
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.OpenInNew,
                                contentDescription = null,
                                tint = SoykenGold,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = "Ekran Üstü PiP",
                                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    if (!isPremium) {
                                        Spacer(modifier = Modifier.width(5.dp))
                                        QualityProBadge()
                                    }
                                }
                                Text(
                                    text = "Ana ekranda izle",
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                    .soykenTvFocus(RoundedCornerShape(12.dp))
                    .clickable {
                        if (!isPremium && !isBackgroundPlayEnabled) {
                            onDismiss()
                            onOpenPremium(PremiumFeature.BACKGROUND_MINI_PIP)
                        } else {
                            onToggleBackgroundPlay(!isBackgroundPlayEnabled)
                        }
                    }
                    .padding(horizontal = 14.dp, vertical = 8.dp)
                    .testTag("background_play_toggle_row"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Arka Planda Mini Oynatma",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        if (!isPremium) {
                            Spacer(modifier = Modifier.width(6.dp))
                            QualityProBadge()
                        }
                    }
                    Text(
                        text = "Ana ekrana dönüldüğünde PiP mini penceresi ve arka plan sesi devam eder",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Switch(
                    checked = isPremium && isBackgroundPlayEnabled,
                    onCheckedChange = { enabled ->
                        if (enabled && !isPremium) {
                            onDismiss()
                            onOpenPremium(PremiumFeature.BACKGROUND_MINI_PIP)
                        } else {
                            onToggleBackgroundPlay(enabled)
                        }
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.Black,
                        checkedTrackColor = SoykenGold
                    )
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                    .border(
                        width = 1.dp,
                        color = if (isPremium && isTurboStreamEnabled) SoykenGold.copy(alpha = 0.45f) else Color.Transparent,
                        shape = RoundedCornerShape(12.dp)
                    )
                    .soykenTvFocus(RoundedCornerShape(12.dp))
                    .clickable {
                        if (!isPremium && !isTurboStreamEnabled) {
                            onDismiss()
                            onOpenPremium(PremiumFeature.TURBO_CACHE)
                        } else {
                            onToggleTurboStream(!isTurboStreamEnabled)
                        }
                    }
                    .padding(horizontal = 14.dp, vertical = 8.dp)
                    .testTag("turbo_stream_toggle_row"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.Bolt,
                        contentDescription = null,
                        tint = SoykenGold,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "Hızlı Bağlantı & Donma Önleyici (Turbo)",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            if (!isPremium) {
                                Spacer(modifier = Modifier.width(6.dp))
                                QualityProBadge()
                            }
                        }
                        Text(
                            text = "Düşük hızda anında açılış, 65 sn derin önbellek ve 200 MB VOD disk önbellekleme",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
                Switch(
                    checked = isPremium && isTurboStreamEnabled,
                    onCheckedChange = { enabled ->
                        if (enabled && !isPremium) {
                            onDismiss()
                            onOpenPremium(PremiumFeature.TURBO_CACHE)
                        } else {
                            onToggleTurboStream(enabled)
                        }
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.Black,
                        checkedTrackColor = SoykenGold
                    )
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
            Spacer(modifier = Modifier.height(14.dp))

            // Stream-specific HLS / DASH quality layers if multiple variants exist
            if (availableStreamTracks.size > 1) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "BU YAYINDAKİ KALİTE KATMANLARI",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.8.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (!isPremium) {
                        Spacer(modifier = Modifier.width(6.dp))
                        QualityProBadge()
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))

                availableStreamTracks.forEach { track ->
                    val isSelected = selectedStreamTrack?.id == track.id
                    QualityOptionRow(
                        title = track.title,
                        subtitle = track.detail.ifBlank { "Doğrudan yayın katmanı" },
                        badge = resolutionShortLabel(track.height).ifBlank { "HD" },
                        isSelected = isSelected,
                        isPremiumLocked = !isPremium,
                        onClick = {
                            onDismiss()
                            if (isPremium) {
                                onSelectStreamTrack(track)
                            } else {
                                onOpenPremium(PremiumFeature.MANUAL_STREAM_TRACKS)
                            }
                        },
                        testTag = "stream_track_option_${track.height}"
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                }

                Spacer(modifier = Modifier.height(10.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                Spacer(modifier = Modifier.height(12.dp))
            }

            // Global Quality Presets
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.HighQuality,
                    contentDescription = null,
                    tint = SoykenGold,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "GENEL GÖRÜNTÜ KALİTESİ SEÇİMİ",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            VideoQualityPreset.entries.forEach { preset ->
                val isSelected = selectedStreamTrack == null && selectedPreset == preset
                val isLocked = !isPremium && preset == VideoQualityPreset.UHD_4K
                QualityOptionRow(
                    title = preset.title,
                    subtitle = preset.subtitle,
                    badge = preset.badge,
                    isSelected = isSelected,
                    isPremiumLocked = isLocked,
                    onClick = {
                        onDismiss()
                        if (isLocked) {
                            onOpenPremium(PremiumFeature.ULTRA_HD_4K)
                        } else {
                            onSelectPreset(preset)
                        }
                    },
                    testTag = "quality_preset_${preset.name.lowercase()}"
                )
                Spacer(modifier = Modifier.height(6.dp))
            }

            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

@Composable
private fun QualityOptionRow(
    title: String,
    subtitle: String,
    badge: String,
    isSelected: Boolean,
    isPremiumLocked: Boolean = false,
    onClick: () -> Unit,
    testTag: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (isSelected) SoykenGold.copy(alpha = 0.14f)
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
            )
            .border(
                width = if (isSelected) 1.5.dp else 1.dp,
                color = if (isSelected) SoykenGold else Color.Transparent,
                shape = RoundedCornerShape(12.dp)
            )
            .soykenTvFocus(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            Icon(
                imageVector = if (isSelected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                contentDescription = null,
                tint = if (isSelected) SoykenGold else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold
                        ),
                        color = if (isSelected) SoykenGold else MaterialTheme.colorScheme.onSurface
                    )
                    if (isPremiumLocked) {
                        Spacer(modifier = Modifier.width(6.dp))
                        QualityProBadge()
                    }
                }
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        Box(
            modifier = Modifier
                .clip(CircleShape)
                .background(if (isSelected) SoykenGold else MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 9.dp, vertical = 4.dp)
        ) {
            Text(
                text = badge,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp
                ),
                color = if (isSelected) Color.Black else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun QualityProBadge() {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(5.dp))
            .background(SoykenGold.copy(alpha = 0.2f))
            .border(0.8.dp, SoykenGold.copy(alpha = 0.75f), RoundedCornerShape(5.dp))
            .padding(horizontal = 5.dp, vertical = 1.dp)
    ) {
        Text(
            text = "PRO",
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 9.sp,
                fontWeight = FontWeight.ExtraBold
            ),
            color = SoykenGold
        )
    }
}
