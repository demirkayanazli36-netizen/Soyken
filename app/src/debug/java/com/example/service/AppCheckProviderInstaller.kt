package com.example.service

import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory

/**
 * Debug-only Firebase App Check provider installer.
 * Persists an optional `FIREBASE_APPCHECK_DEBUG_TOKEN` extra synchronously before installing
 * [DebugAppCheckProviderFactory] so local devices and emulators pass attestation.
 */
internal object AppCheckProviderInstaller {
    private const val TAG = "AppCheckInit"
    private const val DEBUG_TOKEN_EXTRA = "FIREBASE_APPCHECK_DEBUG_TOKEN"
    private const val DEBUG_STORE_PREFIX = "com.google.firebase.appcheck.debug.store."
    private const val DEBUG_SECRET_KEY = "com.google.firebase.appcheck.debug.DEBUG_SECRET"

    fun install(context: Context, intent: Intent?) {
        val debugToken = intent?.getStringExtra(DEBUG_TOKEN_EXTRA)
        if (!debugToken.isNullOrBlank()) {
            val persistenceKey = FirebaseApp.getInstance().persistenceKey
            val prefs = context.getSharedPreferences(
                "$DEBUG_STORE_PREFIX$persistenceKey",
                Context.MODE_PRIVATE
            )
            // Synchronous commit() avoids racing DebugAppCheckProviderFactory initialization.
            prefs.edit()
                .putString(DEBUG_SECRET_KEY, debugToken)
                .commit()
            Log.d(TAG, "App Check debug secret configured for debug build.")
        }

        FirebaseAppCheck.getInstance().installAppCheckProviderFactory(
            DebugAppCheckProviderFactory.getInstance()
        )
        Log.d(TAG, "DebugAppCheckProviderFactory installed for debug build.")
    }
}
