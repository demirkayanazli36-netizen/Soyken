package com.example.data.parser

import android.content.Context
import com.example.R
import com.example.data.model.Channel

object DemoPlaylists {

    const val DEMO_PLAYLIST_NAME = "Soyken Play Başlangıç Paketi"

    fun getOfflineTestUri(context: Context): String {
        return "android.resource://${context.packageName}/${R.raw.demo_broadcast}"
    }

    fun getDemoChannels(context: Context, playlistId: Long): List<Channel> {
        return listOf(
            Channel(
                playlistId = playlistId,
                name = "Soyken Play 4K Canlı Test Yayını (HLS)",
                streamUrl = "https://devstreaming-cdn.apple.com/videos/streaming/examples/img_bipbop_adv_example_ts/master.m3u8",
                logoUrl = null,
                category = "Canlı Test & Tanıtım",
                channelNumber = 1,
                isFavorite = true
            ),
            Channel(
                playlistId = playlistId,
                name = "Big Buck Bunny HD (HLS)",
                streamUrl = "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8",
                logoUrl = "https://upload.wikimedia.org/wikipedia/commons/thumb/c/c5/Big_buck_bunny_poster_big.jpg/320px-Big_buck_bunny_poster_big.jpg",
                category = "Çocuk & Animasyon",
                channelNumber = 2,
                isFavorite = true
            ),
            Channel(
                playlistId = playlistId,
                name = "Soyken Haber HD Canlı (HLS)",
                streamUrl = "https://devstreaming-cdn.apple.com/videos/streaming/examples/bipbop_16x9/bipbop_16x9_variant.m3u8",
                logoUrl = "https://upload.wikimedia.org/wikipedia/commons/thumb/7/75/Deutsche_Welle_logo.svg/320px-Deutsche_Welle_logo.svg.png",
                category = "Haber",
                channelNumber = 3,
                isFavorite = false
            ),
            Channel(
                playlistId = playlistId,
                name = "Apple TV HLS Multi-Stream",
                streamUrl = "https://devstreaming-cdn.apple.com/videos/streaming/examples/bipbop_4x3/bipbop_4x3_variant.m3u8",
                logoUrl = "https://upload.wikimedia.org/wikipedia/commons/thumb/f/fa/Apple_logo_black.svg/320px-Apple_logo_black.svg.png",
                category = "Sinema & Dizi",
                channelNumber = 4,
                isFavorite = true
            ),
            Channel(
                playlistId = playlistId,
                name = "Adaptive Spor & Aksiyon HD (HLS)",
                streamUrl = "https://devstreaming-cdn.apple.com/videos/streaming/examples/img_bipbop_adv_example_fmp4/master.m3u8",
                logoUrl = "https://upload.wikimedia.org/wikipedia/en/thumb/f/f5/Red_Bull_TV_logo.svg/320px-Red_Bull_TV_logo.svg.png",
                category = "Spor & Aksiyon",
                channelNumber = 5,
                isFavorite = false
            ),
            Channel(
                playlistId = playlistId,
                name = "Bilim & Teknoloji TV (HLS)",
                streamUrl = "https://demo.unified-streaming.com/k8s/features/stable/video/tears-of-steel/tears-of-steel.ism/.m3u8",
                logoUrl = null,
                category = "Belgesel & Bilim",
                channelNumber = 6,
                isFavorite = true
            )
        )
    }
}
