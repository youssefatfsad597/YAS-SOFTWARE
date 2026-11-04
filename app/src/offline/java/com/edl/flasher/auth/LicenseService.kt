package com.edl.flasher.auth

import android.content.Context

/**
 * OFFLINE EDITION stand-in for the real (online) LicenseService.
 * No Supabase, no account, no network call of any kind: this build never
 * needs a license, so every method here is a harmless no-op. It exists only
 * so MainActivity.kt / EdlViewModel.kt (shared between both flavors) keep
 * compiling unchanged against `LicenseService(context)`.
 */
class LicenseService(context: Context) {
    fun clearSession() {}
}
