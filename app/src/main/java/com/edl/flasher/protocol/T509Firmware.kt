package com.edl.flasher.protocol

import android.content.Context
import java.io.InputStream

/**
 * T509 / T509N service files (Unisoc BROM stage-1/stage-2 loaders, "FDL1"/"FDL2")
 * bundled inside the APK, mirroring [EmbeddedFirmware] for the X216B flow.
 *
 * Committed into the repo under firmware/t509/ and firmware/t509n/ and copied into
 * app/src/main/assets/t509/<variant>/ at build time by the "Copy Embedded T509
 * Firmware Assets" step in this workflow. Variant selection is done at runtime
 * (ServiceTab T509 tab toggle), not at build time.
 */
enum class T509Variant(val assetDir: String, val label: String) {
    T509("t509", "T509"),
    T509N("t509n", "T509N")
}

object T509Firmware {
    const val FDL1 = "fdl1-sign.bin"
    const val FDL2 = "fdl2-sign.bin"
    val ALL_FILES = listOf(FDL1, FDL2)

    private const val ENC_SUFFIX = ".enc"

    // Assets are AES-256-CTR encrypted at build time (encrypt_firmware_assets.sh) and
    // land as "<name>.enc"; see EmbeddedFirmware's doc comment / FirmwareCipher for the format.
    private fun path(variant: T509Variant, name: String) = "${variant.assetDir}/$name$ENC_SUFFIX"

    fun exists(context: Context, variant: T509Variant, name: String): Boolean =
        runCatching { context.assets.open(path(variant, name)).close() }.isSuccess

    fun allPresent(context: Context, variant: T509Variant): Boolean =
        ALL_FILES.all { exists(context, variant, it) }

    /** Exact plaintext (decrypted) size. Returns -1 if unavailable. */
    fun sizeOf(context: Context, variant: T509Variant, name: String): Long =
        runCatching {
            FirmwareCipher.plaintextSize(context.assets.openFd(path(variant, name)).use { it.length })
        }.getOrDefault(-1L)

    fun openStream(context: Context, variant: T509Variant, name: String): InputStream =
        FirmwareCipher.decryptingStream(context.assets.open(path(variant, name)))

    fun readAllBytes(context: Context, variant: T509Variant, name: String): ByteArray =
        openStream(context, variant, name).use { it.readBytes() }

    fun describeStatus(context: Context, variant: T509Variant): String {
        val missing = ALL_FILES.filterNot { exists(context, variant, it) }
        if (missing.isNotEmpty()) {
            return "MISSING: ${missing.joinToString(", ")} -- commit these under firmware/${variant.assetDir}/ in the repo and rebuild."
        }
        val sizes = ALL_FILES.joinToString(", ") { "$it=${sizeOf(context, variant, it) / 1024}KB" }
        return "Ready ($sizes)"
    }
}
