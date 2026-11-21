package com.example.ui.components

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.ReportProblem
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.model.Channel
import com.example.data.parser.M3uParser
import com.example.ui.theme.LiveRed
import com.example.ui.theme.SoykenGold
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

const val SUPPORT_REPORT_EMAIL = "polazmaison@gmail.com"

enum class StreamIssueType(
    val label: String,
    val description: String
) {
    DUPLICATE_STREAM(
        label = "Kopya / Mükerrer Yayın",
        description = "Aynı kanal veya yayın akışı listede birden fazla kez yer alıyor"
    ),
    STREAM_NOT_WORKING(
        label = "Yayın Açılmıyor / Bağlantı Hatası",
        description = "Kanal yüklenmiyor, siyah ekran veya sunucu hatası veriyor"
    ),
    BUFFERING_FREEZING(
        label = "Donma / Takılma Sorunu",
        description = "Yayın sık sık duraklıyor, kesiliyor veya görüntü donuyor"
    ),
    AUDIO_VIDEO_QUALITY(
        label = "Ses / Görüntü veya Kalite Sorunu",
        description = "Ses gelmiyor, senkron kayması var veya görüntü bozuk"
    ),
    WRONG_CHANNEL_CONTENT(
        label = "Yanlış Kanal / Yayın Akışı Uyuşmazlığı",
        description = "Kanal adı veya yayın akışı ile oynatılan içerik farklı"
    ),
    OTHER(
        label = "Diğer Yayın Sorunu",
        description = "Yayınla ilgili farklı bir sorun veya geri bildirim"
    )
}

enum class EmailDispatchOutcome {
    EMAIL_CLIENT_OPENED,
    COPIED_TO_CLIPBOARD
}

fun detectDuplicateChannels(
    target: Channel?,
    allChannels: List<Channel>
): List<Channel> {
    if (target == null || allChannels.isEmpty()) return emptyList()
    val targetCleanUrl = M3uParser.extractStreamRequestInfo(target.streamUrl).cleanUrl.trim().lowercase(Locale.ROOT)
    val targetNormalizedName = normalizeChannelNameForComparison(target.name)

    return allChannels.filter { candidate ->
        if (candidate.id == target.id) return@filter false
        val candidateUrl = M3uParser.extractStreamRequestInfo(candidate.streamUrl).cleanUrl.trim().lowercase(Locale.ROOT)
        val sameStreamUrl = targetCleanUrl.isNotEmpty() && candidateUrl == targetCleanUrl
        val sameChannelName = targetNormalizedName.length >= 3 &&
            normalizeChannelNameForComparison(candidate.name) == targetNormalizedName
        sameStreamUrl || sameChannelName
    }.take(5)
}

private fun normalizeChannelNameForComparison(name: String): String {
    return name.lowercase(Locale.ROOT)
        .replace("(", " ")
        .replace(")", " ")
        .replace("[", " ")
        .replace("]", " ")
        .replace("-", " ")
        .replace("_", " ")
        .split(" ")
        .filter { it.isNotBlank() }
        .joinToString(" ")
}

fun resolveDefaultIssueType(
    playbackError: String?,
    duplicateChannels: List<Channel>
): StreamIssueType {
    return when {
        !playbackError.isNullOrBlank() -> StreamIssueType.STREAM_NOT_WORKING
        duplicateChannels.isNotEmpty() -> StreamIssueType.DUPLICATE_STREAM
        else -> StreamIssueType.DUPLICATE_STREAM
    }
}

fun buildIssueReportSubject(
    channel: Channel?,
    issueType: StreamIssueType
): String {
    return if (channel != null) {
        "[Soyken Play Sorun Bildirimi] Kanal #${channel.channelNumber} - ${channel.name} (${issueType.label})"
    } else {
        "[Soyken Play Sorun Bildirimi] Genel Yayın Bildirimi (${issueType.label})"
    }
}

