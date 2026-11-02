package com.edl.flasher.auth

import android.content.Context
import androidx.work.*
import java.util.concurrent.TimeUnit

/**
 * Deliberately NOT part of AuthGate/LicenseService.authorize(). That call only
 * runs once at login/app-open -- someone who gets past the UI once (e.g. by
 * patching AuthGate's `authorized` state) would otherwise never be checked
 * again until they restart the app. WorkManager keeps polling in the
 * background on its own schedule, independent of whatever the UI/foreground
 * code is doing, and independent of OperationAuthClient/OperationKeyClient.
 *
 * This does not grant or carry any key material -- it ONLY asks "has an
 * admin force-logged-out this account" (yas_profiles.force_logout_at) and,
 * if so, clears the local session so the next authorize_operation call (or
 * app resume) requires a fresh sign-in. Even if this worker is disabled
 * entirely, it never WEAKENS the other two classes -- it's a convenience
 * for faster admin-initiated revocation, not a security boundary itself.
 */
class KillSwitchWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val service = LicenseService(applicationContext)
        if (!service.hasSession()) return Result.success()
        return try {
            val license = service.authorize() // already checks force_logout via authorize_device path
            if (!license.active && license.status in setOf("force_logout", "suspended", "device_banned")) {
                service.clearSession()
            }
            Result.success()
        } catch (_: Exception) {
            // Network failure: do nothing. Per SETUP.md, offline already fails
            // closed for protected operations elsewhere -- this worker should
            // not itself cause a hard crash loop on a flaky connection.
            Result.success()
        }
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "yas_kill_switch_poll"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<KillSwitchWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request
            )
        }
    }
}
