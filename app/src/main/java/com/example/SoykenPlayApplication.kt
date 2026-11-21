package com.example

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.example.data.network.CloudflareDns
import com.example.service.FirebaseAppCheckConfig
import com.example.service.NotificationHelper
import com.example.service.ReminderScheduler
import okhttp3.Dispatcher

class SoykenPlayApplication : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()
        if (!BuildConfig.DEBUG) {
            FirebaseAppCheckConfig.initialize(this, intent = null)
        }
        NotificationHelper.createNotificationChannels(this)
        ReminderScheduler.bootstrapAsync(this)
        runCatching { CloudflareDns.warmUpAsync() }
    }

    override fun newImageLoader(): ImageLoader {
        // Dedicated low-contention dispatcher so channel logo downloads never compete with
        // active live TV / movie video segments for downstream bandwidth on the same IPTV host.
        val logoDispatcher = Dispatcher().apply {
            maxRequests = 8
            maxRequestsPerHost = 3
        }
        return ImageLoader.Builder(this)
            .okHttpClient {
                CloudflareDns.createHttpClient(
                    connectTimeoutMs = 6_000L,
                    readTimeoutMs = 10_000L,
                    customDispatcher = logoDispatcher
                )
            }
            // IPTV logo servers frequently omit Cache-Control headers; caching unconditionally
            // prevents re-downloading the same logos on every scroll and saves 100% bandwidth for video.
            .respectCacheHeaders(false)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.20)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("soyken_channel_logos_cache"))
                    .maxSizeBytes(120L * 1024L * 1024L)
                    .build()
            }
            .crossfade(true)
            .build()
    }
}
