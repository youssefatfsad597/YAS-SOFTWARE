package com.edl.flasher.auth

import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Server-hosted-firmware counterpart to OperationAuthClient. Same philosophy: a real
 * network call that the server can refuse at any moment (suspended account, expired
 * license, kill-switch, banned device), not a local boolean. See
 * supabase/03_firmware_storage.sql for the server side and
 * protocol/RemoteFirmware.kt for how the released key_material_hex turns into an
 * actual decrypting InputStream.
 */
class RemoteFirmwareClient(private val licenseService: LicenseService) {

    class CatalogItem(val slug: String, val displayName: String, val deviceModel: String?, val sizeBytes: Long)

    class DownloadGrant(
        val granted: Boolean,
        val status: String,
        val storagePath: String,
        val keyMaterialHex: String,
        val sha256Plaintext: String,
        val sizeBytes: Long
    )

    suspend fun catalog(): List<CatalogItem> = withContext(Dispatchers.IO) {
        val result = licenseService.raw(
            "/rest/v1/rpc/firmware_catalog", "POST", JSONObject(), authenticated = true
        )
        val items = result.optJSONArray("items") ?: return@withContext emptyList()
        (0 until items.length()).map { i ->
            val o = items.getJSONObject(i)
            CatalogItem(
                slug = o.optString("slug"),
                displayName = o.optString("display_name"),
                deviceModel = o.optString("device_model", null.toString()).takeIf { it.isNotBlank() && it != "null" },
                sizeBytes = o.optLong("size_bytes", -1L)
            )
        }
    }

    /** slug: catalog entry, e.g. "x216b/sec_efs.bin". deviceId/androidId/widevineId:
     *  same identifiers LicenseService/OperationAuthClient already use. tamperReason:
     *  TamperGuard.currentIndicators() -- when non-null the server bans this device
     *  immediately instead of releasing the file (see authorize_firmware_download's
     *  p_tamper_reason). */
    suspend fun requestDownload(slug: String, deviceId: String, androidId: String, tamperReason: String? = null, widevineId: String? = null): DownloadGrant =
        withContext(Dispatchers.IO) {
            val body = JSONObject()
                .put("p_device_id", deviceId)
                .put("p_slug", slug)
                .put("p_android_id", androidId)
            if (!widevineId.isNullOrBlank()) body.put("p_widevine_id", widevineId)
            if (!tamperReason.isNullOrBlank()) body.put("p_tamper_reason", tamperReason)
            val result = licenseService.raw(
                "/rest/v1/rpc/authorize_firmware_download", "POST", body, authenticated = true
            )
            DownloadGrant(
                granted = result.optBoolean("granted", false),
                status = result.optString("status", "unknown"),
                storagePath = result.optString("storage_path", ""),
                keyMaterialHex = result.optString("key_material_hex", ""),
                sha256Plaintext = result.optString("sha256_plaintext", ""),
                sizeBytes = result.optLong("size_bytes", -1L)
            )
        }
}
