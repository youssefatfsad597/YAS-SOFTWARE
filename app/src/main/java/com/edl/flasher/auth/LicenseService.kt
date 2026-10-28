package com.edl.flasher.auth

import com.edl.flasher.BuildConfig

import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/** Supabase Auth + PostgREST RPC client. Only the public anon key belongs in the app. */
class LicenseService(context: Context) {
    private val appContext: Context = context.applicationContext
    private val prefs = EncryptedSharedPreferences.create(
        context,
        "yas_secure_session",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )
    private val baseUrl = BuildConfig.SUPABASE_URL.trimEnd('/')
    private val anonKey = BuildConfig.SUPABASE_ANON_KEY

    data class License(val active: Boolean, val status: String, val expiresAt: String?, val message: String = "")

    fun hasSession(): Boolean = !prefs.getString("access_token", null).isNullOrBlank()

    /** Ends the local session only. Called both from an explicit sign-out and whenever
     *  the app itself is closed, so closing the app always "counts" as a logout and the
     *  next launch requires signing in again. Saved remember-me credentials are untouched. */
    fun clearSession() { prefs.edit().remove("access_token").remove("refresh_token").apply() }

    fun email(): String = prefs.getString("email", "") ?: ""

    fun rememberEnabled(): Boolean = prefs.getBoolean("remember_me", false)
    fun savedPassword(): String = if (rememberEnabled()) prefs.getString("saved_password", "") ?: "" else ""

    fun saveCredentials(email: String, password: String, remember: Boolean) {
        val editor = prefs.edit().putString("email", email.trim()).putBoolean("remember_me", remember)
        if (remember) editor.putString("saved_password", password) else editor.remove("saved_password")
        editor.apply()
    }

    private fun deviceId(): String {
        val existing = prefs.getString("device_id", null)
        if (!existing.isNullOrBlank()) return existing
        val generated = UUID.randomUUID().toString()
        prefs.edit().putString("device_id", generated).apply()
        return generated
    }

