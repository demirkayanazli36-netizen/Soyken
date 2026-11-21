package com.example

import com.example.billing.PremiumGatePolicy
import com.example.billing.PremiumStatus
import com.example.billing.SubscriptionConfig
import com.example.data.model.Playlist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PremiumGatePolicyTest {

    private val demoPlaylist = Playlist(
        id = 1L,
        name = "Soyken Play Demo",
        sourceUrl = SubscriptionConfig.DEMO_PLAYLIST_SOURCE_URL,
        channelCount = 2
    )

    private val userPlaylist1 = Playlist(
        id = 2L,
        name = "Kişisel Liste 1",
        sourceUrl = "https://example.com/list1.m3u8",
        channelCount = 10
    )

    private val userPlaylist2 = Playlist(
        id = 3L,
        name = "Kişisel Liste 2",
        sourceUrl = "https://example.com/list2.m3u8",
        channelCount = 20
    )

    @Test
    fun freeTierAllowsOneUserPlaylistExcludingDemo() {
        assertEquals(0, PremiumGatePolicy.countUserPlaylists(listOf(demoPlaylist)))
        assertTrue(PremiumGatePolicy.canAddAnotherUserPlaylist(false, listOf(demoPlaylist)))

        assertEquals(1, PremiumGatePolicy.countUserPlaylists(listOf(demoPlaylist, userPlaylist1)))
        assertFalse(PremiumGatePolicy.canAddAnotherUserPlaylist(false, listOf(demoPlaylist, userPlaylist1)))
    }

    @Test
    fun premiumTierAllowsUnlimitedPlaylists() {
        assertTrue(
            PremiumGatePolicy.canAddAnotherUserPlaylist(
                isPremium = true,
                playlists = listOf(demoPlaylist, userPlaylist1, userPlaylist2)
            )
        )
    }

    @Test
    fun freeTierAllowsTwoRemindersAndPremiumUnlimited() {
        assertTrue(PremiumGatePolicy.canAddAnotherReminder(false, 0))
        assertTrue(PremiumGatePolicy.canAddAnotherReminder(false, 1))
        assertFalse(PremiumGatePolicy.canAddAnotherReminder(false, 2))
        assertTrue(PremiumGatePolicy.canAddAnotherReminder(true, 10))
    }

    @Test
    fun freeTierAllowsUpTo1080pAndGates4k() {
        assertTrue(PremiumGatePolicy.isQualityHeightAllowedForTier(false, 720))
        assertTrue(PremiumGatePolicy.isQualityHeightAllowedForTier(false, 1080))
        assertFalse(PremiumGatePolicy.isQualityHeightAllowedForTier(false, 2160))
        assertTrue(PremiumGatePolicy.isQualityHeightAllowedForTier(true, 2160))
    }

    @Test
    fun premiumStatusEnforcesExpiryAndOfflineCacheGraceWindow() {
        val now = 1_700_000_000_000L
        val validLive = PremiumStatus(
            isActive = true,
            basePlanId = SubscriptionConfig.BASE_PLAN_YEARLY,
            expiryTimeMillis = now + 3_600_000L,
            verifiedAtMillis = now,
            fromOfflineCache = false
        )
        assertTrue(validLive.isEffectivelyPremium(now))

        val expired = validLive.copy(expiryTimeMillis = now - 1_000L)
        assertFalse(expired.isEffectivelyPremium(now))

        val freshCache = validLive.copy(
            verifiedAtMillis = now - 60_000L,
            fromOfflineCache = true
        )
        assertTrue(freshCache.isEffectivelyPremium(now))

        val staleCache = validLive.copy(
            verifiedAtMillis = now - SubscriptionConfig.OFFLINE_CACHE_GRACE_MS - 1_000L,
            fromOfflineCache = true
        )
        assertFalse(staleCache.isEffectivelyPremium(now))
    }
}