fun buildIssueReportBody(
    channel: Channel?,
    playlistName: String?,
    issueType: StreamIssueType,
    userNote: String,
    playbackError: String?,
    duplicateChannels: List<Channel> = emptyList(),
    appVersion: String = "15.0",
    reportedAtFormatted: String? = null
): String {
    val timestamp = reportedAtFormatted ?: SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
    val cleanStreamUrl = channel?.let {
        M3uParser.extractStreamRequestInfo(it.streamUrl).cleanUrl
    } ?: "Belirtilmedi"

    val duplicateSummary = if (duplicateChannels.isNotEmpty()) {
        duplicateChannels.joinToString(", ") { "Kanal #${it.channelNumber} (${it.name})" }
    } else {
        "Tespit edilmedi"
    }

    val androidRelease = Build.VERSION.RELEASE?.takeIf { it.isNotBlank() } ?: "Android API ${Build.VERSION.SDK_INT}"
    val deviceInfo = listOfNotNull(
        Build.MANUFACTURER?.takeIf { it.isNotBlank() },
        Build.MODEL?.takeIf { it.isNotBlank() }
    ).joinToString(" ").ifBlank { "Android Cihaz" }

    return buildString {
        appendLine("Merhaba, Soyken Play üzerinden yayın akışıyla ilgili otomatik sorun bildirimi gönderildi.")
        appendLine()
        appendLine("=== YAYIN VE KANAL BİLGİLERİ ===")
        if (channel != null) {
            appendLine("Kanal Numarası: #${channel.channelNumber}")
            appendLine("Kanal Adı: ${channel.name}")
            appendLine("Kategori: ${channel.category}")
            appendLine("Kanal Kayıt ID: ${channel.id}")
            appendLine("Yayın Bağlantısı: $cleanStreamUrl")
        } else {
            appendLine("Kanal Numarası: Seçilmedi (Genel Liste Bildirimi)")
            appendLine("Kanal Adı: Seçilmedi")
        }
        appendLine("Oynatma Listesi: ${playlistName?.takeIf { it.isNotBlank() } ?: "Varsayılan Liste"}")
        appendLine()
        appendLine("=== SORUN DETAYI ===")
        appendLine("Sorun Türü: ${issueType.label}")
        appendLine("Olası Kopya / Mükerrer Kanallar: $duplicateSummary")
        appendLine("Teknik Hata Durumu: ${playbackError?.takeIf { it.isNotBlank() } ?: "Aktif oynatıcı hatası yok (Kullanıcı bildirimi)"}")
        appendLine("Kullanıcı Notu: ${userNote.trim().ifBlank { "Ek açıklama girilmedi" }}")
        appendLine()
        appendLine("=== UYGULAMA BİLGİLERİ ===")
        appendLine("Uygulama Sürümü: Soyken Play v$appVersion")
        appendLine("Cihaz / Sistem: $deviceInfo ($androidRelease)")
        appendLine("Bildirim Zamanı: $timestamp")
    }.trimEnd()
}

fun buildIssueReportEmailIntent(
    channel: Channel?,
    playlistName: String?,
    issueType: StreamIssueType,
    userNote: String,
    playbackError: String?,
    duplicateChannels: List<Channel> = emptyList(),
    appVersion: String = "15.0"
): Intent {
    val subject = buildIssueReportSubject(channel, issueType)
    val body = buildIssueReportBody(
        channel = channel,
        playlistName = playlistName,
        issueType = issueType,
        userNote = userNote,
        playbackError = playbackError,
        duplicateChannels = duplicateChannels,
        appVersion = appVersion
    )
    val mailtoUri = Uri.parse(
        "mailto:$SUPPORT_REPORT_EMAIL?subject=${Uri.encode(subject)}&body=${Uri.encode(body)}"
    )
    return Intent(Intent.ACTION_SENDTO).apply {
        data = mailtoUri
        putExtra(Intent.EXTRA_EMAIL, arrayOf(SUPPORT_REPORT_EMAIL))
        putExtra(Intent.EXTRA_SUBJECT, subject)
        putExtra(Intent.EXTRA_TEXT, body)
    }
}

