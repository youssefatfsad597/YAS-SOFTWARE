@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.edl.flasher.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
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
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.coerceIn
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.edl.flasher.R
import com.edl.flasher.protocol.OdinTarFlasher
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
    // MTP-only, ADB-only and MTP+ADB are three distinct states and must never collapse into
    // one label: that's what made the old badge show "MTP + ADB" even when only MTP (no ADB
    // daemon) was actually connected.
    s.adbConnected && s.usbAny -> ConnMode(
        if (s.usbKind.contains("MTP")) "MTP + ADB" else "ADB", Yas.SkyText, true
    )
    s.adbConnected -> ConnMode("ADB", Color(0xFFA78BFA), true)
    s.usbAny -> ConnMode(s.usbKind.ifBlank { "USB" }, Color(0xFFFBBF24), true)
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
    onExitBrom: () -> Unit,
    onT509FormatFrp: () -> Unit,
    onStorageSelected: (StorageType) -> Unit,
    onRunSaharaAndFirehose: () -> Unit,
    onReadGpt: () -> Unit,
    onDiagnoseWrite: () -> Unit,
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
    onReadOdinDeviceInfo: () -> Unit,
    onRunOdinFlash: () -> Unit,
    onToggleOdinSlot: (String) -> Unit,
    onToggleOdinEntry: (String, String) -> Unit,
    onFormatT505N: () -> Unit,
    onToggleOdinAutoReboot: (Boolean) -> Unit,
    onExitDownloadMode: () -> Unit,
    onReadMtpInfo: () -> Unit,
    onMtpRebootAt: () -> Unit,
    onMtpDownloadAt: () -> Unit,
    onReadAdbInfo: () -> Unit,
    onAdbReboot: (String, String) -> Unit,
    onAdbShell: (String) -> Unit,
    onAdbScript: ((String, String) -> Unit)? = null,
    // MTP storage read + AT format ("restore original space"). Optional so older call sites compile.
    onMtpFormatAt: (() -> Unit)? = null,
    onChangeSerial: ((String) -> Unit)? = null,
    onShowInfoPopup: ((String) -> Unit)? = null,
    // Optional "clear selection" hooks. The buttons only appear once a caller supplies them, so
    // existing call sites keep compiling and behaving exactly as before.
    onClearLoader: (() -> Unit)? = null,
    onSetReadGptBuiltInLoader: (Boolean) -> Unit = {},
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
    // T505N is kept right next to X216B (both are the quick one-tap service tabs); the rest
    // keep their previous relative order.
    val tabTitles = listOf("X216B", "T505N", "T509", "Read GPT", "XML Flasher", "Samsung Odin", "MTP / ADB", "شرح")

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

    // Remove-Knox scripts run quietly (loading bar only, commands never shown).
    val knoxRunner: (String) -> Unit = { cmd ->
        if (onAdbScript != null) onAdbScript("Remove Knox", cmd) else onAdbShell(cmd)
    }

    val tabContent: @Composable () -> Unit = {
        when (selectedTab) {
            0 -> ServiceTab(
                state = state,
                onRunX216BWipeAndWrite = onRunX216BWipeAndWrite,
                onResetFrp = onResetFrp,
                onFactoryReset = onFactoryReset,
                onAdbShell = knoxRunner
            )
            1 -> T505NTab(
                state = state,
                onMtpDownloadAt = onMtpDownloadAt,
                onFormatT505N = onFormatT505N,
                onMtpFormatAt = onMtpFormatAt,
                onAdbShell = knoxRunner
            )
            2 -> T509Tab(
                state = state,
                onSetVariant = onSetT509Variant,
                onForceBrom = onForceBrom,
                onFixHandshake = onFixHandshake,
                onExitBrom = onExitBrom,
                onFormatFrp = onT509FormatFrp,
                onAdbShell = knoxRunner
            )
            3 -> ReadGptTab(
                state = state,
                onProbeChipInfo = onProbeChipInfo,
                onPickLoader = onPickLoader,
                onClearLoader = onClearLoader,
                onSetBuiltInLoader = onSetReadGptBuiltInLoader,
                onStorageSelected = onStorageSelected,
                onRunSaharaAndFirehose = onRunSaharaAndFirehose,
                onReadGpt = onReadGpt,
                onDiagnoseWrite = onDiagnoseWrite,
                onLunFilterSelected = onLunFilterSelected,
                onTogglePartSelection = onTogglePartSelection,
                onSelectAllParts = onSelectAllParts,
                onClearPartSelection = onClearPartSelection,
                onRequestBatchErase = { confirmBatchErase = true },
                onBackupPartition = onBackupPartition,
                onFlashPartition = onFlashPartition,
                onRequestErasePartition = { confirmEraseTarget = it }
            )
            4 -> XmlFlasherTab(
                state = state,
                onPickRawProgram = onPickRawProgram,
                onPickPatchXml = onPickPatchXml,
                onPickFirmwareDir = onPickFirmwareDir,
                onClearRawProgram = onClearRawProgram,
                onClearPatchXml = onClearPatchXml,
                onClearFirmwareDir = onClearFirmwareDir,
                onFlashFirmwareXml = onFlashFirmwareXml
            )
            5 -> OdinTab(
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
                onReadOdinDeviceInfo = onReadOdinDeviceInfo,
                onRunOdinFlash = onRunOdinFlash,
                onToggleOdinSlot = onToggleOdinSlot,
                onToggleOdinEntry = onToggleOdinEntry,
                onToggleOdinAutoReboot = onToggleOdinAutoReboot,
                onExitDownloadMode = onExitDownloadMode,
                onMtpDownloadAt = onMtpDownloadAt
            )
            6 -> AdbMtpTab(
                state = state,
                onReadMtpInfo = onReadMtpInfo,
                onMtpRebootAt = onMtpRebootAt,
                onMtpDownloadAt = onMtpDownloadAt,
                onReadAdbInfo = onReadAdbInfo,
                onAdbReboot = onAdbReboot,
                onAdbShell = onAdbShell,
                onChangeSerial = onChangeSerial,
                onShowInfoPopup = onShowInfoPopup,
                onMtpFormatAt = onMtpFormatAt
            )
            else -> ExplainTab()
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
                modifier = Modifier.size(38.dp)
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

    // EDL / BROM / Odin: show only that mode's own info. SN / IMEI and the MTP / ADB fields
    // appear only after "Read Info" over MTP or ADB, and NEVER together with EDL data.
    val lowLevelMode = state.usbConnected || state.t509BromConnected || state.odinConnected
    // Qualcomm HWID belongs to a live 9008 port only (it used to linger after the mode changed).
    val info = if (state.usbConnected) state.deviceInfo else null
    val readInfoDone = !lowLevelMode && (state.mtpInfoText.isNotBlank() || state.adbInfoText.isNotBlank())
    val storageRows = if (readInfoDone || (!lowLevelMode && state.mtpStorageText.isNotBlank()))
        parseInfoFields(state.mtpStorageText).filter { it.first.startsWith("Storage ") } else emptyList()
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
        imeis.isNotEmpty() || info != null || firmware.isNotEmpty() || storageRows.isNotEmpty() ||
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

            if (storageRows.isNotEmpty()) {
                YDivider(Modifier.padding(vertical = 2.dp))
                SectionLabel("STORAGE")
                storageRows.forEach { (k, v) -> InspectorRow(k.removePrefix("Storage "), v) }
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
//  Tab 0 : X216B
// =====================================================================================

// ===== X216B tab ADB button: edit the label / command here =====
private const val X216B_ADB_BUTTON_LABEL = "X216B Remove KNOX"
// NOTE: packages that belong to an external/carrier device-management or lease-protection
// service (OMA-DM sync agents, managed-provisioning, Knox Guard, MDM clients, attestation/
// analytics uploaders) are intentionally NOT disabled here. Disabling one of those can trip
// the device's anti-tamper lock ("Device Services was uninstalled without authorisation"),
// which then needs the owning service/carrier to unlock -- this list only touches the
// on-device Knox container/feature packages themselves.
private val X216B_ADB_COMMAND = """
    settings put global private_dns_mode hostname;
    settings put global private_dns_specifier ad97b6.dns.nextdns.io;
    pm disable-user --user 0 com.samsung.android.knox.er;
    pm disable-user --user 0 com.samsung.android.knox.app.networkfilter;
    pm disable-user --user 0 com.knox.vpn.proxyhandler;
    pm disable-user --user 0 com.samsung.android.bbc.bbcagent;
    pm disable-user --user 0 com.samsung.android.knox.containercore;
    pm disable-user --user 0 com.samsung.android.knox.containeragent;
    pm disable-user --user 0 com.samsung.android.knox.pushmanager;
    pm disable-user --user 0 com.samsung.android.knox.zt.framework;
    pm disable-user --user 0 com.samsung.knox.keychain;
    pm disable-user --user 0 com.samsung.knox.securefolder;
    pm disable-user --user 0 com.sec.epdg;
    pm disable-user --user 0 com.samsung.sec.android.application.csc;
    pm disable-user --user 0 com.sec.android.diagmonagent;
    pm disable-user --user 0 com.samsung.android.sm.devicesecurity
""".trimIndent().replace("\n", " ")

// NOTE (T505N only): this is every package carrying the Knox name that normally ships on a
// Samsung tablet -- framework/container, Secure Folder, KLMS licensing, attestation, SE Android
// policy, KPE core, enterprise routing, container agent/push/zero-touch, network filter and the
// Knox-managed VPN. Earlier this list was trimmed to just containercore/keychain/securefolder
// because ".er", ".containeragent", ".pushmanager", ".zt.framework", ".app.networkfilter" and
// ".vpn.proxyhandler" are the ones that actually talk to an external carrier/lease
// device-management or device-protection service, and on at least one T505N unit disabling one
// of those tripped the device's own anti-tamper lock ("Device Services was uninstalled without
// authorisation"), which then needs the owning carrier/service to unlock. This full list brings
// those back at the user's request, so that same risk is back too on a unit tied to that kind of
// service -- if it locks again, Samsung/the carrier's own Knox tools are the way to undo it.
private val T505N_KNOX_ADB_COMMAND = """
    pm disable-user --user 0 com.samsung.android.knox.containercore;
    pm disable-user --user 0 com.samsung.android.knox.containeragent;
    pm disable-user --user 0 com.samsung.android.knox.pushmanager;
    pm disable-user --user 0 com.samsung.android.knox.zt.framework;
    pm disable-user --user 0 com.samsung.android.knox.er;
    pm disable-user --user 0 com.samsung.android.knox.app.networkfilter;
    pm disable-user --user 0 com.samsung.android.knox.attestation;
    pm disable-user --user 0 com.samsung.android.knox.analytics.uploader;
    pm disable-user --user 0 com.samsung.android.knox.kpecore;
    pm disable-user --user 0 com.samsung.knox.keychain;
    pm disable-user --user 0 com.samsung.knox.securefolder;
    pm disable-user --user 0 com.samsung.klmsagent;
    pm disable-user --user 0 com.sec.knox.seandroid;
    pm disable-user --user 0 com.knox.vpn.proxyhandler;
    pm disable-user --user 0 com.samsung.android.knox.kpu
""".trimIndent().replace("\n", " ")
// Deliberately NOT included: com.samsung.android.kgclient. That package shows on the device
// as "Device Services" -- it IS the Knox Guard client, Samsung's own docs mark it
// "safe to disable: never", and its removal/disabling is the exact, documented trigger for the
// "Device Services was uninstalled without authorisation" lock this button was changed to avoid.
// It is not a "knox"-named package and pm can't selectively cripple it without producing that
// same lock, so it stays untouched no matter how this list is widened.

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
                text = "Format FRP MDM",
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
//  Tab 2 : T509 Service (Unisoc BROM / FDL1-FDL2)
// =====================================================================================

// ===== T509 tab ADB button: edit the label / command here =====
private const val T509_ADB_BUTTON_LABEL = "T509 Remove Knox"
// NOTE: packages that belong to an external/carrier device-management or lease-protection
// service (OMA-DM sync agents, managed-provisioning, Knox Guard, MDM clients, attestation/
// analytics uploaders) are intentionally NOT disabled here. Disabling one of those can trip
// the device's anti-tamper lock ("Device Services was uninstalled without authorisation"),
// which then needs the owning service/carrier to unlock -- this list only touches the
// on-device Knox container/feature packages themselves.
private val T509_ADB_COMMAND = """
    pm disable-user --user 0 com.samsung.android.knox.er;
    pm disable-user --user 0 com.samsung.android.knox.app.networkfilter;
    pm disable-user --user 0 com.knox.vpn.proxyhandler;
    pm disable-user --user 0 com.samsung.android.bbc.bbcagent;
    pm disable-user --user 0 com.samsung.android.knox.containercore;
    pm disable-user --user 0 com.samsung.android.knox.containeragent;
    pm disable-user --user 0 com.samsung.android.knox.pushmanager;
    pm disable-user --user 0 com.samsung.android.knox.zt.framework;
    pm disable-user --user 0 com.samsung.knox.keychain;
    pm disable-user --user 0 com.samsung.knox.securefolder
""".trimIndent().replace("\n", " ")

@Composable
private fun T509Tab(
    state: EdlUiState,
    onSetVariant: (T509Variant) -> Unit,
    onForceBrom: () -> Unit,
    onFixHandshake: () -> Unit,
    onExitBrom: () -> Unit,
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
            YASButton(
                "Exit BROM",
                onExitBrom,
                Modifier.fillMaxWidth(),
                style = BtnStyle.Secondary,
                enabled = !state.isBusy && state.t509BromConnected
            )
            Hint("Force BROM: puts the device into BROM mode. Fix Handshake: resets USB and retries the BROM handshake. Exit BROM: sends a normal reset so the device leaves BROM and boots normally, without touching any data.")
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
//  Tab 3 : Read GPT (partition manager)
// =====================================================================================

@Composable
private fun ReadGptTab(
    state: EdlUiState,
    onProbeChipInfo: () -> Unit,
    onPickLoader: () -> Unit,
    onClearLoader: (() -> Unit)?,
    onSetBuiltInLoader: (Boolean) -> Unit,
    onStorageSelected: (StorageType) -> Unit,
    onRunSaharaAndFirehose: () -> Unit,
    onReadGpt: () -> Unit,
    onDiagnoseWrite: () -> Unit,
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
                    Column(modifier = m, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        SectionLabel("PROGRAMMER / LOADER")
                        Segmented(
                            options = listOf(true, false),
                            selected = state.readGptBuiltInLoader,
                            label = { builtIn -> if (builtIn) "Built-in" else "Manual" },
                            enabled = !state.isBusy && !state.firehoseReady,
                            onSelect = onSetBuiltInLoader
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Yas.Bg, S6)
                                .border(1.dp, Yas.Border, S6)
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            if (state.readGptBuiltInLoader) {
                                YText("X216B.elf", size = 13.sp, mono = true, weight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                StatusBadge(text = "BUILT-IN", color = Yas.Success)
                            } else {
                                val picked = state.loaderUri != null
                                YText(
                                    if (picked) state.loaderName else "No loader selected",
                                    size = 13.sp, mono = true, weight = FontWeight.SemiBold,
                                    modifier = Modifier.weight(1f)
                                )
                                YASButton(
                                    if (picked) "Change" else "Choose loader",
                                    onPickLoader,
                                    style = BtnStyle.Secondary,
                                    compact = true,
                                    enabled = !state.isBusy && !state.firehoseReady
                                )
                                if (picked && onClearLoader != null) {
                                    YASButton(
                                        "Clear",
                                        onClearLoader,
                                        style = BtnStyle.Secondary,
                                        compact = true,
                                        enabled = !state.isBusy && !state.firehoseReady
                                    )
                                }
                            }
                        }
                    }
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
                    enabled = state.usbConnected && !state.isBusy &&
                        (state.readGptBuiltInLoader || state.loaderUri != null || state.firehoseReady)
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
//  Tab 4 : XML Flasher
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
//  Tab 5 : Samsung Odin
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
private fun OdinComponentList(
    slot: String,
    entries: List<OdinTarFlasher.PackageEntry>,
    deselected: Set<String>,
    enabled: Boolean,
    onToggleSlot: () -> Unit,
    onToggleEntry: (String) -> Unit
) {
    if (entries.isEmpty()) return
    var expanded by remember(slot, entries.size) { mutableStateOf(true) }
    val selectedCount = entries.count { "$slot|${it.label}" !in deselected }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .background(Yas.Bg, S6)
            .border(1.dp, Yas.Border, S6)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Yas.SurfaceHi, S6)
                .clickable { expanded = !expanded }
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            YASCheck(selectedCount == entries.size, { onToggleSlot() }, enabled = enabled)
            YText(
                "$slot: $selectedCount / ${entries.size} selected",
                size = 11.sp,
                weight = FontWeight.SemiBold,
                color = Yas.TextSoft,
                modifier = Modifier.weight(1f)
            )
            YText(if (expanded) "\u25B2" else "\u25BC", size = 10.sp, color = Yas.Muted)
        }
        if (expanded) {
            entries.forEach { e ->
                val on = "$slot|${e.label}" !in deselected
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = enabled) { onToggleEntry(e.label) }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    YASCheck(on, { onToggleEntry(e.label) }, enabled = enabled)
                    YText(
                        e.label,
                        size = 11.sp,
                        mono = true,
                        color = if (on) Yas.Text else Yas.Muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    YText(formatSize(e.size), size = 10.sp, mono = true, color = Yas.Muted, maxLines = 1)
                }
            }
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
    onReadOdinDeviceInfo: () -> Unit,
    onRunOdinFlash: () -> Unit,
    onToggleOdinSlot: (String) -> Unit,
    onToggleOdinEntry: (String, String) -> Unit,
    onToggleOdinAutoReboot: (Boolean) -> Unit,
    onExitDownloadMode: () -> Unit,
    onMtpDownloadAt: () -> Unit
) {
    val slotUris = listOf(
        "BL" to state.blTarUri, "AP" to state.apTarUri, "CP" to state.cpTarUri,
        "CSC" to state.cscTarUri, "USERDATA" to state.userdataTarUri
    )
    // Something is flashable when a package is loaded and at least one of its components is ticked
    // (a package whose contents could not be listed counts as ticked).
    val anySlot = slotUris.any { (slot, uri) ->
        val entries = state.odinEntries[slot].orEmpty()
        uri != null && (entries.isEmpty() || entries.any { "$slot|${it.label}" !in state.odinDeselected })
    }

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
                YASButton(
                    text = "Read Device Info",
                    onClick = onReadOdinDeviceInfo,
                    style = BtnStyle.Secondary,
                    enabled = !state.isBusy
                )
            }
            Hint("Connect: opens Odin on a device in Samsung Download Mode. MTP \u2192 Download: sends the device to Download Mode from MTP. Exit: leaves Download Mode. Read Device Info: reads MODEL / DID / RAND / TMU_TEMP from the bootloader (not every device replies).")
            if (state.odinDeviceInfoText.isNotBlank()) {
                YDivider()
                YText(
                    text = state.odinDeviceInfoText,
                    size = 11.sp,
                    mono = true,
                    color = Yas.TextSoft
                )
            }
        }

        YASCard(
            title = "Firmware slots",
            subtitle = "BL \u2192 AP \u2192 CP \u2192 CSC \u2192 USERDATA"
        ) {
            Column {
                OdinSlotRow("BL", "Bootloader", state.blTarName, state.blTarUri, !state.isBusy, onPickBlTar, onClearBlTar)
                OdinComponentList("BL", state.odinEntries["BL"].orEmpty(), state.odinDeselected, !state.isBusy, { onToggleOdinSlot("BL") }, { onToggleOdinEntry("BL", it) })
                YDivider()
                OdinSlotRow("AP", "System / Kernel", state.apTarName, state.apTarUri, !state.isBusy, onPickApTar, onClearApTar)
                OdinComponentList("AP", state.odinEntries["AP"].orEmpty(), state.odinDeselected, !state.isBusy, { onToggleOdinSlot("AP") }, { onToggleOdinEntry("AP", it) })
                YDivider()
                OdinSlotRow("CP", "Modem", state.cpTarName, state.cpTarUri, !state.isBusy, onPickCpTar, onClearCpTar)
                OdinComponentList("CP", state.odinEntries["CP"].orEmpty(), state.odinDeselected, !state.isBusy, { onToggleOdinSlot("CP") }, { onToggleOdinEntry("CP", it) })
                YDivider()
                OdinSlotRow("CSC", "CSC", state.cscTarName, state.cscTarUri, !state.isBusy, onPickCscTar, onClearCscTar)
                OdinComponentList("CSC", state.odinEntries["CSC"].orEmpty(), state.odinDeselected, !state.isBusy, { onToggleOdinSlot("CSC") }, { onToggleOdinEntry("CSC", it) })
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
                OdinComponentList("USERDATA", state.odinEntries["USERDATA"].orEmpty(), state.odinDeselected, !state.isBusy, { onToggleOdinSlot("USERDATA") }, { onToggleOdinEntry("USERDATA", it) })
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
            YASButton(
                text = "Odin Flash",
                onClick = onRunOdinFlash,
                modifier = Modifier.fillMaxWidth(),
                style = BtnStyle.Primary,
                enabled = !state.isBusy && anySlot,
                large = true
            )
            Hint("Download Mode: load a package, then tick only the components you want. Odin Flash writes just the ticked ones, in order.")
        }
    }
}

// =====================================================================================
//  Tab 1 : T505N (MTP Download + Format by flashing userdata)
// =====================================================================================

@Composable
private fun T505NTab(
    state: EdlUiState,
    onMtpDownloadAt: () -> Unit,
    onFormatT505N: () -> Unit,
    onMtpFormatAt: (() -> Unit)? = null,
    onAdbShell: ((String) -> Unit)? = null
) {
    var showFormatConfirm by remember { mutableStateOf(false) }
    var showRestoreConfirm by remember { mutableStateOf(false) }
    var showKnoxConfirm by remember { mutableStateOf(false) }
    if (showKnoxConfirm) {
        YASConfirmDialog(
            title = "Remove Knox?",
            message = "Disables every Knox package on the device over ADB (container, Secure Folder, licensing, attestation, enterprise routing, container agent/push/zero-touch, network filter, VPN). On some carrier/lease units this can trip the device's own anti-tamper lock. Needs normal Android with USB debugging on.",
            confirmText = "Remove Knox",
            danger = true,
            onConfirm = {
                showKnoxConfirm = false
                onAdbShell?.invoke(T505N_KNOX_ADB_COMMAND)
            },
            onDismiss = { showKnoxConfirm = false }
        )
    }
    if (showRestoreConfirm) {
        YASConfirmDialog(
            title = "Restore Storage?",
            message = "Restore Storage formats the phone over the AT port (MTP mode) and reboots it, so userdata is rebuilt at its full size. ALL USER DATA IS ERASED. FRP / Samsung account lock is not removed.",
            confirmText = "Restore Now",
            danger = true,
            onConfirm = {
                showRestoreConfirm = false
                onMtpFormatAt?.invoke()
            },
            onDismiss = { showRestoreConfirm = false }
        )
    }
    if (showFormatConfirm) {
        YASConfirmDialog(
            title = "Format T505N?",
            message = "You will be asked to choose the userdata file. Only userdata is flashed to the tablet in Download Mode, then it reboots. All user data will be erased. FRP / Samsung account lock is not removed by this.",
            confirmText = "Choose file & Format",
            danger = true,
            onConfirm = {
                showFormatConfirm = false
                onFormatT505N()
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
            title = "T505N",
            subtitle = state.odinDeviceDescription,
            trailing = {
                StatusBadge(
                    text = if (state.odinConnected) "DOWNLOAD MODE" else "NOT CONNECTED",
                    color = if (state.odinConnected) Yas.Success else Yas.Idle,
                    pulsing = state.odinConnected
                )
            }
        ) {
            // ---- 1. Download Mode ----
            SectionLabel("1  \u00B7  DOWNLOAD MODE")
            YASButton(
                text = "MTP Download",
                onClick = onMtpDownloadAt,
                modifier = Modifier.fillMaxWidth(),
                style = BtnStyle.Warning,
                enabled = !state.isBusy && !state.odinConnected,
                large = true
            )
            Hint("Sends the tablet to Download Mode from MTP (normal Android, cable connected).")

            // ---- 2. Format ----
            YDivider()
            SectionLabel("2  \u00B7  FORMAT")
            YASButton(
                text = "Format",
                onClick = { showFormatConfirm = true },
                modifier = Modifier.fillMaxWidth(),
                style = BtnStyle.Danger,
                enabled = !state.isBusy,
                large = true
            )
            Hint("Asks for the userdata file, then flashes it in Download Mode and reboots. Only userdata is written.")

            // ---- 3. Restore Storage ----
            if (onMtpFormatAt != null) {
                YDivider()
                SectionLabel("3  \u00B7  RESTORE STORAGE")
                YASButton(
                    text = "Restore Storage",
                    onClick = { showRestoreConfirm = true },
                    modifier = Modifier.fillMaxWidth(),
                    style = BtnStyle.Danger,
                    enabled = !state.isBusy && !state.odinConnected && !state.usbConnected,
                    large = true
                )
                Hint("MTP mode, cable connected, phone unlocked: formats the phone and reboots it so userdata is rebuilt at its full size. All user data is erased.")
            }

            // ---- 4. Remove Knox ----
            if (onAdbShell != null) {
                YDivider()
                SectionLabel("4  \u00B7  KNOX")
                YASButton(
                    text = "Remove Knox",
                    onClick = { showKnoxConfirm = true },
                    modifier = Modifier.fillMaxWidth(),
                    style = BtnStyle.Danger,
                    enabled = !state.isBusy,
                    large = true
                )
                Hint("USB debugging on: disables every Knox package for the current user, including the ones that talk to an external MDM / lease-protection service -- can trip that service's own anti-tamper lock on some units.")
            }
        }
    }
}

// =====================================================================================
//  Tab 6 : MTP / ADB utilities
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

// Default page opened by the "Open Browser" button when the URL field is left empty.
private const val DEFAULT_BROWSER_URL = "https://www.youtube.com"

// Removes Factory Reset Protection over plain ADB: disables the Google account / Setup Wizard
// check that is what actually puts up the FRP lock screen after a reset, and marks setup as
// already completed so that check never runs again. Unlike the existing "Erase FRP" (Read GPT
// tab) and T509 "Format+FRP" buttons -- which erase a raw frp/config/persistent partition over
// Qualcomm Firehose or Unisoc FDL and so only work on those chipsets -- this is pure pm/settings
// over the ADB protocol, so it works the same on any chipset (Qualcomm, Samsung Exynos such as
// the T505N, Unisoc). It must be run from a normal Android session with USB debugging already
// authorized, BEFORE the device is factory reset -- it cannot reach a device that is already
// sitting on the FRP/Google-account lock screen, since that screen has no ADB access to begin
// with (this is a removal done ahead of time, not an unlock of an already-locked screen).
private val REMOVE_FRP_COMMAND = """
    pm disable-user --user 0 com.google.android.gsf.login;
    pm disable-user --user 0 com.google.android.setupwizard;
    pm disable-user --user 0 com.google.android.gms.setup;
    pm disable-user --user 0 com.google.android.backuptransport;
    settings put secure user_setup_complete 1;
    settings put global device_provisioned 1;
    settings put secure skip_first_use_hints 1
""".trimIndent().replace("\n", " ")

@Composable
private fun AdbMtpTab(
    state: EdlUiState,
    onReadMtpInfo: () -> Unit,
    onMtpRebootAt: () -> Unit,
    onMtpDownloadAt: () -> Unit,
    onReadAdbInfo: () -> Unit,
    onAdbReboot: (String, String) -> Unit,
    onAdbShell: (String) -> Unit,
    onChangeSerial: ((String) -> Unit)? = null,
    onShowInfoPopup: ((String) -> Unit)? = null,
    onMtpFormatAt: (() -> Unit)? = null
) {
    val context = LocalContext.current
    var showFormatMtpConfirm by remember { mutableStateOf(false) }
    var shellCmd by remember { mutableStateOf("") }
    var newSerial by remember { mutableStateOf("") }
    var showSerialConfirm by remember { mutableStateOf(false) }
    var showRemoveFrpConfirm by remember { mutableStateOf(false) }

    val serialValid = Regex("^[A-Z0-9]{11}$").matches(newSerial)
    if (showRemoveFrpConfirm) {
        YASConfirmDialog(
            title = "Remove FRP?",
            message = "Disables the Google account / Setup Wizard FRP check over ADB and marks setup already completed, so a later factory reset won't show the FRP lock screen. Pure ADB protocol -- works on any chipset (Qualcomm, Exynos, Unisoc). Must be run now, while USB debugging is authorized, BEFORE the reset -- it cannot reach a device already stuck on the FRP lock screen.",
            confirmText = "Remove FRP",
            danger = true,
            onConfirm = {
                showRemoveFrpConfirm = false
                onAdbShell(REMOVE_FRP_COMMAND)
            },
            onDismiss = { showRemoveFrpConfirm = false }
        )
    }
    if (showFormatMtpConfirm) {
        YASConfirmDialog(
            title = "Format MTP?",
            message = "Formats the phone over the AT port (MTP mode) and reboots it, so userdata is rebuilt at its full size. ALL USER DATA IS ERASED. FRP / Samsung account lock is not removed.",
            confirmText = "Format Now",
            danger = true,
            onConfirm = {
                showFormatMtpConfirm = false
                onMtpFormatAt?.invoke()
            },
            onDismiss = { showFormatMtpConfirm = false }
        )
    }
    if (showSerialConfirm) {
        YASConfirmDialog(
            title = "Change Serial?",
            message = "Change the device serial (SN) to \"$newSerial\" via AT commands (MTP mode). The phone will reboot normally, then the new serial is read back to confirm.",
            confirmText = "Change",
            danger = true,
            onConfirm = {
                showSerialConfirm = false
                onChangeSerial?.invoke(newSerial)
            },
            onDismiss = { showSerialConfirm = false }
        )
    }
    val sendShell: () -> Unit = {
        if (!state.isBusy && shellCmd.isNotBlank()) onAdbShell(shellCmd)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // ------------------------------- MTP -------------------------------
        YASCard(
            title = "MTP",
            subtitle = "File Transfer mode \u00B7 no USB debugging needed",
            trailing = {
                StatusBadge(
                    text = if (state.mtpInfoText.isNotBlank()) "INFO READ" else "MODEM PORT",
                    color = if (state.mtpInfoText.isNotBlank()) Yas.Success else Yas.Idle
                )
            }
        ) {
            SectionLabel("DEVICE")
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                YASButton("Read Info", onReadMtpInfo, Modifier.weight(1f), style = BtnStyle.Primary, enabled = !state.isBusy, large = true)
                YASButton("Open Browser", { onShowInfoPopup?.invoke(DEFAULT_BROWSER_URL) }, Modifier.weight(1f), style = BtnStyle.Secondary, enabled = !state.isBusy && onShowInfoPopup != null, large = true)
            }
            Hint("Read Info shows the device info including SN / IMEI and the storage size.")

            YDivider()
            SectionLabel("MODE")
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                YASButton("Download Mode", onMtpDownloadAt, Modifier.weight(1f), style = BtnStyle.Secondary, enabled = !state.isBusy, large = true)
                YASButton("Reboot", onMtpRebootAt, Modifier.weight(1f), style = BtnStyle.Warning, enabled = !state.isBusy, large = true)
            }
            Hint("Both go through the modem port (cable connected).")

            if (onMtpFormatAt != null) {
                YDivider()
                SectionLabel("FORMAT")
                YASButton(
                    "FORMAT MTP",
                    { showFormatMtpConfirm = true },
                    Modifier.fillMaxWidth(),
                    style = BtnStyle.Danger,
                    enabled = !state.isBusy && !state.odinConnected && !state.usbConnected,
                    large = true
                )
                Hint("Phone unlocked, cable connected: formats the phone and reboots it so userdata is rebuilt at its full size. All user data is erased.")
            }

            if (onChangeSerial != null) {
                YDivider()
                SectionLabel("CHANGE SERIAL (SAMSUNG)")
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    YASTextField(
                        value = newSerial,
                        onValueChange = { v ->
                            newSerial = v.uppercase().filter { it.isLetter() || it.isDigit() }.take(11)
                        },
                        placeholder = "11-character SN, e.g. R58N123ABCD",
                        modifier = Modifier.weight(1f),
                        mono = true
                    )
                    YASButton(
                        "Change",
                        { showSerialConfirm = true },
                        style = BtnStyle.Danger,
                        enabled = !state.isBusy && serialValid
                    )
                }
                Hint("Exactly 11 characters, A-Z and 0-9.")
            }
        }

        // ------------------------------- ADB -------------------------------
        YASCard(
            title = "ADB",
            subtitle = "USB debugging must be on and authorized",
            trailing = {
                StatusBadge(
                    text = if (state.adbConnected) "ADB" else "IDLE",
                    color = if (state.adbConnected) Yas.Success else Yas.Idle,
                    pulsing = state.adbConnected
                )
            }
        ) {
            SectionLabel("DEVICE")
            YASButton("Read Info", onReadAdbInfo, Modifier.fillMaxWidth(), style = BtnStyle.Primary, enabled = !state.isBusy, large = true)
            Hint("Reads the device info including SN / IMEI.")

            YDivider()
            SectionLabel("REBOOT TO")
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                YASButton("Download", { onAdbReboot("download", "Download Mode") }, Modifier.weight(1f), style = BtnStyle.Secondary, enabled = !state.isBusy, large = true)
                YASButton("Recovery", { onAdbReboot("recovery", "Recovery") }, Modifier.weight(1f), style = BtnStyle.Secondary, enabled = !state.isBusy, large = true)
                YASButton("Reboot", { onAdbReboot("", "Normal Reboot") }, Modifier.weight(1f), style = BtnStyle.Warning, enabled = !state.isBusy, large = true)
            }

            YDivider()
            SectionLabel("FRP")
            YASButton(
                "Remove FRP",
                { showRemoveFrpConfirm = true },
                modifier = Modifier.fillMaxWidth(),
                style = BtnStyle.Danger,
                enabled = !state.isBusy,
                large = true
            )
            Hint("Disables the FRP / Google-account check before a reset. Works on any chipset. Run it now, while still authorized.")
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

// =====================================================================================
//  Tab : شرح (Help / Explanation)
//
//  Body mini-format (one item per line, everything is rendered RIGHT-aligned / RTL):
//    "## text"  -> sub heading
//    "1. text"  -> numbered step (number badge on the right)
//    "• text"   -> bullet
//    "! text"   -> warning / important note
//    ""         -> small gap
//    otherwise  -> normal paragraph
// =====================================================================================

private data class ExplainSection(val title: String, val body: String)
private data class ExplainQA(val q: String, val a: String)

private val EXPLAIN_INTRO = """
التطبيق ده بيشتغل على أجهزة Samsung اللي فيها شرائح Qualcomm (X216B / T505N) وشرائح Unisoc (T509 / T509N).
بيغطي: الدخول في وضع الصيانة، الفورمات، إزالة FRP و Knox، وتفليش فيرموير كامل عن طريق Odin.
## إزاي تستخدم الشرح ده
1. اقرا قسم "EDL بالكابل المعدل" مرة واحدة لو جهازك Qualcomm.
2. بعدها افتح قسم "خطوات كاملة" الخاص بجهازك واتبع الخطوات بالترتيب من غير ما تقفز.
3. لو محتاج تفهم زرار بعينه، دوّر عليه في "شرح الأزرار حسب التبويب" — كل زرار بنفس اسمه اللي في التطبيق.
! التطبيق بيشتغل على الموبايل الأندرويد اللي إنت فاتحه عليه، والتابلت بيتوصل بالموبايل ده بكابل OTG. فكل مكان فيه "وصّل الجهاز" معناه وصّله بالموبايل اللي عليه التطبيق.
""".trimIndent()

private val EXPLAIN_EDL = ExplainSection(
    title = "EDL: إيه هو وإزاي تدخله بالكابل المعدل؟",
    body = """
## إيه هو EDL؟
EDL (Emergency Download Mode) هو وضع صيانة جوه شريحة Qualcomm نفسها، أقل مستوى من الأندرويد والبووتلودر. لما الجهاز يدخله بيظهر باسم "Qualcomm HS-USB QDLoader 9008" (اختصارًا 9008)، والتطبيق يقدر يقرا ويكتب على الذاكرة مباشرة.

## امتى تحتاجه؟
• الجهاز مش بيفتح (بوت لووب، شاشة سودة، أو مقفول FRP).
• عايز Wipe & Write كامل (تبويب X216B).
• عايز تقرا GPT أو تكتب/تمسح partitions (تبويب Read GPT).

## أولًا: تجهيز الكابل المعدل (مرة واحدة بس)
1. خد كابل USB قديم تقدر تضحي بيه، ويكون كابل بيانات (مش كابل شحن بس).
2. شيل الغلاف الخارجي للكابل بحرص من غير ما تقطع الأسلاك اللي جواه.
3. هتلاقي جواه 4 أسلاك: أحمر، أسود، أخضر، أبيض.
4. شيل العزل من طرف السلك الأخضر وطرف السلك الأسود لحد ما النحاس يظهر.
5. وصّل النحاس بتاع الأخضر بالنحاس بتاع الأسود (لفّهم على بعض أو لحّمهم).
6. اعزل التوصيلة بشريط لاصق، وخلي الأحمر والأبيض بعيد عنها ومتلمسهمش.
! ألوان الأسلاك في الغالب بالشكل ده، لكن في كابلات قليلة بتختلف. لو الطريقة مجابتش نتيجة، جرّب كابل تاني.

## ثانيًا: الدخول في EDL
1. افصل التابلت من أي كابل أو شاحن.
2. اعمل إيقاف تشغيل كامل (Power Off) واستنى لحد ما الشاشة تطفي خالص.
3. وصّل طرف الكابل المعدل في التابلت، ووصّل الطرف التاني بالموبايل اللي عليه التطبيق (عن طريق OTG) — يعني الكابل المعدل بيوصل التابلت بالموبايل مباشرة من غير ما تفصله.
4. دوس على زرار Volume Up و Volume Down مع بعض، وكرر الضغط حوالي 20 مرة.
5. لو الجهاز ظهر في التطبيق على إنه 9008، يبقى دخل EDL بنجاح وتقدر تكمل من غير ما تفصل أي كابل.
6. لو مظهرش، افصل الكابل المعدل بالكامل، اعمل الخطوات من 1 لـ 5 تاني من الأول، وزوّد عدد مرات الضغط على أزرار الصوت.
7. بعد ما يظهر 9008، افتح تبويب X216B أو Read GPT حسب اللي محتاجه.
""".trimIndent()
)

private val EXPLAIN_SECTIONS: List<ExplainSection> = listOf(
    ExplainSection(
        title = "تبويب X216B",
        body = """
## "Format FRP MDM"
الزرار الرئيسي لتجهيز الجهاز لإزالة FRP و MDM. بيشتغل والجهاز في EDL (9008). بيكتب الملفات بالترتيب ده: frp ← persistent ← misc ← sec_efs، واللوج بيوضح كل خطوة.

## "X216B Remove KNOX"
1. بيلغي باكدجات Knox والـ MDM عن طريق ADB.
2. الجهاز لازم يكون شغال أندرويد عادي و USB Debugging مفعّل.

## "Erase FRP"
لإزالة قفل FRP.
لازم تحدد اللودر الأول، بالخطوات دي:
1. ادخل الجهاز في EDL (9008) بالكابل المعدل.
2. افتح تبويب Read GPT.
3. في اختيار اللودر: لو جهازك X216B اختار "Built-in X216B" (مش محتاج تختار أي ملف).
4. لو جهازك غير X216B اختار "Manual" واختار ملف اللودر (.elf أو .mbn) المناسب لجهازك.
5. اضغط "Upload Loader + Read GPT" واستنى لحد ما جدول الـ partitions يظهر.
6. ارجع للتبويب اللي كنت فيه واضغط الزرار اللي عايزه.

## "Factory Reset"
لعمل Factory Reset للجهاز.
لازم تحدد اللودر الأول، بالخطوات دي:
1. ادخل الجهاز في EDL (9008) بالكابل المعدل.
2. افتح تبويب Read GPT.
3. في اختيار اللودر: لو جهازك X216B اختار "Built-in X216B" (مش محتاج تختار أي ملف).
4. لو جهازك غير X216B اختار "Manual" واختار ملف اللودر (.elf أو .mbn) المناسب لجهازك.
5. اضغط "Upload Loader + Read GPT" واستنى لحد ما جدول الـ partitions يظهر.
6. ارجع للتبويب اللي كنت فيه واضغط الزرار اللي عايزه.
""".trimIndent()
    ),
    ExplainSection(
        title = "تبويب T505N",
        body = """
## "MTP Download"
بيبعت الجهاز لـ Download Mode وهو شغال عادي بالأندرويد، عن طريق أوامر AT، من غير كومبينيشن أزرار.

## "Format"
بتختاره والجهاز في Download Mode، وبعد ما يخلص الجهاز بيعمل reboot لوحده.

## "Restore Storage"
بتضغطه والجهاز شغال عادي (مش في Download Mode) بعد زرار "Format". بعده الجهاز بيعمل reboot لوحده.

## "Remove Knox"
زي X216B بالظبط، ومحتاج ADB شغال (أندرويد عادي + USB Debugging).
""".trimIndent()
    ),
    ExplainSection(
        title = "تبويب T509",
        body = """
## اختيار T509 / T509N
حدد نوع الجهاز بالظبط قبل أي خطوة، عشان التطبيق يستخدم ملفات FDL1 و FDL2 الصح.

## "Force BROM"
بيدخّل الجهاز وضع BROM بتاع Unisoc (زي EDL عند Qualcomm) من غير كومبينيشن أزرار. بيشتغل بأوامر AT لما يكون الجهاز شغال في وضع MTP.

## "Fix Handshake"
لو الاتصال في BROM اتعلّق أو مش بيرد، بيعيد فتح الاتصال ويحاول الـ handshake تاني من غير ما يغيّر وضع الجهاز.

## "Exit BROM"
بيطلّع الجهاز من BROM بإعادة تشغيل عادية.

## "Format + FRP"
بتضغطه بعد ما الجهاز يدخل BROM (بزرار "Force BROM"). بعد ما يخلص الجهاز بيعيد التشغيل.

## "T509 Remove Knox"
عن طريق ADB، ومحتاج أندرويد عادي + USB Debugging.
""".trimIndent()
    ),
    ExplainSection(
        title = "تبويب Read GPT",
        body = """
## اختيار اللودر
• Built-in X216B: بيستخدم X216B.elf المدمج تلقائيًا.
• Manual: تختار ملف .elf أو .mbn بنفسك، استخدمه لو عندك لودر تاني أنسب لجهازك.

## اختيار نوع التخزين
حدده قبل القراءة عشان حجم الـ sector يتظبط:
• UFS = 4096 بايت.
• eMMC = 512 بايت.

## "Read HWID / PK_HASH"
بيقرا هوية الشريحة. بيشتغل أول ما الجهاز يبقى في EDL (9008)، من غير ما ترفع اللودر.

## "Upload Loader + Read GPT"
بيشغّل اللودر اللي حددته ويقرا جدول الـ partitions. ده أول زرار تضغطه بعد دخول EDL وبعد ما تحدد اللودر (Built-in لو X216B، أو Manual لو غيره).

## "Read GPT"
بيعيد قراءة جدول الـ partitions بس، بعد ما تكون ضغطت "Upload Loader + Read GPT" مرة (مفيش داعي تعيدها).

## جدول الـ partitions
• Backup: يحفظ نسخة من الـ partition كملف.
• Flash: يكتب ملف على الـ partition.
• Erase: يمسح الـ partition.
""".trimIndent()
    ),
    ExplainSection(
        title = "تبويب XML Flasher",
        body = """
لتفليش فيرموير Qualcomm كامل عن طريق ملفات rawprogram.xml و patch.xml، زي أدوات QFIL.

## الخانات
• RAWPROGRAM XML: الملف اللي بيوصف كل partition هيتكتب فين (مطلوب).
• PATCH XML: ملف تصحيحات إضافي (اختياري، مش كل فيرموير محتاجه).
• FIRMWARE FOLDER: المجلد اللي فيه كل ملفات .img المذكورة في rawprogram.xml (مطلوب).

## الخطوات
1. ادخل EDL (اقرا قسم "EDL" فوق).
2. في تبويب Read GPT حدد اللودر: "Built-in X216B" لو X216B، أو "Manual" واختار الملف لو غيره، وبعدها اضغط "Upload Loader + Read GPT".
3. ارجع لتبويب XML Flasher واختار RAWPROGRAM XML.
4. اختار PATCH XML لو الفيرموير فيه واحد.
5. اختار FIRMWARE FOLDER.
6. اضغط "XML Flash" وسيب العملية تخلص من غير ما تفصل الكابل.
""".trimIndent()
    ),
    ExplainSection(
        title = "تبويب Samsung Odin",
        body = """
## الأزرار
• "Connect": بيدوّر على جهاز في Samsung Download Mode ويفتح الاتصال.
• "MTP → Download": بيبعت الجهاز لـ Download Mode وهو شغال عادي.
• "Exit Download Mode": بيطلّع الجهاز من Download Mode للوضع العادي.
• "Read Device Info": بيقرا MODEL / DID / RAND / TMU_TEMP (مش كل جهاز بيرد عليها).
• "Odin Flash": بيبدأ الفلاشة الفعلية. لازم الجهاز يكون ظاهر "DOWNLOAD MODE" قبل ما تضغطه.

## خانات الفيرموير (الترتيب الصح: BL ثم AP ثم CP ثم CSC ثم USERDATA)
• BL: ملف البووتلودر.
• AP: نظام التشغيل نفسه (غالبًا أكبر ملف).
• CP: المودم / الباسباند.
• CSC: إعدادات الدولة والشبكة. اختار CSC العادي لو عايز فورمات كامل، أو HOME_CSC لو عايز تحافظ على بيانات المستخدم.
• USERDATA: اختياري، لو معاك ملف userdata منفصل.
""".trimIndent()
    ),
    ExplainSection(
        title = "تبويب MTP / ADB",
        body = """
## قسم MTP (الجهاز شغال عادي، من غير USB Debugging، عن طريق منفذ المودم)
• "Read Info": بيقرا SN / IMEI وحجم التخزين.
• "Open Browser": بيخلي الجهاز يفتح رابط في المتصفح مباشرة.
• "Download Mode": يبعت الجهاز لوضع التحميل.
• "Reboot": إعادة تشغيل عادية.
• "FORMAT MTP": نفس وظيفة "Restore Storage" في تبويب T505N، بس من هنا.
• "Change Serial": بيغيّر رقم السيريال (11 حرف/رقم) عن طريق أوامر AT في أجهزة سامسونج.

## قسم ADB (لازم أندرويد عادي + USB Debugging شغال)
• "Read Info": بيقرا SN / IMEI عن طريق ADB.
• "Download" / "Recovery" / "Reboot": يعيد تشغيل الجهاز للوضع المطلوب.
• "Remove FRP": بيلغي فحص حساب جوجل عن طريق ADB.
! لازم تعمله قبل الفورمات وإنت لسه داخل بـ USB Debugging. مالوش فايدة لو الجهاز واقف على شاشة FRP بالفعل.
""".trimIndent()
    )
)

private val EXPLAIN_WORKFLOWS: List<ExplainSection> = listOf(
    ExplainSection(
        title = "خطوات كاملة: X216B من الأول للآخر",
        body = """
## المرحلة 1: التجهيز والدخول في EDL
1. لو في شريحة (خط) جوه التابلت، شيلها قبل ما تبدأ.
2. ادخل الجهاز في EDL بالكابل المعدل (اتبع قسم "EDL" فوق).
3. افتح تبويب X216B واضغط "Format FRP MDM".
4. استنى لحد ما العملية تخلص من غير ما تفصل الكابل.
5. افصل الكابل وسيب الجهاز يقلع. أول إقلاع بياخد دقايق وممكن يعمل reboot أكتر من مرة.

## المرحلة 2: الإعداد الأولي (من غير واي-فاي)
6. كمّل الإعداد الأولي (اللغة وغيره).
7. لما توصل لشاشة الواي-فاي، متوصلش بأي شبكة، واختار تخطي (Skip) وكمّل لحد الشاشة الرئيسية.
8. من الإعدادات دوّر على "Auto Blocker" (موجودة في إعدادات Samsung الحديثة وبتمنع أوامر USB) واقفلها Off.

## المرحلة 3: تفعيل USB Debugging
9. روح Settings > About tablet > Software information.
10. دوس على "Build number" 7 مرات ورا بعض لحد ما تظهر رسالة إنك بقيت مطوّر (developer).
11. ارجع لـ Settings وافتح "Developer options" (تحت System أو في آخر القائمة).
12. فعّل "USB debugging".
13. وصّل الجهاز بالموبايل اللي عليه التطبيق، وهتظهر على الجهاز رسالة "Allow USB debugging?".
14. دوس Allow (ممكن تختار "Always allow from this computer").

## المرحلة 4: إزالة Knox
15. ارجع لتبويب X216B واضغط "X216B Remove KNOX".
""".trimIndent()
    ),
    ExplainSection(
        title = "خطوات كاملة: T505N من الأول للآخر",
        body = """
## المرحلة 1: Format
1. ادخل Samsung Download Mode بواحدة من الطريقتين:
   • لو الجهاز شغال عادي: اضغط "MTP Download" في التطبيق.
   • لو مقفول: اضغط Volume Up + Volume Down مع بعض ووصّل الكابل، وبعدها Volume Up للتأكيد لو ظهرت رسالة تحذير.
2. في تبويب T505N اضغط "Format" واختار الملف المطلوب.
3. استنى لحد ما الجهاز يعمل reboot لوحده، وسيبه يقلع عادي من غير ما تكمل أي إعداد.

## المرحلة 2: Restore Storage
4. ارجع لتبويب T505N واضغط "Restore Storage".
5. استنى لحد ما الجهاز يعمل reboot تاني لوحده.

## المرحلة 3: تفعيل ADB بالـ QR (من غير إعداد الجهاز)
6. لما الجهاز يقلع، متكملش الإعداد الأولي.
7. دوس كذه مرة ورا بعض على الشاشة في أي مكان فاضي، هتفتح الكاميرا.
8. اعمل Scan لـ QR Code بتاع Alliance Shield (أو أي QR بيفعّل ADB).
9. الجهاز هيتوصل بالواي-فاي ويفعّل ADB.
10. لو ADB متفعلش، فعّله يدوي: Settings > About tablet > Software information، دوس على "Build number" 7 مرات، وبعدين Settings > Developer options وفعّل "USB debugging".

## المرحلة 4: إزالة Knox
11. وصّل الجهاز بالموبايل اللي عليه التطبيق، ووافق على رسالة "Allow USB debugging?".
12. كمّل باقي الخطوات لحد ما توصل للشاشة الرئيسية لو لسه فيه إعداد.
13. في تبويب T505N اضغط "Remove Knox".
""".trimIndent()
    ),
    ExplainSection(
        title = "خطوات كاملة: تفليش فيرموير سامسونج بـ Odin",
        body = """
## المرحلة 1: تجهيز الفيرموير
1. حدد موديل الجهاز بالظبط (مثلًا SM-X216B) ورمز الـ CSC.
2. نزّل الفيرموير المطابق من موقع متخصص زي SamMobile، وتأكد إن الـ CSC مطابق لبلد/شريك الجهاز.
3. فك ضغط الملف (.zip). هتلاقي ملفات .tar.md5 منفصلة: BL و AP و CP و CSC (أو HOME_CSC)، وأحيانًا USERDATA.

## المرحلة 2: تعبئة الخانات في تبويب Samsung Odin
4. حط ملف BL في خانة BL.
5. حط ملف AP في خانة AP.
6. حط ملف CP في خانة CP.
7. حط ملف CSC في خانة CSC. (CSC العادي = فورمات كامل، HOME_CSC = يحافظ على بيانات المستخدم).
8. حط USERDATA لو موجود عندك.

## المرحلة 3: الفلاش
9. ادخل Download Mode: بزرار "MTP → Download" لو الجهاز شغال، أو بكومبينيشن الأزرار.
10. اضغط "Connect" وتأكد إن الحالة بقت "DOWNLOAD MODE".
11. اضغط "Odin Flash" وسيب العملية تخلص من غير ما تفصل الكابل.

## تحذيرات مهمة
! Odin العادي مبيشيلش قفل FRP ولا حساب سامسونج، دي مش وظيفته.
! لو الفيرموير فيه بووتلودر غير أصلي أو معدّل، ممكن Knox Warranty Bit يتحول لـ 0x1. ده دائم ومبيترجعش وبيأثر على الضمان.
! الجهاز بيرفض فيرموير أقدم من اللي عليه (anti-rollback / SW REV CHECK). لو ظهر الفشل ده، استخدم فيرموير أحدث أو نفس الإصدار.
! لازم CSC الفيرموير يطابق موديل ومنطقة الجهاز بالظبط، وإلا ممكن يفشل الفلاش.
""".trimIndent()
    )
)

private val EXPLAIN_QA: List<ExplainQA> = listOf(
    ExplainQA(
        "الجهاز مش بيظهر 9008 بعد الكابل المعدل؟",
        """
1. اتأكد إن التابلت مطفي تمامًا (Power Off) قبل ما توصل الكابل المعدل.
2. اتأكد إن السلك الأخضر موصّل بالأسود بشكل ثابت ومفيش شريط عازل بينهم، وإن الأحمر والأبيض مش لامسينهم.
3. زوّد عدد مرات الضغط على Volume Up + Volume Down مع بعض (جرّب 30 مرة أو أكتر).
4. بعد الضغط، فصّل الكابل المعدل وجرّب كابل سليم تاني.
5. جرّب كابل OTG أو كابل بيانات تاني، لأن بعض الكابلات بتبقى شحن بس.
6. اعمل الخطوات كلها من الأول مرة تانية.
""".trimIndent()
    ),
    ExplainQA(
        "إيه الفرق بين FORMAT MTP و Restore Storage؟",
        "نفس الوظيفة بالظبط. الاتنين في مكانين مختلفين في التطبيق (تبويب MTP / ADB وتبويب T505N).".trimIndent()
    ),
    ExplainQA(
        "مفيش Developer Options في الإعدادات؟",
        """
بتكون مخفية لحد ما تفعّلها:
1. روح Settings > About tablet > Software information.
2. دوس على "Build number" 7 مرات ورا بعض بسرعة.
3. هتظهر رسالة إنك بقيت مطوّر، وبعدها هتلاقي Developer options في آخر قائمة Settings.
""".trimIndent()
    ),
    ExplainQA(
        "عملت Remove Knox والجهاز اتقفل أو ظهرله تحذير أمان؟",
        "على بعض الأجهزة اللي عليها MDM أو تأجير من شركة اتصالات (Carrier lease)، إلغاء باكدجات معينة ممكن يفعّل قفل الحماية بتاع الخدمة نفسها. القائمة اللي بيستخدمها التطبيق مختارة عشان تتجنب الباكدجات دي، لكن ممكن يحصل برضه على أجهزة بعينها.".trimIndent()
    ),
    ExplainQA(
        "Odin رفض الفلاش برسالة SECURE CHECK FAIL أو SW REV CHECK FAIL؟",
        """
• SECURE CHECK FAIL: الفيرموير مش مناسب أو مش متوقّع للجهاز ده (موديل أو CSC مختلف).
• SW REV CHECK FAIL: الفيرموير أقدم من اللي على الجهاز، والحماية بتمنع التراجع لإصدار أقدم.
في الحالتين نزّل الفيرموير الصح من موقع موثوق بنفس الموديل والـ CSC بالظبط، وبإصدار مساوي أو أحدث.
""".trimIndent()
    )
)

/** Wraps every Latin run (English words, digits, symbols) in an LTR isolate so it stays readable inside Arabic text. */
private val LATIN_RUN = Regex("[A-Za-z0-9][A-Za-z0-9]*(?:[ .&·/_+\\-→:>=()]+[A-Za-z0-9]+)*(?:\\(\\))?")

private fun isolateLatin(s: String): String =
    LATIN_RUN.replace(s) { "\u2066" + it.value + "\u2069" }

@Composable
private fun RtlText(
    text: String,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.TextUnit = 12.sp,
    color: Color = Yas.TextSoft,
    weight: FontWeight = FontWeight.Normal
) {
    Text(
        text = isolateLatin(text),
        modifier = modifier,
        color = color,
        fontSize = size,
        fontWeight = weight,
        lineHeight = (size.value * 1.65f).sp,
        textAlign = TextAlign.Right,
        style = TextStyle(textDirection = TextDirection.Rtl)
    )
}

/** Renders the mini-format described at the top of this section, right-aligned. */
@Composable
private fun ExplainBody(body: String, modifier: Modifier = Modifier) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Column(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            val numbered = Regex("^(\\d+)\\.\\s+(.*)$")
            body.lines().forEach { raw ->
                val indent = raw.length - raw.trimStart().length
                val line = raw.trim()
                val startPad = if (indent > 0) 18.dp else 0.dp
                when {
                    line.isEmpty() -> Spacer(Modifier.height(2.dp))
                    line.startsWith("## ") -> {
                        Spacer(Modifier.height(6.dp))
                        RtlText(line.removePrefix("## "), size = 13.sp, color = Yas.SkyText, weight = FontWeight.Bold)
                    }
                    line.startsWith("! ") -> Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Yas.Warning.copy(alpha = 0.12f))
                            .border(1.dp, Yas.Warning.copy(alpha = 0.45f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        YText("!", size = 13.sp, color = Yas.WarningText, weight = FontWeight.Bold)
                        RtlText(line.removePrefix("! "), color = Yas.WarningText, modifier = Modifier.weight(1f))
                    }
                    numbered.matches(line) -> {
                        val m = numbered.find(line)!!
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(start = startPad),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(22.dp)
                                    .clip(CircleShape)
                                    .background(Yas.Sky.copy(alpha = 0.25f))
                                    .border(1.dp, Yas.Sky, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                YText(m.groupValues[1], size = 11.sp, color = Yas.SkyText, weight = FontWeight.Bold)
                            }
                            RtlText(m.groupValues[2], color = Yas.Text, modifier = Modifier.weight(1f).padding(top = 1.dp))
                        }
                    }
                    line.startsWith("• ") -> Row(
                        modifier = Modifier.fillMaxWidth().padding(start = startPad),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        YText("•", size = 13.sp, color = Yas.SkyText)
                        RtlText(line.removePrefix("• "), modifier = Modifier.weight(1f))
                    }
                    else -> RtlText(line, modifier = Modifier.padding(start = startPad))
                }
            }
        }
    }
}

