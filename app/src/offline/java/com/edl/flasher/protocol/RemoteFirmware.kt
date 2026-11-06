package com.edl.flasher.protocol

import android.content.Context
import com.edl.flasher.auth.LicenseService
import java.io.InputStream

/**
 * OFFLINE EDITION: there is no server-hosted catalog in this build, so instead of
 * calling out to a server this reads straight from the bundled, unencrypted assets
 * under app/src/offline/assets/ -- using `slug` itself as the asset-relative path
 * (e.g. "x216b/frp.bin" -> assets/x216b/frp.bin). Every slug EdlViewModel.kt uses
 * (X216B_*_SLUG, T505N_*_SLUG, T509_*_SLUG) already matches that layout exactly, so
 * EdlViewModel.kt needs no changes beyond passing `context` into catalog() (it already
 * passes it into openStream/readAllBytes).
 */
object RemoteFirmware {

    data class CatalogItem(val slug: String, val sizeBytes: Long)

    class DownloadDenied(val status: String) : Exception(status)

    /** All known slugs across the three offline-bundled devices. Kept here (rather than
     *  importing EmbeddedFirmware/T505NFirmware/T509Firmware) so this file has no
     *  dependency on exactly which object owns which device's asset list. */
    private val ALL_SLUGS = listOf(
        "x216b/X216B.elf", "x216b/frp.bin", "x216b/sec_efs.bin", "x216b/persistent.bin", "x216b/misc.bin",
        "t505n/T505N.elf", "t505n/frp.bin", "t505n/persistent.bin", "t505n/misc.bin",
        "t509/fdl1-sign.bin", "t509/fdl2-sign.bin", "t509/splloader.bin"
    )

    /** `context` is needed here (unlike the no-arg-context online call) to read assets --
     *  see the matching one-line change at the EdlViewModel.kt call site
     *  (catalog(context, licenseService) instead of catalog(licenseService)). */
    suspend fun catalog(context: Context, licenseService: LicenseService): List<CatalogItem> =
        ALL_SLUGS.mapNotNull { slug ->
            val size = runCatching { context.assets.openFd(slug).use { it.length } }.getOrNull()
                ?: return@mapNotNull null
            CatalogItem(slug, size)
        }

    suspend fun openStream(context: Context, licenseService: LicenseService, slug: String): InputStream =
        runCatching { context.assets.open(slug) }.getOrElse {
            throw DownloadDenied("offline_asset_missing: $slug")
        }

    suspend fun readAllBytes(context: Context, licenseService: LicenseService, slug: String): ByteArray =
        openStream(context, licenseService, slug).use { it.readBytes() }
}
