package com.edl.flasher.auth

import android.content.Context

/**
 * Deliberately thin, and deliberately NOT the thing that actually stops a
 * patched client. Someone could delete this whole file, call
 * OperationAuthClient + OperationKeyClient directly, or skip straight to
 * FirmwareCipher -- and they'd STILL need a fresh, valid grant to get a
 * working key. This class exists for convenience and logging on the honest
 * path, not as a gate that "0x0" would meaningfully defeat.
 *
 * Usage, right before a sensitive action:
 *   val outcome = SensitiveOperationGate.authorize(context, licenseService, "flash_frp")
 *   when (outcome) {
 *       is Outcome.Key -> FirmwareCipher.decryptWith(outcome.key, ...)
 *       is Outcome.Denied -> showMessage(outcome.reason)
 *   }
 */
object SensitiveOperationGate {

    sealed class Outcome {
        data class Key(val key: ByteArray) : Outcome()
        data class Denied(val reason: String) : Outcome()
    }

    suspend fun authorize(context: Context, licenseService: LicenseService, operation: String): Outcome {
        // Soft signal only -- logged, not solely relied upon. A clean result
        // here is NOT sufficient on its own; see the class doc on TamperGuard.
        val tamperSuspected = runCatching {
            TamperGuard.hasRootIndicators() || TamperGuard.isDebuggerAttached() ||
                TamperGuard.hasHookingFrameworkIndicators() || TamperGuard.hasEmulatorIndicators()
        }.getOrDefault(false)

        val deviceId = DeviceIdentity.deviceId(context, licenseService)
        val androidId = DeviceIdentity.androidId(context, licenseService)

        val grant = try {
            OperationAuthClient(licenseService).requestGrant(operation, deviceId, androidId)
        } catch (e: Exception) {
            return Outcome.Denied(e.message ?: "تعذر الاتصال بالخادم للتحقق من العملية.")
        }

        if (!grant.granted) return Outcome.Denied(grant.status)

        val key = OperationKeyClient.deriveFirmwareKey(grant, context, System.currentTimeMillis() / 1000)
            ?: return Outcome.Denied("expired_or_replayed_grant")

        if (tamperSuspected) {
            // Still return the key -- the point of this whole design is that
            // the key itself, not this flag, is the real gate. But surface
            // the signal so the UI/telemetry can flag it for review.
            com.edl.flasher.auth.TamperTelemetry.reportSuspected(operation)
        }

        return Outcome.Key(key)
    }
}

/** Minimal placeholder -- wire this to whatever logging/analytics the app
 *  already has (e.g. an authenticated RPC call logging client-side suspicion
 *  flags into yas_operation_log's `reason` column for admin review). */
object TamperTelemetry {
    fun reportSuspected(operation: String) { /* TODO: send to server, best-effort, non-blocking */ }
}

/**
 * Reuses the SAME device_id / ANDROID_ID values LicenseService already
 * generates and persists, so the identifiers authorize_device and
 * authorize_operation see always agree.
 *
 * ONE-LINE CHANGE NEEDED in LicenseService.kt: change
 *   private fun deviceId(): String
 *   private fun androidId(): String
 * to
 *   internal fun deviceId(): String
 *   internal fun androidId(): String
 * (same file/module, so this is not exposing them outside the auth package --
 * just letting this sibling class call them directly instead of duplicating
 * the EncryptedSharedPreferences logic here, which would risk the two
 * classes drifting out of sync.)
 */
object DeviceIdentity {
    fun deviceId(context: Context, licenseService: LicenseService): String = licenseService.deviceId()
    fun androidId(context: Context, licenseService: LicenseService): String = licenseService.androidId()
}
