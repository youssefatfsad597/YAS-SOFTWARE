package com.edl.flasher.auth

import com.edl.flasher.BuildConfig

import android.content.Context
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
    private val prefs = EncryptedSharedPreferences.create(
        context,
        "yas_secure_session",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )
    private val baseUrl = BuildConfig.SUPABASE_URL.trimEnd('/')
    private val anonKey = BuildConfig.SUPABASE_ANON_KEY

    data class License(val active: Boolean, val status: String, val expiresAt: String?, val deviceLimit: Int, val deviceCount: Int, val message: String = "")

    fun hasSession(): Boolean = !prefs.getString("access_token", null).isNullOrBlank()
    fun clearSession() { prefs.edit().remove("access_token").remove("refresh_token").remove("email").apply() }
    fun email(): String = prefs.getString("email", "") ?: ""

    private fun deviceId(): String {
        val existing = prefs.getString("device_id", null)
        if (!existing.isNullOrBlank()) return existing
        val generated = UUID.randomUUID().toString()
        prefs.edit().putString("device_id", generated).apply()
        return generated
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

    suspend fun authorize(): License = withContext(Dispatchers.IO) {
        if (!hasSession()) return@withContext License(false, "signed_out", null, 0, 0, "سجّل الدخول أولًا.")
        try {
            val body = JSONObject().put("p_device_id", deviceId())
            val result = try {
                request("/rest/v1/rpc/authorize_device", "POST", body, authenticated = true)
            } catch (e: Exception) {
                val reason = e.message.orEmpty().lowercase()
                if (reason.contains("jwt") || reason.contains("token") || reason.contains("401")) {
                    refreshSession()
                    request("/rest/v1/rpc/authorize_device", "POST", body, authenticated = true)
                } else throw e
            }
            License(
                active = result.optBoolean("active", false),
                status = result.optString("status", "pending"),
                expiresAt = result.optString("expires_at").takeIf { it.isNotBlank() && it != "null" },
                deviceLimit = result.optInt("device_limit", 0),
                deviceCount = result.optInt("device_count", 0),
                message = result.optString("message", "")
            )
        } catch (e: Exception) {
            License(false, "offline", null, 0, 0, e.message ?: "تعذر التحقق من الترخيص. تحقق من الإنترنت وإعدادات Supabase.")
        }
    }

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
