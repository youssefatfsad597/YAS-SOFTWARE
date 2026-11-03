package com.edl.flasher.auth

import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Deliberately separate from TamperGuard and from LicenseService's authorize().
 * This class's only job: ask the server, right before ONE specific sensitive
 * operation, "is this still allowed, and if so give me the key material for it."
 *
 * Patching TamperGuard.verifySigningCertificate() or AuthGate's `authorized`
 * flag to always be true does NOT help an attacker here: this class still
 * makes a real network call, and without a valid `keyMaterial` back,
 * FirmwareCipher (see OperationKeyClient.kt) cannot derive a working key.
 * There is no local boolean in this class worth patching.
 */
class OperationAuthClient(private val licenseService: LicenseService) {

    class Grant(
        val granted: Boolean,
        val status: String,
        val issuedAtEpochSeconds: Long,
        val nonce: String,
        val keyMaterialHex: String,
        val ttlSeconds: Int
    ) {
        /** A grant is only usable for a few seconds after issue -- reject anything stale
         *  even if the device clock is slightly off, rather than trust a cached grant. */
        fun isFresh(nowEpochSeconds: Long): Boolean =
            granted && (nowEpochSeconds - issuedAtEpochSeconds) in 0..ttlSeconds.toLong()
    }

    /** operation: a short machine name, e.g. "flash_frp", "flash_full", "read_efs".
     *  deviceId/androidId: same identifiers already used by LicenseService. tamperReason:
     *  TamperGuard.currentIndicators() -- when non-null the server bans this device
     *  immediately instead of issuing a grant (see authorize_operation's p_tamper_reason). */
    suspend fun requestGrant(operation: String, deviceId: String, androidId: String, tamperReason: String? = null): Grant =
        withContext(Dispatchers.IO) {
            val body = JSONObject()
                .put("p_device_id", deviceId)
                .put("p_operation", operation)
                .put("p_android_id", androidId)
            if (!tamperReason.isNullOrBlank()) body.put("p_tamper_reason", tamperReason)
            val result = licenseService.raw(
                "/rest/v1/rpc/authorize_operation", "POST", body, authenticated = true
            )
            Grant(
                granted = result.optBoolean("granted", false),
                status = result.optString("status", "unknown"),
                issuedAtEpochSeconds = result.optLong("issued_at", 0L),
                nonce = result.optString("nonce", ""),
                keyMaterialHex = result.optString("key_material", ""),
                ttlSeconds = result.optInt("ttl_seconds", 30)
            )
        }
}
