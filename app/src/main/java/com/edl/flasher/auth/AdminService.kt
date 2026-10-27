package com.edl.flasher.auth

import org.json.JSONObject
import java.security.SecureRandom
import java.time.OffsetDateTime

/** Admin operations layered on top of [LicenseService]'s connection, using only the
 *  public anon key plus the signed-in admin's own access token. There is no
 *  service_role key anywhere in the app: creating accounts goes through the normal
 *  public sign-up endpoint (the same one regular users hit), and every admin_* call
 *  is a Postgres function that itself re-checks the caller has role='admin'. */
class AdminService(private val license: LicenseService) {

    data class DeviceRow(val id: String, val deviceId: String, val label: String, val lastSeenAt: String?, val revokedAt: String?)

    data class Account(
        val userId: String,
        val email: String,
        val role: String,
        val status: String,
        val expiresAt: String?,
        val createdAt: String?,
        val devices: List<DeviceRow>
    ) {
        val activeDeviceCount: Int get() = devices.count { it.revokedAt.isNullOrBlank() }
    }

    data class NewAccountResult(val email: String, val password: String, val error: String?)

    suspend fun isAdmin(): Boolean = try {
        license.raw("/rest/v1/rpc/am_i_admin", "POST", JSONObject(), authenticated = true).optBoolean("is_admin", false)
    } catch (_: Exception) { false }

    suspend fun listAccounts(): List<Account> {
        val result = license.raw("/rest/v1/rpc/admin_list_users", "POST", JSONObject(), authenticated = true)
        val arr = result.optJSONArray("items") ?: return emptyList()
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val devicesArr = o.optJSONArray("devices")
            val devices = devicesArr?.let { d ->
                (0 until d.length()).map { j ->
                    val dev = d.getJSONObject(j)
                    DeviceRow(
                        id = dev.optString("id"),
                        deviceId = dev.optString("device_id"),
                        label = dev.optString("label"),
                        lastSeenAt = dev.optString("last_seen_at").takeIf { it.isNotBlank() && it != "null" },
                        revokedAt = dev.optString("revoked_at").takeIf { it.isNotBlank() && it != "null" }
                    )
                }
            } ?: emptyList()
            Account(
                userId = o.optString("user_id"),
                email = o.optString("email"),
                role = o.optString("role"),
                status = o.optString("status"),
                expiresAt = o.optString("expires_at").takeIf { it.isNotBlank() && it != "null" },
                createdAt = o.optString("created_at").takeIf { it.isNotBlank() && it != "null" },
                devices = devices
            )
        }
    }

    /** Activates every pending account at once, all expiring in [days] days from now.
     *  Returns how many accounts were activated. */
    suspend fun activateAllPending(days: Int): Int {
        val expiresAt = OffsetDateTime.now().plusDays(days.toLong()).toString()
        val body = JSONObject().put("p_expires_at", expiresAt)
        val result = license.raw("/rest/v1/rpc/admin_activate_all_pending", "POST", body, authenticated = true)
        return result.optInt("activated_count", 0)
    }

    /** Activates, suspends, or re-pends one account. Pass expiresAt only for "active". */
    suspend fun setLicense(userId: String, status: String, days: Int?) {
        val expiresAt = if (status == "active") OffsetDateTime.now().plusDays((days ?: 30).toLong()).toString() else null
        val body = JSONObject().put("p_user_id", userId).put("p_status", status).put("p_expires_at", expiresAt ?: JSONObject.NULL)
        license.raw("/rest/v1/rpc/admin_set_license", "POST", body, authenticated = true)
    }

    suspend fun revokeDevice(deviceRowId: String) {
        license.raw("/rest/v1/rpc/admin_revoke_device", "POST", JSONObject().put("p_device_row_id", deviceRowId), authenticated = true)
    }

    /** Creates several accounts in one go through the normal public sign-up endpoint
     *  (anon key only — never the admin's own session), each with its own randomly
     *  generated password so the admin can hand each client a unique credential.
     *  New accounts land as "pending" until activated (individually or via
     *  [activateAllPending]). */
    suspend fun createAccountsBulk(emails: List<String>): List<NewAccountResult> =
        emails.map { rawEmail ->
            val email = rawEmail.trim()
            val password = generatePassword()
            try {
                license.raw("/auth/v1/signup", "POST", JSONObject().put("email", email).put("password", password), authenticated = false)
                NewAccountResult(email, password, null)
            } catch (e: Exception) {
                NewAccountResult(email, password, e.message ?: "فشل إنشاء الحساب.")
            }
        }

    private fun generatePassword(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789"
        val random = SecureRandom()
        return (1..12).map { chars[random.nextInt(chars.length)] }.joinToString("")
    }
}
