package com.edl.flasher.auth

import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.time.OffsetDateTime

/** Admin operations layered on top of [LicenseService]'s connection, using only the
 *  public anon key plus the signed-in admin's own access token. There is no
 *  service_role key anywhere in the app: every admin_* call is a Postgres function
 *  that itself re-checks the caller has role='admin'. The app admin has the same
 *  powers as the web dashboard. */
class AdminService(private val license: LicenseService) {

    data class DeviceRow(
        val id: String,
        val deviceId: String,
        val label: String,
        val lastSeenAt: String?,
        val revokedAt: String?,
        val deletedAt: String?,
        val firstIp: String?,
        val lastIp: String?,
        val androidId: String?
    ) {
        /** "active" | "revoked" (ملغي) | "deleted" (محذوف) */
        val state: String get() = when {
            deletedAt != null -> "deleted"
            revokedAt != null -> "revoked"
            else -> "active"
        }
    }

    data class Account(
        val userId: String,
        val email: String,
        val role: String,
        val status: String,
        val expiresAt: String?,
        val createdAt: String?,
        val devices: List<DeviceRow>
    ) {
        val activeDeviceCount: Int get() = devices.count { it.state == "active" }
    }

    data class BanRow(
        val id: String,
        val label: String,
        val deviceId: String?,
        val androidId: String?,
        val lastIp: String?,
        val reason: String?,
        val bannedAt: String?
    )

    data class NewAccountResult(val email: String, val password: String, val error: String?)
    data class ActivateResult(val activated: Int, val notFound: List<String>)

    private fun JSONObject.str(key: String): String? =
        optString(key).takeIf { it.isNotBlank() && it != "null" }

    private suspend fun rpc(name: String, body: JSONObject = JSONObject()) =
        license.raw("/rest/v1/rpc/$name", "POST", body, authenticated = true)

    suspend fun isAdmin(): Boolean = try {
        rpc("am_i_admin").optBoolean("is_admin", false)
    } catch (_: Exception) { false }

