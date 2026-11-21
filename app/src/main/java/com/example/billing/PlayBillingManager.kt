package com.example.billing

import android.app.Activity
import android.content.Context
import android.util.Log
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.queryProductDetails
import com.android.billingclient.api.queryPurchasesAsync
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Manages Google Play Billing Library 8.x interactions for Soyken Play subscriptions
 * (productId = "soyken_premium", base plans = "monthly" and "yearly").
 *
 * Security invariant:
 * - This manager NEVER grants Premium locally and NEVER blindly acknowledges purchases.
 * - Every PURCHASED transaction emits a [PurchaseEvent.VerifiedPurchaseReady] containing
 *   the purchaseToken so [SubscriptionRepository] verifies and acknowledges it server-side
 *   via Firebase Cloud Functions + Google Play Developer API Subscriptions V2.
 */
class PlayBillingManager(context: Context) : PurchasesUpdatedListener {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connectMutex = Mutex()

    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    private val _offers = MutableStateFlow<List<SubscriptionPlanOffer>>(emptyList())
    val offers: StateFlow<List<SubscriptionPlanOffer>> = _offers.asStateFlow()

    private val _isLoadingOffers = MutableStateFlow(false)
    val isLoadingOffers: StateFlow<Boolean> = _isLoadingOffers.asStateFlow()

    private val _billingNotice = MutableStateFlow<String?>(null)
    val billingNotice: StateFlow<String?> = _billingNotice.asStateFlow()

    private val _purchaseEvents = MutableSharedFlow<PurchaseEvent>(extraBufferCapacity = 8)
    val purchaseEvents: SharedFlow<PurchaseEvent> = _purchaseEvents.asSharedFlow()

    @Volatile
    private var cachedProductDetails: ProductDetails? = null

