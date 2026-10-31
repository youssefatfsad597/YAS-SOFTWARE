package com.edl.flasher.protocol

import android.content.Context
import java.io.InputStream

/**
 * T509 service files (Unisoc BROM stage-1/stage-2 loaders, "FDL1"/"FDL2") bundled
 * inside the APK, mirroring [T505NFirmware] / [EmbeddedFirmware] for the other tabs.
 *
 * Committed into the repo under firmware/t509/ and copied into app/src/main/assets/t509/
 * at build time by the "Encrypt + Stage Embedded Firmware Assets" step (same
 * tools/encrypt_firmware_assets.sh pipeline as X216B / T505N).
 *
 * There is only one T509 flow -- there is no "T509N" device/variant, so this object
 * (unlike an earlier revision of this file) does not take one.
 *
 * NOTE on FDL1/FDL2 vs SPLLOADER: FDL1 and FDL2 are both real, signed Unisoc "DHTB"
 * images (magic "DHTB", a 512-byte header, real AArch64 code starting right after it)
 * and are what [sprdFdl.uploadFdl1]/[uploadFdl2] actually upload today -- an earlier
 * pass over this file mis-read their header layout and wrongly flagged them as empty
 * placeholders; they are not. SPLLOADER (splloader.bin) is a third, also-real DHTB
 * image that was supplied separately; it is bundled here as an available asset but is
 * NOT currently uploaded by the Format+FRP flow, because address.txt for this device
 * only documents load addresses for FDL1 (0x5500) and FDL2 (0x9EFFFE00) -- not for
 * SPLLOADER -- so wiring it into [EdlViewModel.t509FormatFrp] without a confirmed load
 * address/role would risk a wrong guess against real hardware.
 */
object T509Firmware {
    private const val ASSET_DIR = "t509"
    private const val ENC_SUFFIX = ".enc"

    const val FDL1 = "fdl1-sign.bin"
    const val FDL2 = "fdl2-sign.bin"
    const val SPLLOADER = "splloader.bin"

    /** Required for the BROM upload sequence in [EdlViewModel.t509FormatFrp]. */
    val ALL_FILES = listOf(FDL1, FDL2)

    /** Bundled but optional until its role/load address is confirmed (see class doc). */
    val OPTIONAL_FILES = listOf(SPLLOADER)

    // Assets are AES-256-CTR encrypted at build time (encrypt_firmware_assets.sh) and
    // land as "<name>.enc"; see EmbeddedFirmware's doc comment / FirmwareCipher for the format.
    private fun path(name: String) = "$ASSET_DIR/$name$ENC_SUFFIX"

    fun exists(context: Context, name: String): Boolean =
        runCatching { context.assets.open(path(name)).close() }.isSuccess

    fun allPresent(context: Context): Boolean =
        ALL_FILES.all { exists(context, it) }

    /** Exact plaintext (decrypted) size. Returns -1 if unavailable. */
    fun sizeOf(context: Context, name: String): Long =
        runCatching {
            FirmwareCipher.plaintextSize(context.assets.openFd(path(name)).use { it.length })
        }.getOrDefault(-1L)

    fun openStream(context: Context, name: String): InputStream =
        FirmwareCipher.decryptingStream(context.assets.open(path(name)))

    fun readAllBytes(context: Context, name: String): ByteArray =
        openStream(context, name).use { it.readBytes() }

    fun describeStatus(context: Context): String {
        val missing = ALL_FILES.filterNot { exists(context, it) }
        if (missing.isNotEmpty()) {
            return "MISSING: ${missing.joinToString(", ")} -- commit these under firmware/$ASSET_DIR/ in the repo and rebuild."
        }
        val sizes = ALL_FILES.joinToString(", ") { "$it=${sizeOf(context, it) / 1024}KB" }
        val optional = OPTIONAL_FILES.filter { exists(context, it) }
            .joinToString(", ") { "$it=${sizeOf(context, it) / 1024}KB" }
        return if (optional.isEmpty()) "Ready ($sizes)" else "Ready ($sizes) -- also bundled: $optional"
    }
}
