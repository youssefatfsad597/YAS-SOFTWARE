package com.edl.flasher.auth

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Independent of OperationAuthClient and TamperGuard on purpose. Even someone
 * who patches BOTH of those to "always succeed" still lands here with no
 * valid keyMaterial, and deriveFirmwareKey() below produces a USELESS key in
 * that case -- not an exception that can be caught-and-ignored, an actual
 * wrong key that fails to decrypt (see FirmwareCipher's GCM tag check).
 *
 * This is the real backstop: it doesn't matter how many of the "soft" gate
 * classes get neutralized, because none of them hold the key. The server
 * does, and only releases a fresh fragment of it per authorized operation.
 */
object OperationKeyClient {

    // Partial key embedded in the APK. On its own this is NOT the firmware
    // key -- it's one input to HKDF, the other being server-issued key
    // material that only exists for ~60 seconds after a granted operation.
    // Regenerate this (and re-derive your firmware encryption accordingly)
    // rather than reusing any example value.
    private val embeddedPartialKey: ByteArray = byteArrayOf(
        0x4f, 0x1a.toByte(), 0x9c.toByte(), 0x33, 0x7e, 0x02, 0xd5.toByte(), 0x61,
        0x8b.toByte(), 0x44, 0xf0.toByte(), 0x19, 0x27, 0xae.toByte(), 0x5d, 0x90.toByte(),
        0x3c, 0xb7.toByte(), 0x6a, 0x15, 0xdc.toByte(), 0x48, 0x22, 0xf3.toByte(),
        0x09, 0x76, 0xcf.toByte(), 0x51, 0x8e.toByte(), 0x3f, 0xa1.toByte(), 0x60
    )

    private fun usedNoncesPrefs(context: Context) = EncryptedSharedPreferences.create(
        context, "yas_used_nonces",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    /** Rejects a grant whose nonce was already used -- a captured/replayed grant
     *  (e.g. from a proxied request) can't be reused to derive the key twice. */
    fun isNonceReused(context: Context, nonce: String): Boolean {
        val prefs = usedNoncesPrefs(context)
        if (prefs.contains(nonce)) return true
        // Cheap unbounded-growth guard: cap stored nonces; real deployments
        // should prune by timestamp instead of count.
        if (prefs.all.size > 500) prefs.edit().clear().apply()
        prefs.edit().putBoolean(nonce, true).apply()
        return false
    }

    /** HKDF-ish derivation (HMAC-SHA256 extract-and-expand, single block --
     *  enough for a 32-byte AES-256 key). Returns null if the grant is
     *  missing/invalid key material, so callers fail closed instead of
     *  silently using a predictable key. */
    fun deriveFirmwareKey(grant: OperationAuthClient.Grant, context: Context, nowEpochSeconds: Long): ByteArray? {
        if (!grant.isFresh(nowEpochSeconds)) return null
        if (grant.keyMaterialHex.isBlank() || grant.nonce.isBlank()) return null
        if (isNonceReused(context, grant.nonce)) return null

        val serverMaterial = hexToBytes(grant.keyMaterialHex) ?: return null

        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(embeddedPartialKey, "HmacSHA256"))
        val prk = mac.doFinal(serverMaterial) // extract

        val mac2 = Mac.getInstance("HmacSHA256")
        mac2.init(SecretKeySpec(prk, "HmacSHA256"))
        return mac2.doFinal("yas-firmware-key-v1".toByteArray(Charsets.UTF_8)).copyOf(32) // expand
    }

    private fun hexToBytes(hex: String): ByteArray? {
        if (hex.length % 2 != 0) return null
        return try {
            ByteArray(hex.length / 2) { i ->
                ((Character.digit(hex[i * 2], 16) shl 4) + Character.digit(hex[i * 2 + 1], 16)).toByte()
            }
        } catch (_: Exception) { null }
    }

    /** Constant-time compare, for anywhere a derived value is checked against
     *  an expected value -- avoids leaking timing info about where a mismatch
     *  occurs (not critical here, but cheap and removes the question). */
    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)
}
