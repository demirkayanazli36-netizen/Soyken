package com.example.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.BrightnessAuto
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SystemUpdateAlt
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.model.Playlist
import com.example.ui.theme.SoykenGold
import com.example.ui.theme.ThemeMode
import com.example.ui.tv.soykenTvFocus

const val PLAY_STORE_UPDATE_URL =
    "https://play.google.com/store/apps/details?id=com.aistudio.soykenplay.txvqen"

fun openPlayStoreUpdatePage(context: Context): Boolean {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(PLAY_STORE_UPDATE_URL)).apply {
        if (context !is Activity) {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
    return try {
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}

@Composable
fun SoykenHeader(
    activePlaylist: Playlist?,
    searchQuery: String,
    themeMode: ThemeMode,
    onSearchChange: (String) -> Unit,
    onThemeToggle: () -> Unit,
    onOpenImport: () -> Unit,
    onOpenPlaylists: () -> Unit,
    onSendTestNotification: () -> Unit,
    isNotificationsEnabled: Boolean = true,
    onNotificationsEnabledChange: (Boolean) -> Unit = {},
    isBackgroundPlayEnabled: Boolean = false,
    onBackgroundPlayChange: (Boolean) -> Unit = {},
    isTurboStreamEnabled: Boolean = false,
    onTurboStreamChange: (Boolean) -> Unit = {},
    isPremium: Boolean = false,
    onOpenPremium: () -> Unit = {},
    onOpenPrivacyPolicy: () -> Unit = {},
    onOpenReportIssue: () -> Unit = {},
    onOpenUpdateApp: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var isMenuExpanded by remember { mutableStateOf(false) }

    val themeLabel = when (themeMode) {
        ThemeMode.DARK -> "Karanlık Mod"
        ThemeMode.LIGHT -> "Aydınlık Mod"
        ThemeMode.SYSTEM -> "Sistem Teması"
    }
    val themeIcon = when (themeMode) {
        ThemeMode.DARK -> Icons.Default.DarkMode
        ThemeMode.LIGHT -> Icons.Default.LightMode
        ThemeMode.SYSTEM -> Icons.Default.BrightnessAuto
    }

    val neutralSwitchColors = SwitchDefaults.colors(
        checkedThumbColor = MaterialTheme.colorScheme.surface,
        checkedTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.82f),
        checkedBorderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.82f),
        uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
        uncheckedTrackColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
        uncheckedBorderColor = MaterialTheme.colorScheme.outline
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        // Top Branding + Top-Right Settings Dropdown Trigger
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Logo & Title
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .weight(1f, fill = false)
                    .clip(RoundedCornerShape(8.dp))
                    .soykenTvFocus(RoundedCornerShape(8.dp))
                    .clickable { onOpenPlaylists() }
                    .padding(4.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp))
                        .shadow(4.dp, RoundedCornerShape(10.dp))
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.ic_soyken_logo),
                        contentDescription = "Soyken Play Logo",
                        modifier = Modifier.size(44.dp)
                    )
                }

                Spacer(modifier = Modifier.width(10.dp))

                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "SOYKEN",
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = 1.sp
                            ),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "PLAY",
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = 1.sp
                            ),
                            color = SoykenGold
                        )
                    }

                    // Active playlist indicator
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(SoykenGold.copy(alpha = 0.85f))
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = activePlaylist?.name ?: "Liste seçilmedi",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Minimal, Text-Free Modern Settings & Actions Icon Button (Obsidian/Slate Neutral)
            val triggerContainerColor by animateColorAsState(
                targetValue = if (isMenuExpanded) {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
                },
                animationSpec = tween(durationMillis = 180),
                label = "menuTriggerBg"
            )
            val triggerBorderColor by animateColorAsState(
                targetValue = if (isMenuExpanded) {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.28f)
                } else {
                    MaterialTheme.colorScheme.outline.copy(alpha = 0.7f)
                },
                animationSpec = tween(durationMillis = 180),
                label = "menuTriggerBorder"
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Compact Gold Premium Pill (always accessible on Phone & Android TV)
                val premiumChipShape = RoundedCornerShape(12.dp)
                Row(
                    modifier = Modifier
                        .height(42.dp)
                        .clip(premiumChipShape)
                        .background(
                            if (isPremium) SoykenGold.copy(alpha = 0.2f)
                            else SoykenGold.copy(alpha = 0.14f)
                        )
                        .border(
                            width = 1.dp,
                            color = SoykenGold.copy(alpha = if (isPremium) 0.9f else 0.6f),
                            shape = premiumChipShape
                        )
                        .soykenTvFocus(premiumChipShape)
                        .clickable(role = Role.Button) { onOpenPremium() }
                        .padding(horizontal = 10.dp)
                        .testTag("header_premium_button"),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.WorkspacePremium,
                        contentDescription = "Soyken Premium",
                        tint = SoykenGold,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = if (isPremium) "PRO" else "Premium",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.ExtraBold
                        ),
                        color = SoykenGold
                    )
                }

                Box(contentAlignment = Alignment.TopEnd) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .soykenTvFocus(RoundedCornerShape(14.dp))
                            .clickable(role = Role.Button) { isMenuExpanded = !isMenuExpanded }
                            .semantics { contentDescription = "Ayarlar ve Menü" }
                            .testTag("top_right_settings_menu_button"),
                        contentAlignment = Alignment.Center
                    ) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(RoundedCornerShape(13.dp))
                            .background(triggerContainerColor)
                            .border(
                                width = 1.dp,
                                color = triggerBorderColor,
                                shape = RoundedCornerShape(13.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Crossfade(
                            targetState = isMenuExpanded,
                            animationSpec = tween(durationMillis = 180),
                            label = "menuTriggerIcon"
                        ) { expanded ->
                            Icon(
                                imageVector = if (expanded) Icons.Rounded.Close else Icons.Rounded.Tune,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }

                // Tonal elevation set to 0.dp so Material 3 does not tint the slate menu card yellow
                DropdownMenu(
                    expanded = isMenuExpanded,
                    onDismissRequest = { isMenuExpanded = false },
                    offset = DpOffset(x = 0.dp, y = 6.dp),
                    shape = RoundedCornerShape(16.dp),
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    tonalElevation = 0.dp,
                    shadowElevation = 14.dp,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.85f)),
                    modifier = Modifier
                        .widthIn(min = 280.dp, max = 320.dp)
                        .testTag("top_right_dropdown_menu")
                ) {
                    // Section 0: Soyken Premium Membership
                    MenuSectionHeader("ÜYELİK")

                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(
                                    text = if (isPremium) "Soyken Premium Aktif" else "Soyken Premium'a Geç",
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontWeight = FontWeight.Bold
                                    ),
                                    color = SoykenGold
                                )
                                Text(
                                    text = if (isPremium) {
                                        "4K Ultra HD, Turbo ve tüm özellikler açık"
                                    } else {
                                        "4K Ultra HD, Turbo bağlantı, sınırsız liste & PiP"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        leadingIcon = {
                            MenuIconBadge(
                                icon = Icons.Default.WorkspacePremium,
                                isSubtleBrandAccent = true
                            )
                        },
                        onClick = {
                            isMenuExpanded = false
                            onOpenPremium()
                        },
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                        modifier = Modifier.testTag("menu_premium_button")
                    )

                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)
                    )

                    // Section 1: Playlist Management
                    MenuSectionHeader("LİSTE İŞLEMLERİ")

                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(
                                    text = "M3U / Liste Ekle",
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontWeight = FontWeight.SemiBold
                                    ),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "URL, dosya veya metin ile yeni liste ekle",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        leadingIcon = {
                            MenuIconBadge(
                                icon = Icons.Default.Add,
                                isSubtleBrandAccent = true
                            )
                        },
                        onClick = {
                            isMenuExpanded = false
                            onOpenImport()
                        },
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                        modifier = Modifier.testTag("add_playlist_button")
                    )

                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(
                                    text = "Oynatma Listelerim",
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontWeight = FontWeight.Medium
                                    ),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = activePlaylist?.name ?: "Liste seçilmedi",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        },
                        leadingIcon = {
                            MenuIconBadge(icon = Icons.AutoMirrored.Filled.PlaylistPlay)
                        },
                        onClick = {
                            isMenuExpanded = false
                            onOpenPlaylists()
                        },
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                        modifier = Modifier.testTag("playlists_button")
                    )

                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)
                    )

                    // Section 2: App Settings
                    MenuSectionHeader("UYGULAMA AYARLARI")

                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(
                                    text = "Karanlık Mod / Tema",
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontWeight = FontWeight.Medium
                                    ),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = themeLabel,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        leadingIcon = {
                            MenuIconBadge(icon = themeIcon)
                        },
                        trailingIcon = {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.75f))
                                    .border(
                                        width = 1.dp,
                                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.8f),
                                        shape = RoundedCornerShape(8.dp)
                                    )
                                    .padding(horizontal = 9.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = when (themeMode) {
                                        ThemeMode.DARK -> "Koyu"
                                        ThemeMode.LIGHT -> "Açık"
                                        ThemeMode.SYSTEM -> "Oto"
                                    },
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.SemiBold
                                    ),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        },
                        onClick = { onThemeToggle() },
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                        modifier = Modifier.testTag("theme_toggle_button")
                    )

                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(
                                    text = "Bildirim Ayarı",
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontWeight = FontWeight.Medium
                                    ),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = if (isNotificationsEnabled) {
                                        "Yayın hatırlatıcıları açık"
                                    } else {
                                        "Yayın hatırlatıcıları kapalı"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        leadingIcon = {
                            MenuIconBadge(
                                icon = if (isNotificationsEnabled) {
                                    Icons.Default.NotificationsActive
                                } else {
                                    Icons.Default.NotificationsOff
                                }
                            )
                        },
                        trailingIcon = {
                            Switch(
                                checked = isNotificationsEnabled,
                                onCheckedChange = { onNotificationsEnabledChange(it) },
                                modifier = Modifier.scale(0.78f),
                                colors = neutralSwitchColors
                            )
                        },
                        onClick = { onNotificationsEnabledChange(!isNotificationsEnabled) },
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                        modifier = Modifier.testTag("notification_button")
                    )

                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(
                                    text = "Test Bildirimi Gönder",
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontWeight = FontWeight.Medium
                                    ),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "Bildirim görünümünü test et",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        leadingIcon = {
                            MenuIconBadge(icon = Icons.Default.Notifications)
                        },
                        onClick = {
                            isMenuExpanded = false
                            onSendTestNotification()
                        },
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                        modifier = Modifier.testTag("send_test_notification_menu_item")
                    )

                    DropdownMenuItem(
                        text = {
                            Column {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = "Arka Planda Mini Oynatma",
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            fontWeight = FontWeight.Medium
                                        ),
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    if (!isPremium) {
                                        Spacer(modifier = Modifier.width(6.dp))
                                        ProMiniPill()
                                    }
                                }
                                Text(
                                    text = when {
                                        !isPremium -> "Premium özellik • Mini pencere & PiP"
                                        isBackgroundPlayEnabled -> "PiP penceresi aktif"
                                        else -> "PiP penceresi kapalı"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        leadingIcon = {
                            MenuIconBadge(icon = Icons.Default.PictureInPictureAlt)
                        },
                        trailingIcon = {
                            Switch(
                                checked = isBackgroundPlayEnabled,
                                onCheckedChange = { onBackgroundPlayChange(it) },
                                modifier = Modifier.scale(0.78f),
                                colors = neutralSwitchColors
                            )
                        },
                        onClick = { onBackgroundPlayChange(!isBackgroundPlayEnabled) },
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                        modifier = Modifier.testTag("background_play_menu_item")
                    )

                    DropdownMenuItem(
                        text = {
                            Column {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = "Turbo Hızlı Bağlantı",
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            fontWeight = FontWeight.Medium
                                        ),
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    if (!isPremium) {
                                        Spacer(modifier = Modifier.width(6.dp))
                                        ProMiniPill()
                                    }
                                }
                                Text(
                                    text = when {
                                        !isPremium -> "Premium özellik • 200 MB önbellek & Turbo"
                                        isTurboStreamEnabled -> "Anında açılış & donma önleyici açık"
                                        else -> "Standart akış tamponu aktif"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        leadingIcon = {
                            MenuIconBadge(icon = Icons.Default.Bolt)
                        },
                        trailingIcon = {
                            Switch(
                                checked = isTurboStreamEnabled,
                                onCheckedChange = { onTurboStreamChange(it) },
                                modifier = Modifier.scale(0.78f),
                                colors = neutralSwitchColors
                            )
                        },
                        onClick = { onTurboStreamChange(!isTurboStreamEnabled) },
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                        modifier = Modifier.testTag("turbo_stream_menu_item")
                    )

                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)
                    )

                    // Section 3: Support & Privacy
                    MenuSectionHeader("DESTEK & BİLGİ")

                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(
                                    text = "Uygulamayı Güncelle",
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontWeight = FontWeight.Medium
                                    ),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "Google Play üzerinden son sürümü indir",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        leadingIcon = {
                            MenuIconBadge(icon = Icons.Default.SystemUpdateAlt)
                        },
                        trailingIcon = {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                                modifier = Modifier.size(16.dp)
                            )
                        },
                        onClick = {
                            isMenuExpanded = false
                            if (onOpenUpdateApp != null) {
                                onOpenUpdateApp()
                            } else {
                                openPlayStoreUpdatePage(context)
                            }
                        },
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                        modifier = Modifier.testTag("update_app_button")
                    )

                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(
                                    text = "Sorun Bildir",
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontWeight = FontWeight.Medium
                                    ),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "Kopya yayın veya akış hatası bildir",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        leadingIcon = {
                            MenuIconBadge(icon = Icons.Outlined.Flag)
                        },
                        onClick = {
                            isMenuExpanded = false
                            onOpenReportIssue()
                        },
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                        modifier = Modifier.testTag("header_report_issue_button")
                    )

                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(
                                    text = "Gizlilik Politikası",
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontWeight = FontWeight.Medium
                                    ),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "Veri güvenliği ve kullanım ilkeleri",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        leadingIcon = {
                            MenuIconBadge(icon = Icons.Default.Shield)
                        },
                        onClick = {
                            isMenuExpanded = false
                            onOpenPrivacyPolicy()
                        },
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                        modifier = Modifier.testTag("privacy_policy_button")
                    )
                }
            }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Flexible & Punctuation-Insensitive Search Bar
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchChange,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("channel_search_bar"),
            placeholder = {
                Text(
                    text = "Kanal veya film ara (isim, numara veya tür)...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = "Ara",
                    tint = if (searchQuery.isNotBlank()) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            },
            trailingIcon = if (searchQuery.isNotEmpty()) {
                {
                    IconButton(
                        onClick = { onSearchChange("") },
                        modifier = Modifier.testTag("clear_search_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Aramayı Temizle",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            } else {
                null
            },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = SoykenGold.copy(alpha = 0.75f),
                unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.65f),
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
            )
        )
    }
}

@Composable
private fun MenuIconBadge(
    icon: ImageVector,
    isSubtleBrandAccent: Boolean = false
) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (isSubtleBrandAccent) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f)
                }
            )
            .border(
                width = 1.dp,
                color = if (isSubtleBrandAccent) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                } else {
                    MaterialTheme.colorScheme.outline.copy(alpha = 0.65f)
                },
                shape = RoundedCornerShape(10.dp)
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
private fun MenuSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelSmall.copy(
            fontWeight = FontWeight.Bold,
            fontSize = 10.sp,
            letterSpacing = 0.8.sp
        ),
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
        modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp)
    )
}

@Composable
private fun ProMiniPill() {
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
