@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.edl.flasher.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.coerceIn
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.edl.flasher.R
import com.edl.flasher.protocol.PartitionEntry
import com.edl.flasher.protocol.StorageType
import com.edl.flasher.protocol.T509Variant
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// =====================================================================================
//  YAS SOFTWARE - design tokens (Obsidian & Deep Slate, desktop / GSM-tool style)
// =====================================================================================
private object Yas {
    val Bg = Color(0xFF0A0D14)
    val Surface = Color(0xFF121722)
    val SurfaceHi = Color(0xFF182030)
    val Border = Color(0xFF232D3F)
    val BorderStrong = Color(0xFF334155)
    val Terminal = Color(0xFF080B10)

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
    val Idle = Color(0xFF94A3B8)
}

private val S6 = RoundedCornerShape(6.dp)
private val S8 = RoundedCornerShape(8.dp)

// =====================================================================================
//  Reusable building blocks
// =====================================================================================

@Composable
private fun YText(
    text: String,
    modifier: Modifier = Modifier,
    size: TextUnit = 12.sp,
    color: Color = Yas.Text,
    weight: FontWeight = FontWeight.Normal,
    mono: Boolean = false,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    align: TextAlign? = null,
    spacing: TextUnit = TextUnit.Unspecified
) {
    Text(
        text = text,
        modifier = modifier,
        color = color,
        fontSize = size,
        fontWeight = weight,
        fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
        maxLines = maxLines,
        overflow = overflow,
        textAlign = align,
        letterSpacing = spacing
    )
}

@Composable
private fun YDivider(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(Yas.Border))
}

/** Standard surface card with an optional header (tag + title + subtitle + trailing slot). */
@Composable
private fun YASCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    subtitle: String? = null,
    tag: String? = null,
    elevated: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(if (elevated) Yas.SurfaceHi else Yas.Surface, S8)
            .border(1.dp, Yas.Border, S8)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (title != null || trailing != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (tag != null) TagChip(tag)
                Column(modifier = Modifier.weight(1f)) {
                    if (title != null) YText(title, size = 13.sp, weight = FontWeight.SemiBold)
                    if (subtitle != null) YText(subtitle, size = 11.sp, color = Yas.Muted)
                }
                trailing?.invoke()
            }
        }
        content()
    }
}

@Composable
private fun TagChip(text: String, color: Color = Yas.SkyText) {
    YText(
        text = text,
        size = 9.sp,
        mono = true,
        weight = FontWeight.Bold,
        color = color,
        maxLines = 1,
        spacing = 0.8.sp,
        modifier = Modifier
            .background(color.copy(alpha = 0.12f), S6)
            .border(1.dp, color.copy(alpha = 0.30f), S6)
            .padding(horizontal = 6.dp, vertical = 3.dp)
    )
}

@Composable
private fun PulseDot(color: Color, pulsing: Boolean) {
    val alpha = if (pulsing) {
        val transition = rememberInfiniteTransition(label = "pulse")
        transition.animateFloat(
            initialValue = 0.30f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse),
            label = "pulseAlpha"
        ).value
    } else {
        1f
    }
    Box(
        Modifier
            .size(8.dp)
            .background(color.copy(alpha = alpha), CircleShape)
    )
}

@Composable
private fun StatusBadge(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    pulsing: Boolean = false
) {
    Row(
        modifier = modifier
            .background(color.copy(alpha = 0.12f), S6)
            .border(1.dp, color.copy(alpha = 0.35f), S6)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        PulseDot(color, pulsing)
        YText(text, size = 11.sp, color = color, weight = FontWeight.SemiBold, maxLines = 1)
    }
}

private enum class BtnStyle { Primary, Secondary, Danger, DangerSoft, Warning, Success, Ghost }

@Composable
private fun YASButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: BtnStyle = BtnStyle.Secondary,
    enabled: Boolean = true,
    compact: Boolean = false,
    large: Boolean = false
) {
    val (bg, fg, bd) = when (style) {
        BtnStyle.Primary -> Triple(Yas.Primary, Color.White, Yas.Primary)
        BtnStyle.Secondary -> Triple(Color.Transparent, Yas.Text, Yas.BorderStrong)
        BtnStyle.Danger -> Triple(Yas.Danger, Color.White, Yas.Danger)
        BtnStyle.DangerSoft -> Triple(Yas.Danger.copy(alpha = 0.12f), Yas.DangerText, Yas.Danger.copy(alpha = 0.45f))
        BtnStyle.Warning -> Triple(Yas.Warning, Color(0xFF1A1100), Yas.Warning)
        BtnStyle.Success -> Triple(Yas.Success, Color(0xFF03241A), Yas.Success)
        BtnStyle.Ghost -> Triple(Color.Transparent, Yas.SkyText, Color.Transparent)
    }
    val a = if (enabled) 1f else 0.38f
    Row(
        modifier = modifier
            .height(if (large) 44.dp else if (compact) 28.dp else 36.dp)
            .clip(S6)
            .background(bg.copy(alpha = bg.alpha * a))
            .border(1.dp, bd.copy(alpha = bd.alpha * a), S6)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = if (compact) 10.dp else 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        YText(
            text = text,
            size = if (large) 13.sp else if (compact) 11.sp else 12.sp,
            color = fg.copy(alpha = a),
            weight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun YASCheck(checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    val shape = RoundedCornerShape(4.dp)
    Box(
        modifier = Modifier
            .size(18.dp)
            .alpha(if (enabled) 1f else 0.4f)
            .clip(shape)
            .background(if (checked) Yas.Primary else Color.Transparent)
            .border(1.dp, if (checked) Yas.Primary else Yas.BorderStrong, shape)
            .clickable(enabled = enabled, role = Role.Checkbox) { onChange(!checked) },
        contentAlignment = Alignment.Center
    ) {
        if (checked) YText("\u2713", size = 12.sp, color = Color.White, weight = FontWeight.Bold)
    }
}

@Composable
private fun FilterPill(text: String, selected: Boolean, onClick: () -> Unit) {
    YText(
        text = text,
        size = 11.sp,
        mono = true,
        weight = FontWeight.SemiBold,
        color = if (selected) Color.White else Yas.Idle,
        maxLines = 1,
        modifier = Modifier
            .clip(S6)
            .background(if (selected) Yas.Primary else Yas.SurfaceHi)
            .border(1.dp, if (selected) Yas.Primary else Yas.Border, S6)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp)
    )
}

@Composable
private fun <T> Segmented(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    enabled: Boolean = true,
    onSelect: (T) -> Unit
) {
    val inner = RoundedCornerShape(4.dp)
    Row(
        modifier = Modifier
            .height(32.dp)
            .alpha(if (enabled) 1f else 0.5f)
            .background(Yas.Bg, S6)
            .border(1.dp, Yas.Border, S6)
            .padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        options.forEach { opt ->
            val sel = opt == selected
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .clip(inner)
                    .background(if (sel) Yas.Primary else Color.Transparent)
                    .clickable(enabled = enabled) { onSelect(opt) }
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                YText(
                    label(opt),
                    size = 11.sp,
                    weight = FontWeight.SemiBold,
                    color = if (sel) Color.White else Yas.Muted,
                    maxLines = 1
                )
            }
        }
    }
}

/** Compact single-line input (search box / shell prompt) that matches the industrial look. */
@Composable
private fun YASTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    mono: Boolean = false,
    leading: String? = null,
    enabled: Boolean = true,
    imeAction: ImeAction = ImeAction.Done,
    onSubmit: (() -> Unit)? = null
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        singleLine = true,
        textStyle = TextStyle(
            color = Yas.Text,
            fontSize = 12.sp,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default
        ),
        cursorBrush = SolidColor(Yas.SkyText),
        keyboardOptions = KeyboardOptions(imeAction = imeAction),
        keyboardActions = KeyboardActions(
            onDone = { onSubmit?.invoke() },
            onSend = { onSubmit?.invoke() },
            onGo = { onSubmit?.invoke() }
        ),
        modifier = modifier.height(34.dp),
        decorationBox = { inner ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(S6)
                    .background(Yas.Bg)
                    .border(1.dp, Yas.Border, S6)
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (leading != null) YText(leading, size = 12.sp, mono = true, color = Yas.SkyText, weight = FontWeight.Bold)
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) {
                        YText(placeholder, size = 12.sp, color = Yas.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis, mono = mono)
                    }
                    inner()
                }
            }
        }
    )
}

private fun Modifier.dashedBorder(color: Color, radius: Dp = 8.dp, strokeWidth: Dp = 1.dp): Modifier =
    this.drawBehind {
        drawRoundRect(
            color = color,
            style = Stroke(
                width = strokeWidth.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 9f), 0f)
            ),
            cornerRadius = CornerRadius(radius.toPx())
        )
    }

@Composable
private fun FolderGlyph(color: Color, modifier: Modifier = Modifier.size(22.dp)) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        drawRoundRect(
            color = color.copy(alpha = 0.75f),
            topLeft = Offset(0f, h * 0.10f),
            size = Size(w * 0.44f, h * 0.26f),
            cornerRadius = CornerRadius(2.dp.toPx())
        )
        drawRoundRect(
            color = color,
            topLeft = Offset(0f, h * 0.24f),
            size = Size(w, h * 0.66f),
            cornerRadius = CornerRadius(3.dp.toPx())
        )
    }
}

