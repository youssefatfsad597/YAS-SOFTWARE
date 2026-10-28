package com.edl.flasher.auth

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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

private data class ConfirmReq(
    val title: String,
    val text: String,
    val confirmLabel: String,
    val requireText: String? = null,
    val onConfirm: () -> Unit
)

/** Same unit choices as the web dashboard (minutes per unit). */
private val DURATION_UNITS = listOf("ساعة" to 60L, "يوم" to 1440L, "أسبوع" to 10080L, "شهر" to 43200L, "سنة" to 525600L)

@Composable
fun AdminScreen(service: AdminService, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    var accounts by remember { mutableStateOf<List<AdminService.Account>>(emptyList()) }
    var bans by remember { mutableStateOf<List<AdminService.BanRow>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var messageIsError by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<ConfirmReq?>(null) }
    var pwTarget by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var showDeleted by remember { mutableStateOf(true) }

    // Activate several accounts together
    var actEmails by remember { mutableStateOf("") }
    var actDur by remember { mutableStateOf("30") }
    var actUnit by remember { mutableStateOf(1440L) }
    var actBusy by remember { mutableStateOf(false) }

    // Bulk create
    var genCount by remember { mutableStateOf("5") }
    var genPrefix by remember { mutableStateOf("yasoftware") }
    var genDomain by remember { mutableStateOf("yas.com") }
    var newEmails by remember { mutableStateOf("") }
    var pwMode by remember { mutableStateOf(AdminService.PasswordMode.RANDOM) }
    var pwLen by remember { mutableStateOf("12") }
    var pwCustom by remember { mutableStateOf("") }
    var newDur by remember { mutableStateOf("30") }
    var newUnit by remember { mutableStateOf(1440L) }
    var createBusy by remember { mutableStateOf(false) }
    var createResults by remember { mutableStateOf<List<AdminService.NewAccountResult>>(emptyList()) }

    // Manual activation by email
    var manualEmail by remember { mutableStateOf("") }
    var manualStatus by remember { mutableStateOf("active") }
    var manualDur by remember { mutableStateOf("30") }
    var manualUnit by remember { mutableStateOf(1440L) }
    var manualBusy by remember { mutableStateOf(false) }

    // Manual ban
    var banAid by remember { mutableStateOf("") }
    var banReason by remember { mutableStateOf("") }

    fun reload() {
        loading = true; loadError = ""
        scope.launch {
            try { accounts = service.listAccounts() } catch (e: Exception) { loadError = e.message ?: "تعذر تحميل الحسابات." }
            try { bans = service.listBans() } catch (_: Exception) { }
            loading = false
        }
    }
    LaunchedEffect(Unit) { reload() }

    /** Runs an admin action, shows its result, then refreshes the lists. */
    fun perform(okMessage: String, block: suspend () -> Unit) {
        scope.launch {
            try { block(); message = okMessage; messageIsError = false; reload() }
            catch (e: Exception) { message = e.message ?: "فشلت العملية."; messageIsError = true }
        }
    }

    fun parseEmails(raw: String): List<String> =
        raw.split(Regex("[\\s,;]+")).map { it.trim().lowercase() }.filter { it.contains("@") }.distinct()

    val pendingCount = accounts.count { it.status == "pending" }
    val unitName: (Long) -> String = { u -> DURATION_UNITS.firstOrNull { it.second == u }?.first ?: "" }

    confirm?.let { req ->
        ConfirmDialog(req = req, onDismiss = { confirm = null }, onConfirmed = { confirm = null; req.onConfirm() })
    }

    pwTarget?.let { email ->
        PasswordDialog(
            email = email,
            randomPassword = { service.randomPassword(12) },
            onDismiss = { pwTarget = null },
            onConfirm = { pw ->
                pwTarget = null
                scope.launch {
                    try {
                        service.setPassword(email, pw)
                        message = "تم تغيير كلمة المرور لـ $email.\nكلمة المرور الجديدة: $pw\n⚠️ احفظها الآن، لن تظهر مرة أخرى."
                        messageIsError = false
                    } catch (e: Exception) {
                        message = e.message ?: "فشلت العملية."
                        messageIsError = true
                    }
                }
            }
        )
    }

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

                if (message.isNotBlank()) {
                    Text(message, color = if (messageIsError) AdminColors.DangerText else AdminColors.Success, style = MaterialTheme.typography.bodySmall)
                }

                // ---- Activate several accounts together ----
                AdminCard {
                    Text("تفعيل عدة حسابات مع بعض", style = MaterialTheme.typography.titleMedium, color = AdminColors.Text, fontWeight = FontWeight.Bold)
                    Text("اكتب الإيميلات (سطر لكل إيميل) وحدّد المدة، وكلها تتفعل بنفس تاريخ الانتهاء. يوجد حالياً $pendingCount حساب معلّق.", style = MaterialTheme.typography.bodySmall, color = AdminColors.TextSoft)
                    OutlinedTextField(
                        value = actEmails,
                        onValueChange = { actEmails = it },
                        placeholder = { Text("yasoftware1@yas.com\nyasoftware2@yas.com") },
                        colors = fieldColors(),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().height(120.dp)
                    )
                    DurationPicker("مدة التفعيل", actDur, { actDur = it }, actUnit, { actUnit = it })
                    val actList = parseEmails(actEmails)
                    val actValue = actDur.toIntOrNull() ?: 0
                    Button(
                        enabled = !actBusy && actList.isNotEmpty() && actValue > 0,
                        onClick = {
                            val expiry = service.expiryFrom(actValue, actUnit) ?: return@Button
                            confirm = ConfirmReq("تفعيل ${actList.size} حساب", "تفعيل الحسابات لمدة $actValue ${unitName(actUnit)}؟", "تفعيل") {
                                actBusy = true
                                scope.launch {
                                    try {
                                        val r = service.activateUsers(actList, expiry)
                                        message = "تم تفعيل ${r.activated} حساب." +
                                            if (r.notFound.isNotEmpty()) "\nغير موجودة (${r.notFound.size}): ${r.notFound.joinToString("، ")}" else ""
                                        messageIsError = false
                                        reload()
                                    } catch (e: Exception) { message = e.message ?: "فشل التفعيل."; messageIsError = true }
                                    actBusy = false
                                }
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AdminColors.Primary, contentColor = Color.White),
                        modifier = Modifier.fillMaxWidth().height(50.dp)
                    ) { Text(if (actBusy) "جارٍ التفعيل..." else "تفعيل ${actList.size} حساب", fontWeight = FontWeight.Bold) }
                    OutlinedButton(
                        enabled = !actBusy && pendingCount > 0 && actValue > 0,
                        onClick = {
                            val expiry = service.expiryFrom(actValue, actUnit) ?: return@OutlinedButton
                            confirm = ConfirmReq("تفعيل كل المعلّقة", "تفعيل $pendingCount حساب معلّق لمدة $actValue ${unitName(actUnit)}؟", "تفعيل الكل") {
                                perform("تم تفعيل كل الحسابات المعلّقة.") { service.activateAllPending(expiry) }
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().height(50.dp)
                    ) { Text("تفعيل كل الحسابات المعلّقة ($pendingCount)", color = AdminColors.SkyText) }
                }

                // ---- Bulk create ----
                AdminCard {
                    Text("إنشاء عدة حسابات", style = MaterialTheme.typography.titleMedium, color = AdminColors.Text, fontWeight = FontWeight.Bold)
                    Text("الإيميلات المولّدة بالشكل yasoftware1@yas.com ثم 2 ثم 3... حسب العدد (بحروف صغيرة). أو اكتب الإيميلات بنفسك.", style = MaterialTheme.typography.bodySmall, color = AdminColors.TextSoft)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(genCount, { genCount = it.filter { c -> c.isDigit() }.take(3) }, label = { Text("العدد") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), colors = fieldColors(), shape = RoundedCornerShape(12.dp), modifier = Modifier.width(80.dp))
                        OutlinedTextField(genPrefix, { genPrefix = it }, label = { Text("الاسم") }, singleLine = true, colors = fieldColors(), shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1f))
                        OutlinedTextField(genDomain, { genDomain = it }, label = { Text("الدومين") }, singleLine = true, colors = fieldColors(), shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1f))
                    }
                    OutlinedButton(
                        onClick = {
                            val n = (genCount.toIntOrNull() ?: 1).coerceIn(1, 200)
                            newEmails = service.generateEmails(genPrefix, genDomain, n, accounts.map { it.email.lowercase() }.toSet()).joinToString("\n")
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("توليد الإيميلات", color = AdminColors.SkyText) }
                    OutlinedTextField(
                        value = newEmails,
                        onValueChange = { newEmails = it },
                        placeholder = { Text("yasoftware1@yas.com\nyasoftware2@yas.com") },
                        colors = fieldColors(),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().height(130.dp)
                    )
                    Text("كلمة المرور", style = MaterialTheme.typography.labelSmall, color = AdminColors.Muted)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        val modes = listOf(
                            AdminService.PasswordMode.RANDOM to "توليد عشوائي",
                            AdminService.PasswordMode.EMAIL to "نفس البريد",
                            AdminService.PasswordMode.CUSTOM to "موحّدة"
                        )
                        modes.forEach { (mode, labelTxt) ->
                            val selected = pwMode == mode
                            TextButton(onClick = { pwMode = mode }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                                Text(labelTxt, color = if (selected) AdminColors.SkyText else AdminColors.Muted, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, fontSize = 13.sp)
                            }
                        }
                    }
                    when (pwMode) {
                        AdminService.PasswordMode.RANDOM -> OutlinedTextField(
                            value = pwLen,
                            onValueChange = { pwLen = it.filter { c -> c.isDigit() }.take(2) },
                            label = { Text("طول الباسورد (8 - 64)") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            colors = fieldColors(),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.width(170.dp)
                        )
                        AdminService.PasswordMode.EMAIL -> Text(
                            "سيُستخدم البريد الإلكتروني لكل حساب ككلمة مرور له.",
                            style = MaterialTheme.typography.labelSmall, color = AdminColors.TextSoft
                        )
                        AdminService.PasswordMode.CUSTOM -> OutlinedTextField(
                            value = pwCustom,
                            onValueChange = { pwCustom = it },
                            label = { Text("كلمة المرور الموحّدة (8 أحرف على الأقل)") },
                            singleLine = true,
                            colors = fieldColors(),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    DurationPicker("مدة التفعيل (0 = بانتظار التفعيل)", newDur, { newDur = it }, newUnit, { newUnit = it })
                    val emailList = parseEmails(newEmails)
                    val pwReady = when (pwMode) {
                        AdminService.PasswordMode.RANDOM -> true
                        AdminService.PasswordMode.EMAIL -> emailList.all { it.length >= 8 }
                        AdminService.PasswordMode.CUSTOM -> pwCustom.trim().length >= 8
                    }
                    Button(
                        enabled = !createBusy && emailList.isNotEmpty() && emailList.size <= 200 && pwReady,
                        onClick = {
                            val value = newDur.toIntOrNull() ?: 0
                            val expiry = service.expiryFrom(value, newUnit)
                            val what = if (expiry != null) "وتفعيلها لمدة $value ${unitName(newUnit)}" else "(بانتظار التفعيل)"
                            confirm = ConfirmReq("إنشاء ${emailList.size} حساب", "إنشاء ${emailList.size} حساب $what؟", "إنشاء") {
                                createBusy = true; createResults = emptyList()
                                scope.launch {
                                    try {
                                        createResults = service.createAccountsBulk(
                                            emails = emailList,
                                            passwordLength = (pwLen.toIntOrNull() ?: 12).coerceIn(8, 64),
                                            expiresAt = expiry,
                                            passwordMode = pwMode,
                                            customPassword = pwCustom.trim()
                                        )
                                        message = "تم إنشاء ${createResults.count { it.error == null }} من ${createResults.size}."
                                        messageIsError = false
                                        reload()
                                    } catch (e: Exception) { message = e.message ?: "فشل الإنشاء."; messageIsError = true }
                                    createBusy = false
                                }
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AdminColors.Primary, contentColor = Color.White),
                        modifier = Modifier.fillMaxWidth().height(52.dp)
                    ) { Text(if (createBusy) "جارٍ الإنشاء..." else "إنشاء ${emailList.size} حساب", fontWeight = FontWeight.Bold) }

                    if (createResults.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("بيانات الدخول الجديدة — تظهر مرة واحدة فقط، انسخها الآن:", style = MaterialTheme.typography.labelMedium, color = AdminColors.WarningText)
                            OutlinedButton(
                                onClick = {
                                    clipboard.setText(AnnotatedString(createResults.filter { it.error == null }.joinToString("\n") { "${it.email}\t${it.password}" }))
                                    message = "تم نسخ كل البيانات."; messageIsError = false
                                },
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("نسخ الكل", color = AdminColors.SkyText) }
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

                // ---- Manual activation / edit by email ----
                AdminCard {
                    Text("تفعيل / تعديل حساب (يدوي)", style = MaterialTheme.typography.titleMedium, color = AdminColors.Text, fontWeight = FontWeight.Bold)
                    Text("اكتب البريد الإلكتروني للحساب، حدّد الحالة والمدة، ثم احفظ. يعمل بالبريد الإلكتروني فقط.", style = MaterialTheme.typography.bodySmall, color = AdminColors.TextSoft)
                    OutlinedTextField(
                        value = manualEmail,
                        onValueChange = { manualEmail = it },
                        label = { Text("البريد الإلكتروني") },
                        placeholder = { Text("account@example.com") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        colors = fieldColors(),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        val statuses = listOf("active" to "مفعّل", "pending" to "بانتظار التفعيل", "suspended" to "موقوف")
                        statuses.forEach { (value, labelTxt) ->
                            val selected = manualStatus == value
                            TextButton(onClick = { manualStatus = value }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                                Text(labelTxt, color = if (selected) AdminColors.SkyText else AdminColors.Muted, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, fontSize = 13.sp)
                            }
                        }
                    }
                    if (manualStatus == "active") {
                        DurationPicker("مدة التفعيل", manualDur, { manualDur = it }, manualUnit, { manualUnit = it })
                    }
                    val manualValue = manualDur.toIntOrNull() ?: 0
                    val manualReady = manualEmail.trim().contains("@") && (manualStatus != "active" || manualValue > 0)
                    Button(
                        enabled = !manualBusy && manualReady,
                        onClick = {
                            val email = manualEmail.trim()
                            val userId = service.userIdForEmail(accounts, email)
                            if (userId == null) {
                                message = "لا يوجد حساب بالبريد: $email"
                                messageIsError = true
                                return@Button
                            }
                            val expiry = if (manualStatus == "active") service.expiryFrom(manualValue, manualUnit) else null
                            val what = if (expiry != null) "لمدة $manualValue ${unitName(manualUnit)}" else "(${statusLabelAr(manualStatus)})"
                            confirm = ConfirmReq("تحديث الترخيص", "تحديث حساب $email $what؟", "حفظ") {
                                manualBusy = true
                                scope.launch {
                                    try {
                                        service.setLicenseDirect(userId, manualStatus, expiry)
                                        message = "تم تحديث الترخيص على الخادم."
                                        messageIsError = false
                                        reload()
                                    } catch (e: Exception) { message = e.message ?: "فشل تحديث الترخيص."; messageIsError = true }
                                    manualBusy = false
                                }
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AdminColors.Primary, contentColor = Color.White),
                        modifier = Modifier.fillMaxWidth().height(48.dp)
                    ) { Text(if (manualBusy) "جارٍ الحفظ..." else "حفظ الترخيص", fontWeight = FontWeight.Bold) }
                }

                // ---- Ban list ----
                AdminCard {
                    Text("الأجهزة المحظورة (${bans.size})", style = MaterialTheme.typography.titleMedium, color = AdminColors.Text, fontWeight = FontWeight.Bold)
                    Text("الجهاز المحظور لا يستطيع تشغيل البرنامج: يُفحص قبل شاشة الدخول ويرفضه السيرفر أيضًا. يطابق معرّف التثبيت أو Android ID.", style = MaterialTheme.typography.bodySmall, color = AdminColors.TextSoft)
                    OutlinedTextField(banAid, { banAid = it }, label = { Text("Android ID") }, singleLine = true, colors = fieldColors(), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(banReason, { banReason = it }, label = { Text("سبب الحظر (اختياري)") }, singleLine = true, colors = fieldColors(), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth())
                    Button(
                        enabled = banAid.isNotBlank(),
                        onClick = {
                            val aid = banAid.trim(); val reason = banReason.trim()
                            confirm = ConfirmReq("حظر يدوي", "حظر هذا الجهاز نهائيًا؟", "حظر") {
                                perform("تم الحظر.") { service.banManual(aid, reason) }
                                banAid = ""; banReason = ""
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AdminColors.Danger, contentColor = Color.White),
                        modifier = Modifier.fillMaxWidth().height(48.dp)
                    ) { Text("حظر يدوي", fontWeight = FontWeight.Bold) }
                    bans.forEach { b ->
                        Surface(shape = RoundedCornerShape(12.dp), color = AdminColors.SurfaceHi, border = BorderStroke(1.dp, AdminColors.Border), modifier = Modifier.fillMaxWidth()) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(b.label.ifBlank { "جهاز" }, style = MaterialTheme.typography.bodyMedium, color = AdminColors.Text, fontWeight = FontWeight.Bold)
                                    Text("Android ID: ${b.androidId ?: "—"}", style = MaterialTheme.typography.labelSmall, color = AdminColors.Muted)
                                    b.lastIp?.let { Text("IP: $it", style = MaterialTheme.typography.labelSmall, color = AdminColors.Muted) }
                                    b.reason?.let { Text("السبب: $it", style = MaterialTheme.typography.labelSmall, color = AdminColors.TextSoft) }
                                    b.bannedAt?.let { Text(formatShortDate(it), style = MaterialTheme.typography.labelSmall, color = AdminColors.Muted) }
                                }
                                TextButton(onClick = {
                                    confirm = ConfirmReq("رفع الحظر", "رفع الحظر عن هذا الجهاز؟ (يبقى «ملغي» حتى تستعيده).", "رفع الحظر") {
                                        perform("تم رفع الحظر.") { service.unban(b.id) }
                                    }
                                }) { Text("رفع الحظر", color = AdminColors.SkyText) }
                            }
                        }
                    }
                }

                // ---- Account list ----
                AdminCard {
                    Text("الحسابات (${accounts.size})", style = MaterialTheme.typography.titleMedium, color = AdminColors.Text, fontWeight = FontWeight.Bold)
                    OutlinedTextField(query, { query = it }, label = { Text("بحث: بريد / اسم جهاز / IP / Android ID") }, singleLine = true, colors = fieldColors(), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth())
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = showDeleted, onCheckedChange = { showDeleted = it })
                        Text("إظهار الأجهزة المحذوفة", style = MaterialTheme.typography.bodySmall, color = AdminColors.TextSoft)
                    }
                    val q = query.trim().lowercase()
                    val shown = accounts.filter { a ->
                        q.isEmpty() || a.email.lowercase().contains(q) || a.userId.lowercase().contains(q) ||
                            a.devices.any { d -> listOfNotNull(d.label, d.lastIp, d.firstIp, d.androidId).any { it.lowercase().contains(q) } }
                    }
                    when {
                        loading -> LinearProgressIndicator(Modifier.fillMaxWidth(), color = AdminColors.SkyText, trackColor = AdminColors.SurfaceHi)
                        loadError.isNotBlank() -> Text(loadError, color = AdminColors.DangerText, style = MaterialTheme.typography.bodySmall)
                        shown.isEmpty() -> Text("لا توجد حسابات.", color = AdminColors.Muted, style = MaterialTheme.typography.bodySmall)
                        else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            shown.forEach { account ->
                                AccountRow(
                                    account = account,
                                    showDeleted = showDeleted,
                                    onToggle = { newStatus ->
                                        perform("تم تحديث الحساب.") { service.setLicense(account.userId, newStatus, if (actUnit == 1440L) (actDur.toIntOrNull() ?: 30) else 30) }
                                    },
                                    onRevoke = { d -> confirm = ConfirmReq("إلغاء الجهاز", "سيُمنع هذا الجهاز ويبقى ظاهرًا بحالة «ملغي»، ويستطيع الحساب الدخول من جهاز جديد فورًا.", "إلغاء") { perform("تم إلغاء الجهاز.") { service.revokeDevice(d.id) } } },
                                    onDelete = { d -> confirm = ConfirmReq("حذف الجهاز", "سيُزال من الحساب ويبقى في السجل «محذوف»، ولو دخل منه المستخدم لاحقًا يُسجَّل كجهاز جديد.", "حذف") { perform("تم حذف الجهاز.") { service.deleteDevice(d.id) } } },
                                    onRestore = { d -> confirm = ConfirmReq("استعادة الجهاز", "سيعود الجهاز نشطًا، وأي جهاز نشط آخر لنفس الحساب سيُلغى.", "استعادة") { perform("تمت الاستعادة.") { service.restoreDevice(d.id) } } },
                                    onBan = { d -> confirm = ConfirmReq("حظر نهائي", "لن يستطيع هذا الجهاز تشغيل البرنامج حتى بحساب آخر أو بعد إعادة التثبيت (طالما Android ID كما هو).", "حظر") { perform("تم حظر الجهاز.") { service.banDevice(d.id, "") } } },
                                    onDeleteAccount = {
                                        confirm = ConfirmReq("حذف الحساب نهائيًا", "سيُحذف الحساب وكل أجهزته من المنصة ولا يمكن التراجع. للتأكيد اكتب البريد بالضبط:", "حذف نهائي", requireText = account.email) {
                                            perform("تم حذف الحساب نهائيًا.") { service.deleteUser(account.userId) }
                                        }
                                    },
                                    onChangePassword = { pwTarget = account.email },
                                    onQuickExtend = { mins ->
                                        perform("تم التمديد.") { service.extendLicense(account.userId, account.expiresAt, mins) }
                                    },
                                    onCopy = { clipboard.setText(AnnotatedString(it)) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConfirmDialog(req: ConfirmReq, onDismiss: () -> Unit, onConfirmed: () -> Unit) {
    var typed by remember { mutableStateOf("") }
    val ok = req.requireText == null || typed.trim().equals(req.requireText, ignoreCase = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AdminColors.Surface,
        titleContentColor = AdminColors.Text,
        textContentColor = AdminColors.TextSoft,
        title = { Text(req.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(req.text)
                if (req.requireText != null) {
                    Text(req.requireText, color = AdminColors.WarningText, fontWeight = FontWeight.Bold)
                    OutlinedTextField(typed, { typed = it }, singleLine = true, colors = fieldColors(), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = { TextButton(enabled = ok, onClick = onConfirmed) { Text(req.confirmLabel, color = if (ok) AdminColors.DangerText else AdminColors.Muted, fontWeight = FontWeight.Bold) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("تراجع", color = AdminColors.SkyText) } }
    )
}

/** Matches the web dashboard's "تغيير الباسورد" prompt: type a password (8+ chars) or leave
 *  blank to auto-generate one. Confirming invalidates the account's current password immediately. */
@Composable
private fun PasswordDialog(email: String, randomPassword: () -> String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var pw by remember { mutableStateOf("") }
    val trimmed = pw.trim()
    val valid = trimmed.isEmpty() || trimmed.length >= 8
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AdminColors.Surface,
        titleContentColor = AdminColors.Text,
        textContentColor = AdminColors.TextSoft,
        title = { Text("تغيير كلمة مرور $email") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("هذا يُبطل كلمة المرور الحالية فورًا. اتركه فارغًا لتوليد كلمة مرور عشوائية تلقائيًا، أو اكتب كلمة مرور بنفسك (8 أحرف على الأقل):")
                OutlinedTextField(
                    value = pw, onValueChange = { pw = it }, singleLine = true,
                    colors = fieldColors(), shape = RoundedCornerShape(12.dp),
                    isError = !valid, modifier = Modifier.fillMaxWidth()
                )
                if (!valid) Text("كلمة المرور يجب ألا تقل عن 8 أحرف.", color = AdminColors.DangerText, style = MaterialTheme.typography.labelSmall)
                TextButton(onClick = { pw = randomPassword() }) { Text("توليد تلقائي", color = AdminColors.SkyText) }
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { onConfirm(trimmed.ifEmpty { randomPassword() }) }) {
                Text("تغيير", color = if (valid) AdminColors.DangerText else AdminColors.Muted, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("تراجع", color = AdminColors.SkyText) } }
    )
}

@Composable
private fun DurationPicker(label: String, value: String, onValue: (String) -> Unit, unit: Long, onUnit: (Long) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = AdminColors.Muted)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            OutlinedTextField(
                value = value,
                onValueChange = { v -> onValue(v.filter { it.isDigit() }.take(5)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                colors = fieldColors(),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.width(84.dp)
            )
            DURATION_UNITS.forEach { (name, mins) ->
                val selected = unit == mins
                TextButton(onClick = { onUnit(mins) }, contentPadding = PaddingValues(horizontal = 6.dp)) {
                    Text(name, color = if (selected) AdminColors.SkyText else AdminColors.Muted, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, fontSize = 13.sp)
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
private fun AccountRow(
    account: AdminService.Account,
    showDeleted: Boolean,
    onToggle: (String) -> Unit,
    onRevoke: (AdminService.DeviceRow) -> Unit,
    onDelete: (AdminService.DeviceRow) -> Unit,
    onRestore: (AdminService.DeviceRow) -> Unit,
    onBan: (AdminService.DeviceRow) -> Unit,
    onDeleteAccount: () -> Unit,
    onChangePassword: () -> Unit,
    onQuickExtend: (Long) -> Unit,
    onCopy: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val (label, color) = when (account.status) {
        "active" -> "مفعّل" to AdminColors.Success
        "pending" -> "معلّق" to AdminColors.WarningText
        "suspended" -> "موقوف" to AdminColors.DangerText
        "expired" -> "منتهي" to AdminColors.DangerText
        else -> account.status to AdminColors.Muted
    }
    val nRevoked = account.devices.count { it.state == "revoked" }
    val nDeleted = account.devices.count { it.state == "deleted" }
    Surface(shape = RoundedCornerShape(12.dp), color = AdminColors.SurfaceHi, border = BorderStroke(1.dp, AdminColors.Border), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(account.email + if (account.role == "admin") "  (admin)" else "", style = MaterialTheme.typography.bodyMedium, color = AdminColors.Text, fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(label, style = MaterialTheme.typography.labelSmall, color = color, fontWeight = FontWeight.Bold)
                        account.expiresAt?.let { Text("حتى ${formatShortDate(it)}", style = MaterialTheme.typography.labelSmall, color = AdminColors.Muted) }
                    }
                    Text("أجهزة: نشطة ${account.activeDeviceCount} • ملغاة $nRevoked • محذوفة $nDeleted  ${if (expanded) "▲" else "▼"}", style = MaterialTheme.typography.labelSmall, color = AdminColors.Muted)
                }
                when (account.status) {
                    "pending", "expired" -> TextButton(onClick = { onToggle("active") }) { Text("تفعيل", color = AdminColors.SkyText) }
                    "active" -> TextButton(onClick = { onToggle("suspended") }) { Text("إيقاف", color = AdminColors.DangerText) }
                    "suspended" -> TextButton(onClick = { onToggle("active") }) { Text("تفعيل", color = AdminColors.SkyText) }
                }
                TextButton(onClick = onChangePassword) { Text("تغيير الباسورد", color = AdminColors.SkyText) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = { onQuickExtend(1440L) }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("+يوم", color = AdminColors.SkyText, fontSize = 12.sp) }
                TextButton(onClick = { onQuickExtend(10080L) }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("+أسبوع", color = AdminColors.SkyText, fontSize = 12.sp) }
                TextButton(onClick = { onQuickExtend(43200L) }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("+شهر", color = AdminColors.SkyText, fontSize = 12.sp) }
                TextButton(onClick = { onQuickExtend(525600L) }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("+سنة", color = AdminColors.SkyText, fontSize = 12.sp) }
            }
            if (expanded) {
                val devices = account.devices.filter { showDeleted || it.state != "deleted" }
                if (devices.isEmpty()) Text("لا يوجد أجهزة.", style = MaterialTheme.typography.labelSmall, color = AdminColors.Muted)
                devices.forEach { d ->
                    DeviceCard(d, onRevoke = { onRevoke(d) }, onDelete = { onDelete(d) }, onRestore = { onRestore(d) }, onBan = { onBan(d) }, onCopy = onCopy)
                }
                if (account.role != "admin") {
                    OutlinedButton(onClick = onDeleteAccount, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                        Text("حذف الحساب نهائيًا", color = AdminColors.DangerText, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun DeviceCard(
    d: AdminService.DeviceRow,
    onRevoke: () -> Unit,
    onDelete: () -> Unit,
    onRestore: () -> Unit,
    onBan: () -> Unit,
    onCopy: (String) -> Unit
) {
    val (stateLabel, stateColor) = when (d.state) {
        "revoked" -> "ملغي" to AdminColors.WarningText
        "deleted" -> "محذوف" to AdminColors.DangerText
        else -> "نشط" to AdminColors.Success
    }
    val name = if (d.label.isNotBlank() && d.label != "Android installation") d.label else "${d.label.ifBlank { "جهاز" }} (لم يرسل اسمه بعد)"
    Surface(shape = RoundedCornerShape(10.dp), color = AdminColors.Surface, border = BorderStroke(1.dp, stateColor.copy(alpha = 0.5f)), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(name, style = MaterialTheme.typography.bodySmall, color = AdminColors.Text, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(stateLabel, style = MaterialTheme.typography.labelSmall, color = stateColor, fontWeight = FontWeight.Bold)
            }
            Text("IP: ${d.lastIp ?: "غير مسجّل"}" + if (d.firstIp != null && d.firstIp != d.lastIp) "  (أول IP: ${d.firstIp})" else "", style = MaterialTheme.typography.labelSmall, color = AdminColors.TextSoft,
                modifier = Modifier.clickable { d.lastIp?.let(onCopy) })
            Text("Android ID: ${d.androidId ?: "—"}", style = MaterialTheme.typography.labelSmall, color = AdminColors.Muted)
            Text("معرّف التثبيت: ${d.deviceId}", style = MaterialTheme.typography.labelSmall, color = AdminColors.Muted)
            d.lastSeenAt?.let { Text("آخر ظهور: ${formatShortDate(it)}", style = MaterialTheme.typography.labelSmall, color = AdminColors.Muted) }
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                when (d.state) {
                    "active" -> {
                        TextButton(onClick = onRevoke, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("إلغاء", color = AdminColors.WarningText, fontSize = 12.sp) }
                        TextButton(onClick = onDelete, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("حذف", color = AdminColors.DangerText, fontSize = 12.sp) }
                    }
                    "revoked" -> {
                        TextButton(onClick = onRestore, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("استعادة", color = AdminColors.Success, fontSize = 12.sp) }
                        TextButton(onClick = onDelete, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("حذف", color = AdminColors.DangerText, fontSize = 12.sp) }
                    }
                    else -> TextButton(onClick = onRestore, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("استعادة", color = AdminColors.Success, fontSize = 12.sp) }
                }
                TextButton(onClick = onBan, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("حظر نهائي", color = AdminColors.DangerText, fontWeight = FontWeight.Bold, fontSize = 12.sp) }
            }
        }
    }
}

private fun statusLabelAr(status: String): String = when (status) {
    "active" -> "مفعّل"
    "pending" -> "بانتظار التفعيل"
    "suspended" -> "موقوف"
    else -> status
}

private fun formatShortDate(raw: String): String = try {
    val parsed = java.time.OffsetDateTime.parse(raw).toLocalDate()
    parsed.format(java.time.format.DateTimeFormatter.ofPattern("yyyy/MM/dd", Locale.getDefault()))
} catch (_: Exception) { raw }
