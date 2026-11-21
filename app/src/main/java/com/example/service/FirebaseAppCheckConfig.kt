package com.example.service

import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import kotlinx.coroutines.tasks.await
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Centralized, idempotent Firebase App Check bootstrap and token helper.
 *
 * - Release builds initialize [com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory]
 *   in [com.example.SoykenPlayApplication.onCreate] before any Firebase service runs.
 * - Debug builds initialize [com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory]
 *   in [com.example.MainActivity.onCreate] before ViewModels, FirebaseAuth, or Firestore run.
 */
object FirebaseAppCheckConfig {
    private const val TAG = "AppCheckInit"
    private val initialized = AtomicBoolean(false)

    fun initialize(context: Context, intent: Intent? = null) {
        if (initialized.get()) return
        synchronized(this) {
            if (initialized.get()) return
            try {
                val appContext = context.applicationContext
                if (FirebaseApp.getApps(appContext).isEmpty()) {
                    FirebaseApp.initializeApp(appContext)
                }
                if (FirebaseApp.getApps(appContext).isEmpty()) {
                    Log.w(TAG, "FirebaseApp is not initialized; skipping App Check setup.")
                    return
                }
                AppCheckProviderInstaller.install(appContext, intent)
                initialized.set(true)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize Firebase App Check", e)
            }
        }
    }

    /**
     * Fetches the current App Check JWT token for backend HTTP verification (`X-Firebase-AppCheck`).
     * Never logs raw token values.
     */
    suspend fun getAppCheckToken(forceRefresh: Boolean = false): String? {
        return try {
            val result = FirebaseAppCheck.getInstance()
                .getAppCheckToken(forceRefresh)
                .await()
            result.token.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            Log.w(TAG, "Unable to obtain Firebase App Check token (forceRefresh=$forceRefresh): ${e.message}")
            null
        }
    }
}