/**
 * Tap-to-browse "drop target" card for a file / folder. Shows a dashed outline while empty and a
 * solid card with the selected name (and an optional clear button) once something is chosen.
 */
@Composable
private fun FileSelectorField(
    label: String,
    value: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    badge: String? = null,
    badgeColor: Color = Yas.Idle,
    onClear: (() -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.5f)
            .clip(S8)
            .background(if (selected) Yas.SurfaceHi else Yas.Bg)
            .then(
                if (selected) Modifier.border(1.dp, Yas.BorderStrong, S8)
                else Modifier.dashedBorder(Yas.BorderStrong)
            )
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        FolderGlyph(if (selected) Yas.SkyText else Yas.Muted)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                YText(label, size = 10.sp, color = Yas.Muted, weight = FontWeight.SemiBold, spacing = 0.6.sp, maxLines = 1)
                if (badge != null) TagChip(badge, badgeColor)
            }
            YText(
                text = value,
                size = 12.sp,
                mono = selected,
                color = if (selected) Yas.Text else Yas.TextSoft,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (selected && onClear != null) {
            YASButton("\u2715", onClear, style = BtnStyle.Ghost, compact = true, enabled = enabled)
        } else {
            YText("BROWSE", size = 10.sp, color = Yas.SkyText, weight = FontWeight.Bold, spacing = 0.8.sp)
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    YText(text, size = 10.sp, color = Yas.Muted, weight = FontWeight.Bold, spacing = 1.sp)
}

@Composable
private fun YASConfirmDialog(
    title: String,
    message: String,
    confirmText: String,
    danger: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Yas.Surface,
        shape = S8,
        title = { YText(title, size = 15.sp, weight = FontWeight.Bold) },
        text = { YText(message, size = 12.sp, color = Yas.TextSoft) },
        confirmButton = {
            YASButton(confirmText, onConfirm, style = if (danger) BtnStyle.Danger else BtnStyle.Primary)
        },
        dismissButton = {
            YASButton("Cancel", onDismiss, style = BtnStyle.Secondary)
        }
    )
}

// =====================================================================================
//  Helpers
// =====================================================================================

private fun copyToClipboard(context: Context, label: String, text: String) {
    if (text.isBlank()) return
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(context, "Copied: $label", Toast.LENGTH_SHORT).show()
}

private fun formatSize(bytes: Long): String {
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    val gb = mb / 1024.0
    return when {
        gb >= 1.0 -> String.format(Locale.US, "%.2f GB", gb)
        mb >= 1.0 -> String.format(Locale.US, "%.2f MB", mb)
        else -> String.format(Locale.US, "%.0f KB", kb)
    }
}

/** Size of a SAF document, resolved off the main thread. Null while loading / unknown. */
@Composable
private fun rememberUriSize(uri: Uri?): String? {
    val context = LocalContext.current
    val size by produceState<Long?>(initialValue = null, key1 = uri) {
        value = if (uri == null) {
            null
        } else {
            withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                        if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
                    }
                }.getOrNull()
            }
        }
    }
    return size?.let { formatSize(it) }
}

private data class ConnMode(val label: String, val color: Color, val live: Boolean)

private fun connMode(s: EdlUiState): ConnMode = when {
    s.t509BromConnected -> ConnMode("Unisoc BROM", Yas.Success, true)
    s.usbConnected -> ConnMode("9008 EDL", Yas.Success, true)
    s.odinConnected -> ConnMode("Samsung Odin", Color(0xFF60A5FA), true)
    s.adbConnected || s.usbAny -> ConnMode("MTP + ADB", Yas.SkyText, true)
    else -> ConnMode("Disconnected", Yas.Idle, false)
}

private sealed interface InfoItem {
    data class Section(val title: String) : InfoItem
    data class Field(val key: String, val value: String) : InfoItem
    data class Note(val text: String) : InfoItem
}

/** Turns the free-form "Key: value" / "[Group]" / "--- Group ---" info text into typed rows. */
private fun parseInfoBlocks(text: String): List<InfoItem> = text.lines().mapNotNull { raw ->
    val l = raw.trim()
    when {
        l.isEmpty() -> null
        l.startsWith("[") && l.endsWith("]") -> InfoItem.Section(l.substring(1, l.length - 1))
        l.startsWith("---") -> InfoItem.Section(l.trim('-', ' '))
        else -> {
            val i = l.indexOf(':')
            if (i in 1..32) InfoItem.Field(l.substring(0, i).trim(), l.substring(i + 1).trim())
            else InfoItem.Note(l)
        }
    }
}

private fun parseInfoFields(text: String): List<Pair<String, String>> =
    parseInfoBlocks(text)
        .filterIsInstance<InfoItem.Field>()
        .filter { it.value.isNotBlank() }
        .map { it.key to it.value }

private fun logColor(line: String): Color = when {
    line.contains("ERROR", ignoreCase = true) || line.contains("FAILED", ignoreCase = true) -> Yas.DangerText
    line.contains("WARN", ignoreCase = true) -> Yas.WarningText
    line.contains("Successfully", ignoreCase = true) ||
        line.contains("FINISHED") ||
        line.contains("completed successfully", ignoreCase = true) -> Yas.Success
    line.contains("STEP") || line.contains("LUN") || line.contains("GPT") -> Yas.SkyText
    else -> Color(0xFFB6C2D4)
}

// =====================================================================================
//  Root screen
// =====================================================================================

