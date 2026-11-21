package com.example.service

import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory

/**
 * Release-only Firebase App Check provider installer.
 * Strictly installs [PlayIntegrityAppCheckProviderFactory] so debug providers are never
 * compiled into or shipped with release AAB/APK builds.
 */
internal object AppCheckProviderInstaller {
    private const val TAG = "AppCheckInit"

    fun install(context: Context, intent: Intent?) {
        FirebaseAppCheck.getInstance().installAppCheckProviderFactory(
            PlayIntegrityAppCheckProviderFactory.getInstance()
        )
        Log.d(TAG, "PlayIntegrityAppCheckProviderFactory installed for release build.")
    }
}
