package com.example.ui.components

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.billing.BillingUser
import com.example.billing.PremiumFeature
import com.example.billing.PremiumStatus
import com.example.billing.SubscriptionConfig
import com.example.billing.SubscriptionPlanOffer
import com.example.ui.theme.DarkBackground
import com.example.ui.theme.DarkOutline
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.DarkSurfaceVariant
import com.example.ui.theme.SoykenGold
import com.example.ui.theme.SoykenGoldDark
import com.example.ui.theme.SuccessGreen
import com.example.ui.tv.rememberIsAndroidTv
import com.example.ui.tv.soykenTvFocus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun openPlayStoreSubscriptionsPage(context: Context): Boolean {
    val intent = Intent(
        Intent.ACTION_VIEW,
        Uri.parse(SubscriptionConfig.PLAY_MANAGE_SUBSCRIPTIONS_URL)
    ).apply {
        if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    return try {
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}

@Composable
fun PremiumPaywallDialog(
    isOpen: Boolean,
    highlightedFeature: PremiumFeature?,
    premiumStatus: PremiumStatus,
    currentUser: BillingUser?,
    offers: List<SubscriptionPlanOffer>,
    isLoadingOffers: Boolean,
    isVerifying: Boolean,
    billingNotice: String?,
    onDismiss: () -> Unit,
    onSignInWithGoogle: () -> Unit,
    onSignOut: () -> Unit,
    onPurchaseOffer: (SubscriptionPlanOffer) -> Unit,
    onRestorePurchases: () -> Unit,
    onRetryLoadOffers: () -> Unit
) {
    if (!isOpen) return

    val context = LocalContext.current
    val isTv = rememberIsAndroidTv()
    val isPremiumActive = premiumStatus.isEffectivelyPremium()

    var selectedBasePlanId by remember(offers) {
        mutableStateOf(
            offers.firstOrNull { it.basePlanId == SubscriptionConfig.BASE_PLAN_YEARLY }?.basePlanId
                ?: offers.firstOrNull()?.basePlanId
                ?: SubscriptionConfig.BASE_PLAN_YEARLY
        )
    }

    LaunchedEffect(offers) {
        if (offers.isNotEmpty() && offers.none { it.basePlanId == selectedBasePlanId }) {
            selectedBasePlanId = offers.firstOrNull {
                it.basePlanId == SubscriptionConfig.BASE_PLAN_YEARLY
            }?.basePlanId ?: offers.first().basePlanId
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true
        )
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = if (isTv) 32.dp else 14.dp, vertical = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            val useTwoColumnTvLayout = isTv || maxWidth >= 680.dp
            val containerMaxWidth = if (useTwoColumnTvLayout) 920.dp else 520.dp

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = containerMaxWidth)
                    .heightIn(max = if (isTv) 680.dp else 760.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(DarkBackground)
                    .border(1.5.dp, SoykenGold.copy(alpha = 0.55f), RoundedCornerShape(22.dp))
                    .padding(if (isTv) 24.dp else 18.dp)
                    .verticalScroll(rememberScrollState())
                    .testTag("premium_paywall_dialog")
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
                                .size(44.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    Brush.linearGradient(
                                        listOf(SoykenGold, SoykenGoldDark)
                                    )
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.WorkspacePremium,
                                contentDescription = null,
                                tint = Color.Black,
                                modifier = Modifier.size(26.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "SOYKEN ",
                                    style = MaterialTheme.typography.titleLarge.copy(
                                        fontWeight = FontWeight.ExtraBold,
                                        letterSpacing = 0.8.sp
                                    ),
                                    color = Color(0xFFF5F5F7)
                                )
                                Text(
                                    text = "PREMIUM",
                                    style = MaterialTheme.typography.titleLarge.copy(
                                        fontWeight = FontWeight.ExtraBold,
                                        letterSpacing = 0.8.sp
                                    ),
                                    color = SoykenGold
                                )
                            }
                            Text(
                                text = "Google Play güvencesiyle sınırsız 4K ve Turbo yayın deneyimi",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFFA2A2B4)
                            )
                        }
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(48.dp)
                            .soykenTvFocus(CircleShape)
                            .testTag("close_premium_dialog_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Kapat",
                            tint = Color(0xFFA2A2B4)
                        )
                    }
                }

                // Highlighted feature callout if opened from a specific Premium lock
                if (highlightedFeature != null && !isPremiumActive) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(SoykenGold.copy(alpha = 0.14f))
                            .border(1.dp, SoykenGold.copy(alpha = 0.55f), RoundedCornerShape(12.dp))
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = featureIcon(highlightedFeature),
                            contentDescription = null,
                            tint = SoykenGold,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = highlightedFeature.title,
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                                color = SoykenGold
                            )
                            Text(
                                text = highlightedFeature.subtitle,
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFFF5F5F7)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                if (useTwoColumnTvLayout) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(20.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Column(modifier = Modifier.weight(1.05f)) {
                            FeaturesShowcaseSection(highlightedFeature = highlightedFeature)
                        }
                        Column(modifier = Modifier.weight(0.95f)) {
                            AccountAndPlansSection(
                                isPremiumActive = isPremiumActive,
                                premiumStatus = premiumStatus,
                                currentUser = currentUser,
                                offers = offers,
                                selectedBasePlanId = selectedBasePlanId,
                                onSelectBasePlan = { selectedBasePlanId = it },
                                isLoadingOffers = isLoadingOffers,
                                isVerifying = isVerifying,
                                billingNotice = billingNotice,
                                onSignInWithGoogle = onSignInWithGoogle,
                                onSignOut = onSignOut,
                                onPurchaseOffer = onPurchaseOffer,
                                onRestorePurchases = onRestorePurchases,
                                onRetryLoadOffers = onRetryLoadOffers,
                                onManageInPlay = { openPlayStoreSubscriptionsPage(context) }
                            )
                        }
                    }
                } else {
                    AccountAndPlansSection(
                        isPremiumActive = isPremiumActive,
                        premiumStatus = premiumStatus,
                        currentUser = currentUser,
                        offers = offers,
                        selectedBasePlanId = selectedBasePlanId,
                        onSelectBasePlan = { selectedBasePlanId = it },
                        isLoadingOffers = isLoadingOffers,
                        isVerifying = isVerifying,
                        billingNotice = billingNotice,
                        onSignInWithGoogle = onSignInWithGoogle,
                        onSignOut = onSignOut,
                        onPurchaseOffer = onPurchaseOffer,
                        onRestorePurchases = onRestorePurchases,
                        onRetryLoadOffers = onRetryLoadOffers,
                        onManageInPlay = { openPlayStoreSubscriptionsPage(context) }
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    FeaturesShowcaseSection(highlightedFeature = highlightedFeature)
                }

                Spacer(modifier = Modifier.height(14.dp))
                HorizontalDivider(color = DarkOutline)
                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = "Ücretsiz sürümde Canlı TV, 1 kişisel M3U/M3U8 listesi, favoriler ve 1080p HD oynatma her zaman ücretsizdir. " +
                        "Premium abonelik yalnızca Google Play üzerinden işlenir; istediğiniz zaman Google Play Abonelikler bölümünden iptal edebilirsiniz.",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                    color = Color(0xFFA2A2B4)
                )
            }
        }
    }
}