    /** ANDROID_ID: survives app reinstall (same signing key); changes only on factory reset. */
    private fun androidId(): String = try {
        Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ANDROID_ID).orEmpty()
    } catch (_: Exception) { "" }

    private fun identityBody(): JSONObject = JSONObject()
        .put("p_device_id", deviceId())
        .put("p_android_id", androidId())

    /** Remembered locally once the server says this phone is banned, so the block also holds offline. */
    fun isLocallyBanned(): Boolean = prefs.getBoolean("device_banned", false)
    private fun markBanned() { prefs.edit().putBoolean("device_banned", true).apply() }

    /** Runs BEFORE the login screen / any UI. True = this phone is on the ban list.
     *  Network errors return false here (the license check that follows still fails closed offline). */
    suspend fun checkBanned(): Boolean = withContext(Dispatchers.IO) {
        if (isLocallyBanned()) return@withContext true
        try {
            val r = request("/rest/v1/rpc/is_device_banned", "POST", identityBody(), authenticated = false)
            val banned = r.optBoolean("banned", false)
            if (banned) markBanned()
            banned
        } catch (_: Exception) { false }
    }

    /** Human-readable device name shown in the admin dashboard, e.g. "samsung SM-T509 (Android 13)". */
    private fun deviceName(): String {
        val maker = Build.MANUFACTURER.orEmpty().trim()
        val model = Build.MODEL.orEmpty().trim()
        val name = if (model.startsWith(maker, ignoreCase = true)) model else "$maker $model"
        return "${name.trim().ifBlank { "Android device" }} (Android ${Build.VERSION.RELEASE})".take(80)
    }

    suspend fun signUp(email: String, password: String): String = withContext(Dispatchers.IO) {
        val body = JSONObject().put("email", email.trim()).put("password", password)
        val result = request("/auth/v1/signup", "POST", body, authenticated = false)
        val user = result.optJSONObject("user") ?: result
        if (user.optString("id").isNotBlank() && result.optString("access_token").isNotBlank()) {
            saveSession(result, email)
            "تم إنشاء الحساب. سيظل معلقًا حتى تؤكد البريد وتفعّل الترخيص من لوحة الإدارة."
        } else {
            "تم إرسال طلب إنشاء الحساب. افحص بريدك لتأكيده، ثم سجّل الدخول بعد التفعيل الإداري."
        }
    }

    suspend fun signIn(email: String, password: String): License = withContext(Dispatchers.IO) {
        val result = request("/auth/v1/token?grant_type=password", "POST", JSONObject().put("email", email.trim()).put("password", password), authenticated = false)
        saveSession(result, email)
        authorize()
    }

    suspend fun resetPassword(email: String): String = withContext(Dispatchers.IO) {
        try {
            request("/auth/v1/recover", "POST", JSONObject().put("email", email.trim()), authenticated = false)
        } catch (e: Exception) {
            val reason = e.message.orEmpty().lowercase()
            if (reason.contains("error sending recovery email") || reason.contains("error sending confirmation") ||
                reason.contains("smtp") || (reason.contains("email") && reason.contains("rate limit"))
            ) {
                throw IllegalStateException(
                    "تعذر إرسال بريد الاستعادة. خدمة الإيميل الافتراضية في Supabase محدودة جدًا — " +
                    "يجب على المسؤول تفعيل SMTP مخصص من لوحة Supabase (Authentication → Settings → SMTP Settings)."
                )
            }
            throw e
        }
        "إذا كان هذا البريد مسجلاً لدينا، فستصلك رسالة لإعادة تعيين كلمة المرور خلال دقائق."
    }

    /** Self-service password change for a signed-in user who still remembers their current
     *  password. The current password is re-verified with a throwaway sign-in call (its
     *  tokens are discarded) before the update, so a stolen/unlocked session on its own is
     *  never enough to change the password. Never touches the admin-only admin_set_password
     *  RPC, which exists for the opposite case (the user forgot it and an admin resets it). */
    suspend fun changePassword(currentPassword: String, newPassword: String): String = withContext(Dispatchers.IO) {
        if (newPassword.length < 8) throw IllegalStateException("كلمة المرور الجديدة يجب ألا تقل عن 8 أحرف.")
        if (newPassword == currentPassword) throw IllegalStateException("كلمة المرور الجديدة مطابقة للحالية.")
        val userEmail = email()
        if (userEmail.isBlank()) throw IllegalStateException("لا توجد جلسة دخول حالية.")
        // Confirms the current password without disturbing the stored session.
        try {
            request(
                "/auth/v1/token?grant_type=password", "POST",
                JSONObject().put("email", userEmail).put("password", currentPassword),
                authenticated = false
            )
        } catch (_: Exception) {
            throw IllegalStateException("كلمة المرور الحالية غير صحيحة.")
        }
        val result = request("/auth/v1/user", "PUT", JSONObject().put("password", newPassword), authenticated = true)
        if (result.optString("id").isBlank()) {
            throw IllegalStateException(result.optString("msg", result.optString("message", "تعذر تحديث كلمة المرور.")))
        }
        // Keep the saved remember-me password (if any) in sync so auto-fill still works.
        if (rememberEnabled()) saveCredentials(userEmail, newPassword, true)
        "تم تغيير كلمة المرور بنجاح."
    }

    suspend fun authorize(): License = withContext(Dispatchers.IO) {
        if (!hasSession()) return@withContext License(false, "signed_out", null, "سجّل الدخول أولًا.")
        try {
            val body = identityBody().put("p_device_name", deviceName())
            val result = try {
                request("/rest/v1/rpc/authorize_device", "POST", body, authenticated = true)
            } catch (e: Exception) {
                val reason = e.message.orEmpty().lowercase()
                if (reason.contains("jwt") || reason.contains("token") || reason.contains("401")) {
                    refreshSession()
                    request("/rest/v1/rpc/authorize_device", "POST", body, authenticated = true)
                } else throw e
            }
            if (result.optString("status") == "device_banned") markBanned()
            License(
                active = result.optBoolean("active", false),
                status = result.optString("status", "pending"),
                expiresAt = result.optString("expires_at").takeIf { it.isNotBlank() && it != "null" },
                message = result.optString("message", "")
            )
        } catch (e: Exception) {
            License(false, "offline", null, e.message ?: "تعذر التحقق من الترخيص. تحقق من الإنترنت وإعدادات Supabase.")
        }
    }

    /** Low-level escape hatch for AdminService: the same request plumbing used above,
     *  but it never reads or writes the stored session. This matters for account
     *  creation in particular — an admin creating accounts for other people must not
     *  have their own session silently replaced by the new account's tokens. */
    suspend fun raw(path: String, method: String, body: JSONObject?, authenticated: Boolean): JSONObject =
        withContext(Dispatchers.IO) { request(path, method, body, authenticated) }

    private fun saveSession(result: JSONObject, email: String) {
        val access = result.optString("access_token")
        if (access.isBlank()) throw IllegalStateException(result.optString("msg", result.optString("message", "لم يرجع الخادم جلسة دخول. تأكد من تأكيد البريد.")))
        prefs.edit().putString("access_token", access)
            .putString("refresh_token", result.optString("refresh_token"))
            .putString("email", email.trim()).apply()
    }

    private suspend fun refreshSession() = withContext(Dispatchers.IO) {
        val refresh = prefs.getString("refresh_token", null) ?: throw IllegalStateException("انتهت الجلسة. سجّل الدخول مرة أخرى.")
        val result = request("/auth/v1/token?grant_type=refresh_token", "POST", JSONObject().put("refresh_token", refresh), authenticated = false)
        saveSession(result, email())
    }

    private fun request(path: String, method: String, body: JSONObject?, authenticated: Boolean): JSONObject {
        if (baseUrl.contains("YOUR_PROJECT") || anonKey == "YOUR_SUPABASE_ANON_KEY") {
            throw IllegalStateException("لم يتم إعداد Supabase بعد. اتبع ملف SETUP.md وأضف SUPABASE_URL وSUPABASE_ANON_KEY.")
        }
        val conn = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15000
            readTimeout = 20000
            setRequestProperty("apikey", anonKey)
            setRequestProperty("Accept", "application/json")
            if (authenticated) {
                val token = prefs.getString("access_token", null) ?: ""
                setRequestProperty("Authorization", "Bearer $token")
            } else setRequestProperty("Authorization", "Bearer $anonKey")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
        }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        conn.disconnect()
        val json = try { if (text.startsWith("[")) JSONObject().put("items", org.json.JSONArray(text)) else JSONObject(text.ifBlank { "{}" }) }
            catch (_: Exception) { JSONObject().put("message", text) }
        if (code !in 200..299) {
            val msg = json.optString("msg", json.optString("message", json.optString("error_description", "HTTP $code")))
            throw IllegalStateException(msg)
        }
        return json
    }
}