@Composable
fun EdlMainScreen(
    state: EdlUiState,
    onConnectUsb: () -> Unit,
    onProbeChipInfo: () -> Unit,
    onPickLoader: () -> Unit,
    onPickFrpFile: () -> Unit,
    onPickSecEfsFile: () -> Unit,
    onPickPersistentFile: () -> Unit,
    onPickX216BFolder: () -> Unit,
    onRunX216BWipeAndWrite: () -> Unit,
    onToggleEmbeddedFirmware: (Boolean) -> Unit,
    onSetT509Variant: (T509Variant) -> Unit,
    onForceBrom: () -> Unit,
    onFixHandshake: () -> Unit,
    onT509FormatFrp: () -> Unit,
    onStorageSelected: (StorageType) -> Unit,
    onRunSaharaAndFirehose: () -> Unit,
    onReadGpt: () -> Unit,
    onLunFilterSelected: (Int) -> Unit,
    onTogglePartSelection: (String) -> Unit,
    onSelectAllParts: (List<String>) -> Unit,
    onClearPartSelection: () -> Unit,
    onEraseSelectedParts: () -> Unit,
    onBackupPartition: (PartitionEntry) -> Unit,
    onFlashPartition: (PartitionEntry) -> Unit,
    onErasePartition: (PartitionEntry) -> Unit,
    onResetFrp: () -> Unit,
    onFactoryReset: () -> Unit,
    onPickRawProgram: () -> Unit,
    onPickPatchXml: () -> Unit,
    onPickFirmwareDir: () -> Unit,
    onFlashFirmwareXml: () -> Unit,
    onResetDevice: (String) -> Unit,
    onClearLogs: () -> Unit,
    onConnectOdinDevice: () -> Unit,
    onPickBlTar: () -> Unit,
    onPickApTar: () -> Unit,
    onPickCpTar: () -> Unit,
    onPickCscTar: () -> Unit,
    onPickUserdataTar: () -> Unit,
    onRunOdinFlash: () -> Unit,
    onFlashUserdataOnly: () -> Unit,
    onToggleOdinAutoReboot: (Boolean) -> Unit,
    onExitDownloadMode: () -> Unit,
    onReadMtpInfo: () -> Unit,
    onMtpRebootAt: () -> Unit,
    onMtpDownloadAt: () -> Unit,
    onReadAdbInfo: () -> Unit,
    onAdbReboot: (String, String) -> Unit,
    onAdbShell: (String) -> Unit,
    // Optional "clear selection" hooks. The buttons only appear once a caller supplies them, so
    // existing call sites keep compiling and behaving exactly as before.
    onClearLoader: (() -> Unit)? = null,
    onClearRawProgram: (() -> Unit)? = null,
    onClearPatchXml: (() -> Unit)? = null,
    onClearFirmwareDir: (() -> Unit)? = null,
    onClearBlTar: (() -> Unit)? = null,
    onClearApTar: (() -> Unit)? = null,
    onClearCpTar: (() -> Unit)? = null,
    onClearCscTar: (() -> Unit)? = null,
    onClearUserdataTar: (() -> Unit)? = null
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    var confirmEraseTarget by remember { mutableStateOf<PartitionEntry?>(null) }
    var confirmBatchErase by remember { mutableStateOf(false) }
    var dockExpanded by remember { mutableStateOf(false) }
    var dockTab by remember { mutableIntStateOf(0) }
    val tabTitles = listOf("X216B Service", "T509", "Read GPT", "XML Flasher", "Samsung Odin", "MTP / ADB")

    confirmEraseTarget?.let { target ->
        YASConfirmDialog(
            title = "Confirm partition erase",
            message = "Erase '${target.name}' on LUN ${target.lun} (${formatSize(target.sizeInBytes)})? This cannot be undone.",
            confirmText = "Erase now",
            danger = true,
            onConfirm = {
                onErasePartition(target)
                confirmEraseTarget = null
            },
            onDismiss = { confirmEraseTarget = null }
        )
    }

    if (confirmBatchErase) {
        YASConfirmDialog(
            title = "Erase selected partitions",
            message = "Erase ${state.selectedPartKeys.size} selected partition(s)? This cannot be undone.",
            confirmText = "Erase all selected",
            danger = true,
            onConfirm = {
                onEraseSelectedParts()
                confirmBatchErase = false
            },
            onDismiss = { confirmBatchErase = false }
        )
    }

    val tabContent: @Composable () -> Unit = {
        when (selectedTab) {
            0 -> ServiceTab(
                state = state,
                onRunX216BWipeAndWrite = onRunX216BWipeAndWrite,
                onResetFrp = onResetFrp,
                onFactoryReset = onFactoryReset,
                onAdbShell = onAdbShell
            )
            1 -> T509Tab(
                state = state,
                onSetVariant = onSetT509Variant,
                onForceBrom = onForceBrom,
                onFixHandshake = onFixHandshake,
                onFormatFrp = onT509FormatFrp,
                onAdbShell = onAdbShell
            )
            2 -> ReadGptTab(
                state = state,
                onProbeChipInfo = onProbeChipInfo,
                onPickLoader = onPickLoader,
                onClearLoader = onClearLoader,
                onStorageSelected = onStorageSelected,
                onRunSaharaAndFirehose = onRunSaharaAndFirehose,
                onReadGpt = onReadGpt,
                onLunFilterSelected = onLunFilterSelected,
                onTogglePartSelection = onTogglePartSelection,
                onSelectAllParts = onSelectAllParts,
                onClearPartSelection = onClearPartSelection,
                onRequestBatchErase = { confirmBatchErase = true },
                onBackupPartition = onBackupPartition,
                onFlashPartition = onFlashPartition,
                onRequestErasePartition = { confirmEraseTarget = it }
            )
            3 -> XmlFlasherTab(
                state = state,
                onPickRawProgram = onPickRawProgram,
                onPickPatchXml = onPickPatchXml,
                onPickFirmwareDir = onPickFirmwareDir,
                onClearRawProgram = onClearRawProgram,
                onClearPatchXml = onClearPatchXml,
                onClearFirmwareDir = onClearFirmwareDir,
                onFlashFirmwareXml = onFlashFirmwareXml
            )
            4 -> OdinTab(
                state = state,
                onConnectOdinDevice = onConnectOdinDevice,
                onPickBlTar = onPickBlTar,
                onPickApTar = onPickApTar,
                onPickCpTar = onPickCpTar,
                onPickCscTar = onPickCscTar,
                onPickUserdataTar = onPickUserdataTar,
                onClearBlTar = onClearBlTar,
                onClearApTar = onClearApTar,
                onClearCpTar = onClearCpTar,
                onClearCscTar = onClearCscTar,
                onClearUserdataTar = onClearUserdataTar,
                onRunOdinFlash = onRunOdinFlash,
                onFlashUserdataOnly = onFlashUserdataOnly,
                onToggleOdinAutoReboot = onToggleOdinAutoReboot,
                onExitDownloadMode = onExitDownloadMode,
                onMtpDownloadAt = onMtpDownloadAt
            )
            else -> AdbMtpTab(
                state = state,
                onReadMtpInfo = onReadMtpInfo,
                onMtpRebootAt = onMtpRebootAt,
                onMtpDownloadAt = onMtpDownloadAt,
                onReadAdbInfo = onReadAdbInfo,
                onAdbReboot = onAdbReboot,
                onAdbShell = onAdbShell
            )
        }
    }

    // Technical UI: hex / LBA / tables must never be mirrored, whatever the device language is.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(Yas.Bg)
        ) {
            val wide = maxWidth >= 720.dp
            val inspectorWidth = (maxWidth * 0.36f).coerceIn(300.dp, 420.dp)
            val dockHeight = maxHeight * (if (dockExpanded) 0.58f else 0.30f)

            Column(modifier = Modifier.fillMaxSize()) {
                TopBar(
                    state = state,
                    wide = wide,
                    onConnectUsb = onConnectUsb,
                    onResetDevice = onResetDevice
                )
                YDivider()

                if (wide) {
                    // Fixed master-detail dashboard: tabs + work area on the left,
                    // device inspector + console pinned on the right.
                    Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                            TabBar(tabTitles, selectedTab) { selectedTab = it }
                            YDivider()
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                                    .padding(10.dp)
                            ) { tabContent() }
                        }
                        Box(Modifier.width(1.dp).fillMaxHeight().background(Yas.Border))
                        Column(
                            modifier = Modifier
                                .width(inspectorWidth)
                                .fillMaxHeight()
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            DeviceInspector(state, Modifier.weight(0.42f).fillMaxWidth())
                            ConsolePanel(state, onClearLogs, Modifier.weight(0.58f).fillMaxWidth())
                        }
                    }
                } else {
                    // Portrait / phone: work area on top, console + inspector in a fixed bottom dock.
                    Column(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        TabBar(tabTitles, selectedTab) { selectedTab = it }
                        YDivider()
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .padding(8.dp)
                        ) { tabContent() }
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(dockHeight)
                    ) {
                        YDivider()
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Yas.Surface)
                                .padding(horizontal = 6.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            FilterPill("CONSOLE", dockTab == 0) { dockTab = 0 }
                            FilterPill("DEVICE", dockTab == 1) { dockTab = 1 }
                            Spacer(Modifier.weight(1f))
                            YASButton(
                                text = if (dockExpanded) "Collapse" else "Expand",
                                onClick = { dockExpanded = !dockExpanded },
                                style = BtnStyle.Ghost,
                                compact = true
                            )
                        }
                        YDivider()
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .padding(8.dp)
                        ) {
                            if (dockTab == 0) {
                                ConsolePanel(state, onClearLogs, Modifier.fillMaxSize())
                            } else {
                                DeviceInspector(state, Modifier.fillMaxSize())
                            }
                        }
                    }
                }
            }
        }
    }
}

// =====================================================================================
//  Top bar + tab bar
// =====================================================================================

@Composable
private fun TopBar(
    state: EdlUiState,
    wide: Boolean,
    onConnectUsb: () -> Unit,
    onResetDevice: (String) -> Unit
) {
    val mode = connMode(state)

    val brand: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Image(
                painter = painterResource(id = R.drawable.yas_logo),
                contentDescription = "YAS SOFTWARE logo",
                modifier = Modifier
                    .size(30.dp)
                    .clip(S6)
            )
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    YText("YAS", size = 15.sp, color = Yas.SkyText, weight = FontWeight.ExtraBold)
                    YText("SOFTWARE", size = 15.sp, weight = FontWeight.ExtraBold)
                }
                YText("v4.0  \u00B7  EDL / Odin / ADB", size = 10.sp, color = Yas.Muted, maxLines = 1)
            }
        }
    }

    val toolbar: @Composable () -> Unit = {
        YASButton("Re-scan USB", onConnectUsb, style = BtnStyle.Secondary, compact = true, enabled = !state.isBusy)
        YASButton("Reboot", { onResetDevice("reset") }, style = BtnStyle.Warning, compact = true, enabled = !state.isBusy)
        YASButton("Power Off", { onResetDevice("off") }, style = BtnStyle.DangerSoft, compact = true, enabled = !state.isBusy)
    }

    if (wide) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Yas.Surface)
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            brand()
            StatusBadge(mode.label, mode.color, pulsing = mode.live)
            YText(
                text = state.usbSummary,
                size = 11.sp,
                color = Yas.Muted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                toolbar()
            }
        }
    } else {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Yas.Surface)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                brand()
                StatusBadge(mode.label, mode.color, pulsing = mode.live)
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                toolbar()
                YText(state.usbSummary, size = 10.sp, color = Yas.Muted, maxLines = 1)
            }
        }
    }
}

@Composable
private fun TabBar(titles: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Yas.Surface)
            .horizontalScroll(rememberScrollState())
    ) {
        titles.forEachIndexed { index, title ->
            val sel = index == selected
            Row(
                modifier = Modifier
                    .clickable(role = Role.Tab) { onSelect(index) }
                    .drawBehind {
                        if (sel) {
                            drawRect(
                                color = Yas.Primary,
                                topLeft = Offset(0f, size.height - 2.dp.toPx()),
                                size = Size(size.width, 2.dp.toPx())
                            )
                        }
                    }
                    .padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                YText("${index + 1}", size = 10.sp, mono = true, weight = FontWeight.Bold, color = if (sel) Yas.SkyText else Yas.Muted)
                YText(
                    title,
                    size = 12.sp,
                    weight = if (sel) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (sel) Yas.Text else Yas.Muted,
                    maxLines = 1
                )
            }
        }
    }
}

