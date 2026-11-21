package com.example.data.repository

import android.content.Context
import com.example.BuildConfig
import com.example.data.local.AppDatabase
import com.example.data.model.Channel
import com.example.data.model.PlaybackProgress
import com.example.data.model.Playlist
import com.example.data.model.ProgramReminder
import com.example.data.parser.DemoPlaylists
import com.example.data.parser.M3uParser
import com.example.data.network.CloudflareDns
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.InputStream

class IptvRepository(context: Context) {

    private val appContext = context.applicationContext
    private val db = AppDatabase.getInstance(appContext)
    private val playlistDao = db.playlistDao()
    private val channelDao = db.channelDao()
    private val reminderDao = db.reminderDao()
    private val playbackProgressDao = db.playbackProgressDao()

    private val httpClient by lazy {
        CloudflareDns.createHttpClient(
            connectTimeoutMs = 8_000L,
            readTimeoutMs = 25_000L
        )
    }

    fun getAllPlaylists(): Flow<List<Playlist>> = playlistDao.getAllPlaylists()

    fun getActivePlaylist(): Flow<Playlist?> = playlistDao.getActivePlaylist()

    suspend fun getActivePlaylistSync(): Playlist? = withContext(Dispatchers.IO) {
        playlistDao.getActivePlaylistSync()
    }

    fun getChannelsForPlaylist(playlistId: Long): Flow<List<Channel>> =
        channelDao.getChannelsForPlaylist(playlistId)

    fun getFavoritesForPlaylist(playlistId: Long): Flow<List<Channel>> =
        channelDao.getFavoritesForPlaylist(playlistId)

    fun getCategoriesForPlaylist(playlistId: Long): Flow<List<String>> =
        channelDao.getCategoriesForPlaylist(playlistId)

    fun getChannelsByCategory(playlistId: Long, category: String): Flow<List<Channel>> =
        channelDao.getChannelsByCategory(playlistId, category)

    fun searchChannels(playlistId: Long, query: String): Flow<List<Channel>> =
        channelDao.searchChannels(playlistId, query)

    suspend fun setActivePlaylist(playlistId: Long) = withContext(Dispatchers.IO) {
        playlistDao.clearActivePlaylists()
        playlistDao.setActivePlaylist(playlistId)
    }

    suspend fun deletePlaylist(playlist: Playlist) = withContext(Dispatchers.IO) {
        playlistDao.deletePlaylist(playlist)
        // If deleted playlist was active, activate the first available
        val remaining = playlistDao.getActivePlaylistSync()
        if (remaining == null) {
            val all = playlistDao.getPlaylistCount()
            // Room cascades channel deletion
        }
    }

    suspend fun toggleFavorite(channel: Channel) = withContext(Dispatchers.IO) {
        channelDao.setFavorite(channel.id, !channel.isFavorite)
    }

    private suspend fun insertChannelsChunked(channels: List<Channel>) {
        channels.chunked(500).forEach { batch ->
            channelDao.insertChannels(batch)
        }
    }

