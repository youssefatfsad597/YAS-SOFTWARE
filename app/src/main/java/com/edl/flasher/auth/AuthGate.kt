package com.edl.flasher.auth

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Locale

@Composable
fun AuthGate(service: LicenseService, onAuthorizationChanged: (Boolean) -> Unit, content: @Composable () -> Unit) {
    var authorized by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(true) }
    var registerMode by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf(service.email()) }
    var password by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var license by remember { mutableStateOf<LicenseService.License?>(null) }
    val scope = rememberCoroutineScope()

    fun verify() {
        checking = true
        scope.launch {
            val result = service.authorize()
            license = result
            authorized = result.active
            message = result.message
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
            }
        }
    }
    if (authorized) {
        Box(Modifier.fillMaxSize()) {
            content()
            Surface(Modifier.align(Alignment.TopEnd).padding(6.dp), shape = MaterialTheme.shapes.large, tonalElevation = 5.dp) {
                TextButton(onClick = { service.clearSession(); authorized = false; license = null; message = "تم تسجيل الخروج." }) {
                    Text("خروج")
                }
            }
        }
        return
    }

    MaterialTheme(colorScheme = darkColorScheme()) {
        Box(Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) {
            Column(Modifier.fillMaxWidth().widthIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("YAS SOFTWARE", style = MaterialTheme.typography.headlineMedium)
                Text("حساب المستخدم والترخيص", style = MaterialTheme.typography.titleMedium)
                val current = license
                if (current != null) {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text("حالة الحساب: ${statusArabic(current.status)}", style = MaterialTheme.typography.titleSmall)
                            current.expiresAt?.let { Text("انتهاء التفعيل: ${formatDate(it)}") }
                            if (current.deviceLimit > 0) Text("الأجهزة المسجلة: ${current.deviceCount} من ${current.deviceLimit}")
                            if (current.message.isNotBlank()) Text(current.message)
                        }
                    }
                }
                OutlinedTextField(value = email, onValueChange = { email = it }, label = { Text("البريد الإلكتروني") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("كلمة المرور (8 أحرف على الأقل)") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
                if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.error)
                Button(enabled = !checking && email.contains("@") && password.length >= 8, modifier = Modifier.fillMaxWidth(), onClick = {
                    checking = true; message = ""
                    scope.launch {
                        try {
                            if (registerMode) message = service.signUp(email, password)
                            else {
                                val result = service.signIn(email, password)
                                license = result
                                message = result.message.ifBlank { if (result.active) "تم التحقق من الترخيص." else "الحساب غير مفعّل أو انتهت مدة الترخيص." }
                                authorized = result.active
                            }
                        } catch (e: Exception) { message = e.message ?: "حدث خطأ." }
                        checking = false
                    }
                }) { Text(if (checking) "جارٍ التحقق..." else if (registerMode) "إنشاء حساب" else "تسجيل الدخول") }
                TextButton(onClick = { registerMode = !registerMode; message = "" }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text(if (registerMode) "لديك حساب؟ سجّل الدخول" else "إنشاء حساب جديد")
                }
                if (service.hasSession()) TextButton(onClick = { service.clearSession(); license = null; message = "تم تسجيل الخروج." }) { Text("تسجيل الخروج") }
                if (current?.status == "offline") TextButton(onClick = { verify() }) { Text("إعادة التحقق") }
                Text("يجب تأكيد البريد الإلكتروني وتفعيل الترخيص من الإدارة قبل استخدام الأدوات.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun statusArabic(status: String) = when (status.lowercase()) {
    "active" -> "مفعّل"; "pending" -> "بانتظار التفعيل"; "expired" -> "منتهي"; "suspended" -> "موقوف"; "signed_out" -> "غير مسجل"; "offline" -> "تعذر الاتصال"; "device_limit" -> "تجاوز حد الأجهزة"; else -> status
}
private fun formatDate(raw: String): String = try {
    val parsed = java.time.OffsetDateTime.parse(raw).toLocalDateTime()
    parsed.format(java.time.format.DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm", Locale.getDefault()))
} catch (_: Exception) { raw }
