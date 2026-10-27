package com.edl.flasher.auth

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.time.Duration
import java.util.Locale

@Composable
fun AuthGate(service: LicenseService, onAuthorizationChanged: (Boolean) -> Unit, content: @Composable () -> Unit) {
    val context = LocalContext.current
    var authorized by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(true) }
    var registerMode by remember { mutableStateOf(false) }
    var forgotMode by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf(service.email()) }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var isError by remember { mutableStateOf(false) }
    var license by remember { mutableStateOf<LicenseService.License?>(null) }
    var tamperBlocked by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun verify() {
        // Refuse to run against a re-signed/repackaged build or a mismatched applicationId.
        if (!TamperGuard.verifySigningCertificate(context) || !TamperGuard.applicationIdMatches(context)) {
            tamperBlocked = true
            checking = false
            return
        }
        checking = true
        scope.launch {
            val result = service.authorize()
            license = result
            authorized = result.active
            message = result.message
            isError = !result.active
            checking = false
        }
    }

    LaunchedEffect(Unit) { verify() }
    LaunchedEffect(authorized) {
        onAuthorizationChanged(authorized)
        while (authorized) {
            delay(60_000)
            val result = service.authorize()
            license = result
            if (!result.active) {
                authorized = false
                message = result.message.ifBlank { "لم يعد الترخيص صالحًا." }
                isError = true
            }
        }
    }
    if (tamperBlocked) {
        MaterialTheme(colorScheme = darkColorScheme()) {
            Box(Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) {
                Text(
                    "تعذر التحقق: هذه النسخة من التطبيق معدّلة أو موقّعة بشكل غير صحيح. يرجى تثبيت النسخة الرسمية من YAS SOFTWARE.",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
        return
    }

    if (authorized) {
        Box(Modifier.fillMaxSize()) {
            content()
            Surface(Modifier.align(Alignment.TopEnd).padding(6.dp), shape = MaterialTheme.shapes.large, tonalElevation = 5.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    license?.expiresAt?.let {
                        Text(
                            remainingLabel(it),
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(start = 10.dp)
                        )
                    }
                    TextButton(onClick = { service.clearSession(); authorized = false; license = null; message = "تم تسجيل الخروج."; isError = false }) {
                        Text("خروج")
                    }
                }
            }
        }
        return
    }

    MaterialTheme(colorScheme = darkColorScheme()) {
        Box(Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) {
            Column(
                Modifier.fillMaxWidth().widthIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("YAS SOFTWARE", style = MaterialTheme.typography.headlineMedium)
                Text(
                    if (checking) "جارٍ التحقق من الترخيص..." else if (forgotMode) "استعادة كلمة المرور" else if (registerMode) "إنشاء حساب جديد" else "تسجيل الدخول",
                    style = MaterialTheme.typography.titleMedium
                )

                val current = license
                if (current != null && !forgotMode) {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            StatusRow(current.status)
                            current.expiresAt?.let {
                                Text("انتهاء التفعيل: ${formatDate(it)}")
                                Text(remainingLabel(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                            }
                            if (current.deviceLimit > 0) Text("الأجهزة المسجلة: ${current.deviceCount} من ${current.deviceLimit}")
                        }
                    }
                }

                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it; isError = false },
                    label = { Text("البريد الإلكتروني") },
                    singleLine = true,
                    isError = isError && email.isBlank(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth()
                )

                if (!forgotMode) {
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it; isError = false },
                        label = { Text("كلمة المرور (8 أحرف على الأقل)") },
                        singleLine = true,
                        isError = isError && password.length < 8,
                        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { showPassword = !showPassword }) {
                                Icon(
                                    if (showPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                    contentDescription = if (showPassword) "إخفاء كلمة المرور" else "إظهار كلمة المرور"
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                if (message.isNotBlank()) {
                    Text(message, color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                }

                if (checking) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }

                val canSubmit = !checking && email.contains("@") && (forgotMode || password.length >= 8)
                Button(
                    enabled = canSubmit,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        checking = true; message = ""; isError = false
                        scope.launch {
                            try {
                                when {
                                    forgotMode -> {
                                        message = service.resetPassword(email)
                                        forgotMode = false
                                    }
                                    registerMode -> {
                                        message = service.signUp(email, password)
                                    }
                                    else -> {
                                        val result = service.signIn(email, password)
                                        license = result
                                        message = result.message.ifBlank { if (result.active) "تم التحقق من الترخيص." else "الحساب غير مفعّل أو انتهت مدة الترخيص." }
                                        isError = !result.active
                                        authorized = result.active
                                    }
                                }
                            } catch (e: Exception) {
                                message = e.message ?: "حدث خطأ."
                                isError = true
                            }
                            checking = false
                        }
                    }
                ) {
                    Text(
                        if (checking) "جارٍ المعالجة..." else if (forgotMode) "إرسال رابط الاستعادة" else if (registerMode) "إنشاء حساب" else "تسجيل الدخول"
                    )
                }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { registerMode = !registerMode; forgotMode = false; message = ""; isError = false }) {
                        Text(if (registerMode) "لديك حساب؟ سجّل الدخول" else "إنشاء حساب جديد")
                    }
                    if (!registerMode) {
                        TextButton(onClick = { forgotMode = !forgotMode; message = ""; isError = false }) {
                            Text(if (forgotMode) "رجوع لتسجيل الدخول" else "نسيت كلمة المرور؟")
                        }
                    }
                }

                if (service.hasSession()) {
                    TextButton(onClick = { service.clearSession(); license = null; message = "تم تسجيل الخروج."; isError = false }) {
                        Text("تسجيل الخروج")
                    }
                }
                if (current?.status == "offline") {
                    OutlinedButton(onClick = { verify() }, modifier = Modifier.fillMaxWidth()) { Text("إعادة التحقق") }
                }
                Text(
                    "يجب تأكيد البريد الإلكتروني وتفعيل الترخيص من الإدارة قبل استخدام الأدوات.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun StatusRow(status: String) {
    val (label, color) = when (status.lowercase()) {
        "active" -> "مفعّل" to MaterialTheme.colorScheme.primary
        "pending" -> "بانتظار التفعيل" to MaterialTheme.colorScheme.secondary
        "expired" -> "منتهي" to MaterialTheme.colorScheme.error
        "suspended" -> "موقوف" to MaterialTheme.colorScheme.error
        "signed_out" -> "غير مسجل" to MaterialTheme.colorScheme.outline
        "offline" -> "تعذر الاتصال" to MaterialTheme.colorScheme.error
        "device_limit" -> "تجاوز حد الأجهزة" to MaterialTheme.colorScheme.error
        else -> status to MaterialTheme.colorScheme.outline
    }
    Text("حالة الحساب: $label", style = MaterialTheme.typography.titleSmall, color = color)
}

private fun formatDate(raw: String): String = try {
    val parsed = java.time.OffsetDateTime.parse(raw).toLocalDateTime()
    parsed.format(java.time.format.DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm", Locale.getDefault()))
} catch (_: Exception) { raw }

/** Human-friendly "متبقٍ: يومان وساعة" style countdown used both in the login card and the in-app badge. */
private fun remainingLabel(raw: String): String = try {
    val expiry = java.time.OffsetDateTime.parse(raw)
    val now = java.time.OffsetDateTime.now()
    val seconds = Duration.between(now, expiry).seconds
    if (seconds <= 0) "منتهي الصلاحية" else {
        val days = seconds / 86400
        val hours = (seconds % 86400) / 3600
        val minutes = (seconds % 3600) / 60
        val parts = mutableListOf<String>()
        if (days > 0) parts += "${days} يوم"
        if (days < 3 && hours > 0) parts += "${hours} ساعة"
        if (days == 0L && hours == 0L) parts += "${minutes} دقيقة"
        "متبقٍ: " + parts.joinToString(" و")
    }
} catch (_: Exception) { "" }