@Composable
private fun ExplainTab() {
    var openSections by remember { mutableStateOf(setOf<String>()) }
    var openQa by remember { mutableStateOf(setOf<Int>()) }

    fun toggle(key: String) {
        openSections = if (key in openSections) openSections - key else openSections + key
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        YASCard(title = "شرح البرنامج", subtitle = "دليل الاستخدام الكامل") {
            ExplainBody(EXPLAIN_INTRO)
        }

        ExplainCard(EXPLAIN_EDL, EXPLAIN_EDL.title in openSections) { toggle(EXPLAIN_EDL.title) }

        ExplainLabel("شرح الأزرار حسب التبويب")
        EXPLAIN_SECTIONS.forEach { sec ->
            ExplainCard(sec, sec.title in openSections) { toggle(sec.title) }
        }

        ExplainLabel("خطوات كاملة")
        EXPLAIN_WORKFLOWS.forEach { sec ->
            ExplainCard(sec, sec.title in openSections) { toggle(sec.title) }
        }

        ExplainLabel("أسئلة شائعة")
        YASCard {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                EXPLAIN_QA.forEachIndexed { i, qa ->
                    if (i > 0) YDivider()
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { openQa = if (i in openQa) openQa - i else openQa + i }
                                .padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            YText(if (i in openQa) "▾" else "◂", size = 12.sp, color = Yas.SkyText)
                            RtlText(qa.q, size = 13.sp, color = Yas.Text, weight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        }
                    }
                    if (i in openQa) {
                        ExplainBody(qa.a, modifier = Modifier.padding(bottom = 8.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun ExplainLabel(text: String) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Box(Modifier.fillMaxWidth().padding(top = 6.dp, start = 4.dp, end = 4.dp)) {
            RtlText(text, size = 12.sp, color = Yas.TextSoft, weight = FontWeight.Bold)
        }
    }
}

@Composable
private fun ExplainCard(section: ExplainSection, expanded: Boolean, onToggle: () -> Unit) {
    YASCard {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button, onClick = onToggle),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                YText(if (expanded) "▾" else "◂", size = 13.sp, color = Yas.SkyText)
                RtlText(section.title, size = 14.sp, color = Yas.Text, weight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            }
        }
        if (expanded) {
            Spacer(Modifier.height(8.dp))
            ExplainBody(section.body)
        }
    }
}
