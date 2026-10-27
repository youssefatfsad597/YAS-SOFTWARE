package com.edl.flasher.auth

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.edl.flasher.R
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.time.Duration
import java.util.Locale

/** Dark, techy palette shared across the gate screens (mirrors the in-app theme). */
private object Yas {
    val Bg = Color(0xFF05070A)
    val BgGlowTop = Color(0xFF101C33)
    val Surface = Color(0xFF121722)
    val SurfaceHi = Color(0xFF182030)
    val Border = Color(0xFF232D3F)
    val BorderStrong = Color(0xFF334155)

    val Primary = Color(0xFF2563EB)
    val Sky = Color(0xFF0284C7)
    val SkyText = Color(0xFF38BDF8)
    val Danger = Color(0xFFDC2626)
    val DangerText = Color(0xFFF87171)
    val Warning = Color(0xFFD97706)
    val WarningText = Color(0xFFFBBF24)
    val Success = Color(0xFF10B981)

    val Text = Color(0xFFF1F5F9)
    val TextSoft = Color(0xFFCBD5E1)
    val Muted = Color(0xFF64748B)
}

@Composable
fun AuthGate(service: LicenseService, onAuthorizationChanged: (Boolean) -> Unit, content: @Composable () -> Unit) {
    val context = LocalContext.current
    var authorized by remember { mutableStateOf(false) }
    var enteredApp by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(true) }
    var registerMode by remember { mutableStateOf(false) }
    var forgotMode by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf(service.email()) }
    var password by remember { mutableStateOf(service.savedPassword()) }
    var rememberMe by remember { mutableStateOf(service.rememberEnabled()) }
    var showPassword by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var isError by remember { mutableStateOf(false) }
    var license by remember { mutableStateOf<LicenseService.License?>(null) }
    var tamperBlocked by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val adminService = remember { AdminService(service) }
    var isAdmin by remember { mutableStateOf(false) }
    var showAdmin by remember { mutableStateOf(false) }

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
        onAuthorizationChanged(authorized && enteredApp)
        if (!authorized) { enteredApp = false; showAdmin = false; isAdmin = false }
        if (authorized) isAdmin = adminService.isAdmin()
        while (authorized) {
            delay(60_000)
            val result = service.authorize()
            license = result
            if (!result.active) {
                authorized = false
                enteredApp = false
                message = result.message.ifBlank { "لم يعد الترخيص صالحًا." }
                isError = true
            }
        }
    }
    // The running app itself has no exit/logout control — closing the app (see
    // MainActivity.onDestroy) is what ends the session and counts as a logout.
    LaunchedEffect(enteredApp) { onAuthorizationChanged(authorized && enteredApp) }

    if (tamperBlocked) {
        GateScaffold {
            Text(
                "تعذر التحقق: هذه النسخة من التطبيق معدّلة أو موقّعة بشكل غير صحيح. يرجى تثبيت النسخة الرسمية من YAS SOFTWARE.",
                style = MaterialTheme.typography.titleMedium,
                color = Yas.DangerText,
                textAlign = TextAlign.Center
            )
        }
        return
    }

    if (authorized && showAdmin) {
        AdminScreen(service = adminService, onBack = { showAdmin = false })
        return
    }

    if (authorized && enteredApp) {
        content()
        return
    }

    if (authorized && !enteredApp) {
        SubscriptionGateScreen(
            license = license,
            isAdmin = isAdmin,
            onEnter = { enteredApp = true },
            onOpenAdmin = { showAdmin = true },
            onSignOut = {
                service.clearSession()
                authorized = false
                enteredApp = false
                license = null
                message = "تم تسجيل الخروج."
                isError = false
            }
        )
        return
    }

    LoginScreen(
        checking = checking,
        registerMode = registerMode,
        forgotMode = forgotMode,
        email = email,
        password = password,
        rememberMe = rememberMe,
        showPassword = showPassword,
        message = message,
        isError = isError,
        license = license,
        hasSession = service.hasSession(),
        onEmailChange = { email = it; isError = false },
        onPasswordChange = { password = it; isError = false },
        onToggleShowPassword = { showPassword = !showPassword },
        onToggleRemember = { rememberMe = it },
        onToggleRegisterMode = { registerMode = !registerMode; forgotMode = false; message = ""; isError = false },
        onToggleForgotMode = { forgotMode = !forgotMode; message = ""; isError = false },
        onRetry = { verify() },
        onSignOutStaleSession = {
            service.clearSession()
            license = null
            message = "تم تسجيل الخروج."
            isError = false
        },
        onSubmit = {
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
                            service.saveCredentials(email, password, rememberMe)
                        }
                    }
                } catch (e: Exception) {
                    message = e.message ?: "حدث خطأ."
                    isError = true
                }
                checking = false
            }
        }
    )
}

