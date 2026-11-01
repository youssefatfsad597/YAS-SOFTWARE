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
        var process: Process? = null
        return try {
            process = Runtime.getRuntime().exec(arrayOf("which", "su"))
            process.inputStream.bufferedReader().use { it.readLine() != null }
        } catch (_: Exception) {
            false
        } finally {
            // exec() leaks a live process + open streams if never cleaned up.
            process?.destroy()
        }
    }

    /**
     * Detects common runtime hooking/injection frameworks (Frida, Xposed/LSPosed,
     * Substrate) that patch the app's behavior in memory, without necessarily changing
     * its signing certificate — so verifySigningCertificate() alone won't catch them.
     * Cheap, non-exhaustive signal, same spirit as hasRootIndicators(): raise the bar,
     * not a guarantee against a determined attacker.
     */
    fun hasHookingFrameworkIndicators(): Boolean {
        // Known Xposed/LSPosed bridge class, present in the classloader once hooked.
        try {
            Class.forName("de.robv.android.xposed.XposedBridge")
            return true
        } catch (_: ClassNotFoundException) {
            // expected on a clean device
        } catch (_: Exception) {
            // reflection quirk — don't let it crash the check either way
        }

        // Frida's default on-device server / injected-agent artifacts.
        val suspiciousPaths = arrayOf(
            "/data/local/tmp/frida-server", "/data/local/tmp/re.frida.server",
            "/data/local/tmp/frida-agent-32.so", "/data/local/tmp/frida-agent-64.so"
        )
        if (suspiciousPaths.any { File(it).exists() }) return true

        // Loaded libraries mentioning known hooking frameworks in this process's own
        // memory map — catches injection even when no file was dropped on disk.
        return try {
            File("/proc/self/maps").readLines().any { line ->
                val l = line.lowercase()
                "frida" in l || "substrate" in l || "xposed" in l
            }
        } catch (_: Exception) {
            false
        }
    }

    /** Detects an APK installed under a different applicationId than the one it was built for
     *  (a common trick: rename the package to dodge a device/signature-based ban list). */
    fun applicationIdMatches(context: Context): Boolean = context.packageName == BuildConfig.APPLICATION_ID
}
