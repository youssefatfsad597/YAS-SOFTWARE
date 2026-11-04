package com.edl.flasher.protocol

import android.content.Context
import java.io.InputStream

/**
 * OFFLINE EDITION: T509 BROM loader files bundled UNENCRYPTED as assets
 * (app/src/offline/assets/t509/), mirroring [T505NFirmware]'s offline
 * version. `serverKey` is accepted only to match the online signature and
 * is ignored.
 */
object T509Firmware {
    private const val ASSET_DIR = "t509"

    const val FDL1 = "fdl1-sign.bin"
    const val FDL2 = "fdl2-sign.bin"
    const val SPLLOADER = "splloader.bin"

    val ALL_FILES = listOf(FDL1, FDL2)
    val OPTIONAL_FILES = listOf(SPLLOADER)

    private fun path(name: String) = "$ASSET_DIR/$name"

    fun exists(context: Context, name: String): Boolean =
        runCatching { context.assets.open(path(name)).close() }.isSuccess

    fun allPresent(context: Context): Boolean =
        ALL_FILES.all { exists(context, it) }

    fun sizeOf(context: Context, name: String): Long =
        runCatching { context.assets.openFd(path(name)).use { it.length } }.getOrDefault(-1L)

    fun openStream(context: Context, name: String, serverKey: ByteArray): InputStream =
        context.assets.open(path(name))

    fun readAllBytes(context: Context, name: String, serverKey: ByteArray): ByteArray =
        openStream(context, name, serverKey).use { it.readBytes() }

    fun describeStatus(context: Context): String {
        val missing = ALL_FILES.filterNot { exists(context, it) }
        if (missing.isNotEmpty()) {
            return "MISSING: ${missing.joinToString(", ")} -- commit these under app/src/offline/assets/$ASSET_DIR/ and rebuild."
        }
        val sizes = ALL_FILES.joinToString(", ") { "$it=${sizeOf(context, it) / 1024}KB" }
        val optional = OPTIONAL_FILES.filter { exists(context, it) }
            .joinToString(", ") { "$it=${sizeOf(context, it) / 1024}KB" }
        return if (optional.isEmpty()) "Ready ($sizes)" else "Ready ($sizes) -- also bundled: $optional"
    }
}
