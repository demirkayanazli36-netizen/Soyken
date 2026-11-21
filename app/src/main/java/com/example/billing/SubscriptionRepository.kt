package com.example.billing

import android.content.Context
import android.util.Log
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.example.BuildConfig
import com.example.R
import com.example.data.network.CloudflareDns
import com.example.service.FirebaseAppCheckConfig
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.Firebase
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Zero-trust subscription entitlement & Google Sign-In repository.
 *
 * - Authenticates users strictly via Jetpack Credential Manager (Google Sign-In).
 * - Reads `/users/{uid}/entitlements/premium` from the provisioned named Firestore database.
 * - Verifies Google Play purchaseTokens exclusively via the backend Cloud Function
 *   (`BuildConfig.BILLING_VERIFY_URL`), never trusting client-only state.
 */
class SubscriptionRepository(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val httpClient by lazy {
        CloudflareDns.createHttpClient(
            connectTimeoutMs = 10_000L,
            readTimeoutMs = 25_000L
        )
    }

    private val firebaseAvailable: Boolean
        get() = runCatching {
            if (FirebaseApp.getApps(appContext).isEmpty()) {
                FirebaseApp.initializeApp(appContext) != null
            } else {
                true
            }
        }.getOrDefault(false)

    private val auth: FirebaseAuth?
        get() = if (firebaseAvailable) runCatching { Firebase.auth }.getOrNull() else null

    private val firestore: FirebaseFirestore? by lazy {
        if (!firebaseAvailable) {
            null
        } else {
            runCatching {
                val databaseId = appContext.getString(R.string.firestore_database_id)
                FirebaseFirestore.getInstance(databaseId)
            }.onFailure { err ->
                Log.e(TAG, "Failed to initialize named Firestore instance", err)
            }.getOrNull()
        }
    }

    fun observeAuthUser(): Flow<BillingUser?> {
        val firebaseAuth = auth ?: return flowOf(null)
        return callbackFlow {
            val listener = FirebaseAuth.AuthStateListener { state ->
                val user = state.currentUser
                trySend(
                    user?.let {
                        BillingUser(
                            uid = it.uid,
                            email = it.email,
                            displayName = it.displayName
                        )
                    }
                )
            }
            firebaseAuth.addAuthStateListener(listener)
            awaitClose { firebaseAuth.removeAuthStateListener(listener) }
        }
    }

    fun currentUser(): BillingUser? {
        val user = auth?.currentUser ?: return null
        return BillingUser(uid = user.uid, email = user.email, displayName = user.displayName)
    }

    /**
     * Silent auto sign-in using previously authorized Google credential (no popups on launch).
     * Uses a single [GetGoogleIdOption] per request for Android 16 (API 36) compatibility.
     */
    suspend fun attemptSilentSignIn(activityContext: Context): BillingUser? {
        val firebaseAuth = auth ?: return null
        firebaseAuth.currentUser?.let {
            return BillingUser(it.uid, it.email, it.displayName)
        }
        val webClientId = resolveWebClientId() ?: return null
        return try {
            val credentialManager = CredentialManager.create(activityContext)
            val option = GetGoogleIdOption.Builder()
                .setFilterByAuthorizedAccounts(true)
                .setServerClientId(webClientId)
                .setAutoSelectEnabled(true)
                .build()
            val request = GetCredentialRequest.Builder()
                .addCredentialOption(option)
                .build()
            val result = credentialManager.getCredential(activityContext, request)
            exchangeGoogleCredentialForFirebaseUser(result.credential, firebaseAuth)
        } catch (_: NoCredentialException) {
            null
        } catch (e: GetCredentialCancellationException) {
            Log.w("Auth", "Silent Google sign-in canceled", e)
            null
        } catch (e: GetCredentialException) {
            Log.w("Auth", "Silent Google sign-in unavailable: ${e.type}")
            null
        } catch (e: Exception) {
            Log.w("Auth", "Silent Google sign-in failed", e)
            null
        }
    }

    /**
     * Interactive Google Sign-In via Jetpack Credential Manager ([GetSignInWithGoogleOption]).
     */
    suspend fun signInWithGoogle(activityContext: Context): Result<BillingUser> {
        val firebaseAuth = auth
            ?: return Result.failure(IllegalStateException("Firebase oturum servisi bu cihazda başlatılamadı."))
        val webClientId = resolveWebClientId()
            ?: return Result.failure(IllegalStateException("Google OAuth istemci kimliği yapılandırılmamış."))

        return try {
            val credentialManager = CredentialManager.create(activityContext)
            val option = GetSignInWithGoogleOption.Builder(webClientId).build()
            val request = GetCredentialRequest.Builder()
                .addCredentialOption(option)
                .build()
            val result = credentialManager.getCredential(activityContext, request)
            val user = exchangeGoogleCredentialForFirebaseUser(result.credential, firebaseAuth)
                ?: return Result.failure(IllegalStateException("Google hesabı doğrulanamadı."))
            Result.success(user)
        } catch (e: GetCredentialCancellationException) {
            Log.w("Auth", "Google sign-in canceled by user: ${e.message}", e)
            Result.failure(e)
        } catch (e: NoCredentialException) {
            Log.w("Auth", "No Google credential available on device: ${e.message}", e)
            Result.failure(
                IllegalStateException(
                    "Bu cihazda kullanılabilir bir Google hesabı bulunamadı. Cihaz ayarlarından Google hesabınızı ekleyin veya Google Play Hizmetleri'ni güncelleyin."
                )
            )
        } catch (e: GetCredentialException) {
            Log.w("Auth", "Google sign-in failed: ${e.type} - ${e.message}", e)
            Result.failure(IllegalStateException("Google ile giriş yapılamadı: ${e.localizedMessage ?: e.type}"))
        } catch (e: Exception) {
            Log.e("Auth", "Unexpected Google sign-in failure", e)
            Result.failure(e)
        }
    }

    suspend fun signOut(activityContext: Context) {
        withContext(NonCancellable) {
            try {
                runCatching {
                    CredentialManager.create(activityContext)
                        .clearCredentialState(ClearCredentialStateRequest())
                }
            } finally {
                runCatching { auth?.signOut() }
                clearCachedEntitlement()
                clearPendingVerificationToken()
            }
        }
    }

    private suspend fun exchangeGoogleCredentialForFirebaseUser(
        credential: androidx.credentials.Credential,
        firebaseAuth: FirebaseAuth
    ): BillingUser? {
        if (credential is CustomCredential &&
            credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
        ) {
            val googleCred = GoogleIdTokenCredential.createFrom(credential.data)
            val firebaseCredential = GoogleAuthProvider.getCredential(googleCred.idToken, null)
            val authResult = firebaseAuth.signInWithCredential(firebaseCredential).await()
            val user = authResult.user ?: return null
            return BillingUser(uid = user.uid, email = user.email, displayName = user.displayName)
        }
        return null
    }

    /**
     * Observes `/users/{uid}/entitlements/premium` in real time.
     * NEVER attaches a listener when [uid] is blank.
     */
    fun observeServerEntitlement(uid: String): Flow<PremiumStatus> {
        if (uid.isBlank()) return flowOf(PremiumStatus())
        val db = firestore ?: return flowOf(loadBoundedOfflineCache(uid))
        val docPath = "users/$uid/entitlements/premium"

        return callbackFlow {
            // Emit bounded offline cache immediately if valid for this UID while live snapshot connects
            trySend(loadBoundedOfflineCache(uid))

            val registration = db.collection("users")
                .document(uid)
                .collection("entitlements")
                .document("premium")
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        handleFirestoreError(error, OperationType.GET, docPath)
                        close(error)
                        return@addSnapshotListener
                    }

                    if (snapshot == null || !snapshot.exists()) {
                        clearCachedEntitlement()
                        trySend(PremiumStatus(isActive = false, subscriptionState = "INACTIVE"))
                        return@addSnapshotListener
                    }

                    val raw = snapshot.toObject(
                        FirestorePremiumEntitlement::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                    if (raw == null) {
                        clearCachedEntitlement()
                        trySend(PremiumStatus(isActive = false, subscriptionState = "INACTIVE"))
                        return@addSnapshotListener
                    }

                    val verifiedAtMs = raw.verifiedAt?.toDate()?.time
                        ?: raw.updatedAt?.toDate()?.time
                        ?: System.currentTimeMillis()
                    val status = PremiumStatus(
                        isActive = raw.active,
                        subscriptionState = raw.subscriptionState,
                        productId = raw.productId.ifBlank { SubscriptionConfig.SUBSCRIPTION_PRODUCT_ID },
                        basePlanId = raw.basePlanId,
                        expiryTimeMillis = raw.expiryTimeMillis,
                        autoRenewing = raw.autoRenewing,
                        verifiedAtMillis = verifiedAtMs,
                        fromOfflineCache = false
                    )
                    if (status.isEffectivelyPremium()) {
                        saveVerifiedOfflineCache(uid, status)
                    } else {
                        clearCachedEntitlement()
                    }
                    trySend(status)
                }

            awaitClose { registration.remove() }
        }
    }

    /**
     * Sends the Google Play purchaseToken + Firebase Auth ID token to the backend Cloud Function
     * for Google Play Developer API Subscriptions V2 verification & server-side acknowledgement.
     */
    suspend fun verifyPurchaseWithServer(
        purchaseToken: String,
        productId: String = SubscriptionConfig.SUBSCRIPTION_PRODUCT_ID
    ): Result<PremiumStatus> = withContext(Dispatchers.IO) {
        val user = auth?.currentUser
            ?: return@withContext Result.failure(
                IllegalStateException("Aboneliğinizi doğrulamak için Google hesabınızla giriş yapın.")
            )

        savePendingVerificationToken(purchaseToken)

        val idToken = try {
            user.getIdToken(true).await()?.token
        } catch (e: Exception) {
            Log.e(TAG, "Failed to obtain Firebase ID token", e)
            null
        } ?: return@withContext Result.failure(
            IllegalStateException("Oturum belirteci alınamadı. Lütfen yeniden giriş yapın.")
        )

        val verifyUrl = BuildConfig.BILLING_VERIFY_URL
        val jsonPayload = JSONObject().apply {
            put("purchaseToken", purchaseToken)
            put("productId", productId)
        }.toString()

        FirebaseAppCheckConfig.initialize(appContext)
        var forceRefreshAppCheck = false
        var retriedInvalidAppCheck = false

        var lastError: Exception? = null
        val maxAttempts = 3
        for (attempt in 1..maxAttempts) {
            try {
                val appCheckToken = FirebaseAppCheckConfig.getAppCheckToken(
                    forceRefresh = forceRefreshAppCheck
                )
                forceRefreshAppCheck = false

                val requestBuilder = Request.Builder()
                    .url(verifyUrl)
                    .header("Authorization", "Bearer $idToken")
                    .header("Content-Type", "application/json")
                    .post(jsonPayload.toRequestBody("application/json; charset=utf-8".toMediaType()))

                if (!appCheckToken.isNullOrBlank()) {
                    requestBuilder.header("X-Firebase-AppCheck", appCheckToken)
                }

                httpClient.newCall(requestBuilder.build()).execute().use { response ->
                    val bodyString = response.body?.string().orEmpty()
                    if (response.isSuccessful) {
                        val json = JSONObject(bodyString)
                        val status = PremiumStatus(
                            isActive = json.optBoolean("active", false),
                            subscriptionState = json.optString("subscriptionState", "INACTIVE"),
                            productId = json.optString("productId", productId),
                            basePlanId = json.optString("basePlanId", ""),
                            expiryTimeMillis = json.optLong("expiryTimeMillis", 0L),
                            autoRenewing = json.optBoolean("autoRenewing", false),
                            verifiedAtMillis = json.optLong("verifiedAtMillis", System.currentTimeMillis()),
                            fromOfflineCache = false
                        )
                        clearPendingVerificationToken()
                        if (status.isEffectivelyPremium()) {
                            saveVerifiedOfflineCache(user.uid, status)
                        } else {
                            clearCachedEntitlement()
                        }
                        return@withContext Result.success(status)
                    } else {
                        val parsedErrorBody = runCatching { JSONObject(bodyString) }.getOrNull()
                        val serverCode = parsedErrorBody?.optString("error").orEmpty()
                        val serverMsg = parsedErrorBody?.optString("message")
                            ?.takeIf { it.isNotBlank() }

                        if (response.code == 401 &&
                            serverCode == "INVALID_APP_CHECK_TOKEN" &&
                            !retriedInvalidAppCheck &&
                            attempt < maxAttempts
                        ) {
                            retriedInvalidAppCheck = true
                            forceRefreshAppCheck = true
                            lastError = IllegalStateException(
                                serverMsg ?: "Uygulama güvenlik doğrulaması yenileniyor."
                            )
                            Log.w(TAG, "App Check token rejected by server; forcing refresh and retrying once.")
                        } else if (response.code in 400..499 && response.code != 408 && response.code != 429) {
                            if (response.code == 409) {
                                clearPendingVerificationToken()
                            }
                            return@withContext Result.failure(
                                IllegalStateException(
                                    serverMsg ?: "Satın alma sunucu tarafından doğrulanamadı (${response.code})."
                                )
                            )
                        } else {
                            lastError = IllegalStateException(
                                serverMsg ?: "Doğrulama sunucusu yanıt vermedi (${response.code})."
                            )
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                Log.w(TAG, "verifyPurchaseWithServer attempt $attempt failed: ${e.message}")
            }

            if (attempt < maxAttempts) {
                delay(attempt * 1_200L)
            }
        }

        Result.failure(
            lastError ?: IllegalStateException(
                "Satın alma doğrulanamadı. Bağlantı sağlandığında otomatik olarak tekrar denenecek."
            )
        )
    }

    fun getPendingVerificationToken(): String? {
        return prefs.getString(KEY_PENDING_PURCHASE_TOKEN, null)?.takeIf { it.isNotBlank() }
    }

    private fun savePendingVerificationToken(token: String) {
        prefs.edit().putString(KEY_PENDING_PURCHASE_TOKEN, token).apply()
    }

    private fun clearPendingVerificationToken() {
        prefs.edit().remove(KEY_PENDING_PURCHASE_TOKEN).apply()
    }

    private fun saveVerifiedOfflineCache(uid: String, status: PremiumStatus) {
        prefs.edit()
            .putString(KEY_CACHE_UID, uid)
            .putBoolean(KEY_CACHE_ACTIVE, status.isActive)
            .putString(KEY_CACHE_STATE, status.subscriptionState)
            .putString(KEY_CACHE_BASE_PLAN, status.basePlanId)
            .putLong(KEY_CACHE_EXPIRY_MS, status.expiryTimeMillis)
            .putBoolean(KEY_CACHE_AUTO_RENEW, status.autoRenewing)
            .putLong(KEY_CACHE_VERIFIED_AT_MS, status.verifiedAtMillis)
            .apply()
    }

    private fun loadBoundedOfflineCache(uid: String): PremiumStatus {
        val cachedUid = prefs.getString(KEY_CACHE_UID, null) ?: return PremiumStatus()
        if (cachedUid != uid) return PremiumStatus()
        val cached = PremiumStatus(
            isActive = prefs.getBoolean(KEY_CACHE_ACTIVE, false),
            subscriptionState = prefs.getString(KEY_CACHE_STATE, "INACTIVE") ?: "INACTIVE",
            productId = SubscriptionConfig.SUBSCRIPTION_PRODUCT_ID,
            basePlanId = prefs.getString(KEY_CACHE_BASE_PLAN, "") ?: "",
            expiryTimeMillis = prefs.getLong(KEY_CACHE_EXPIRY_MS, 0L),
            autoRenewing = prefs.getBoolean(KEY_CACHE_AUTO_RENEW, false),
            verifiedAtMillis = prefs.getLong(KEY_CACHE_VERIFIED_AT_MS, 0L),
            fromOfflineCache = true
        )
        return if (cached.isEffectivelyPremium()) cached else PremiumStatus()
    }

    private fun clearCachedEntitlement() {
        prefs.edit()
            .remove(KEY_CACHE_UID)
            .remove(KEY_CACHE_ACTIVE)
            .remove(KEY_CACHE_STATE)
            .remove(KEY_CACHE_BASE_PLAN)
            .remove(KEY_CACHE_EXPIRY_MS)
            .remove(KEY_CACHE_AUTO_RENEW)
            .remove(KEY_CACHE_VERIFIED_AT_MS)
            .apply()
    }

    private fun resolveWebClientId(): String? {
        return runCatching {
            appContext.getString(R.string.default_web_client_id).takeIf { it.isNotBlank() }
        }.onFailure { err ->
            Log.e(TAG, "Failed to resolve R.string.default_web_client_id", err)
        }.getOrNull()
    }

    companion object {
        private const val TAG = "SubscriptionRepository"
        private const val PREFS_NAME = "soyken_premium_verified_cache"
        private const val KEY_CACHE_UID = "cache_uid"
        private const val KEY_CACHE_ACTIVE = "cache_active"
        private const val KEY_CACHE_STATE = "cache_state"
        private const val KEY_CACHE_BASE_PLAN = "cache_base_plan"
        private const val KEY_CACHE_EXPIRY_MS = "cache_expiry_ms"
        private const val KEY_CACHE_AUTO_RENEW = "cache_auto_renew"
        private const val KEY_CACHE_VERIFIED_AT_MS = "cache_verified_at_ms"
        private const val KEY_PENDING_PURCHASE_TOKEN = "pending_purchase_token"

        fun unwrapFirestoreException(e: Throwable): FirebaseFirestoreException? {
            return e as? FirebaseFirestoreException ?: e.cause as? FirebaseFirestoreException
        }
    }
}