    suspend fun importFromUrl(name: String, url: String): Result<Playlist> = withContext(Dispatchers.IO) {
        try {
            val sanitizedRaw = M3uParser.sanitizeUrlPart(url)
            val normalizedUrl = M3uParser.toHlsPlaylistUrl(sanitizedRaw)
            val httpFallbackRaw = if (sanitizedRaw.startsWith("https://", ignoreCase = true)) {
                "http://" + sanitizedRaw.substring(8)
            } else {
                null
            }
            val candidateUrls = buildList {
                add(sanitizedRaw)
                add(normalizedUrl)
                httpFallbackRaw?.let {
                    add(it)
                    add(M3uParser.toHlsPlaylistUrl(it))
                }
            }.filter { it.isNotBlank() }.distinct()

            val userAgents = listOf(
                "IPTVSmartersPlayer",
                "Mozilla/5.0 (Linux; Android 14; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36",
                "VLC/3.0.20 LibVLC/3.0.20",
                "SoykenPlay/${BuildConfig.VERSION_NAME} IPTV HLS Client"
            )

            var bodyText: String? = null
            var resolvedSourceUrl: String = normalizedUrl.ifBlank { sanitizedRaw }
            var lastHttpCode = 0
            var lastException: Exception? = null

            // Connect to 1dot1dot1dot1.cloudflare-dns.com first before accessing M3U URLs
            CloudflareDns.ensureConnectedForUrl(sanitizedRaw)

            outer@ for (candidateUrl in candidateUrls) {
                CloudflareDns.ensureConnectedForUrl(candidateUrl)
                val schemeIdx = candidateUrl.indexOf("://")
                val origin = if (schemeIdx != -1) {
                    val pathIdx = candidateUrl.indexOf('/', schemeIdx + 3)
                    if (pathIdx == -1) candidateUrl else candidateUrl.substring(0, pathIdx)
                } else {
                    null
                }
                var sslFailedForUrl = false

                for (ua in userAgents) {
                    if (sslFailedForUrl) break
                    val reqBuilder = Request.Builder()
                        .url(candidateUrl)
                        .header("User-Agent", ua)
                        .header("Accept", "*/*")
                    if (ua.startsWith("Mozilla/") && origin != null) {
                        reqBuilder.header("Referer", "$origin/")
                    }
                    try {
                        httpClient.newCall(reqBuilder.build()).execute().use { resp ->
                            if (resp.isSuccessful) {
                                val text = resp.body?.string()
                                if (!text.isNullOrBlank()) {
                                    bodyText = text
                                    resolvedSourceUrl = resp.request.url.toString()
                                    break@outer
                                }
                            } else {
                                if (lastHttpCode == 0 || (lastHttpCode == 404 && resp.code != 404)) {
                                    lastHttpCode = resp.code
                                }
                            }
                        }
                    } catch (e: Exception) {
                        lastException = e
                        val cls = e.javaClass.name
                        if (cls.contains("SSL", ignoreCase = true) || cls.contains("Cert", ignoreCase = true)) {
                            sslFailedForUrl = true
                        }
                    }
                }
            }

            val resolvedBody = bodyText
                ?: return@withContext Result.failure(
                    Exception(
                        when {
                            lastHttpCode > 0 -> "HTTP Hatası: $lastHttpCode"
                            lastException != null -> lastException?.localizedMessage ?: "Bağlantı hatası"
                            else -> "Boş yanıt alındı"
                        }
                    )
                )

            val playlistId = playlistDao.insertPlaylist(
                Playlist(
                    name = name.ifBlank { "HLS (M3U8) Listesi" },
                    sourceUrl = resolvedSourceUrl,
                    isLocalFile = false,
                    channelCount = 0,
                    isActive = false
                )
            )

            val channels = if (M3uParser.isDirectHlsManifest(resolvedBody)) {
                listOf(
                    Channel(
                        playlistId = playlistId,
                        name = name.ifBlank { "Canlı HLS Yayını" },
                        streamUrl = resolvedSourceUrl,
                        logoUrl = null,
                        category = "Canlı HLS (M3U8)",
                        channelNumber = 1
                    )
                )
            } else {
                M3uParser.parseString(resolvedBody, playlistId)
            }

            if (channels.isEmpty()) {
                playlistDao.deletePlaylist(Playlist(id = playlistId, name = name))
                return@withContext Result.failure(Exception("M3U8/M3U bağlantısında geçerli kanal bulunamadı"))
            }

            insertChannelsChunked(channels)
            val updated = Playlist(
                id = playlistId,
                name = name.ifBlank { "HLS (M3U8) Listesi" },
                sourceUrl = resolvedSourceUrl,
                isLocalFile = false,
                channelCount = channels.size,
                isActive = true
            )
            playlistDao.clearActivePlaylists()
            playlistDao.updatePlaylist(updated)

            Result.success(updated)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun importFromInputStream(name: String, inputStream: InputStream): Result<Playlist> =
        withContext(Dispatchers.IO) {
            try {
                val playlistId = playlistDao.insertPlaylist(
                    Playlist(
                        name = name.ifBlank { "Yüklenen HLS Listesi" },
                        sourceUrl = null,
                        isLocalFile = true,
                        channelCount = 0,
                        isActive = false
                    )
                )

                val channels = M3uParser.parse(inputStream, playlistId)
                if (channels.isEmpty()) {
                    playlistDao.deletePlaylist(Playlist(id = playlistId, name = name))
                    return@withContext Result.failure(Exception("Dosyada geçerli kanal bulunamadı"))
                }

                insertChannelsChunked(channels)
                val updated = Playlist(
                    id = playlistId,
                    name = name.ifBlank { "Yüklenen HLS Listesi" },
                    sourceUrl = null,
                    isLocalFile = true,
                    channelCount = channels.size,
                    isActive = true
                )
                playlistDao.clearActivePlaylists()
                playlistDao.updatePlaylist(updated)

                Result.success(updated)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    suspend fun importFromString(name: String, content: String): Result<Playlist> =
        withContext(Dispatchers.IO) {
            try {
                val playlistId = playlistDao.insertPlaylist(
                    Playlist(
                        name = name.ifBlank { "Özel HLS Listesi" },
                        sourceUrl = null,
                        isLocalFile = false,
                        channelCount = 0,
                        isActive = false
                    )
                )

                val trimmedContent = content.trim()
                val channels = if (trimmedContent.startsWith("http://", ignoreCase = true) ||
                    trimmedContent.startsWith("https://", ignoreCase = true)
                ) {
                    val parsed = M3uParser.parseString(trimmedContent, playlistId)
                    parsed
                } else {
                    M3uParser.parseString(trimmedContent, playlistId)
                }

                if (channels.isEmpty()) {
                    playlistDao.deletePlaylist(Playlist(id = playlistId, name = name))
                    return@withContext Result.failure(Exception("M3U8/M3U içeriğinde geçerli kanal bulunamadı"))
                }

                insertChannelsChunked(channels)
                val updated = Playlist(
                    id = playlistId,
                    name = name.ifBlank { "Özel HLS Listesi" },
                    sourceUrl = null,
                    isLocalFile = false,
                    channelCount = channels.size,
                    isActive = true
                )
                playlistDao.clearActivePlaylists()
                playlistDao.updatePlaylist(updated)

                Result.success(updated)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    private suspend fun migrateAllChannelsToHlsOnce() {
        val prefs = appContext.getSharedPreferences("soyken_play_prefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("hls_url_migrated_v3", false)) {
            val existingChannels = channelDao.getAllChannelsSync()
            val toUpdate = existingChannels.mapNotNull { ch ->
                val hlsUrl = M3uParser.toHlsStreamUrl(ch.streamUrl)
                if (hlsUrl != ch.streamUrl) ch.copy(streamUrl = hlsUrl) else null
            }
            if (toUpdate.isNotEmpty()) {
                db.withTransaction {
                    toUpdate.chunked(500).forEach { batch ->
                        channelDao.insertChannels(batch)
                    }
                }
            }
            prefs.edit().putBoolean("hls_url_migrated_v3", true).apply()
        }
    }

    suspend fun cleanupInvalidProgress() = withContext(Dispatchers.IO) {
        playbackProgressDao.deleteInvalidLiveOrZeroDurationProgress()
    }

    suspend fun initializeDemoIfEmpty() = withContext(Dispatchers.IO) {
        playbackProgressDao.deleteInvalidLiveOrZeroDurationProgress()
        val count = playlistDao.getPlaylistCount()
        if (count == 0) {
            val playlistId = playlistDao.insertPlaylist(
                Playlist(
                    name = DemoPlaylists.DEMO_PLAYLIST_NAME,
                    sourceUrl = "demo://soyken_play",
                    isLocalFile = false,
                    channelCount = 0,
                    isActive = true
                )
            )
            val demoChannels = DemoPlaylists.getDemoChannels(appContext, playlistId)
            insertChannelsChunked(demoChannels)
            playlistDao.updatePlaylist(
                Playlist(
                    id = playlistId,
                    name = DemoPlaylists.DEMO_PLAYLIST_NAME,
                    sourceUrl = "demo://soyken_play",
                    isLocalFile = false,
                    channelCount = demoChannels.size,
                    isActive = true
                )
            )
        } else {
            val allPlaylists = playlistDao.getAllPlaylistsSync()
            for (pl in allPlaylists) {
                if (pl.sourceUrl == "demo://soyken_play") {
                    val existingDemo = channelDao.getChannelsForPlaylistSync(pl.id)
                    val needsRefresh = existingDemo.isEmpty() ||
                        existingDemo.any {
                            !it.streamUrl.contains(".m3u8", ignoreCase = true) ||
                                it.streamUrl.contains("hls.ted.com", ignoreCase = true) ||
                                it.streamUrl.contains("test_001/stream.m3u8", ignoreCase = true) ||
                                it.streamUrl.contains("cph-p2p-msl.akamaized.net", ignoreCase = true)
                        }
                    if (needsRefresh) {
                        channelDao.deleteChannelsByPlaylist(pl.id)
                        val updatedChannels = DemoPlaylists.getDemoChannels(appContext, pl.id)
                        insertChannelsChunked(updatedChannels)
                        playlistDao.updatePlaylist(pl.copy(channelCount = updatedChannels.size))
                    }
                }
            }
            migrateAllChannelsToHlsOnce()
        }
    }

    fun getAllPlaybackProgress(): Flow<List<PlaybackProgress>> =
        playbackProgressDao.getAllProgress()

    suspend fun getPlaybackProgressSync(streamUrl: String): PlaybackProgress? =
        withContext(Dispatchers.IO) {
            val normalized = M3uParser.toHlsStreamUrl(streamUrl)
            val found = playbackProgressDao.getProgressByStreamUrl(normalized)
                ?: playbackProgressDao.getProgressByStreamUrl(streamUrl)
            found?.takeIf { !it.isLive && it.durationMs >= MIN_VOD_DURATION_MS }
        }

    suspend fun savePlaybackProgress(
        channel: Channel,
        positionMs: Long,
        durationMs: Long,
        isLive: Boolean
    ) = withContext(Dispatchers.IO) {
        val normalizedUrl = M3uParser.toHlsStreamUrl(channel.streamUrl)
        // Never save resume timestamp for live broadcasts or unknown-duration streams
        if (isLive || durationMs < MIN_VOD_DURATION_MS) {
            return@withContext
        }
        if (isPlaybackCompleted(positionMs, durationMs)) {
            playbackProgressDao.deleteProgress(normalizedUrl)
            return@withContext
        }
        if (positionMs < MIN_RESUME_POSITION_MS) {
            return@withContext
        }
        playbackProgressDao.upsertProgress(
            PlaybackProgress(
                streamUrl = normalizedUrl,
                channelId = channel.id,
                channelName = channel.name,
                positionMs = positionMs,
                durationMs = durationMs,
                isLive = false,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun clearPlaybackProgress(streamUrl: String) = withContext(Dispatchers.IO) {
        val normalizedUrl = M3uParser.toHlsStreamUrl(streamUrl)
        playbackProgressDao.deleteProgress(normalizedUrl)
        if (normalizedUrl != streamUrl) {
            playbackProgressDao.deleteProgress(streamUrl)
        }
    }

    suspend fun findChannelForResume(channelId: Long, streamUrl: String?): Channel? =
        withContext(Dispatchers.IO) {
            if (channelId > 0L) {
                val byId = channelDao.getChannelById(channelId)
                if (byId != null) return@withContext byId
            }
            if (!streamUrl.isNullOrBlank()) {
                val normalized = M3uParser.toHlsStreamUrl(streamUrl)
                channelDao.getChannelByStreamUrl(normalized)
                    ?: channelDao.getChannelByStreamUrl(streamUrl)
            } else {
                null
            }
        }

    fun getAllReminders(): Flow<List<ProgramReminder>> = reminderDao.getAllReminders()

    suspend fun hasReminder(programId: String): Boolean = withContext(Dispatchers.IO) {
        reminderDao.hasReminder(programId)
    }

    suspend fun addReminder(reminder: ProgramReminder) = withContext(Dispatchers.IO) {
        reminderDao.insertReminder(reminder)
    }

    suspend fun removeReminder(programId: String): Int = withContext(Dispatchers.IO) {
        reminderDao.deleteReminderById(programId)
    }

    suspend fun deleteExpiredReminders(cutoffMillis: Long) = withContext(Dispatchers.IO) {
        reminderDao.deleteLegacyProgramReminders()
        reminderDao.deleteExpiredReminders(cutoffMillis)
    }

    companion object {
        const val MIN_RESUME_POSITION_MS = 3_000L
        const val MIN_VOD_DURATION_MS = 15_000L

        fun isPlaybackCompleted(positionMs: Long, durationMs: Long): Boolean {
            if (durationMs < 20_000L) return false
            val remainingMs = durationMs - positionMs
            val ratio = positionMs.toDouble() / durationMs.toDouble()
            return remainingMs <= 8_000L || ratio >= 0.97
        }
    }
}
