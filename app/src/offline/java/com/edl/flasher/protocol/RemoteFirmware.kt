package com.edl.flasher.protocol

import android.content.Context
import com.edl.flasher.auth.LicenseService
import java.io.InputStream

/**
 * OFFLINE EDITION stub: there is no server-hosted firmware catalog in this
 * build, so this cleanly reports "nothing available" instead of ever
 * touching a network. The real offline firmware path is EmbeddedFirmware /
 * T505NFirmware / T509Firmware (bundled, unencrypted assets) -- this object
 * exists only so EdlViewModel.kt (shared between both flavors) keeps
 * compiling and failing gracefully if a remote-only slug is ever requested.
 */
object RemoteFirmware {

    data class CatalogItem(val slug: String, val sizeBytes: Long)

    class DownloadDenied(val status: String) : Exception(status)

    suspend fun catalog(licenseService: LicenseService): List<CatalogItem> = emptyList()

    suspend fun openStream(context: Context, licenseService: LicenseService, slug: String): InputStream =
        throw DownloadDenied("offline_edition_no_server")

    suspend fun readAllBytes(context: Context, licenseService: LicenseService, slug: String): ByteArray =
        throw DownloadDenied("offline_edition_no_server")
}