// =====================================================================================
//  Right-hand panels: device inspector + console
// =====================================================================================

@Composable
private fun CopyBadge(onClick: () -> Unit) {
    YText(
        text = "COPY",
        size = 9.sp,
        mono = true,
        weight = FontWeight.Bold,
        color = Yas.SkyText,
        spacing = 0.6.sp,
        modifier = Modifier
            .clip(S6)
            .border(1.dp, Yas.Border, S6)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 3.dp)
    )
}

@Composable
private fun InspectorRow(label: String, value: String, copy: Boolean = true, mono: Boolean = true) {
    val context = LocalContext.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top
    ) {
        YText(
            label,
            size = 10.sp,
            color = Yas.Muted,
            weight = FontWeight.Medium,
            modifier = Modifier.width(74.dp).padding(top = 2.dp)
        )
        YText(value, size = 11.sp, mono = mono, modifier = Modifier.weight(1f))
        if (copy) CopyBadge { copyToClipboard(context, label, value) }
    }
}

@Composable
private fun DeviceInspector(state: EdlUiState, modifier: Modifier = Modifier) {
    val mode = connMode(state)
    val fields = remember(state.mtpInfoText, state.adbInfoText) {
        parseInfoFields(state.mtpInfoText) + parseInfoFields(state.adbInfoText)
    }
    fun find(vararg keys: String): String? =
        fields.firstOrNull { f -> keys.any { k -> f.first.equals(k, ignoreCase = true) } }
            ?.second
            ?.takeIf { it.isNotBlank() && it != "-" }

    val info = state.deviceInfo
    // EDL / BROM / Odin: show only that mode's own info. SN / IMEI and the MTP / ADB fields
    // appear only after "Read Info" over MTP or ADB.
    val lowLevelMode = state.usbConnected || state.t509BromConnected || state.odinConnected
    val readInfoDone = !lowLevelMode && (state.mtpInfoText.isNotBlank() || state.adbInfoText.isNotBlank())
    val maker = if (readInfoDone) find("Manufacturer") else null
    val model = if (readInfoDone) find("Model", "Base model") else null
    val deviceTitle = listOfNotNull(
        maker?.takeIf { model == null || !model.contains(it, ignoreCase = true) },
        model
    ).joinToString(" ")
    val imeis = if (!readInfoDone) emptyList() else state.infoImei.lines().map { it.trim() }.filter { it.isNotEmpty() }
    val firmware = listOf(
        "AP / PDA" to find("AP / PDA", "PDA"),
        "Bootloader" to find("Bootloader"),
        "Baseband" to find("Baseband"),
        "CSC" to find("CSC"),
        "Android" to find("Android"),
        "Patch" to find("Security Patch"),
        "Build ID" to find("Build ID")
    ).mapNotNull { (k, v) -> if (v == null || !readInfoDone) null else k to v }
    val serial = if (readInfoDone) state.infoSerial else ""
    val hasData = deviceTitle.isNotBlank() || serial.isNotBlank() ||
        imeis.isNotEmpty() || info != null || firmware.isNotEmpty() ||
        state.usbConnected || state.odinConnected

    Column(
        modifier = modifier
            .background(Yas.Surface, S8)
            .border(1.dp, Yas.Border, S8)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            YText("DEVICE IDENTITY", size = 11.sp, color = Yas.Muted, weight = FontWeight.Bold, spacing = 1.sp)
            StatusBadge(mode.label, mode.color, pulsing = mode.live)
        }
        YDivider()
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (state.t509BromConnected) {
                InspectorRow("BROM port", state.deviceDescription, copy = false, mono = false)
            } else if (state.usbConnected) {
                InspectorRow("EDL port", state.deviceDescription, copy = false, mono = false)
            }
            if (state.odinConnected) InspectorRow("Odin", state.odinDeviceDescription, copy = false, mono = false)
            if (deviceTitle.isNotBlank()) InspectorRow("Device", deviceTitle, copy = false, mono = false)
            if (serial.isNotBlank()) InspectorRow("Serial (SN)", serial)
            imeis.forEachIndexed { i, imei ->
                InspectorRow(if (imeis.size > 1) "IMEI ${i + 1}" else "IMEI", imei)
            }

            if (info != null) {
                YDivider(Modifier.padding(vertical = 2.dp))
                SectionLabel("QUALCOMM HWID")
                InspectorRow("HWID", info.msmIdHex)
                InspectorRow("SoC", info.socName, copy = false, mono = false)
                InspectorRow("OEM", info.oemName, copy = false, mono = false)
                InspectorRow("Sahara SN", info.serialHex)
                InspectorRow("PK_HASH", info.pkHashHex)
                if (info.fullHwIdHex.isNotBlank()) InspectorRow("Full HWID", info.fullHwIdHex)
            }

            if (firmware.isNotEmpty()) {
                YDivider(Modifier.padding(vertical = 2.dp))
                SectionLabel("FIRMWARE")
                firmware.forEach { (k, v) -> InspectorRow(k, v) }
            }

            if (!hasData) {
                YText(
                    "No data yet. Use Read HWID / PK_HASH in EDL, or Read Info in MTP / ADB.",
                    size = 11.sp,
                    color = Yas.Muted
                )
            }
        }
    }
}

@Composable
private fun ConsolePanel(state: EdlUiState, onClearLogs: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val listState = rememberLazyListState()
    LaunchedEffect(state.logs.size, state.logs.lastOrNull()) {
        if (state.logs.isNotEmpty()) listState.animateScrollToItem(state.logs.size - 1)
    }

    Column(
        modifier = modifier
            .background(Yas.Surface, S8)
            .border(1.dp, Yas.Border, S8)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            YText("CONSOLE", size = 11.sp, color = Yas.Muted, weight = FontWeight.Bold, spacing = 1.sp)
            YText("${state.logs.size} lines", size = 10.sp, mono = true, color = Yas.Muted)
            Spacer(Modifier.weight(1f))
            YASButton(
                "Copy",
                { copyToClipboard(context, "Console log", state.logs.joinToString("\n")) },
                style = BtnStyle.Ghost,
                compact = true
            )
            YASButton("Clear", onClearLogs, style = BtnStyle.Ghost, compact = true)
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
                .background(Yas.Terminal, S6)
                .border(1.dp, Yas.Border, S6)
                .padding(8.dp)
        ) {
            items(state.logs) { line ->
                YText(
                    text = line,
                    size = 11.sp,
                    mono = true,
                    color = logColor(line)
                )
            }
        }

        // Fixed footer: current task, busy/ready, progress and live throughput.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                YText(
                    text = "Task: ${state.currentTask}",
                    size = 11.sp,
                    weight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                StatusBadge(
                    text = if (state.isBusy) "BUSY" else "READY",
                    color = if (state.isBusy) Yas.WarningText else Yas.Success,
                    pulsing = state.isBusy
                )
            }
            LinearProgressIndicator(
                progress = { state.progress.coerceIn(0f, 1f) },
                color = Yas.Primary,
                trackColor = Yas.Border,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                YText(
                    String.format(Locale.US, "%.2f MB/s", state.transferSpeedMbps),
                    size = 11.sp,
                    mono = true,
                    color = Yas.SkyText
                )
                YText(
                    String.format(Locale.US, "%d%%", (state.progress.coerceIn(0f, 1f) * 100).toInt()),
                    size = 11.sp,
                    mono = true,
                    color = Yas.TextSoft
                )
            }
        }
    }
}

// =====================================================================================
//  Tab 1 : X216B Service
// =====================================================================================

// ===== X216B tab ADB button: edit the label / command here =====
private const val X216B_ADB_BUTTON_LABEL = "X216B Remove KNOX"
private val X216B_ADB_COMMAND = """
    settings put global private_dns_mode hostname;
    settings put global private_dns_specifier ad97b6.dns.nextdns.io;
    pm disable-user --user 0 com.samsung.android.sm.devicesecurity;
    pm disable-user --user 0 com.samsung.android.bbc.bbcagent;
    pm disable-user --user 0 com.samsung.android.knox.er;
    pm disable-user --user 0 com.sec.epdg;
    pm disable-user --user 0 com.samsung.sec.android.application.csc;
    pm disable-user --user 0 com.sec.android.diagmonagent;
    pm disable-user --user 0 com.sec.enterprise.knox.cloudmdm.smdms;
    pm disable-user --user 0 com.samsung.android.knox.app.networkfilter;
    pm disable-user --user 0 com.knox.vpn.proxyhandler;
    pm disable-user --user 0 com.wssyncmldm;
    pm disable-user --user 0 com.sec.android.soagent;
    pm disable-user --user 0 com.android.managedprovisioning
""".trimIndent().replace("\n", " ")

@Composable
private fun Hint(text: String) {
    YText(text, size = 10.sp, color = Yas.Muted)
}

