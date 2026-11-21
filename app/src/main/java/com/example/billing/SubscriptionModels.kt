package com.example.billing

import android.util.Log
import androidx.annotation.Keep
import com.example.data.model.Playlist
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp
import org.json.JSONArray
import org.json.JSONObject

object SubscriptionConfig {
    const val PACKAGE_NAME = "com.aistudio.soykenplay.txvqen"
    const val SUBSCRIPTION_PRODUCT_ID = "soyken_premium"
    const val BASE_PLAN_MONTHLY = "monthly"
    const val BASE_PLAN_YEARLY = "yearly"
    const val FREE_MAX_USER_PLAYLISTS = 1
    const val FREE_MAX_REMINDERS = 2
    const val DEMO_PLAYLIST_SOURCE_URL = "demo://soyken_play"
    const val PLAY_MANAGE_SUBSCRIPTIONS_URL =
        "https://play.google.com/store/account/subscriptions?sku=$SUBSCRIPTION_PRODUCT_ID&package=$PACKAGE_NAME"
    const val OFFLINE_CACHE_GRACE_MS = 24L * 60L * 60L * 1000L
}

enum class PremiumFeature(
    val title: String,
    val subtitle: String,
    val badge: String
) {
    UNLIMITED_PLAYLISTS(
        title = "Sınırsız M3U / M3U8 Oynatma Listesi",
        subtitle = "Ücretsiz planda 1 kişisel liste ekleyebilirsiniz. Premium ile sınırsız liste yönetin.",
        badge = "LİSTE"
    ),
    ULTRA_HD_4K(
        title = "4K Ultra HD (2160p) Oynatma",
        subtitle = "Ücretsiz planda 1080p Full HD dahil. Premium ile desteklenen yayınlarda 4K Ultra HD izleyin.",
        badge = "4K"
    ),
    MANUAL_STREAM_TRACKS(
        title = "Gelişmiş Manuel Akış Seçimi",
        subtitle = "Çoklu bitrate HLS/DASH yayınlarında belirli çözünürlük ve FPS katmanına doğrudan sabitleyin.",
        badge = "AKIŞ"
    ),
    TURBO_CACHE(
        title = "Gelişmiş Önbellek & Turbo Bağlantı",
        subtitle = "Anında kanal açılışı, 200 MB VOD disk önbelleği, akıllı ön-ısıtma ve düşük gecikmeli Wi-Fi kilidi.",
        badge = "TURBO"
    ),
    BACKGROUND_MINI_PIP(
        title = "Arka Planda Mini Oynatma & PiP",
        subtitle = "Uygulama içi sürüklenebilir mini oynatıcı ve sistem Ekran Üstü PiP penceresi ile kesintisiz izleyin.",
        badge = "PiP"
    ),
    UNLIMITED_REMINDERS(
        title = "Sınırsız Yayın Hatırlatıcısı",
        subtitle = "Ücretsiz planda en fazla 2 aktif hatırlatıcı kurulabilir. Premium ile dilediğiniz kadar alarm ekleyin.",
        badge = "ALARM"
    )
}

@Keep
@IgnoreExtraProperties
data class FirestorePremiumEntitlement(
    val active: Boolean = false,
    val subscriptionState: String = "INACTIVE",
    val productId: String = "",
    val basePlanId: String = "",
    val expiryTimeMillis: Long = 0L,
    val autoRenewing: Boolean = false,
    val orderId: String = "",
    @ServerTimestamp val verifiedAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

data class PremiumStatus(
    val isActive: Boolean = false,
    val subscriptionState: String = "INACTIVE",
    val productId: String = SubscriptionConfig.SUBSCRIPTION_PRODUCT_ID,
    val basePlanId: String = "",
    val expiryTimeMillis: Long = 0L,
    val autoRenewing: Boolean = false,
    val verifiedAtMillis: Long = 0L,
    val fromOfflineCache: Boolean = false
) {
    fun isEffectivelyPremium(nowMillis: Long = System.currentTimeMillis()): Boolean {
        if (!isActive) return false
        if (expiryTimeMillis > 0L && nowMillis > expiryTimeMillis) return false
        if (fromOfflineCache && verifiedAtMillis > 0L &&
            (nowMillis - verifiedAtMillis) > SubscriptionConfig.OFFLINE_CACHE_GRACE_MS
        ) {
            return false
        }
        return true
    }

    val planDisplayName: String
        get() = when (basePlanId) {
            SubscriptionConfig.BASE_PLAN_YEARLY -> "Yıllık Premium"
            SubscriptionConfig.BASE_PLAN_MONTHLY -> "Aylık Premium"
            else -> if (isActive) "Soyken Premium" else "Ücretsiz Plan"
        }
}

data class SubscriptionPlanOffer(
    val productId: String,
    val basePlanId: String,
    val offerToken: String,
    val formattedPrice: String,
    val billingPeriodIso: String,
    val title: String,
    val subtitle: String,
    val badge: String? = null
)

data class BillingUser(
    val uid: String,
    val email: String?,
    val displayName: String?
)

sealed class PurchaseEvent {
    data class VerifiedPurchaseReady(
        val purchaseToken: String,
        val productId: String,
        val orderId: String?
    ) : PurchaseEvent()

    data object Pending : PurchaseEvent()
    data object Canceled : PurchaseEvent()
    data class Error(val message: String) : PurchaseEvent()
}

object PremiumGatePolicy {
    fun countUserPlaylists(playlists: List<Playlist>): Int {
        return playlists.count { it.sourceUrl != SubscriptionConfig.DEMO_PLAYLIST_SOURCE_URL }
    }

    fun canAddAnotherUserPlaylist(isPremium: Boolean, playlists: List<Playlist>): Boolean {
        if (isPremium) return true
        return countUserPlaylists(playlists) < SubscriptionConfig.FREE_MAX_USER_PLAYLISTS
    }

    fun canAddAnotherReminder(isPremium: Boolean, activeReminderCount: Int): Boolean {
        if (isPremium) return true
        return activeReminderCount < SubscriptionConfig.FREE_MAX_REMINDERS
    }

    fun isQualityHeightAllowedForTier(isPremium: Boolean, height: Int): Boolean {
        if (isPremium) return true
        return height <= 1080
    }
}

enum class OperationType {
    CREATE, UPDATE, DELETE, LIST, GET, WRITE
}

fun handleFirestoreError(
    error: Exception,
    operationType: OperationType,
    path: String?
) {
    val user = runCatching { FirebaseAuth.getInstance().currentUser }.getOrNull()
    val payload = JSONObject().apply {
        put("error", error.message ?: "Unknown Firestore error")
        put("operationType", operationType.name.lowercase())
        put("path", path ?: JSONObject.NULL)
        put(
            "authInfo",
            JSONObject().apply {
                put("userId", user?.uid ?: JSONObject.NULL)
                put("email", user?.email ?: JSONObject.NULL)
                put("emailVerified", user?.isEmailVerified ?: false)
                put("isAnonymous", user?.isAnonymous ?: false)
                val providers = JSONArray()
                user?.providerData?.forEach { info ->
                    providers.put(
                        JSONObject().apply {
                            put("providerId", info.providerId)
                            put("displayName", info.displayName ?: JSONObject.NULL)
                            put("email", info.email ?: JSONObject.NULL)
                        }
                    )
                }
                put("providerInfo", providers)
            }
        )
    }
    Log.e("FirestoreError", payload.toString(), error)
}
