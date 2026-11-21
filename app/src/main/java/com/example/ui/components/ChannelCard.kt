package com.example.ui.components

import androidx.compose.animation.animateColorAsState
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.AlarmOn
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.data.model.Channel
import com.example.data.model.PlaybackProgress
import com.example.ui.theme.FavoriteGold
import com.example.ui.theme.LiveRed
import com.example.ui.theme.SoykenGold
import com.example.ui.tv.soykenTvFocus

@Composable
fun ChannelCard(
    channel: Channel,
    isPlaying: Boolean,
    onPlay: () -> Unit,
    onToggleFavorite: () -> Unit,
    onOpenSchedule: () -> Unit,
    onReportIssue: () -> Unit = {},
    playbackProgress: PlaybackProgress? = null,
    activeReminderTime: String? = null,
    modifier: Modifier = Modifier
) {
    val borderColor by animateColorAsState(
        targetValue = if (isPlaying) SoykenGold else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
        label = "card_border"
    )

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .border(
                width = if (isPlaying) 1.5.dp else 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(14.dp)
            )
            .soykenTvFocus(RoundedCornerShape(14.dp), scaleOnFocus = 1.015f)
            .clickable { onPlay() }
            .testTag("channel_card_${channel.id}"),
        colors = CardDefaults.cardColors(
            containerColor = if (isPlaying) {
                MaterialTheme.colorScheme.surfaceVariant
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Channel Logo / Number Box
            Box(
                modifier = Modifier
                    .size(54.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (!channel.logoUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(channel.logoUrl)
                            .crossfade(true)
                            .build(),
                        contentDescription = "${channel.name} Logosu",
                        modifier = Modifier
                            .size(46.dp)
                            .clip(RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.Fit
                    )
                } else {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Tv,
                            contentDescription = null,
                            tint = SoykenGold,
                            modifier = Modifier.size(24.dp)
                        )
                        Text(
                            text = "#${channel.channelNumber}",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // If currently playing, show play overlay indicator
                if (isPlaying) {
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .background(Color.Black.copy(alpha = 0.45f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .background(SoykenGold),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = "Şu an oynatılıyor",
                                tint = Color.Black,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Channel Details
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = channel.name,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        ),
                        color = if (isPlaying) SoykenGold else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )

                    if (isPlaying) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(LiveRed)
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "CANLI",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold
                                ),
                                color = Color.White
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Category badge
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f))
                            .padding(horizontal = 7.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = channel.category,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold
                            ),
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    Text(
                        text = "Kanal ${channel.channelNumber} • HLS (M3U8)",
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (activeReminderTime != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "⏰ Hatırlatıcı: $activeReminderTime",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = SoykenGold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (playbackProgress != null && !playbackProgress.isLive && playbackProgress.positionMs >= 5_000L) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (playbackProgress.durationMs > 0L) {
                            "▶ Kaldığı yer: ${formatResumeTime(playbackProgress.positionMs)} / ${formatResumeTime(playbackProgress.durationMs)}"
                        } else {
                            "▶ Kaldığı yer: ${formatResumeTime(playbackProgress.positionMs)}"
                        },
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = SoykenGold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Action Buttons (Report Issue, Schedule & Favorite)
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Report Issue (Sorun Bildir) Button
                IconButton(
                    onClick = onReportIssue,
                    modifier = Modifier
                        .size(38.dp)
                        .soykenTvFocus(CircleShape, scaleOnFocus = 1.06f)
                        .testTag("report_button_${channel.id}")
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Flag,
                        contentDescription = "Sorun Bildir",
                        tint = SoykenGold.copy(alpha = 0.9f),
                        modifier = Modifier.size(19.dp)
                    )
                }

                // Saat & Dakika Hatırlatıcı Button
                IconButton(
                    onClick = onOpenSchedule,
                    modifier = Modifier
                        .size(38.dp)
                        .soykenTvFocus(CircleShape, scaleOnFocus = 1.06f)
                        .testTag("schedule_button_${channel.id}")
                ) {
                    Icon(
                        imageVector = if (activeReminderTime != null) Icons.Default.AlarmOn else Icons.Default.Alarm,
                        contentDescription = "Saat Hatırlatıcısı",
                        tint = if (activeReminderTime != null) SoykenGold else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }

                // Favorite Button
                IconButton(
                    onClick = onToggleFavorite,
                    modifier = Modifier
                        .size(38.dp)
                        .soykenTvFocus(CircleShape, scaleOnFocus = 1.06f)
                        .testTag("favorite_button_${channel.id}")
                ) {
                    Icon(
                        imageVector = if (channel.isFavorite) Icons.Default.Star else Icons.Outlined.StarOutline,
                        contentDescription = if (channel.isFavorite) "Favorilerden çıkar" else "Favorilere ekle",
                        tint = if (channel.isFavorite) FavoriteGold else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }

        if (playbackProgress != null &&
            !playbackProgress.isLive &&
            playbackProgress.durationMs > 0L &&
            playbackProgress.positionMs >= 5_000L
        ) {
            val fraction = (playbackProgress.positionMs.toFloat() / playbackProgress.durationMs.toFloat())
                .coerceIn(0.03f, 1f)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction)
                        .height(3.dp)
                        .background(SoykenGold)
                )
            }
        }
        }
    }
}

private fun formatResumeTime(millis: Long): String {
    val totalSeconds = (millis / 1000L).coerceAtLeast(0L)
    val seconds = totalSeconds % 60
    val minutes = (totalSeconds / 60) % 60
    val hours = totalSeconds / 3600
    return if (hours > 0) {
        String.format("%02d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%02d:%02d", minutes, seconds)
    }
}