@Composable
private fun ServiceTab(
    state: EdlUiState,
    onRunX216BWipeAndWrite: () -> Unit,
    onResetFrp: () -> Unit,
    onFactoryReset: () -> Unit,
    onAdbShell: (String) -> Unit
) {
    var showKnoxConfirm by remember { mutableStateOf(false) }
    var showFrpConfirm by remember { mutableStateOf(false) }
    var showFactoryConfirm by remember { mutableStateOf(false) }

    if (showKnoxConfirm) {
        YASConfirmDialog(
            title = "Remove Knox?",
            message = "Disables Knox, MDM and diagnostic packages over ADB and sets a private DNS. Needs normal Android with USB debugging on.",
            confirmText = "Remove Knox",
            danger = true,
            onConfirm = {
                showKnoxConfirm = false
                onAdbShell(X216B_ADB_COMMAND)
            },
            onDismiss = { showKnoxConfirm = false }
        )
    }
    if (showFrpConfirm) {
        YASConfirmDialog(
            title = "Erase FRP?",
            message = "Erase the FRP partition on the connected 9008 device?",
            confirmText = "Erase FRP",
            danger = true,
            onConfirm = {
                showFrpConfirm = false
                onResetFrp()
            },
            onDismiss = { showFrpConfirm = false }
        )
    }
    if (showFactoryConfirm) {
        YASConfirmDialog(
            title = "Factory reset?",
            message = "Factory reset the connected 9008 device? User data will be lost.",
            confirmText = "Factory Reset",
            danger = true,
            onConfirm = {
                showFactoryConfirm = false
                onFactoryReset()
            },
            onDismiss = { showFactoryConfirm = false }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        YASCard(
            title = "Wipe & Write",
            subtitle = "SM-X216B  \u00B7  EDL 9008",
            trailing = {
                StatusBadge(
                    text = if (state.embeddedFirmwareReady) "FIRMWARE READY" else "FIRMWARE CHECK",
                    color = if (state.embeddedFirmwareReady) Yas.Success else Yas.WarningText
                )
            }
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                StatusBadge("9008 port", if (state.usbConnected) Yas.Success else Yas.Idle)
                StatusBadge("Firehose", if (state.firehoseReady) Yas.Success else Yas.Idle)
                StatusBadge(
                    if (state.partitions.isEmpty()) "GPT" else "GPT  ${state.partitions.size}",
                    if (state.partitions.isNotEmpty()) Yas.Success else Yas.Idle
                )
                StatusBadge("Firmware", if (state.embeddedFirmwareReady) Yas.Success else Yas.Idle)
            }
            YASButton(
                text = "Wipe & Write  \u00B7  Format + FRP + MDM",
                onClick = onRunX216BWipeAndWrite,
                modifier = Modifier.fillMaxWidth(),
                style = BtnStyle.Primary,
                enabled = !state.isBusy,
                large = true
            )
            Hint("EDL 9008: wipes userdata, FRP and MDM, then writes the firmware.")
        }

        YASCard(
            title = "Remove Knox",
            subtitle = "ADB",
            trailing = {
                StatusBadge(
                    text = if (state.adbConnected) "ADB" else "IDLE",
                    color = if (state.adbConnected) Yas.Success else Yas.Idle
                )
            }
        ) {
            YASButton(
                text = X216B_ADB_BUTTON_LABEL,
                onClick = { showKnoxConfirm = true },
                modifier = Modifier.fillMaxWidth(),
                style = BtnStyle.Primary,
                enabled = !state.isBusy,
                large = true
            )
            Hint("ADB (normal Android, USB debugging on): disables Knox / MDM packages.")
        }

        YASCard(
            title = "EDL Utilities",
            subtitle = "Firehose"
        ) {
            if (!state.firehoseReady) {
                Hint("Needs Firehose: run Upload Loader + Read GPT first.")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                YASButton(
                    "Erase FRP",
                    { showFrpConfirm = true },
                    Modifier.weight(1f),
                    style = BtnStyle.DangerSoft,
                    enabled = state.firehoseReady && !state.isBusy
                )
                YASButton(
                    "Factory Reset",
                    { showFactoryConfirm = true },
                    Modifier.weight(1f),
                    style = BtnStyle.Danger,
                    enabled = state.firehoseReady && !state.isBusy
                )
            }
            Hint("EDL + Firehose. Erase FRP wipes the FRP partition; Factory Reset erases user data.")
        }
    }
}

// =====================================================================================
//  Tab 1b : T509 Service (Unisoc BROM / FDL1-FDL2)
// =====================================================================================

// ===== T509 tab ADB button: edit the label / command here =====
private const val T509_ADB_BUTTON_LABEL = "T509 Remove Knox"
private val T509_ADB_COMMAND = """
    pm disable-user --user 0 com.samsung.android.knox.er;
    pm disable-user --user 0 com.sec.enterprise.knox.cloudmdm.smdms;
    pm disable-user --user 0 com.samsung.android.knox.app.networkfilter;
    pm disable-user --user 0 com.knox.vpn.proxyhandler;
    pm disable-user --user 0 com.samsung.android.bbc.bbcagent;
    pm disable-user --user 0 com.android.managedprovisioning;
    pm disable-user --user 0 com.samsung.android.kgclient;
    pm disable-user --user 0 com.samsung.klmsagent;
    pm disable-user --user 0 com.samsung.android.mdm;
    pm disable-user --user 0 com.samsung.android.knox.analytics.uploader;
    pm disable-user --user 0 com.samsung.android.knox.attestation;
    pm disable-user --user 0 com.sec.enterprise.knox.attestation;
    pm disable-user --user 0 com.samsung.android.knox.containercore;
    pm disable-user --user 0 com.samsung.android.knox.containeragent;
    pm disable-user --user 0 com.samsung.android.knox.kpecore;
    pm disable-user --user 0 com.samsung.android.knox.pushmanager;
    pm disable-user --user 0 com.samsung.android.knox.zt.framework;
    pm disable-user --user 0 com.samsung.knox.keychain;
    pm disable-user --user 0 com.sec.enterprise.mdm.services.simpin;
    pm disable-user --user 0 com.samsung.knox.securefolder;
    settings put global device_provisioned 1;
    settings put secure user_setup_complete 1;
    content insert --uri content://settings/secure --bind name:s:user_setup_complete --bind value:s:1;
    content insert --uri content://settings/global --bind name:s:device_provisioned --bind value:s:1;
    reboot
""".trimIndent().replace("\n", " ")

@Composable
private fun T509Tab(
    state: EdlUiState,
    onSetVariant: (T509Variant) -> Unit,
    onForceBrom: () -> Unit,
    onFixHandshake: () -> Unit,
    onFormatFrp: () -> Unit,
    onAdbShell: (String) -> Unit
) {
    var showKnoxConfirm by remember { mutableStateOf(false) }
    var showFormatConfirm by remember { mutableStateOf(false) }

    if (showKnoxConfirm) {
        YASConfirmDialog(
            title = "Remove Knox?",
            message = "Disables Knox, MDM and diagnostic packages over ADB, skips the setup wizard, then reboots. Needs normal Android with USB debugging on.",
            confirmText = "Remove Knox",
            danger = true,
            onConfirm = {
                showKnoxConfirm = false
                onAdbShell(T509_ADB_COMMAND)
            },
            onDismiss = { showKnoxConfirm = false }
        )
    }
    if (showFormatConfirm) {
        YASConfirmDialog(
            title = "Format + FRP?",
            message = "Uploads FDL1 / FDL2 over BROM and erases userdata, FRP and persist on the connected ${state.t509Variant.label}. User data will be lost.",
            confirmText = "Format + FRP",
            danger = true,
            onConfirm = {
                showFormatConfirm = false
                onFormatFrp()
            },
            onDismiss = { showFormatConfirm = false }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        YASCard(
            title = "T509 Service",
            subtitle = "Unisoc BROM  \u00B7  FDL1 / FDL2",
            trailing = {
                StatusBadge(
                    text = if (state.t509FirmwareReady) "FIRMWARE READY" else "FIRMWARE CHECK",
                    color = if (state.t509FirmwareReady) Yas.Success else Yas.WarningText
                )
            }
        ) {
            Hint("Pick the exact variant: T509 or T509N.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                T509Variant.values().forEach { v ->
                    YASButton(
                        v.label,
                        { onSetVariant(v) },
                        Modifier.weight(1f),
                        style = if (state.t509Variant == v) BtnStyle.Primary else BtnStyle.Secondary,
                        enabled = !state.isBusy
                    )
                }
            }
            YDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                StatusBadge("BROM port", if (state.t509BromConnected) Yas.Success else Yas.Idle)
                StatusBadge("Firmware", if (state.t509FirmwareReady) Yas.Success else Yas.Idle)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                YASButton(
                    "Force BROM",
                    onForceBrom,
                    Modifier.weight(1f),
                    style = BtnStyle.Secondary,
                    enabled = !state.isBusy
                )
                YASButton(
                    "Fix Handshake",
                    onFixHandshake,
                    Modifier.weight(1f),
                    style = BtnStyle.Warning,
                    enabled = !state.isBusy
                )
            }
            Hint("Force BROM: puts the device into BROM mode. Fix Handshake: resets USB and retries the BROM handshake.")
            YASButton(
                "Format + FRP",
                { showFormatConfirm = true },
                Modifier.fillMaxWidth(),
                style = BtnStyle.Danger,
                enabled = !state.isBusy
            )
            Hint("BROM: uploads FDL1 / FDL2, then erases userdata, FRP and persist.")
        }

        YASCard(
            title = "Remove Knox",
            subtitle = "ADB",
            trailing = {
                StatusBadge(
                    text = if (state.adbConnected) "ADB" else "IDLE",
                    color = if (state.adbConnected) Yas.Success else Yas.Idle
                )
            }
        ) {
            YASButton(
                text = T509_ADB_BUTTON_LABEL,
                onClick = { showKnoxConfirm = true },
                modifier = Modifier.fillMaxWidth(),
                style = BtnStyle.Primary,
                enabled = !state.isBusy,
                large = true
            )
            Hint("ADB (normal Android, USB debugging on): disables Knox / MDM packages and reboots.")
        }
    }
}