/** Shared dark/techy backdrop: soft radial glow over a near-black background. */
@Composable
private fun GateScaffold(content: @Composable ColumnScope.() -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(background = Yas.Bg, surface = Yas.Surface, primary = Yas.Primary)) {
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(Yas.BgGlowTop, Yas.Bg),
                        center = Offset.Zero,
                        radius = 1600f
                    )
                )
                .padding(20.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                Modifier.fillMaxWidth().widthIn(max = 460.dp).verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
                content = content
            )
        }
    }
}

@Composable
private fun BrandHeader(subtitle: String) {
    // The logo artwork already carries its own glow and rounded-square border, so it is
    // shown large and unclipped — a soft halo behind it adds depth without boxing it in
    // or cropping it the way an extra circular frame would.
    Box(contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(136.dp)
                .background(Brush.radialGradient(listOf(Yas.SkyText.copy(alpha = 0.30f), Color.Transparent)))
        )
        Image(
            painter = painterResource(id = R.drawable.yas_logo),
            contentDescription = "YAS SOFTWARE",
            modifier = Modifier.size(108.dp)
        )
    }
    Spacer(Modifier.height(2.dp))
    Text(
        "YAS SOFTWARE",
        style = MaterialTheme.typography.headlineMedium,
        color = Yas.Text,
        fontWeight = FontWeight.ExtraBold
    )
    Text(
        subtitle,
        style = MaterialTheme.typography.bodySmall,
        color = Yas.Muted,
        textAlign = TextAlign.Center
    )
}

@Composable
private fun TechCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = Yas.Surface,
        border = BorderStroke(1.dp, Yas.Border),
        tonalElevation = 6.dp,
        shadowElevation = 10.dp
    ) {
        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
    }
}

@Composable
private fun StatusChip(status: String) {
    val (label, color) = when (status.lowercase()) {
        "active" -> "مفعّل" to Yas.Success
        "pending" -> "بانتظار التفعيل" to Yas.WarningText
        "expired" -> "منتهي" to Yas.DangerText
        "suspended" -> "موقوف" to Yas.DangerText
        "signed_out" -> "غير مسجل" to Yas.Muted
        "offline" -> "تعذر الاتصال" to Yas.DangerText
        "device_cooldown" -> "بانتظار تبديل الجهاز" to Yas.WarningText
        "device_revoked" -> "تم إلغاء الجهاز" to Yas.DangerText
        else -> status to Yas.Muted
    }
    Surface(shape = RoundedCornerShape(50), color = color.copy(alpha = 0.14f), border = BorderStroke(1.dp, color.copy(alpha = 0.4f))) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(color))
            Text("حالة الحساب: $label", style = MaterialTheme.typography.labelMedium, color = color, fontWeight = FontWeight.Bold)
        }
    }
}

private fun formatDate(raw: String): String = try {
    val parsed = java.time.OffsetDateTime.parse(raw).toLocalDateTime()
    parsed.format(java.time.format.DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm", Locale.getDefault()))
} catch (_: Exception) { raw }

/** Human-friendly "متبقٍ: يومان وساعة" style countdown shown on the subscription gate screen. */
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
        parts.joinToString(" و")
    }
} catch (_: Exception) { "" }

/** Shown right after a successful sign-in: the subscription/license summary the user must
 *  see before the main program opens. There is no in-app exit button — leaving the program
 *  (back button / closing the app) is what ends the session. */
