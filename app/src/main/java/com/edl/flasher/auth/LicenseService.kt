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
        request("/auth/v1/recover?redirect_to=" + java.net.URLEncoder.encode(BuildConfig.RESET_REDIRECT_URL, "UTF-8"),
            "POST", JSONObject().put("email", email.trim()), authenticated = false)
        "إذا كان هذا البريد مسجلاً لدينا، فستصلك رسالة لإعادة تعيين كلمة المرور خلال دقائق."
    }

    /** Finishes a password reset opened from the e-mail link: uses the short-lived recovery
     *  access token from the deep link (never stored as a session) to set the new password. */
    suspend fun completePasswordReset(recoveryToken: String, newPassword: String): String = withContext(Dispatchers.IO) {
        if (newPassword.length < 8) throw IllegalStateException("كلمة المرور الجديدة يجب ألا تقل عن 8 أحرف.")
        val result = request("/auth/v1/user", "PUT", JSONObject().put("password", newPassword), authenticated = true, bearerOverride = recoveryToken)
        val mail = result.optString("email")
        if (mail.isNotBlank() && rememberEnabled() && mail.equals(email(), ignoreCase = true)) saveCredentials(mail, newPassword, true)
        "تم تغيير كلمة المرور بنجاح. سجّل الدخول بالباسورد الجديد."
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
            val raw = result.optString("msg", result.optString("message", ""))
            throw IllegalStateException(if (raw.isBlank()) "تعذر تحديث كلمة المرور." else translateAuthError(raw))
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
            } catch (e: AuthHttpException) {
                if (e.httpCode == 401) {
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
        if (access.isBlank()) {
            val raw = result.optString("msg", result.optString("message", ""))
            throw IllegalStateException(if (raw.isBlank()) "لم يرجع الخادم جلسة دخول. تأكد من تأكيد البريد." else translateAuthError(raw))
        }
        prefs.edit().putString("access_token", access)
            .putString("refresh_token", result.optString("refresh_token"))
            .putString("email", email.trim()).apply()
    }

    private suspend fun refreshSession() = withContext(Dispatchers.IO) {
        val refresh = prefs.getString("refresh_token", null) ?: throw IllegalStateException("انتهت الجلسة. سجّل الدخول مرة أخرى.")
        val result = request("/auth/v1/token?grant_type=refresh_token", "POST", JSONObject().put("refresh_token", refresh), authenticated = false)
        saveSession(result, email())
    }

    private fun request(path: String, method: String, body: JSONObject?, authenticated: Boolean, bearerOverride: String? = null): JSONObject {
        if (baseUrl.contains("YOUR_PROJECT") || anonKey == "YOUR_SUPABASE_ANON_KEY") {
            throw IllegalStateException("لم يتم إعداد Supabase بعد. اتبع ملف SETUP.md وأضف SUPABASE_URL وSUPABASE_ANON_KEY.")
        }
        val (code, text) = try {
            val conn = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 15000
                readTimeout = 20000
                setRequestProperty("apikey", anonKey)
                setRequestProperty("Accept", "application/json")
                if (authenticated) {
                    val token = bearerOverride ?: prefs.getString("access_token", null) ?: ""
                    setRequestProperty("Authorization", "Bearer $token")
                } else setRequestProperty("Authorization", "Bearer $anonKey")
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                }
            }
            val c = conn.responseCode
            val stream = if (c in 200..299) conn.inputStream else conn.errorStream
            val t = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            conn.disconnect()
            c to t
        } catch (e: Exception) {
            // Never reached a response -- host unreachable, timed out, TLS failure, etc.
            // There is no JSON body to read here, so translate straight from the raw
            // (English, Java-exception-shaped) message instead of letting it reach the UI.
            throw IllegalStateException(translateAuthError(e.message.orEmpty()))
        }
        val json = try { if (text.startsWith("[")) JSONObject().put("items", org.json.JSONArray(text)) else JSONObject(text.ifBlank { "{}" }) }
            catch (_: Exception) { JSONObject().put("message", text) }
        if (code !in 200..299) {
            val msg = json.optString("msg", json.optString("message", json.optString("error_description", "HTTP $code")))
            throw AuthHttpException(code, translateAuthError(msg))
        }
        return json
    }
}