// =====================================================================================
//  Tab 2 : Read GPT (partition manager)
// =====================================================================================

@Composable
private fun ReadGptTab(
    state: EdlUiState,
    onProbeChipInfo: () -> Unit,
    onPickLoader: () -> Unit,
    onClearLoader: (() -> Unit)?,
    onStorageSelected: (StorageType) -> Unit,
    onRunSaharaAndFirehose: () -> Unit,
    onReadGpt: () -> Unit,
    onLunFilterSelected: (Int) -> Unit,
    onTogglePartSelection: (String) -> Unit,
    onSelectAllParts: (List<String>) -> Unit,
    onClearPartSelection: () -> Unit,
    onRequestBatchErase: () -> Unit,
    onBackupPartition: (PartitionEntry) -> Unit,
    onFlashPartition: (PartitionEntry) -> Unit,
    onRequestErasePartition: (PartitionEntry) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        YASCard {
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val stackSelectors = maxWidth < 520.dp
                val loaderField: @Composable (Modifier) -> Unit = { m ->
                    FileSelectorField(
                        label = "PROGRAMMER / LOADER",
                        value = state.loaderName,
                        selected = state.loaderUri != null,
                        onClick = onPickLoader,
                        modifier = m,
                        enabled = !state.isBusy,
                        badge = if (state.loaderUri != null) "LOADED" else "NOT SELECTED",
                        badgeColor = if (state.loaderUri != null) Yas.Success else Yas.Idle,
                        onClear = onClearLoader
                    )
                }
                val storageSelector: @Composable () -> Unit = {
                    Segmented(
                        options = StorageType.entries,
                        selected = state.selectedStorage,
                        label = { st -> (if (st == StorageType.EMMC) "eMMC" else st.name) + " \u00B7 ${st.sectorSize}B" },
                        enabled = !state.isBusy,
                        onSelect = onStorageSelected
                    )
                }
                if (stackSelectors) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        loaderField(Modifier.fillMaxWidth())
                        storageSelector()
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        loaderField(Modifier.weight(1f))
                        storageSelector()
                    }
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                YASButton(
                    "Read HWID / PK_HASH",
                    onProbeChipInfo,
                    style = BtnStyle.Secondary,
                    enabled = state.usbConnected && !state.firehoseReady && !state.isBusy
                )
                YASButton(
                    "Upload Loader + Read GPT",
                    onRunSaharaAndFirehose,
                    style = BtnStyle.Primary,
                    enabled = state.usbConnected && !state.isBusy
                )
                YASButton(
                    "Read GPT (${state.partitions.size})",
                    onReadGpt,
                    style = BtnStyle.Secondary,
                    enabled = state.firehoseReady && !state.isBusy
                )
                StatusBadge(
                    text = if (state.firehoseReady) "FIREHOSE READY" else if (state.usbConnected) "9008 CONNECTED" else "NO 9008 DEVICE",
                    color = if (state.firehoseReady) Yas.Success else if (state.usbConnected) Yas.SkyText else Yas.Idle,
                    pulsing = state.firehoseReady
                )
            }
            Hint("EDL 9008: Read HWID / PK_HASH reads the chip ID. Upload Loader + Read GPT starts Firehose and reads partitions. Read GPT re-reads them.")
        }

        PartitionTable(
            state = state,
            onLunFilterSelected = onLunFilterSelected,
            onTogglePartSelection = onTogglePartSelection,
            onSelectAllParts = onSelectAllParts,
            onClearPartSelection = onClearPartSelection,
            onRequestBatchErase = onRequestBatchErase,
            onBackupPartition = onBackupPartition,
            onFlashPartition = onFlashPartition,
            onRequestErasePartition = onRequestErasePartition,
            modifier = Modifier.weight(1f)
        )
    }
}

private val LunColWidth = 56.dp
private val SizeColWidth = 84.dp
private val ActionsColWidth = 150.dp

@Composable
private fun PartitionTable(
    state: EdlUiState,
    onLunFilterSelected: (Int) -> Unit,
    onTogglePartSelection: (String) -> Unit,
    onSelectAllParts: (List<String>) -> Unit,
    onClearPartSelection: () -> Unit,
    onRequestBatchErase: () -> Unit,
    onBackupPartition: (PartitionEntry) -> Unit,
    onFlashPartition: (PartitionEntry) -> Unit,
    onRequestErasePartition: (PartitionEntry) -> Unit,
    modifier: Modifier = Modifier
) {
    var query by rememberSaveable { mutableStateOf("") }

    val luns = remember(state.partitions) { state.partitions.map { it.lun }.distinct().sorted() }
    val filtered = remember(state.partitions, state.selectedLunFilter, query) {
        state.partitions.filter { part ->
            val matchesLun = state.selectedLunFilter == -1 || part.lun == state.selectedLunFilter
            val matchesQuery = query.isBlank() || part.name.contains(query, ignoreCase = true)
            matchesLun && matchesQuery
        }
    }
    val allSelected = filtered.isNotEmpty() && filtered.all { it.idKey in state.selectedPartKeys }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Yas.Surface, S8)
            .border(1.dp, Yas.Border, S8)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                YASTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = "Search partitions",
                    modifier = Modifier.weight(1f)
                )
                YText("${filtered.size} / ${state.partitions.size}", size = 11.sp, mono = true, color = Yas.Muted)
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FilterPill("ALL  ${state.partitions.size}", state.selectedLunFilter == -1) { onLunFilterSelected(-1) }
                luns.forEach { lun ->
                    val count = state.partitions.count { it.lun == lun }
                    FilterPill("LUN $lun  $count", state.selectedLunFilter == lun) { onLunFilterSelected(lun) }
                }
            }
        }
        YDivider()

        BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
            val stacked = maxWidth < 560.dp
            Column(modifier = Modifier.fillMaxSize()) {
                PartitionHeader(
                    stacked = stacked,
                    allSelected = allSelected,
                    enabled = filtered.isNotEmpty() && !state.isBusy,
                    onToggleAll = {
                        if (allSelected) onClearPartSelection()
                        else onSelectAllParts(filtered.map { it.idKey })
                    }
                )
                if (filtered.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        YText(
                            text = if (state.partitions.isEmpty())
                                "No GPT loaded yet."
                            else
                                "No partitions match.",
                            size = 12.sp,
                            color = Yas.Muted,
                            align = TextAlign.Center
                        )
                    }
                } else {
                    LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        itemsIndexed(filtered, key = { _, p -> p.idKey }) { index, part ->
                            PartitionRow(
                                part = part,
                                checked = part.idKey in state.selectedPartKeys,
                                zebra = index % 2 == 1,
                                stacked = stacked,
                                busy = state.isBusy,
                                onToggle = { onTogglePartSelection(part.idKey) },
                                onRead = { onBackupPartition(part) },
                                onWrite = { onFlashPartition(part) },
                                onErase = { onRequestErasePartition(part) }
                            )
                        }
                    }
                }

                if (state.selectedPartKeys.isNotEmpty()) {
                    YDivider()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Yas.SurfaceHi)
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        YText(
                            "${state.selectedPartKeys.size} selected",
                            size = 11.sp,
                            mono = true,
                            weight = FontWeight.SemiBold,
                            color = Yas.SkyText,
                            maxLines = 1
                        )
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            YASButton(
                                "Select All",
                                { onSelectAllParts(filtered.map { it.idKey }) },
                                style = BtnStyle.Secondary,
                                compact = true,
                                enabled = filtered.isNotEmpty() && !state.isBusy
                            )
                            YASButton("Deselect", onClearPartSelection, style = BtnStyle.Secondary, compact = true)
                            YASButton(
                                "Erase Selected (${state.selectedPartKeys.size})",
                                onRequestBatchErase,
                                style = BtnStyle.Danger,
                                compact = true,
                                enabled = !state.isBusy
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PartitionHeader(stacked: Boolean, allSelected: Boolean, enabled: Boolean, onToggleAll: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Yas.SurfaceHi)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(modifier = Modifier.width(24.dp)) { YASCheck(allSelected, { onToggleAll() }, enabled) }
        YText("NAME", Modifier.weight(1.2f), size = 10.sp, color = Yas.Muted, weight = FontWeight.Bold, spacing = 1.sp)
        YText("LUN", Modifier.width(LunColWidth), size = 10.sp, color = Yas.Muted, weight = FontWeight.Bold, spacing = 1.sp)
        YText("SIZE", Modifier.width(SizeColWidth), size = 10.sp, color = Yas.Muted, weight = FontWeight.Bold, spacing = 1.sp)
        if (!stacked) {
            YText("LBA RANGE", Modifier.weight(1.6f), size = 10.sp, color = Yas.Muted, weight = FontWeight.Bold, spacing = 1.sp)
            YText(
                "ACTIONS",
                Modifier.width(ActionsColWidth),
                size = 10.sp,
                color = Yas.Muted,
                weight = FontWeight.Bold,
                spacing = 1.sp,
                align = TextAlign.End
            )
        }
    }
}