    suspend fun listAccounts(): List<Account> {
        val arr = rpc("admin_list_users").optJSONArray("items") ?: return emptyList()
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val d: JSONArray? = o.optJSONArray("devices")
            val devices = d?.let { list ->
                (0 until list.length()).map { j ->
                    val dev = list.getJSONObject(j)
                    DeviceRow(
                        id = dev.optString("id"),
                        deviceId = dev.optString("device_id"),
                        label = dev.optString("label"),
                        lastSeenAt = dev.str("last_seen_at"),
                        revokedAt = dev.str("revoked_at"),
                        deletedAt = dev.str("deleted_at"),
                        firstIp = dev.str("first_ip"),
                        lastIp = dev.str("last_ip"),
                        androidId = dev.str("android_id")
                    )
                }
            } ?: emptyList()
            Account(
                userId = o.optString("user_id"),
                email = o.optString("email"),
                role = o.optString("role"),
                status = o.optString("status"),
                expiresAt = o.str("expires_at"),
                createdAt = o.str("created_at"),
                devices = devices
            )
        }
    }

    /** Expiry timestamp [value] x [unitMinutes] from now, or null when value <= 0. */
    fun expiryFrom(value: Int, unitMinutes: Long): String? =
        if (value <= 0) null else OffsetDateTime.now().plusMinutes(value * unitMinutes).toString()

    /** Activates every pending account at once, all expiring at [expiresAt]. Returns how many were activated. */
    suspend fun activateAllPending(expiresAt: String): Int =
        rpc("admin_activate_all_pending", JSONObject().put("p_expires_at", expiresAt)).optInt("activated_count", 0)

    /** Activates several accounts (by email) together with the same expiry. */
    suspend fun activateUsers(emails: List<String>, expiresAt: String): ActivateResult {
        val body = JSONObject().put("p_emails", JSONArray(emails)).put("p_expires_at", expiresAt)
        val r = rpc("admin_activate_users", body)
        val nf = r.optJSONArray("not_found")
        return ActivateResult(
            activated = r.optInt("activated_count", 0),
            notFound = nf?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
        )
    }

    /** Activates, suspends, or re-pends one account. Pass [days] only for "active". */
    suspend fun setLicense(userId: String, status: String, days: Int?) {
        val expiresAt = if (status == "active") OffsetDateTime.now().plusDays((days ?: 30).toLong()).toString() else null
        rpc("admin_set_license", JSONObject().put("p_user_id", userId).put("p_status", status).put("p_expires_at", expiresAt ?: JSONObject.NULL))
    }

    // ---- Devices: cancel (blocked, stays listed) / delete (kept as history) / restore / ban ----
    suspend fun revokeDevice(deviceRowId: String) { rpc("admin_revoke_device", JSONObject().put("p_device_row_id", deviceRowId)) }
    suspend fun deleteDevice(deviceRowId: String) { rpc("admin_delete_device", JSONObject().put("p_device_row_id", deviceRowId)) }
    suspend fun restoreDevice(deviceRowId: String) { rpc("admin_restore_device", JSONObject().put("p_device_row_id", deviceRowId)) }
    suspend fun banDevice(deviceRowId: String, reason: String) {
        rpc("admin_ban_device", JSONObject().put("p_device_row_id", deviceRowId).put("p_reason", reason.ifBlank { JSONObject.NULL }))
    }

    // ---- Ban list ----
    suspend fun listBans(): List<BanRow> {
        val arr = rpc("admin_list_bans").optJSONArray("items") ?: return emptyList()
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            BanRow(o.optString("id"), o.optString("label"), o.str("device_id"), o.str("android_id"), o.str("last_ip"), o.str("reason"), o.str("banned_at"))
        }
    }
    suspend fun unban(banId: String) { rpc("admin_unban", JSONObject().put("p_ban_id", banId)) }
    suspend fun banManual(androidId: String, reason: String) {
        rpc("admin_ban_manual", JSONObject()
            .put("p_android_id", androidId)
            .put("p_reason", reason.ifBlank { JSONObject.NULL }))
    }

    /** Permanently deletes the account (and its devices) from the platform. Server refuses admins / yourself. */
    suspend fun deleteUser(userId: String) { rpc("admin_delete_user", JSONObject().put("p_user_id", userId)) }

    /** Emails like yasoftware1@yas.com, yasoftware2@yas.com ... skipping ones already in [taken]. */
    fun generateEmails(prefix: String, domain: String, count: Int, taken: Set<String>): List<String> {
        val p = prefix.trim().ifBlank { "yasoftware" }.filter { it.isLetterOrDigit() || it == '.' || it == '_' || it == '-' }
        val d = domain.trim().removePrefix("@").ifBlank { "yas.com" }
        val out = ArrayList<String>()
        var i = 1
        while (out.size < count && i < 100000) {
            val e = "$p$i@$d".lowercase()
            if (e !in taken) out.add(e)
            i++
        }
        return out
    }

    /** Creates accounts with random passwords through the admin_create_users function (email pre-confirmed).
     *  [expiresAt] activates them until then; null leaves them pending. Passwords are generated here and
     *  returned so the admin can hand them out — they are not retrievable later. */
    suspend fun createAccountsBulk(emails: List<String>, passwordLength: Int, expiresAt: String?): List<NewAccountResult> {
        val accounts = emails.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct()
            .map { it to generatePassword(passwordLength) }
        if (accounts.isEmpty()) return emptyList()
        val results = ArrayList<NewAccountResult>()
        for (chunk in accounts.chunked(100)) {
            val arr = JSONArray()
            chunk.forEach { (e, p) -> arr.put(JSONObject().put("email", e).put("password", p)) }
            val body = JSONObject().put("p_accounts", arr).put("p_expires_at", expiresAt ?: JSONObject.NULL)
            try {
                val r = rpc("admin_create_users", body).optJSONArray("results") ?: JSONArray()
                val byEmail = (0 until r.length()).associate { idx -> val o = r.getJSONObject(idx); o.optString("email") to o }
                chunk.forEach { (e, p) ->
                    val o = byEmail[e]
                    results.add(if (o != null && o.optBoolean("ok", false)) NewAccountResult(e, p, null)
                        else NewAccountResult(e, p, o?.optString("error")?.ifBlank { null } ?: "فشل إنشاء الحساب."))
                }
            } catch (ex: Exception) {
                chunk.forEach { (e, p) -> results.add(NewAccountResult(e, p, ex.message ?: "فشل إنشاء الحساب.")) }
            }
        }
        return results
    }

    private fun generatePassword(length: Int): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789"
        val random = SecureRandom()
        return (1..length.coerceIn(8, 64)).map { chars[random.nextInt(chars.length)] }.joinToString("")
    }
}
