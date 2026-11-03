package com.edl.flasher.auth

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Debug
import android.util.Base64
import com.edl.flasher.BuildConfig
import java.io.File
import java.security.MessageDigest

/**
 * Keeps the root/hook-framework signature strings (su paths, frida-server paths, the
 * Xposed bridge class name...) out of a plain `strings`/grep pass over the APK. This is
 * NOT encryption and doesn't resist dynamic analysis (a debugger or Frida script reads
 * the decoded value in memory just fine) — it only raises the cost of the cheapest,
 * most common first step someone takes to locate and patch this exact check: grepping
 * the dex/so files for readable indicator strings.
 */
private object Obf {
    private const val KEY = 0x5A
    fun d(encoded: String): String {
        val x = Base64.decode(encoded, Base64.NO_WRAP)
        return String(ByteArray(x.size) { (x[it].toInt() xor KEY).toByte() }, Charsets.UTF_8)
    }
}

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

    /** Exposed (not private) so FirmwareCipher can bind the firmware decryption key to the
     *  real, currently-running signing certificate -- see FirmwareCipher.kt header comment. */
    @SuppressLint("PackageManagerGetSignatures")
    fun currentCertSha256(context: Context): String? {
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
            "dSkjKS4/N3U7Kip1CS8qPygvKT8odDsqMQ==", "dSk4MzR1KS8=", "dSkjKS4/N3U4MzR1KS8=",
            "dSkjKS4/N3UiODM0dSkv", "dT47Ljt1NjU5OzZ1IjgzNHUpLw==", "dT47Ljt1NjU5OzZ1ODM0dSkv",
            "dSkjKS4/N3UpPnUiODM0dSkv", "dSkjKS4/N3U4MzR1PDszNik7PD91KS8=",
            "dT47Ljt1NjU5OzZ1KS8=", "dSkvdTgzNHUpLw=="
        ).map(Obf::d)
        if (paths.any { File(it).exists() }) return true
        var process: Process? = null
        return try {
            process = Runtime.getRuntime().exec(arrayOf(Obf.d("LTIzOTI="), Obf.d("KS8=")))
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
            Class.forName(Obf.d("Pj90KDU4LHQ7ND4oNTM+dCIqNSk/PnQCKjUpPz4YKDM+PT8="))
            return true
        } catch (_: ClassNotFoundException) {
            // expected on a clean device
        } catch (_: Exception) {
            // reflection quirk — don't let it crash the check either way
        }

        // Frida's default on-device server / injected-agent artifacts.
        val suspiciousPaths = arrayOf(
            "dT47Ljt1NjU5OzZ1LjcqdTwoMz47dyk/KCw/KA==", "dT47Ljt1NjU5OzZ1LjcqdSg/dDwoMz47dCk/KCw/KA==",
            "dT47Ljt1NjU5OzZ1LjcqdTwoMz47dzs9PzQud2lodCk1", "dT47Ljt1NjU5OzZ1LjcqdTwoMz47dzs9PzQud2xudCk1"
        ).map(Obf::d)
        if (suspiciousPaths.any { File(it).exists() }) return true

        // Loaded libraries mentioning known hooking frameworks in this process's own
        // memory map — catches injection even when no file was dropped on disk.
        val needles = listOf("PCgzPjs=", "KS84KS4oOy4/", "Iio1KT8+").map(Obf::d)
        return try {
            File(Obf.d("dSooNTl1KT82PHU3Oyop")).readLines().any { line ->
                val l = line.lowercase()
                needles.any { it in l }
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Detects a common Android emulator/VM. Unlike root, this is safe to treat as hard
     * evidence of tampering *for this specific app*: flashing a Qualcomm device needs a
     * real device physically connected over USB/EDL, so there is no legitimate workflow
     * where a technician runs this app inside an emulator. Automated cracking/analysis
     * farms, on the other hand, run almost exclusively on emulators. Still non-exhaustive
     * (a well-configured emulator can hide most of these), same caveat as the rest of
     * this file.
     */
    fun hasEmulatorIndicators(): Boolean {
        val fingerprint = Build.FINGERPRINT.lowercase()
        val model = Build.MODEL.lowercase()
        val manufacturer = Build.MANUFACTURER.lowercase()
        val hardware = Build.HARDWARE.lowercase()
        val product = Build.PRODUCT.lowercase()
        val brand = Build.BRAND.lowercase()
        val device = Build.DEVICE.lowercase()

        val looksLikeEmulator =
            fingerprint.startsWith("generic") || fingerprint.contains("vbox") ||
            fingerprint.contains("test-keys") && (brand.startsWith("generic") && device.startsWith("generic")) ||
            manufacturer.contains("genymotion") ||
            model.contains("google_sdk") || model.contains("emulator") || model.contains("android sdk built for") ||
            hardware.contains("goldfish") || hardware.contains("ranchu") || hardware.contains("vbox86") ||
            product.contains("sdk_gphone") || product.contains("vbox86p") || product.endsWith("_sdk")

        if (looksLikeEmulator) return true

        val qemuArtifacts = arrayOf("/dev/qemu_pipe", "/dev/socket/qemud", "/system/lib/libc_malloc_debug_qemu.so")
        return qemuArtifacts.any { File(it).exists() }
    }

    /** The real applicationId, written here as a literal on purpose -- NOT read from
     *  BuildConfig.APPLICATION_ID. A rebuilt/repackaged APK with a different applicationId
     *  carries its OWN BuildConfig generated for that new id, so comparing against
     *  BuildConfig would make this check pass trivially for exactly the case it exists to
     *  catch. Comparing against a hardcoded literal instead means a renamed repackage has
     *  to find and edit this exact line too, not just change the manifest/gradle id.
     *  Still client-side and still patchable by someone who finds this -- see class doc. */
    private const val EXPECTED_APPLICATION_ID = "com.yas.flashedl"

    /** Detects an APK installed under a different applicationId than the one it was built for
     *  (a common trick: rename the package to dodge a device/signature-based ban list). */
    fun applicationIdMatches(context: Context): Boolean = context.packageName == EXPECTED_APPLICATION_ID

    /** Short, comma-separated summary of every indicator currently tripped (e.g.
     *  "root,hooking"), or null if none are. Sent to the server (authorize_operation's
     *  p_tamper_reason / authorize_firmware_download's p_tamper_reason / report_tamper)
     *  so a device caught tampering is banned the moment it's detected, server-side,
     *  instead of only being blocked locally by this same APK. */
    fun currentIndicators(context: Context? = null): String? {
        val hits = buildList {
            if (runCatching { hasRootIndicators() }.getOrDefault(false)) add("root")
            if (runCatching { isDebuggerAttached() }.getOrDefault(false)) add("debugger")
            if (runCatching { hasHookingFrameworkIndicators() }.getOrDefault(false)) add("hooking")
            if (runCatching { hasEmulatorIndicators() }.getOrDefault(false)) add("emulator")
            if (context != null && runCatching { !verifySigningCertificate(context) }.getOrDefault(false)) add("bad_signature")
            if (context != null && runCatching { !applicationIdMatches(context) }.getOrDefault(false)) add("app_id_mismatch")
        }
        return hits.takeIf { it.isNotEmpty() }?.joinToString(",")
    }
}
