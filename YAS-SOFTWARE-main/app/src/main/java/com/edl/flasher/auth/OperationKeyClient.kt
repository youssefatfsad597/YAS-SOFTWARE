package com.edl.flasher.auth

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.MessageDigest

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

    // The partial key and the HMAC/HKDF derivation itself now live ONLY in
    // libyas_core.so (see NativeCore.deriveFirmwareKeyNative / yas_native.c).
    // Nothing secret remains in Kotlin bytecode here.

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

    /** HKDF-ish derivation, now performed entirely in native code (see
     *  NativeCore.deriveFirmwareKeyNative / yas_native.c) -- the embedded
     *  partial key and the HMAC calls never surface in Kotlin bytecode.
     *  Returns null if the grant is missing/invalid/malformed, so callers
     *  fail closed instead of silently using a predictable key. */
    fun deriveFirmwareKey(grant: OperationAuthClient.Grant, context: Context, nowEpochSeconds: Long): ByteArray? {
        if (!grant.isFresh(nowEpochSeconds)) return null
        if (grant.keyMaterialHex.isBlank() || grant.nonce.isBlank()) return null
        if (isNonceReused(context, grant.nonce)) return null

        return try {
            NativeCore.deriveFirmwareKeyNative(grant.keyMaterialHex)
        } catch (_: UnsatisfiedLinkError) {
            null // libyas_core.so missing/mismatched ABI -- fail closed, never fall back to a Kotlin copy
        }
    }

    /** Constant-time compare, for anywhere a derived value is checked against
     *  an expected value -- avoids leaking timing info about where a mismatch
     *  occurs (not critical here, but cheap and removes the question). */
    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)
}
