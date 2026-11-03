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
        // Sent to the server WITH the request (not just logged locally): when non-null,
        // authorize_operation bans this device immediately and denies the grant in the
        // same call -- see yas_auto_ban_device in 02_operation_auth.sql.
        val tamperReason = TamperGuard.currentIndicators(context)

        val deviceId = DeviceIdentity.deviceId(context, licenseService)
        val androidId = DeviceIdentity.androidId(context, licenseService)
        val widevineId = DeviceIdentity.widevineId(context, licenseService)

        val grant = try {
            OperationAuthClient(licenseService).requestGrant(operation, deviceId, androidId, tamperReason, widevineId)
        } catch (e: Exception) {
            return Outcome.Denied(e.message ?: "تعذر الاتصال بالخادم للتحقق من العملية.")
        }

        if (!grant.granted) {
            return Outcome.Denied(
                if (grant.status == "tamper_auto_banned")
                    "تم رصد محاولة تلاعب بالتطبيق، وتم حظر هذا الجهاز تلقائيًا."
                else grant.status
            )
        }

        val key = OperationKeyClient.deriveFirmwareKey(grant, context, System.currentTimeMillis() / 1000)
            ?: return Outcome.Denied("expired_or_replayed_grant")

        return Outcome.Key(key)
    }
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
    /** See LicenseService.widevineId() -- survives uninstall AND factory reset on most devices. */
    fun widevineId(context: Context, licenseService: LicenseService): String = licenseService.widevineId()
}