    private val billingClient: BillingClient = BillingClient.newBuilder(appContext)
        .setListener(this)
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder()
                .enableOneTimeProducts()
                .enablePrepaidPlans()
                .build()
        )
        .enableAutoServiceReconnection()
        .build()

    suspend fun ensureConnected(): Boolean {
        if (billingClient.isReady) {
            _isReady.value = true
            return true
        }

        return connectMutex.withLock {
            if (billingClient.isReady) {
                _isReady.value = true
                return@withLock true
            }

            val deferred = CompletableDeferred<Boolean>()
            try {
                billingClient.startConnection(object : BillingClientStateListener {
                    override fun onBillingSetupFinished(result: BillingResult) {
                        val ok = result.responseCode == BillingClient.BillingResponseCode.OK
                        _isReady.value = ok
                        if (!ok) {
                            Log.w(TAG, "Billing setup failed: ${result.responseCode} ${result.debugMessage}")
                        }
                        if (!deferred.isCompleted) {
                            deferred.complete(ok)
                        }
                    }

                    override fun onBillingServiceDisconnected() {
                        _isReady.value = false
                        Log.w(TAG, "Billing service disconnected; auto-reconnection enabled.")
                        if (!deferred.isCompleted) {
                            deferred.complete(false)
                        }
                    }
                })
            } catch (e: Exception) {
                Log.w(TAG, "BillingClient.startConnection threw exception", e)
                if (!deferred.isCompleted) deferred.complete(false)
            }

            withTimeoutOrNull(8_000L) { deferred.await() } ?: false
        }
    }

    suspend fun loadSubscriptionOffers() {
        _isLoadingOffers.value = true
        _billingNotice.value = null
        try {
            val connected = ensureConnected()
            if (!connected) {
                _billingNotice.value =
                    "Google Play Faturalandırma servisine şu an ulaşılamıyor. Cihazınızda Google Play oturumunun açık olduğundan emin olun."
                return
            }

            val productList = listOf(
                QueryProductDetailsParams.Product.newBuilder()
                    .setProductId(SubscriptionConfig.SUBSCRIPTION_PRODUCT_ID)
                    .setProductType(BillingClient.ProductType.SUBS)
                    .build()
            )
            val params = QueryProductDetailsParams.newBuilder()
                .setProductList(productList)
                .build()

            val result = billingClient.queryProductDetails(params)
            val billingResult = result.billingResult
            if (billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
                Log.w(TAG, "queryProductDetails failed: ${billingResult.responseCode} ${billingResult.debugMessage}")
                _billingNotice.value = "Abonelik planları Google Play'den alınamadı (${billingResult.responseCode})."
                return
            }

            val productDetails = result.productDetailsList
                ?.firstOrNull { it.productId == SubscriptionConfig.SUBSCRIPTION_PRODUCT_ID }

            if (productDetails == null) {
                _offers.value = emptyList()
                _billingNotice.value =
                    "Abonelik planları henüz Google Play Console üzerinde aktif görünmüyor (soyken_premium: monthly / yearly)."
                return
            }

            cachedProductDetails = productDetails
            val offerDetailsList = productDetails.subscriptionOfferDetails.orEmpty()

            // Pick canonical offer for each base plan (monthly, yearly)
            val mappedOffers = buildList {
                findOfferForBasePlan(offerDetailsList, SubscriptionConfig.BASE_PLAN_MONTHLY)?.let { add(it) }
                findOfferForBasePlan(offerDetailsList, SubscriptionConfig.BASE_PLAN_YEARLY)?.let { add(it) }
            }

            _offers.value = mappedOffers
            if (mappedOffers.isEmpty()) {
                _billingNotice.value = "monthly veya yearly temel planları bulunamadı."
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to query subscription offers", e)
            _billingNotice.value = "Google Play abonelik bilgileri yüklenirken hata oluştu."
        } finally {
            _isLoadingOffers.value = false
        }
    }

    private fun findOfferForBasePlan(
        allOffers: List<ProductDetails.SubscriptionOfferDetails>,
        targetBasePlanId: String
    ): SubscriptionPlanOffer? {
        val matching = allOffers.filter { it.basePlanId == targetBasePlanId }
        if (matching.isEmpty()) return null

        // Prefer promotional/free-trial offer if present; otherwise base plan offer (offerId == null)
        val chosen = matching.firstOrNull { it.offerId != null } ?: matching.first()
        val recurringPhase = chosen.pricingPhases.pricingPhaseList.lastOrNull()
            ?: chosen.pricingPhases.pricingPhaseList.firstOrNull()
            ?: return null

        val isYearly = targetBasePlanId == SubscriptionConfig.BASE_PLAN_YEARLY
        return SubscriptionPlanOffer(
            productId = SubscriptionConfig.SUBSCRIPTION_PRODUCT_ID,
            basePlanId = targetBasePlanId,
            offerToken = chosen.offerToken,
            formattedPrice = recurringPhase.formattedPrice,
            billingPeriodIso = recurringPhase.billingPeriod,
            title = if (isYearly) "Yıllık Plan" else "Aylık Plan",
            subtitle = if (isYearly) {
                "Yıllık otomatik yenilenir • En avantajlı seçenek"
            } else {
                "Aylık otomatik yenilenir • İstediğiniz zaman iptal edin"
            },
            badge = if (isYearly) "EN AVANTAJLI" else null
        )
    }

    fun launchSubscriptionPurchase(
        activity: Activity,
        offer: SubscriptionPlanOffer,
        firebaseUid: String
    ): Boolean {
        val details = cachedProductDetails
        if (!billingClient.isReady || details == null) {
            _purchaseEvents.tryEmit(
                PurchaseEvent.Error("Google Play bağlantısı henüz hazır değil. Lütfen tekrar deneyin.")
            )
            return false
        }

        val productParams = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(details)
            .setOfferToken(offer.offerToken)
            .build()

        val flowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(productParams))
            .setObfuscatedAccountId(firebaseUid)
            .build()

        val result = billingClient.launchBillingFlow(activity, flowParams)
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            Log.w(TAG, "launchBillingFlow error: ${result.responseCode} ${result.debugMessage}")
            _purchaseEvents.tryEmit(
                PurchaseEvent.Error(mapBillingErrorMessage(result.responseCode))
            )
            return false
        }
        return true
    }

    suspend fun queryActiveSubscriptionPurchases(): List<Purchase> {
        if (!ensureConnected()) return emptyList()
        return try {
            val params = QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.SUBS)
                .build()
            val result = billingClient.queryPurchasesAsync(params)
            if (result.billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                result.purchasesList.filter { purchase ->
                    purchase.products.contains(SubscriptionConfig.SUBSCRIPTION_PRODUCT_ID) &&
                        purchase.purchaseState == Purchase.PurchaseState.PURCHASED
                }
            } else {
                Log.w(TAG, "queryPurchasesAsync failed: ${result.billingResult.responseCode}")
                emptyList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "queryPurchasesAsync exception", e)
            emptyList()
        }
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                val list = purchases.orEmpty()
                for (purchase in list) {
                    if (!purchase.products.contains(SubscriptionConfig.SUBSCRIPTION_PRODUCT_ID)) {
                        continue
                    }
                    when (purchase.purchaseState) {
                        Purchase.PurchaseState.PURCHASED -> {
                            _purchaseEvents.tryEmit(
                                PurchaseEvent.VerifiedPurchaseReady(
                                    purchaseToken = purchase.purchaseToken,
                                    productId = SubscriptionConfig.SUBSCRIPTION_PRODUCT_ID,
                                    orderId = purchase.orderId
                                )
                            )
                        }
                        Purchase.PurchaseState.PENDING -> {
                            _purchaseEvents.tryEmit(PurchaseEvent.Pending)
                        }
                        else -> Unit
                    }
                }
            }
            BillingClient.BillingResponseCode.USER_CANCELED -> {
                _purchaseEvents.tryEmit(PurchaseEvent.Canceled)
            }
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> {
                scope.launch {
                    val active = queryActiveSubscriptionPurchases()
                    if (active.isNotEmpty()) {
                        active.forEach { purchase ->
                            _purchaseEvents.tryEmit(
                                PurchaseEvent.VerifiedPurchaseReady(
                                    purchaseToken = purchase.purchaseToken,
                                    productId = SubscriptionConfig.SUBSCRIPTION_PRODUCT_ID,
                                    orderId = purchase.orderId
                                )
                            )
                        }
                    } else {
                        _purchaseEvents.tryEmit(
                            PurchaseEvent.Error("Bu abonelik hesabınızda zaten aktif görünüyor.")
                        )
                    }
                }
            }
            else -> {
                Log.w(TAG, "onPurchasesUpdated error: ${result.responseCode} ${result.debugMessage}")
                _purchaseEvents.tryEmit(PurchaseEvent.Error(mapBillingErrorMessage(result.responseCode)))
            }
        }
    }

    fun endConnection() {
        runCatching { billingClient.endConnection() }
        _isReady.value = false
    }

    private fun mapBillingErrorMessage(responseCode: Int): String {
        return when (responseCode) {
            BillingClient.BillingResponseCode.BILLING_UNAVAILABLE ->
                "Bu cihazda Google Play Faturalandırma kullanılamıyor."
            BillingClient.BillingResponseCode.ITEM_UNAVAILABLE ->
                "Seçilen abonelik planı şu an Google Play'de bulunamıyor."
            BillingClient.BillingResponseCode.NETWORK_ERROR,
            BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE,
            BillingClient.BillingResponseCode.SERVICE_DISCONNECTED ->
                "Google Play sunucularına bağlanılamadı. İnternet bağlantınızı kontrol edip tekrar deneyin."
            else -> "Satın alma işlemi tamamlanamadı (Kod: $responseCode)."
        }
    }

    companion object {
        private const val TAG = "PlayBillingManager"
    }
}