@Composable
private fun SubscriptionGateScreen(
    license: LicenseService.License?,
    isAdmin: Boolean,
    onEnter: () -> Unit,
    onOpenAdmin: () -> Unit,
    onSignOut: () -> Unit
) {
    GateScaffold {
        BrandHeader(subtitle = "تم التحقق من حسابك بنجاح")
        TechCard {
            Text("ملخص الاشتراك", style = MaterialTheme.typography.titleMedium, color = Yas.Text, fontWeight = FontWeight.Bold)
            license?.let { lic ->
                StatusChip(lic.status)
                if (lic.expiresAt != null) {
                    Surface(shape = RoundedCornerShape(16.dp), color = Yas.SurfaceHi, border = BorderStroke(1.dp, Yas.Border), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("المدة المتبقية على الاشتراك", style = MaterialTheme.typography.labelMedium, color = Yas.Muted)
                            Text(
                                remainingLabel(lic.expiresAt).ifBlank { "—" },
                                style = MaterialTheme.typography.headlineSmall,
                                color = Yas.SkyText,
                                fontWeight = FontWeight.ExtraBold
                            )
                            Text("تاريخ الانتهاء: ${formatDate(lic.expiresAt)}", style = MaterialTheme.typography.bodySmall, color = Yas.TextSoft)
                        }
                    }
                }
            }
            Button(
                onClick = onEnter,
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Yas.Primary, contentColor = Color.White)
            ) {
                Text("الدخول إلى البرنامج", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
            if (isAdmin) {
                OutlinedButton(
                    onClick = onOpenAdmin,
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, Yas.SkyText.copy(alpha = 0.5f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Yas.SkyText)
                ) {
                    Text("لوحة الإدارة", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
            }
            TextButton(onClick = onSignOut, modifier = Modifier.fillMaxWidth()) {
                Text("تسجيل الخروج من هذا الحساب", color = Yas.Muted)
            }
        }
    }
}

@Composable
private fun LoginScreen(
    checking: Boolean,
    registerMode: Boolean,
    forgotMode: Boolean,
    email: String,
    password: String,
    rememberMe: Boolean,
    showPassword: Boolean,
    message: String,
    isError: Boolean,
    license: LicenseService.License?,
    hasSession: Boolean,
    onEmailChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onToggleShowPassword: () -> Unit,
    onToggleRemember: (Boolean) -> Unit,
    onToggleRegisterMode: () -> Unit,
    onToggleForgotMode: () -> Unit,
    onRetry: () -> Unit,
    onSignOutStaleSession: () -> Unit,
    onSubmit: () -> Unit
) {
    GateScaffold {
        BrandHeader(subtitle = "منصّة احترافية لإدارة وفلاش الأجهزة")

        TechCard {
            Text(
                if (checking) "جارٍ التحقق من الترخيص..." else if (forgotMode) "استعادة كلمة المرور" else if (registerMode) "إنشاء حساب جديد" else "تسجيل الدخول",
                style = MaterialTheme.typography.titleLarge,
                color = Yas.Text,
                fontWeight = FontWeight.Bold
            )

            if (license != null && !forgotMode) {
                Surface(shape = RoundedCornerShape(16.dp), color = Yas.SurfaceHi, border = BorderStroke(1.dp, Yas.Border), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        StatusChip(license.status)
                        license.expiresAt?.let {
                            Text("انتهاء التفعيل: ${formatDate(it)}", style = MaterialTheme.typography.bodySmall, color = Yas.TextSoft)
                            val remaining = remainingLabel(it)
                            if (remaining.isNotBlank()) Text("متبقٍ: $remaining", style = MaterialTheme.typography.bodySmall, color = Yas.SkyText)
                        }
                    }
                }
            }

            val fieldColors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Yas.SkyText,
                unfocusedBorderColor = Yas.Border,
                focusedLabelColor = Yas.SkyText,
                unfocusedLabelColor = Yas.Muted,
                cursorColor = Yas.SkyText,
                focusedTextColor = Yas.Text,
                unfocusedTextColor = Yas.Text,
                focusedContainerColor = Yas.SurfaceHi,
                unfocusedContainerColor = Yas.SurfaceHi
            )

            OutlinedTextField(
                value = email,
                onValueChange = onEmailChange,
                label = { Text("البريد الإلكتروني") },
                singleLine = true,
                isError = isError && email.isBlank(),
                leadingIcon = { Icon(Icons.Filled.Email, contentDescription = null, tint = Yas.Muted) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                shape = RoundedCornerShape(14.dp),
                colors = fieldColors,
                modifier = Modifier.fillMaxWidth()
            )

            if (!forgotMode) {
                OutlinedTextField(
                    value = password,
                    onValueChange = onPasswordChange,
                    label = { Text("كلمة المرور (8 أحرف على الأقل)") },
                    singleLine = true,
                    isError = isError && password.length < 8,
                    leadingIcon = { Icon(Icons.Filled.Lock, contentDescription = null, tint = Yas.Muted) },
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = onToggleShowPassword) {
                            Icon(
                                if (showPassword) YasIcons.VisibilityOff else YasIcons.Visibility,
                                contentDescription = if (showPassword) "إخفاء كلمة المرور" else "إظهار كلمة المرور",
                                tint = Yas.Muted
                            )
                        }
                    },
                    shape = RoundedCornerShape(14.dp),
                    colors = fieldColors,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            if (!forgotMode && !registerMode) {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = rememberMe,
                        onCheckedChange = onToggleRemember,
                        colors = CheckboxDefaults.colors(checkedColor = Yas.Primary, uncheckedColor = Yas.Muted, checkmarkColor = Color.White)
                    )
                    Text("حفظ البريد الإلكتروني وكلمة المرور", style = MaterialTheme.typography.bodyMedium, color = Yas.TextSoft)
                }
            }

            AnimatedVisibility(visible = message.isNotBlank()) {
                Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(
                        if (isError) YasIcons.ErrorOutline else Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = if (isError) Yas.DangerText else Yas.Success,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(message, color = if (isError) Yas.DangerText else Yas.Success, style = MaterialTheme.typography.bodySmall)
                }
            }

            if (checking) {
                LinearProgressIndicator(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)),
                    color = Yas.SkyText,
                    trackColor = Yas.SurfaceHi
                )
            }

            val canSubmit = !checking && email.contains("@") && (forgotMode || password.length >= 8)
            Button(
                enabled = canSubmit,
                onClick = onSubmit,
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Yas.Primary, contentColor = Color.White, disabledContainerColor = Yas.SurfaceHi)
            ) {
                Text(
                    if (checking) "جارٍ المعالجة..." else if (forgotMode) "إرسال رابط الاستعادة" else if (registerMode) "إنشاء حساب" else "تسجيل الدخول",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = onToggleRegisterMode) {
                    Text(if (registerMode) "لديك حساب؟ سجّل الدخول" else "إنشاء حساب جديد", color = Yas.SkyText)
                }
                if (!registerMode) {
                    TextButton(onClick = onToggleForgotMode) {
                        Text(if (forgotMode) "رجوع لتسجيل الدخول" else "نسيت كلمة المرور؟", color = Yas.SkyText)
                    }
                }
            }

            if (hasSession) {
                TextButton(onClick = onSignOutStaleSession, modifier = Modifier.fillMaxWidth()) {
                    Text("تسجيل الخروج", color = Yas.Muted)
                }
            }
            if (license?.status == "offline") {
                OutlinedButton(
                    onClick = onRetry,
                    modifier = Modifier.fillMaxWidth(),
                    border = BorderStroke(1.dp, Yas.BorderStrong),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Yas.SkyText)
                ) { Text("إعادة التحقق") }
            }
        }

        Text(
            "يجب تأكيد البريد الإلكتروني وتفعيل الترخيص من الإدارة قبل استخدام الأدوات.",
            style = MaterialTheme.typography.bodySmall,
            color = Yas.Muted,
            textAlign = TextAlign.Center
        )
    }
}