/** Thrown by [LicenseService.request] for an HTTP-level error response (as opposed to a
 *  network/connectivity failure, which stays a plain IllegalStateException). Carries the
 *  real status code so callers -- [LicenseService.authorize] in particular, to decide
 *  whether to retry once after a token refresh -- can branch on the actual HTTP code
 *  instead of pattern-matching the (now Arabic, translated) error text for "401". */
private class AuthHttpException(val httpCode: Int, message: String) : IllegalStateException(message)

/** Maps raw Supabase/GoTrue auth error text and low-level network exception messages to a
 *  short, friendly Arabic message. Both of those are English/technical by nature (GoTrue
 *  errors like "Invalid login credentials", Java network exceptions like "Unable to
 *  resolve host ...") and were leaking straight onto the login screen before -- this is
 *  the single place that text gets translated, so every call site (signIn/signUp/
 *  resetPassword/completePasswordReset/authorize/changePassword, all of which end up
 *  calling LicenseService.request) gets a consistent, readable message instead of each
 *  one pattern-matching the raw text on its own. */
private fun translateAuthError(raw: String): String {
    if (raw.isBlank()) return "حدث خطأ غير متوقع. حاول مرة أخرى."
    val r = raw.lowercase()
    return when {
        r.contains("invalid login credentials") || r.contains("invalid_grant") ||
            r.contains("invalid email or password") ->
            "البريد الإلكتروني أو كلمة المرور غير صحيحة."
        r.contains("email not confirmed") ->
            "لم يتم تأكيد البريد الإلكتروني بعد. افحص بريدك واضغط على رابط التأكيد."
        r.contains("user already registered") || r.contains("already registered") ||
            (r.contains("already") && r.contains("exists")) ->
            "هذا البريد مسجل بالفعل. سجّل الدخول بدلًا من إنشاء حساب جديد."
        r.contains("password should be at least") || r.contains("password is too short") ||
            r.contains("should contain at least") ->
            "كلمة المرور قصيرة جدًا، يجب ألا تقل عن 8 أحرف."
        r.contains("weak password") || r.contains("password is known to be weak") ->
            "كلمة المرور ضعيفة وسهلة التخمين. استخدم كلمة مرور أقوى."
        r.contains("error sending recovery email") || r.contains("error sending confirmation") ||
            r.contains("smtp") || (r.contains("email") && r.contains("rate limit")) ->
            "تعذر إرسال البريد الإلكتروني حاليًا. حاول مرة أخرى بعد قليل، أو تواصل مع الإدارة لو المشكلة استمرت."
        r.contains("rate limit") || r.contains("too many requests") || r.contains("429") ->
            "تم إرسال عدد كبير من المحاولات. انتظر شوية وحاول تاني."
        r.contains("signups not allowed") || r.contains("signup is disabled") ->
            "إنشاء حسابات جديدة متوقف حاليًا. تواصل مع الإدارة."
        r.contains("jwt") || r.contains("token is expired") ->
            "انتهت صلاحية الجلسة أو الرابط. حاول تسجيل الدخول من جديد، أو اطلب رابطًا جديدًا لو كنت بتستعيد كلمة المرور."
        r.contains("unable to resolve host") || r.contains("no address associated") ||
            r.contains("unknownhost") ->
            "تعذر الاتصال بالخادم. تأكد من اتصال الإنترنت على الجهاز."
        r.contains("timeout") || r.contains("timed out") ->
            "انتهت مهلة الاتصال بالخادم. تأكد من الإنترنت وحاول مرة أخرى."
        r.contains("connection refused") || r.contains("failed to connect") ||
            r.contains("econnrefused") || r.contains("network is unreachable") ->
            "تعذر الاتصال بالخادم. حاول مرة أخرى بعد قليل."
        r.contains("certificate") || r.contains("sslhandshake") || r.contains("trust anchor") ->
            "تعذر إنشاء اتصال آمن بالخادم. تأكد من ضبط التاريخ والوقت على الجهاز وحاول مرة أخرى."
        else -> "حدث خطأ غير متوقع. حاول مرة أخرى."
    }
}