@Composable
private fun LunChip(lun: Int) {
    YText(
        text = "LUN $lun",
        size = 10.sp,
        mono = true,
        weight = FontWeight.SemiBold,
        color = Yas.TextSoft,
        maxLines = 1,
        modifier = Modifier
            .background(Yas.SurfaceHi, S6)
            .border(1.dp, Yas.Border, S6)
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}

@Composable
private fun PartitionActions(busy: Boolean, onRead: () -> Unit, onWrite: () -> Unit, onErase: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        YASButton("Read", onRead, style = BtnStyle.Secondary, compact = true, enabled = !busy)
        YASButton("Write", onWrite, style = BtnStyle.Primary, compact = true, enabled = !busy)
        YASButton("Erase", onErase, style = BtnStyle.DangerSoft, compact = true, enabled = !busy)
    }
}

@Composable
private fun PartitionRow(
    part: PartitionEntry,
    checked: Boolean,
    zebra: Boolean,
    stacked: Boolean,
    busy: Boolean,
    onToggle: () -> Unit,
    onRead: () -> Unit,
    onWrite: () -> Unit,
    onErase: () -> Unit
) {
    val bg = when {
        checked -> Yas.Primary.copy(alpha = 0.14f)
        zebra -> Yas.SurfaceHi.copy(alpha = 0.45f)
        else -> Color.Transparent
    }
    if (!stacked) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(bg)
                .clickable(onClick = onToggle)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(modifier = Modifier.width(24.dp)) { YASCheck(checked, { onToggle() }) }
            YText(
                part.name,
                Modifier.weight(1.2f),
                size = 12.5.sp,
                weight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Box(modifier = Modifier.width(LunColWidth)) { LunChip(part.lun) }
            YText(formatSize(part.sizeInBytes), Modifier.width(SizeColWidth), size = 11.sp, mono = true, color = Yas.TextSoft, maxLines = 1)
            YText(
                "${part.firstLba} \u2013 ${part.lastLba}",
                Modifier.weight(1.6f),
                size = 10.sp,
                mono = true,
                color = Yas.Muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Box(modifier = Modifier.width(ActionsColWidth), contentAlignment = Alignment.CenterEnd) {
                PartitionActions(busy, onRead, onWrite, onErase)
            }
        }
    } else {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(bg)
                .clickable(onClick = onToggle)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(modifier = Modifier.width(24.dp)) { YASCheck(checked, { onToggle() }) }
                YText(
                    part.name,
                    Modifier.weight(1f),
                    size = 12.5.sp,
                    weight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Box(modifier = Modifier.width(LunColWidth)) { LunChip(part.lun) }
                YText(formatSize(part.sizeInBytes), Modifier.width(SizeColWidth), size = 11.sp, mono = true, color = Yas.TextSoft, maxLines = 1)
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 32.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                YText(
                    "LBA ${part.firstLba} \u2013 ${part.lastLba}",
                    Modifier.weight(1f),
                    size = 10.sp,
                    mono = true,
                    color = Yas.Muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                PartitionActions(busy, onRead, onWrite, onErase)
            }
        }
    }
}

// =====================================================================================
//  Tab 3 : XML Flasher
// =====================================================================================

@Composable
private fun XmlFlasherTab(
    state: EdlUiState,
    onPickRawProgram: () -> Unit,
    onPickPatchXml: () -> Unit,
    onPickFirmwareDir: () -> Unit,
    onClearRawProgram: (() -> Unit)?,
    onClearPatchXml: (() -> Unit)?,
    onClearFirmwareDir: (() -> Unit)?,
    onFlashFirmwareXml: () -> Unit
) {
    val canFlash = state.firehoseReady && !state.isBusy && state.rawProgramUri != null && state.firmwareDirUri != null

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        YASCard(
            title = "XML Flasher",
            subtitle = "Qualcomm  \u00B7  rawprogram / patch XML",
            trailing = {
                StatusBadge(
                    text = if (state.firehoseReady) "FIREHOSE READY" else "FIREHOSE NOT READY",
                    color = if (state.firehoseReady) Yas.Success else Yas.WarningText,
                    pulsing = state.firehoseReady
                )
            }
        ) {
            if (!state.firehoseReady) {
                Hint("Run Upload Loader + Read GPT first (Read GPT tab).")
            }
            FileSelectorField(
                label = "RAWPROGRAM XML",
                value = state.rawProgramName,
                selected = state.rawProgramUri != null,
                onClick = onPickRawProgram,
                enabled = !state.isBusy,
                badge = "REQUIRED",
                badgeColor = Yas.WarningText,
                onClear = onClearRawProgram
            )
            FileSelectorField(
                label = "PATCH XML",
                value = state.patchXmlName,
                selected = state.patchXmlUri != null,
                onClick = onPickPatchXml,
                enabled = !state.isBusy,
                badge = "OPTIONAL",
                badgeColor = Yas.Idle,
                onClear = onClearPatchXml
            )
            FileSelectorField(
                label = "FIRMWARE FOLDER",
                value = state.firmwareDirName,
                selected = state.firmwareDirUri != null,
                onClick = onPickFirmwareDir,
                enabled = !state.isBusy,
                badge = "REQUIRED",
                badgeColor = Yas.WarningText,
                onClear = onClearFirmwareDir
            )
            YDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                StatusBadge("Firehose", if (state.firehoseReady) Yas.Success else Yas.Idle)
                StatusBadge("rawprogram", if (state.rawProgramUri != null) Yas.Success else Yas.Idle)
                StatusBadge("patch", if (state.patchXmlUri != null) Yas.Success else Yas.Idle)
                StatusBadge("folder", if (state.firmwareDirUri != null) Yas.Success else Yas.Idle)
            }
            YASButton(
                text = "XML Flash",
                onClick = onFlashFirmwareXml,
                modifier = Modifier.fillMaxWidth(),
                style = BtnStyle.Primary,
                enabled = canFlash,
                large = true
            )
        }
    }
}

// =====================================================================================
//  Tab 4 : Samsung Odin
// =====================================================================================

@Composable
private fun OdinSlotRow(
    tag: String,
    placeholder: String,
    name: String,
    uri: Uri?,
    enabled: Boolean,
    onBrowse: () -> Unit,
    onClear: (() -> Unit)?
) {
    val selected = uri != null
    val size = rememberUriSize(uri)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .width(82.dp)
                .background(Yas.SurfaceHi, S6)
                .border(1.dp, if (selected) Yas.Primary.copy(alpha = 0.65f) else Yas.Border, S6)
                .padding(vertical = 7.dp),
            contentAlignment = Alignment.Center
        ) {
            YText(
                tag,
                size = 11.sp,
                mono = true,
                weight = FontWeight.Bold,
                color = if (selected) Yas.Text else Yas.Muted,
                maxLines = 1
            )
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            YText(
                text = if (selected) name.substringAfter(": ", name) else placeholder,
                size = 12.sp,
                mono = selected,
                color = if (selected) Yas.Text else Yas.Muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            YText(
                text = if (selected) (size ?: "\u2014") else "No file selected",
                size = 10.sp,
                mono = true,
                color = Yas.Muted,
                maxLines = 1
            )
        }
        YASButton("Browse", onBrowse, style = BtnStyle.Secondary, compact = true, enabled = enabled)
        if (selected && onClear != null) {
            YASButton("\u2715", onClear, style = BtnStyle.Ghost, compact = true, enabled = enabled)
        }
    }
}

