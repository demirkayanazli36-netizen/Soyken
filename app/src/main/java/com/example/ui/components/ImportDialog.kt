package com.example.ui.components

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.SoykenGold
import java.io.InputStream

@Composable
fun ImportDialog(
    isOpen: Boolean,
    onDismiss: () -> Unit,
    onImportUrl: (name: String, url: String) -> Unit,
    onImportStream: (name: String, stream: InputStream) -> Unit,
    onImportText: (name: String, text: String) -> Unit,
    modifier: Modifier = Modifier
) {
    if (!isOpen) return

    val context = LocalContext.current
    var selectedTab by remember { mutableIntStateOf(0) } // 0: URL, 1: File, 2: Text

    var playlistName by remember { mutableStateOf("") }
    var urlInput by remember { mutableStateOf("") }
    var textInput by remember { mutableStateOf("") }
    var selectedFileUri by remember { mutableStateOf<Uri?>(null) }
    var selectedFileName by remember { mutableStateOf<String?>(null) }

    // File picker launcher
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            selectedFileUri = uri
            selectedFileName = uri.lastPathSegment ?: "hls_listesi.m3u8"
            if (playlistName.isBlank()) {
                playlistName = selectedFileName?.substringBeforeLast(".") ?: "İçe Aktarılan Liste"
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.CloudDownload,
                    contentDescription = null,
                    tint = SoykenGold,
                    modifier = Modifier.size(26.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "HLS (M3U8) Listesi İçe Aktar",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("import_dialog_content")
            ) {
                // Tab Selection (URL vs Dosya vs Metin)
                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    indicator = { tabPositions ->
                        TabRowDefaults.SecondaryIndicator(
                            Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                            color = SoykenGold
                        )
                    },
                    modifier = Modifier.clip(RoundedCornerShape(8.dp))
                ) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Link, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Link", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        },
                        selectedContentColor = SoykenGold,
                        unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.UploadFile, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Dosya", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        },
                        selectedContentColor = SoykenGold,
                        unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Tab(
                        selected = selectedTab == 2,
                        onClick = { selectedTab = 2 },
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.TextFields, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Metin", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        },
                        selectedContentColor = SoykenGold,
                        unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Playlist Name Field
                OutlinedTextField(
                    value = playlistName,
                    onValueChange = { playlistName = it },
                    label = { Text("Oynatma Listesi Adı") },
                    placeholder = { Text("Örn: Spor & Haber Paketi") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("playlist_name_input"),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = SoykenGold,
                        focusedLabelColor = SoykenGold
                    )
                )

                Spacer(modifier = Modifier.height(12.dp))

                when (selectedTab) {
                    0 -> {
                        // URL Import
                        OutlinedTextField(
                            value = urlInput,
                            onValueChange = { urlInput = it },
                            label = { Text("HLS (M3U8) / M3U Bağlantısı (URL)") },
                            placeholder = { Text("https://example.com/live/playlist.m3u8") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("playlist_url_input"),
                            maxLines = 3,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = SoykenGold,
                                focusedLabelColor = SoykenGold
                            )
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        // Quick Sample IPTV URLs
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "💡 Hızlı Deneme:",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Text(
                                text = "Örnek HLS (M3U8) Yayın Yapıştır",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = SoykenGold
                                ),
                                modifier = Modifier
                                    .clickable {
                                        urlInput = "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"
                                        if (playlistName.isBlank()) playlistName = "HLS (M3U8) Test Yayını"
                                    }
                                    .padding(4.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        Text(
                            text = "DNS: 1dot1dot1dot1.cloudflare-dns.com üzerinden bağlanır",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    1 -> {
                        // File Upload
                        Column(
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .border(
                                        width = 1.5.dp,
                                        color = if (selectedFileUri != null) SoykenGold else MaterialTheme.colorScheme.outline,
                                        shape = RoundedCornerShape(10.dp)
                                    )
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                    .clickable {
                                        filePickerLauncher.launch("*/*")
                                    }
                                    .padding(16.dp)
                                    .testTag("file_picker_button"),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(
                                        imageVector = Icons.Default.FileOpen,
                                        contentDescription = null,
                                        tint = SoykenGold,
                                        modifier = Modifier.size(36.dp)
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = if (selectedFileUri != null) {
                                            "Seçilen Dosya: $selectedFileName"
                                        } else {
                                            "Cihazdan .m3u8 / .m3u / .txt dosyası seç"
                                        },
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            fontWeight = FontWeight.SemiBold
                                        ),
                                        color = if (selectedFileUri != null) SoykenGold else MaterialTheme.colorScheme.onSurface
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "Tüm yayın bağlantıları otomatik HLS (M3U8) formatına dönüştürülür",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }

                    2 -> {
                        // Raw M3U Text
                        OutlinedTextField(
                            value = textInput,
                            onValueChange = { textInput = it },
                            label = { Text("HLS (M3U8) / M3U İçeriği Yapıştır") },
                            placeholder = { Text("#EXTM3U\n#EXTINF:-1 group-title=\"Haber\",Kanal 1\nhttps://example.com/live/kanal1.m3u8") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(120.dp)
                                .testTag("playlist_raw_text_input"),
                            maxLines = 8,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = SoykenGold,
                                focusedLabelColor = SoykenGold
                            )
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val finalName = playlistName.ifBlank { "Yeni Oynatma Listesi" }
                    when (selectedTab) {
                        0 -> {
                            if (urlInput.isNotBlank()) {
                                onImportUrl(finalName, urlInput.trim())
                            }
                        }
                        1 -> {
                            selectedFileUri?.let { uri ->
                                try {
                                    context.contentResolver.openInputStream(uri)?.let { stream ->
                                        onImportStream(finalName, stream)
                                    }
                                } catch (_: Exception) {}
                            }
                        }
                        2 -> {
                            if (textInput.isNotBlank()) {
                                onImportText(finalName, textInput.trim())
                            }
                        }
                    }
                },
                enabled = when (selectedTab) {
                    0 -> urlInput.isNotBlank()
                    1 -> selectedFileUri != null
                    2 -> textInput.isNotBlank()
                    else -> false
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = SoykenGold,
                    contentColor = Color.Black
                ),
                modifier = Modifier.testTag("submit_import_button")
            ) {
                Text("İçe Aktar", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("cancel_import_button")
            ) {
                Text("İptal")
            }
        },
        modifier = modifier
    )
}
