package com.edl.flasher.auth

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Debug
import com.edl.flasher.BuildConfig
import java.io.File
import java.security.MessageDigest

/**
 * Lightweight anti-tamper / anti-repackaging checks.
 *
 * None of this stops a determined reverse engineer with root + Frida, but it closes the
 * easy paths: re-signing a patched APK with a different key, running under a debugger
 * attached to bypass authorize(), and distributing a repackaged build under the same
 * applicationId. All checks are soft failures (return a reason string) so the caller
 * decides whether to block, log, or silently continue.
 */
object TamperGuard {

    /**
     * SHA-256 of your release signing certificate, lowercase hex, no colons.
     * Fill this in from gradle.properties (RELEASE_CERT_SHA256) — see SETUP.md.
     * Get it with:
     *   keytool -list -v -keystore your.keystore -alias your_alias
     * (use the "Certificate fingerprints: SHA256" line) or, after signing an APK:
     *   apksigner verify --print-certs app-release.apk
     * Left blank = check is skipped (so a fresh checkout still builds and runs).
     */
    private val expectedCertSha256: String = BuildConfig.RELEASE_CERT_SHA256

    @SuppressLint("PackageManagerGetSignatures")
    fun verifySigningCertificate(context: Context): Boolean {
        if (expectedCertSha256.isBlank()) return true // not configured yet, don't block
        return try {
            val actual = currentCertSha256(context) ?: return false
            actual.equals(expectedCertSha256, ignoreCase = true)
        } catch (_: Exception) {
            false
        }
    }

    @SuppressLint("PackageManagerGetSignatures")
    private fun currentCertSha256(context: Context): String? {
        val pm = context.packageManager
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            val signingInfo = info.signingInfo ?: return null
            if (signingInfo.hasMultipleSigners()) signingInfo.apkContentsSigners
            else signingInfo.signingCertificateHistory
        } else {
            @Suppress("DEPRECATION")
            val info = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
            @Suppress("DEPRECATION")
            info.signatures
        }
        val sig = signatures?.firstOrNull() ?: return null
        val digest = MessageDigest.getInstance("SHA-256").digest(sig.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** True if a debugger is attached — a patched license check is usually driven this way. */
    fun isDebuggerAttached(): Boolean = Debug.isDebuggerConnected() || Debug.waitingForDebugger()

    /** Cheap, non-exhaustive root signal. Not meant to be bulletproof, just raise the bar. */
    fun hasRootIndicators(): Boolean {
        val paths = arrayOf(
            "/system/app/Superuser.apk", "/sbin/su", "/system/bin/su", "/system/xbin/su",
            "/data/local/xbin/su", "/data/local/bin/su", "/system/sd/xbin/su",
            "/system/bin/failsafe/su", "/data/local/su", "/su/bin/su"
        )
        if (paths.any { File(it).exists() }) return true
        return try {
            Runtime.getRuntime().exec(arrayOf("which", "su")).inputStream.bufferedReader().readLine() != null
        } catch (_: Exception) {
            false
        }
    }

    /** Detects an APK installed under a different applicationId than the one it was built for
     *  (a common trick: rename the package to dodge a device/signature-based ban list). */
    fun applicationIdMatches(context: Context): Boolean = context.packageName == BuildConfig.APPLICATION_ID
}