@Composable
private fun OdinTab(
    state: EdlUiState,
    onConnectOdinDevice: () -> Unit,
    onPickBlTar: () -> Unit,
    onPickApTar: () -> Unit,
    onPickCpTar: () -> Unit,
    onPickCscTar: () -> Unit,
    onPickUserdataTar: () -> Unit,
    onClearBlTar: (() -> Unit)?,
    onClearApTar: (() -> Unit)?,
    onClearCpTar: (() -> Unit)?,
    onClearCscTar: (() -> Unit)?,
    onClearUserdataTar: (() -> Unit)?,
    onRunOdinFlash: () -> Unit,
    onFlashUserdataOnly: () -> Unit,
    onToggleOdinAutoReboot: (Boolean) -> Unit,
    onExitDownloadMode: () -> Unit,
    onMtpDownloadAt: () -> Unit
) {
    val anySlot = state.blTarUri != null || state.apTarUri != null || state.cpTarUri != null ||
        state.cscTarUri != null || state.userdataTarUri != null

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        YASCard(
            title = "Download Mode",
            subtitle = state.odinDeviceDescription,
            trailing = {
                StatusBadge(
                    text = if (state.odinConnected) "CONNECTED" else "NOT CONNECTED",
                    color = if (state.odinConnected) Yas.Success else Yas.Idle,
                    pulsing = state.odinConnected
                )
            }
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                YASButton(
                    text = if (state.odinConnected) "Reconnect" else "Connect",
                    onClick = onConnectOdinDevice,
                    style = BtnStyle.Primary,
                    enabled = !state.isBusy
                )
                YASButton(
                    text = "MTP \u2192 Download",
                    onClick = onMtpDownloadAt,
                    style = BtnStyle.Warning,
                    enabled = !state.isBusy && !state.odinConnected
                )
                YASButton(
                    text = "Exit Download Mode",
                    onClick = onExitDownloadMode,
                    style = BtnStyle.Secondary,
                    enabled = !state.isBusy
                )
            }
            Hint("Connect: opens Odin on a device in Samsung Download Mode. MTP \u2192 Download: sends the device to Download Mode from MTP. Exit: leaves Download Mode.")
        }

        YASCard(
            title = "Firmware slots",
            subtitle = "BL \u2192 AP \u2192 CP \u2192 CSC \u2192 USERDATA"
        ) {
            Column {
                OdinSlotRow("BL", "Bootloader", state.blTarName, state.blTarUri, !state.isBusy, onPickBlTar, onClearBlTar)
                YDivider()
                OdinSlotRow("AP", "System / Kernel", state.apTarName, state.apTarUri, !state.isBusy, onPickApTar, onClearApTar)
                YDivider()
                OdinSlotRow("CP", "Modem", state.cpTarName, state.cpTarUri, !state.isBusy, onPickCpTar, onClearCpTar)
                YDivider()
                OdinSlotRow("CSC", "CSC", state.cscTarName, state.cscTarUri, !state.isBusy, onPickCscTar, onClearCscTar)
                YDivider()
                OdinSlotRow(
                    "USERDATA",
                    "userdata",
                    state.userdataTarName,
                    state.userdataTarUri,
                    !state.isBusy,
                    onPickUserdataTar,
                    onClearUserdataTar
                )
            }
        }

        YASCard(title = "Execution") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !state.isBusy) { onToggleOdinAutoReboot(!state.odinAutoReboot) },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                YASCheck(state.odinAutoReboot, onToggleOdinAutoReboot, enabled = !state.isBusy)
                YText("Auto-reboot after the operation", size = 12.sp, color = Yas.TextSoft)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                YASButton(
                    text = "Flash userdata only",
                    onClick = onFlashUserdataOnly,
                    modifier = Modifier.weight(1f),
                    style = BtnStyle.Secondary,
                    enabled = !state.isBusy && state.userdataTarUri != null,
                    large = true
                )
                YASButton(
                    text = "Odin Flash",
                    onClick = onRunOdinFlash,
                    modifier = Modifier.weight(1.4f),
                    style = BtnStyle.Primary,
                    enabled = !state.isBusy && anySlot,
                    large = true
                )
            }
            Hint("Download Mode: Odin Flash writes the selected slots in order. Flash userdata only writes the userdata slot.")
        }
    }
}

// =====================================================================================
//  Tab 5 : MTP / ADB utilities
// =====================================================================================

@Composable
private fun InfoGrid(text: String) {
    val items = remember(text) { parseInfoBlocks(text) }
    SelectionContainer {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Yas.Bg, S6)
                .border(1.dp, Yas.Border, S6)
        ) {
            items.forEachIndexed { i, item ->
                when (item) {
                    is InfoItem.Section -> YText(
                        text = item.title.uppercase(),
                        size = 10.sp,
                        color = Yas.Muted,
                        weight = FontWeight.Bold,
                        spacing = 1.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Yas.SurfaceHi)
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                    is InfoItem.Field -> Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(if (i % 2 == 0) Color.Transparent else Yas.Surface)
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        YText(item.key, size = 11.sp, mono = true, color = Yas.Muted, modifier = Modifier.width(120.dp))
                        YText(item.value.ifBlank { "\u2014" }, size = 11.sp, mono = true, modifier = Modifier.weight(1f))
                    }
                    is InfoItem.Note -> YText(
                        text = item.text,
                        size = 11.sp,
                        color = Yas.TextSoft,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun InfoGridCard(title: String, subtitle: String, text: String) {
    val context = LocalContext.current
    YASCard(
        title = title,
        subtitle = subtitle.ifBlank { null },
        trailing = {
            YASButton(
                "Copy all",
                { copyToClipboard(context, title, text) },
                style = BtnStyle.Ghost,
                compact = true
            )
        }
    ) {
        InfoGrid(text)
    }
}

@Composable
private fun AdbMtpTab(
    state: EdlUiState,
    onReadMtpInfo: () -> Unit,
    onMtpRebootAt: () -> Unit,
    onMtpDownloadAt: () -> Unit,
    onReadAdbInfo: () -> Unit,
    onAdbReboot: (String, String) -> Unit,
    onAdbShell: (String) -> Unit
) {
    val context = LocalContext.current
    var shellCmd by remember { mutableStateOf("") }
    val sendShell: () -> Unit = {
        if (!state.isBusy && shellCmd.isNotBlank()) onAdbShell(shellCmd)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        YASCard(title = "Quick functions") {
            SectionLabel("MTP  \u00B7  MODEM PORT")
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                YASButton("Read Info", onReadMtpInfo, Modifier.weight(1f), style = BtnStyle.Primary, enabled = !state.isBusy)
                YASButton("Download Mode", onMtpDownloadAt, Modifier.weight(1f), style = BtnStyle.Secondary, enabled = !state.isBusy)
                YASButton("Reboot", onMtpRebootAt, Modifier.weight(1f), style = BtnStyle.Warning, enabled = !state.isBusy)
            }
            Hint("MTP (no USB debugging): Read Info reads the device info including SN / IMEI. Download Mode and Reboot go through the modem port.")
            YDivider()
            SectionLabel("ADB  \u00B7  USB debugging")
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                YASButton("Read Info", onReadAdbInfo, Modifier.weight(1f), style = BtnStyle.Primary, enabled = !state.isBusy)
                YASButton(
                    "Download",
                    { onAdbReboot("download", "Download Mode") },
                    Modifier.weight(1f),
                    style = BtnStyle.Secondary,
                    enabled = !state.isBusy
                )
                YASButton(
                    "Recovery",
                    { onAdbReboot("recovery", "Recovery") },
                    Modifier.weight(1f),
                    style = BtnStyle.Secondary,
                    enabled = !state.isBusy
                )
                YASButton(
                    "Reboot",
                    { onAdbReboot("", "Normal Reboot") },
                    Modifier.weight(1f),
                    style = BtnStyle.Warning,
                    enabled = !state.isBusy
                )
            }
            Hint("ADB (USB debugging on): Read Info reads the device info including SN / IMEI. Download and Recovery reboot into those modes.")
        }

        YASCard(
            title = "ADB Shell",
            trailing = {
                StatusBadge(
                    text = if (state.adbConnected) "ADB" else "IDLE",
                    color = if (state.adbConnected) Yas.Success else Yas.Idle,
                    pulsing = state.adbConnected
                )
            }
        ) {
            val outScroll = rememberScrollState()
            LaunchedEffect(state.adbShellOutput) { outScroll.animateScrollTo(outScroll.maxValue) }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp, max = 260.dp)
                    .background(Yas.Terminal, S6)
                    .border(1.dp, Yas.Border, S6)
            ) {
                SelectionContainer {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(outScroll)
                            .padding(8.dp)
                    ) {
                        if (state.adbShellOutput.isBlank()) {
                            YText("Output appears here", size = 11.sp, mono = true, color = Yas.Muted)
                        } else {
                            state.adbShellOutput.lines().forEach { l ->
                                YText(
                                    text = l,
                                    size = 11.sp,
                                    mono = true,
                                    color = if (l.startsWith("\$ ")) Yas.SkyText else Yas.TextSoft
                                )
                            }
                        }
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                YASTextField(
                    value = shellCmd,
                    onValueChange = { shellCmd = it },
                    placeholder = "Command",
                    modifier = Modifier.weight(1f),
                    mono = true,
                    leading = "\$",
                    imeAction = ImeAction.Send,
                    onSubmit = sendShell
                )
                YASButton("Send", sendShell, style = BtnStyle.Primary, enabled = !state.isBusy && shellCmd.isNotBlank())
                YASButton(
                    "Copy",
                    { copyToClipboard(context, "ADB output", state.adbShellOutput) },
                    style = BtnStyle.Secondary,
                    enabled = state.adbShellOutput.isNotBlank()
                )
            }
        }

        if (state.mtpInfoText.isNotBlank()) {
            InfoGridCard("MTP info", "", state.mtpInfoText)
        }
        if (state.adbInfoText.isNotBlank()) {
            InfoGridCard("ADB info", "", state.adbInfoText)
        }
    }
}