@Composable
private fun FeaturesShowcaseSection(highlightedFeature: PremiumFeature?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(DarkSurface)
            .border(1.dp, DarkOutline, RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = "PREMİUM AVANTAJLARI",
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            ),
            color = SoykenGold
        )

        PremiumFeature.entries.forEach { feature ->
            val isHighlighted = feature == highlightedFeature
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (isHighlighted) SoykenGold.copy(alpha = 0.12f)
                        else DarkSurfaceVariant.copy(alpha = 0.55f)
                    )
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(SoykenGold.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = featureIcon(feature),
                        contentDescription = null,
                        tint = SoykenGold,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = feature.title,
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                        color = Color(0xFFF5F5F7)
                    )
                    Text(
                        text = feature.subtitle,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                        color = Color(0xFFA2A2B4)
                    )
                }
            }
        }
    }
}

@Composable
private fun AccountAndPlansSection(
    isPremiumActive: Boolean,
    premiumStatus: PremiumStatus,
    currentUser: BillingUser?,
    offers: List<SubscriptionPlanOffer>,
    selectedBasePlanId: String,
    onSelectBasePlan: (String) -> Unit,
    isLoadingOffers: Boolean,
    isVerifying: Boolean,
    billingNotice: String?,
    onSignInWithGoogle: () -> Unit,
    onSignOut: () -> Unit,
    onPurchaseOffer: (SubscriptionPlanOffer) -> Unit,
    onRestorePurchases: () -> Unit,
    onRetryLoadOffers: () -> Unit,
    onManageInPlay: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Account card
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(DarkSurface)
                .border(1.dp, DarkOutline, RoundedCornerShape(14.dp))
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = Icons.Default.AccountCircle,
                    contentDescription = null,
                    tint = if (currentUser != null) SoykenGold else Color(0xFFA2A2B4),
                    modifier = Modifier.size(30.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = currentUser?.displayName?.takeIf { it.isNotBlank() }
                            ?: currentUser?.email
                            ?: "Google Hesabı Bağlı Değil",
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                        color = Color(0xFFF5F5F7),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = if (currentUser != null) {
                            currentUser.email ?: "Abonelik bu hesapla sunucuda eşitlenir"
                        } else {
                            "Aboneliğinizi tüm cihazlarınızda doğrulamak için giriş yapın"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFFA2A2B4),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            if (currentUser != null) {
                IconButton(
                    onClick = onSignOut,
                    modifier = Modifier
                        .size(48.dp)
                        .soykenTvFocus(CircleShape)
                        .testTag("premium_sign_out_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Logout,
                        contentDescription = "Çıkış Yap",
                        tint = Color(0xFFA2A2B4),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        if (isPremiumActive) {
            // Active Server-Verified Premium Card
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(SoykenGold.copy(alpha = 0.14f))
                    .border(1.5.dp, SoykenGold, RoundedCornerShape(16.dp))
                    .padding(16.dp)
                    .testTag("active_premium_status_card"),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Verified,
                        contentDescription = null,
                        tint = SuccessGreen,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "${premiumStatus.planDisplayName} Aktif",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
                        color = SoykenGold
                    )
                }

                val expiryText = if (premiumStatus.expiryTimeMillis > 0L) {
                    val fmt = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
                    val label = if (premiumStatus.autoRenewing) "Yenileme Tarihi" else "Bitiş Tarihi"
                    "$label: ${fmt.format(Date(premiumStatus.expiryTimeMillis))}"
                } else {
                    "Sunucu tarafından doğrulandı"
                }

                Text(
                    text = expiryText,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFF5F5F7)
                )

                Text(
                    text = "Tüm Premium özellikleri (4K Ultra HD, Turbo bağlantı, sınırsız M3U listesi, PiP ve sınırsız hatırlatıcı) açık.",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFA2A2B4)
                )

                Button(
                    onClick = onManageInPlay,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .soykenTvFocus(RoundedCornerShape(12.dp))
                        .testTag("manage_subscription_play_button"),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SoykenGold,
                        contentColor = Color.Black
                    )
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Google Play'de Yönet", fontWeight = FontWeight.Bold)
                }
            }
        } else {
            // Plan Cards (monthly & yearly)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(DarkSurface)
                    .border(1.dp, DarkOutline, RoundedCornerShape(16.dp))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "ABONELİK PLANI SEÇİN",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    ),
                    color = SoykenGold
                )

                if (isLoadingOffers) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            color = SoykenGold,
                            strokeWidth = 2.5.dp,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Google Play fiyatları yükleniyor...",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFA2A2B4)
                        )
                    }
                } else if (offers.isEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(DarkSurfaceVariant.copy(alpha = 0.65f))
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = billingNotice ?: "Google Play abonelik planları şu an alınamadı.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFF5F5F7)
                        )
                        OutlinedButton(
                            onClick = onRetryLoadOffers,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .soykenTvFocus(RoundedCornerShape(10.dp))
                                .testTag("retry_load_offers_button"),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = null,
                                tint = SoykenGold,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Planları Yenile", color = SoykenGold, fontWeight = FontWeight.Bold)
                        }
                    }
                } else {
                    offers.forEach { offer ->
                        val isSelected = offer.basePlanId == selectedBasePlanId
                        val cardShape = RoundedCornerShape(14.dp)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(cardShape)
                                .background(
                                    if (isSelected) SoykenGold.copy(alpha = 0.16f)
                                    else DarkSurfaceVariant.copy(alpha = 0.7f)
                                )
                                .border(
                                    width = if (isSelected) 1.5.dp else 1.dp,
                                    color = if (isSelected) SoykenGold else DarkOutline,
                                    shape = cardShape
                                )
                                .soykenTvFocus(cardShape)
                                .clickable { onSelectBasePlan(offer.basePlanId) }
                                .padding(horizontal = 14.dp, vertical = 12.dp)
                                .testTag("plan_card_${offer.basePlanId}"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = offer.title,
                                        style = MaterialTheme.typography.titleSmall.copy(
                                            fontWeight = FontWeight.ExtraBold
                                        ),
                                        color = if (isSelected) SoykenGold else Color(0xFFF5F5F7)
                                    )
                                    if (offer.badge != null) {
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(SoykenGold)
                                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                        ) {
                                            Text(
                                                text = offer.badge,
                                                style = MaterialTheme.typography.labelSmall.copy(
                                                    fontSize = 9.sp,
                                                    fontWeight = FontWeight.ExtraBold
                                                ),
                                                color = Color.Black
                                            )
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = offer.subtitle,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFFA2A2B4)
                                )
                            }

                            Spacer(modifier = Modifier.width(10.dp))

                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = offer.formattedPrice,
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontWeight = FontWeight.ExtraBold
                                    ),
                                    color = Color(0xFFF5F5F7)
                                )
                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = "Seçili",
                                        tint = SoykenGold,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Primary Action Button: Google Sign-In first (required for zero-trust account binding), then Play Billing
                if (currentUser == null) {
                    Button(
                        onClick = onSignInWithGoogle,
                        enabled = !isVerifying,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .soykenTvFocus(RoundedCornerShape(12.dp))
                            .testTag("premium_google_sign_in_button"),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = SoykenGold,
                            contentColor = Color.Black
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.AccountCircle,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Devam Etmek İçin Google ile Giriş Yap",
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                } else {
                    val chosenOffer = offers.firstOrNull { it.basePlanId == selectedBasePlanId }
                        ?: offers.firstOrNull()
                    Button(
                        onClick = { chosenOffer?.let(onPurchaseOffer) },
                        enabled = !isVerifying && chosenOffer != null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .soykenTvFocus(RoundedCornerShape(12.dp))
                            .testTag("premium_subscribe_button"),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = SoykenGold,
                            contentColor = Color.Black
                        )
                    ) {
                        if (isVerifying) {
                            CircularProgressIndicator(
                                color = Color.Black,
                                strokeWidth = 2.5.dp,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text("Sunucuda Doğrulanıyor...", fontWeight = FontWeight.ExtraBold)
                        } else {
                            Icon(
                                imageVector = Icons.Default.WorkspacePremium,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = chosenOffer?.let {
                                    "Google Play ile Başlat • ${it.formattedPrice}"
                                } ?: "Google Play Planı Bekleniyor",
                                fontWeight = FontWeight.ExtraBold
                            )
                        }
                    }
                }
            }
        }

        // Restore Purchases & Manage Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = onRestorePurchases,
                enabled = !isVerifying,
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .soykenTvFocus(RoundedCornerShape(12.dp))
                    .testTag("restore_purchases_button"),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = null,
                    tint = SoykenGold,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Geri Yükle",
                    color = SoykenGold,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                )
            }

            if (!isPremiumActive) {
                OutlinedButton(
                    onClick = onManageInPlay,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .soykenTvFocus(RoundedCornerShape(12.dp))
                        .testTag("open_play_subscriptions_button"),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = null,
                        tint = Color(0xFFA2A2B4),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Play Yönetimi",
                        color = Color(0xFFF5F5F7),
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
                    )
                }
            }
        }
    }
}

private fun featureIcon(feature: PremiumFeature): ImageVector = when (feature) {
    PremiumFeature.UNLIMITED_PLAYLISTS -> Icons.Default.PlaylistAdd
    PremiumFeature.ULTRA_HD_4K -> Icons.Default.HighQuality
    PremiumFeature.MANUAL_STREAM_TRACKS -> Icons.Default.Tune
    PremiumFeature.TURBO_CACHE -> Icons.Default.Bolt
    PremiumFeature.BACKGROUND_MINI_PIP -> Icons.Default.PictureInPictureAlt
    PremiumFeature.UNLIMITED_REMINDERS -> Icons.Default.NotificationsActive
}