fun dispatchIssueReportEmail(
    context: Context,
    channel: Channel?,
    playlistName: String?,
    issueType: StreamIssueType,
    userNote: String,
    playbackError: String?,
    duplicateChannels: List<Channel> = emptyList()
): EmailDispatchOutcome {
    val appVersion = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: "15.0"

    val primaryIntent = buildIssueReportEmailIntent(
        channel = channel,
        playlistName = playlistName,
        issueType = issueType,
        userNote = userNote,
        playbackError = playbackError,
        duplicateChannels = duplicateChannels,
        appVersion = appVersion
    )
    if (context !is Activity) {
        primaryIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    try {
        context.startActivity(primaryIntent)
        return EmailDispatchOutcome.EMAIL_CLIENT_OPENED
    } catch (_: ActivityNotFoundException) {
        // Fallback to RFC-822 chooser for mail clients that register ACTION_SEND
        val subject = primaryIntent.getStringExtra(Intent.EXTRA_SUBJECT).orEmpty()
        val body = primaryIntent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        val rfc822Intent = Intent(Intent.ACTION_SEND).apply {
            type = "message/rfc822"
            putExtra(Intent.EXTRA_EMAIL, arrayOf(SUPPORT_REPORT_EMAIL))
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
        }
        val chooser = Intent.createChooser(rfc822Intent, "Sorun Bildirimini Gönder").apply {
            if (context !is Activity) {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
        return try {
            context.startActivity(chooser)
            EmailDispatchOutcome.EMAIL_CLIENT_OPENED
        } catch (_: Exception) {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val fullClipboardPayload = "Alıcı: $SUPPORT_REPORT_EMAIL\nKonu: $subject\n\n$body"
            clipboard?.setPrimaryClip(ClipData.newPlainText("Soyken Play Sorun Bildirimi", fullClipboardPayload))
            EmailDispatchOutcome.COPIED_TO_CLIPBOARD
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun IssueReportDialog(
    isOpen: Boolean,
    channel: Channel?,
    availableChannels: List<Channel>,
    playlistName: String?,
    playbackError: String?,
    onSelectChannel: (Channel) -> Unit,
    onDismiss: () -> Unit,
    onSubmitReport: (issueType: StreamIssueType, userNote: String, duplicateChannels: List<Channel>) -> Unit
) {
    if (!isOpen) return

    val duplicateChannels = remember(channel, availableChannels) {
        detectDuplicateChannels(channel, availableChannels)
    }

    var selectedIssueType by remember(channel?.id, playbackError, duplicateChannels.size) {
        mutableStateOf(resolveDefaultIssueType(playbackError, duplicateChannels))
    }
    var userNote by remember(channel?.id) { mutableStateOf("") }

    LaunchedEffect(playbackError) {
        if (!playbackError.isNullOrBlank()) {
            selectedIssueType = StreamIssueType.STREAM_NOT_WORKING
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .widthIn(max = 540.dp)
                .clip(RoundedCornerShape(20.dp))
                .border(1.dp, SoykenGold.copy(alpha = 0.45f), RoundedCornerShape(20.dp))
                .testTag("issue_report_dialog"),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 640.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(42.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(SoykenGold.copy(alpha = 0.16f))
                                .border(1.dp, SoykenGold.copy(alpha = 0.45f), RoundedCornerShape(12.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.ReportProblem,
                                contentDescription = null,
                                tint = SoykenGold,
                                modifier = Modifier.size(22.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        Column {
                            Text(
                                text = "Yayın Sorunu Bildir",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 18.sp
                                ),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Otomatik kanal bilgisi ile e-posta bildirimi",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("close_report_dialog_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Kapat",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Selected Channel Metadata Summary Card
                if (channel != null) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f))
                            .border(
                                width = 1.dp,
                                color = SoykenGold.copy(alpha = 0.4f),
                                shape = RoundedCornerShape(14.dp)
                            )
                            .padding(14.dp)
                            .testTag("report_channel_info_card")
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(SoykenGold)
                                    .padding(horizontal = 10.dp, vertical = 5.dp)
                            ) {
                                Text(
                                    text = "Kanal #${channel.channelNumber}",
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        fontWeight = FontWeight.ExtraBold
                                    ),
                                    color = Color.Black
                                )
                            }

                            Spacer(modifier = Modifier.width(10.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = channel.name,
                                    style = MaterialTheme.typography.titleSmall.copy(
                                        fontWeight = FontWeight.Bold
                                    ),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${channel.category} • ${playlistName ?: "Aktif Liste"}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        if (!playbackError.isNullOrBlank()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(LiveRed.copy(alpha = 0.14f))
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(7.dp)
                                        .clip(CircleShape)
                                        .background(LiveRed)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Algılanan Hata: $playbackError",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = LiveRed,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        if (duplicateChannels.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(SoykenGold.copy(alpha = 0.15f))
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ContentCopy,
                                    contentDescription = null,
                                    tint = SoykenGold,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Listede benzer/kopya kayıt bulundu: " +
                                        duplicateChannels.joinToString(", ") { "#${it.channelNumber} ${it.name}" },
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                    color = SoykenGold,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                } else if (availableChannels.isNotEmpty()) {
                    // Quick channel selector if user opened Report Issue from top header while no channel was playing
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
                            .padding(12.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Tv,
                                contentDescription = null,
                                tint = SoykenGold,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Bildirilecek kanalı seçin (isteğe bağlı):",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            availableChannels.take(6).forEach { candidate ->
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(MaterialTheme.colorScheme.surface)
                                        .border(1.dp, SoykenGold.copy(alpha = 0.45f), RoundedCornerShape(8.dp))
                                        .clickable { onSelectChannel(candidate) }
                                        .padding(horizontal = 10.dp, vertical = 6.dp)
                                ) {
                                    Text(
                                        text = "#${candidate.channelNumber} ${candidate.name}",
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = "Sorun Türünü Seçin",
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Issue Type Options
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    StreamIssueType.entries.forEach { issueType ->
                        val isSelected = selectedIssueType == issueType
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    if (isSelected) {
                                        SoykenGold.copy(alpha = 0.16f)
                                    } else {
                                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f)
                                    }
                                )
                                .border(
                                    width = if (isSelected) 1.5.dp else 1.dp,
                                    color = if (isSelected) {
                                        SoykenGold
                                    } else {
                                        MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                                    },
                                    shape = RoundedCornerShape(12.dp)
                                )
                                .clickable { selectedIssueType = issueType }
                                .padding(horizontal = 12.dp, vertical = 10.dp)
                                .testTag("issue_type_${issueType.name.lowercase(Locale.ROOT)}"),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = issueType.label,
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp
                                    ),
                                    color = if (isSelected) SoykenGold else MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = issueType.description,
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            if (isSelected) {
                                Spacer(modifier = Modifier.width(8.dp))
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = "Seçili",
                                    tint = SoykenGold,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Optional User Note
                OutlinedTextField(
                    value = userNote,
                    onValueChange = { userNote = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("report_note_input"),
                    label = {
                        Text("Ek Açıklama (İsteğe Bağlı)")
                    },
                    placeholder = {
                        Text(
                            text = "Örn: 24. kanal ile aynı yayını veriyor veya ses gelmiyor...",
                            style = MaterialTheme.typography.bodySmall
                        )
                    },
                    minLines = 2,
                    maxLines = 4,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = SoykenGold,
                        focusedLabelColor = SoykenGold,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
                    )
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Recipient & Auto-Included Info Footer
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Email,
                        contentDescription = null,
                        tint = SoykenGold,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = buildString {
                            append("Alıcı: $SUPPORT_REPORT_EMAIL • ")
                            if (channel != null) {
                                append("Kanal #${channel.channelNumber} (${channel.name}) bilgileri otomatik eklenir.")
                            } else {
                                append("Oynatma listesi ve sorun detayları otomatik eklenir.")
                            }
                        },
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .weight(0.38f)
                            .height(48.dp)
                            .testTag("cancel_issue_report_button"),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = "İptal",
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    Button(
                        onClick = {
                            onSubmitReport(selectedIssueType, userNote, duplicateChannels)
                        },
                        modifier = Modifier
                            .weight(0.62f)
                            .height(48.dp)
                            .testTag("submit_issue_report_button"),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = SoykenGold,
                            contentColor = Color.Black
                        )
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Bildirimi Gönder",
                            fontWeight = FontWeight.ExtraBold,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}
