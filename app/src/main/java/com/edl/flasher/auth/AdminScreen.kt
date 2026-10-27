package com.edl.flasher.auth

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
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.edl.flasher.R
import kotlinx.coroutines.launch
import java.util.Locale

private object AdminColors {
    val Bg = Color(0xFF05070A)
    val BgGlowTop = Color(0xFF101C33)
    val Surface = Color(0xFF121722)
    val SurfaceHi = Color(0xFF182030)
    val Border = Color(0xFF232D3F)
    val Primary = Color(0xFF2563EB)
    val SkyText = Color(0xFF38BDF8)
    val Success = Color(0xFF10B981)
    val Danger = Color(0xFFDC2626)
    val DangerText = Color(0xFFF87171)
    val WarningText = Color(0xFFFBBF24)
    val Text = Color(0xFFF1F5F9)
    val TextSoft = Color(0xFFCBD5E1)
    val Muted = Color(0xFF64748B)
}

@Composable
fun AdminScreen(service: AdminService, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    var accounts by remember { mutableStateOf<List<AdminService.Account>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf("") }

    var activateDays by remember { mutableStateOf("30") }
    var activateBusy by remember { mutableStateOf(false) }
    var activateMessage by remember { mutableStateOf("") }

    var newEmails by remember { mutableStateOf("") }
    var createBusy by remember { mutableStateOf(false) }
    var createResults by remember { mutableStateOf<List<AdminService.NewAccountResult>>(emptyList()) }

    fun reload() {
        loading = true; loadError = ""
        scope.launch {
            try { accounts = service.listAccounts() } catch (e: Exception) { loadError = e.message ?: "تعذر تحميل الحسابات." }
            loading = false
        }
    }
    LaunchedEffect(Unit) { reload() }

    val pendingCount = accounts.count { it.status == "pending" }

    MaterialTheme(colorScheme = darkColorScheme(background = AdminColors.Bg, surface = AdminColors.Surface, primary = AdminColors.Primary)) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(AdminColors.BgGlowTop, AdminColors.Bg)))
        ) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowForward, contentDescription = "رجوع", tint = AdminColors.TextSoft) }
                    Image(painter = painterResource(id = R.drawable.yas_logo), contentDescription = null, modifier = Modifier.size(34.dp))
                    Column(Modifier.weight(1f)) {
                        Text("لوحة الإدارة", style = MaterialTheme.typography.titleLarge, color = AdminColors.Text, fontWeight = FontWeight.Bold)
                        Text("YAS SOFTWARE", style = MaterialTheme.typography.labelSmall, color = AdminColors.Muted)
                    }
                    IconButton(onClick = { reload() }) { Icon(Icons.Filled.Refresh, contentDescription = "تحديث", tint = AdminColors.SkyText) }
                }

                // ---- Bulk activate ----
                AdminCard {
                    Text("تفعيل الحسابات المعلّقة دفعة واحدة", style = MaterialTheme.typography.titleMedium, color = AdminColors.Text, fontWeight = FontWeight.Bold)
                    Text("يوجد حالياً $pendingCount حساب بانتظار التفعيل.", style = MaterialTheme.typography.bodySmall, color = AdminColors.TextSoft)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = activateDays,
                            onValueChange = { v -> activateDays = v.filter { it.isDigit() }.take(4) },
                            label = { Text("مدة التفعيل (أيام)") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            colors = fieldColors(),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.width(150.dp)
                        )
                        Button(
                            enabled = !activateBusy && pendingCount > 0 && (activateDays.toIntOrNull() ?: 0) > 0,
                            onClick = {
                                activateBusy = true; activateMessage = ""
                                scope.launch {
                                    try {
                                        val n = service.activateAllPending(activateDays.toInt())
                                        activateMessage = "تم تفعيل $n حساب بنجاح."
                                        reload()
                                    } catch (e: Exception) {
                                        activateMessage = e.message ?: "تعذر تفعيل الحسابات."
                                    }
                                    activateBusy = false
                                }
                            },
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = AdminColors.Primary, contentColor = Color.White),
                            modifier = Modifier.weight(1f).height(52.dp)
                        ) {
                            Text(if (activateBusy) "جارٍ التفعيل..." else "تفعيل كل الحسابات المعلّقة", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                    }
                    if (activateMessage.isNotBlank()) Text(activateMessage, color = AdminColors.Success, style = MaterialTheme.typography.bodySmall)
                }

                // ---- Bulk create ----
                AdminCard {
                    Text("إنشاء عدة حسابات دفعة واحدة", style = MaterialTheme.typography.titleMedium, color = AdminColors.Text, fontWeight = FontWeight.Bold)
                    Text("ضع بريداً إلكترونياً واحداً في كل سطر — سيتم توليد كلمة مرور عشوائية لكل حساب تلقائياً.", style = MaterialTheme.typography.bodySmall, color = AdminColors.TextSoft)
                    OutlinedTextField(
                        value = newEmails,
                        onValueChange = { newEmails = it },
                        placeholder = { Text("user1@example.com\nuser2@example.com\nuser3@example.com") },
                        colors = fieldColors(),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().height(130.dp)
                    )
                    val emailCount = newEmails.lines().map { it.trim() }.count { it.contains("@") }
                    Button(
                        enabled = !createBusy && emailCount > 0,
                        onClick = {
                            createBusy = true; createResults = emptyList()
                            val list = newEmails.lines().map { it.trim() }.filter { it.contains("@") }.distinct()
                            scope.launch {
                                createResults = service.createAccountsBulk(list)
                                createBusy = false
                                reload()
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AdminColors.Primary, contentColor = Color.White),
                        modifier = Modifier.fillMaxWidth().height(52.dp)
                    ) {
                        Text(if (createBusy) "جارٍ الإنشاء..." else "إنشاء $emailCount حساب", fontWeight = FontWeight.Bold)
                    }

                    if (createResults.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("بيانات الدخول الجديدة — انسخها وسلّمها للعميل الآن:", style = MaterialTheme.typography.labelMedium, color = AdminColors.WarningText)
                            createResults.forEach { r ->
                                val credentials = "${r.email}  /  ${r.password}"
                                Surface(shape = RoundedCornerShape(10.dp), color = AdminColors.SurfaceHi, border = BorderStroke(1.dp, AdminColors.Border), modifier = Modifier.fillMaxWidth()) {
                                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            if (r.error == null) Icons.Filled.CheckCircle else YasIcons.ErrorOutline,
                                            contentDescription = null,
                                            tint = if (r.error == null) AdminColors.Success else AdminColors.DangerText,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(r.email, style = MaterialTheme.typography.bodySmall, color = AdminColors.Text, fontWeight = FontWeight.Bold)
                                            Text(r.error ?: "كلمة المرور: ${r.password}", style = MaterialTheme.typography.bodySmall, color = if (r.error == null) AdminColors.SkyText else AdminColors.DangerText)
                                        }
                                        if (r.error == null) {
                                            IconButton(onClick = { clipboard.setText(AnnotatedString(credentials)) }) {
                                                Icon(YasIcons.ContentCopy, contentDescription = "نسخ", tint = AdminColors.Muted, modifier = Modifier.size(18.dp))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // ---- Account list ----
                AdminCard {
                    Text("الحسابات (${accounts.size})", style = MaterialTheme.typography.titleMedium, color = AdminColors.Text, fontWeight = FontWeight.Bold)
                    when {
                        loading -> LinearProgressIndicator(Modifier.fillMaxWidth(), color = AdminColors.SkyText, trackColor = AdminColors.SurfaceHi)
                        loadError.isNotBlank() -> Text(loadError, color = AdminColors.DangerText, style = MaterialTheme.typography.bodySmall)
                        accounts.isEmpty() -> Text("لا توجد حسابات بعد.", color = AdminColors.Muted, style = MaterialTheme.typography.bodySmall)
                        else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            accounts.forEach { account ->
                                AccountRow(account = account, onToggle = { newStatus ->
                                    scope.launch {
                                        try { service.setLicense(account.userId, newStatus, activateDays.toIntOrNull() ?: 30); reload() } catch (_: Exception) {}
                                    }
                                })
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AdminCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = AdminColors.Surface,
        border = BorderStroke(1.dp, AdminColors.Border),
        tonalElevation = 4.dp
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = AdminColors.SkyText,
    unfocusedBorderColor = AdminColors.Border,
    focusedLabelColor = AdminColors.SkyText,
    unfocusedLabelColor = AdminColors.Muted,
    cursorColor = AdminColors.SkyText,
    focusedTextColor = AdminColors.Text,
    unfocusedTextColor = AdminColors.Text,
    focusedContainerColor = AdminColors.SurfaceHi,
    unfocusedContainerColor = AdminColors.SurfaceHi
)

@Composable
private fun AccountRow(account: AdminService.Account, onToggle: (String) -> Unit) {
    val (label, color) = when (account.status) {
        "active" -> "مفعّل" to AdminColors.Success
        "pending" -> "معلّق" to AdminColors.WarningText
        "suspended" -> "موقوف" to AdminColors.DangerText
        else -> account.status to AdminColors.Muted
    }
    Surface(shape = RoundedCornerShape(12.dp), color = AdminColors.SurfaceHi, border = BorderStroke(1.dp, AdminColors.Border), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(account.email, style = MaterialTheme.typography.bodyMedium, color = AdminColors.Text, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(label, style = MaterialTheme.typography.labelSmall, color = color, fontWeight = FontWeight.Bold)
                    account.expiresAt?.let { Text("حتى ${formatShortDate(it)}", style = MaterialTheme.typography.labelSmall, color = AdminColors.Muted) }
                    Text("أجهزة: ${account.activeDeviceCount}", style = MaterialTheme.typography.labelSmall, color = AdminColors.Muted)
                }
            }
            when (account.status) {
                "pending" -> TextButton(onClick = { onToggle("active") }) { Text("تفعيل", color = AdminColors.SkyText) }
                "active" -> TextButton(onClick = { onToggle("suspended") }) { Text("إيقاف", color = AdminColors.DangerText) }
                "suspended" -> TextButton(onClick = { onToggle("active") }) { Text("تفعيل", color = AdminColors.SkyText) }
            }
        }
    }
}

private fun formatShortDate(raw: String): String = try {
    val parsed = java.time.OffsetDateTime.parse(raw).toLocalDate()
    parsed.format(java.time.format.DateTimeFormatter.ofPattern("yyyy/MM/dd", Locale.getDefault()))
} catch (_: Exception) { raw }
