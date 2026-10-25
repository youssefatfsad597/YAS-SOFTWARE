package com.edl.flasher.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.*
import android.os.Build
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.Closeable
import kotlin.coroutines.resume

/**
 * Sends AT commands over a USB CDC/modem-style interface exposed by the phone
 * while it is enumerated as a normal MTP/modem composite device (i.e. BEFORE
 * ADB or a button combo is used). This is the classic "AT+DLOAD" technique some
 * Samsung basebands honor to force the device straight into Download (Odin)
 * mode -- handy when the volume/power combo is broken or awkward, or when USB
 * debugging is not enabled so `adb reboot download` is unavailable.
 *
 * Every candidate interface on the device is tried in turn; nothing here assumes
 * a specific model, since which interface carries the AT port varies by phone
 * and USB mode. The device itself decides whether it honors the command at all.
 */
class AtCommandTransport(private val context: Context) : Closeable {

    companion object {
        const val ACTION_AT_USB_PERMISSION = "com.yas.flashedl.AT_USB_PERMISSION"
        // Classic Samsung modem command that forces an immediate reboot into
        // Download (Odin) mode. A couple of baseband variants are tried too.
        // AT+FUS? is the Samsung "firmware update service" switch; the others are older variants.
        val DOWNLOAD_MODE_COMMANDS = listOf("AT+FUS?\r\n", "AT+DLOAD\r\n", "AT+SWITCH=DOWNLOAD\r\n")
        // Unisoc/Spreadtrum basebands: candidate commands reported to force a reboot
        // straight into boot-ROM (BROM) download mode from a running phone, tried in
        // order. Coverage varies by baseband build -- same best-effort spirit as the
        // Samsung DOWNLOAD_MODE_COMMANDS above. If none of these work on a given T509 /
        // T509N unit, the reliable fallback stays the hardware key-combo + USB plug-in.
        val BROM_MODE_COMMANDS = listOf("AT+SPREBOOT=4\r\n", "AT+SPCHANGEMODE=4\r\n", "AT+ESPEED\r\n", "AT+RESET=4\r\n")
        // Standard 3GPP "full functionality + reset" command: restarts the device/modem.
        // Tried in order. After EACH one we check whether the phone actually dropped off the
        // USB bus; an "OK" alone is not proof (AT+CFUN=1,1 often only resets the modem).
        val REBOOT_COMMANDS = listOf("AT+CFUN=1,1\r\n", "AT+CFUN=1,1\r", "AT+RESET\r\n", "AT+CRESET\r\n", "AT+REBOOT\r\n")
        // Delay between the steps of the serial-number sequence (must be >= 1000 ms).
        const val SERIAL_STEP_DELAY_MS = 1200L
        private const val READ_BUF = 256
    }

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var connection: UsbDeviceConnection? = null
    private var claimedInterface: UsbInterface? = null

    suspend fun ensurePermission(device: UsbDevice): Boolean {
        if (usbManager.hasPermission(device)) return true
        return suspendCancellableCoroutine { cont ->
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    if (intent.action != ACTION_AT_USB_PERMISSION) return
                    runCatching { context.unregisterReceiver(this) }
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) ||
                        usbManager.hasPermission(device)
                    if (cont.isActive) cont.resume(granted)
                }
            }
            val filter = IntentFilter(ACTION_AT_USB_PERMISSION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(receiver, filter)
            }
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_ALLOW_UNSAFE_IMPLICIT_INTENT
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE
            } else {
                0
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context, device.deviceName.hashCode(),
                Intent(ACTION_AT_USB_PERMISSION).setPackage(context.packageName),
                flags or PendingIntent.FLAG_UPDATE_CURRENT
            )
            usbManager.requestPermission(device, pendingIntent)
            cont.invokeOnCancellation { runCatching { context.unregisterReceiver(receiver) } }
        }
    }

    /** Candidate interfaces: CDC-ACM / CDC-DATA / vendor-specific, each with a bulk IN+OUT pair. */
    private fun candidateInterfaces(device: UsbDevice): List<UsbInterface> =
        (0 until device.interfaceCount).map { device.getInterface(it) }.filter { intf ->
            val looksSerial = intf.interfaceClass == UsbConstants.USB_CLASS_COMM ||
                intf.interfaceClass == UsbConstants.USB_CLASS_CDC_DATA ||
                intf.interfaceClass == UsbConstants.USB_CLASS_VENDOR_SPEC
            val eps = (0 until intf.endpointCount).map { intf.getEndpoint(it) }
            val hasBulkPair = eps.any { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK && it.direction == UsbConstants.USB_DIR_IN } &&
                eps.any { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK && it.direction == UsbConstants.USB_DIR_OUT }
            // Never talk AT to the ADB interface (class FF/42/01) or the MTP interface:
            // text written there is read as ADB/MTP protocol data and can upset them.
            val isAdb = intf.interfaceClass == UsbConstants.USB_CLASS_VENDOR_SPEC &&
                intf.interfaceSubclass == 0x42 && intf.interfaceProtocol == 0x01
            val isMtp = intf.interfaceClass == 6 ||
                (intf.interfaceClass == UsbConstants.USB_CLASS_VENDOR_SPEC && intf.interfaceSubclass == 0xFF)
            looksSerial && hasBulkPair && !isAdb && !isMtp
        }

    private fun bulkIn(intf: UsbInterface) =
        (0 until intf.endpointCount).map { intf.getEndpoint(it) }
            .first { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK && it.direction == UsbConstants.USB_DIR_IN }

    private fun bulkOut(intf: UsbInterface) =
        (0 until intf.endpointCount).map { intf.getEndpoint(it) }
            .first { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK && it.direction == UsbConstants.USB_DIR_OUT }

    /**
     * Tries every AT-capable interface on [device], sending each command in
     * [commands] in turn. Phones that honor a mode-switch command often drop
     * straight off the bus without replying "OK" first, since the USB link
     * resets as part of the switch -- so a clean write with no reply is logged
     * as a plausible success too, not just an explicit reply.
     */
    private suspend fun runAtSequence(device: UsbDevice, commands: List<String>, log: (String) -> Unit, stopOnSuccess: Boolean = false): Boolean {
        if (!ensurePermission(device)) {
            log("USB permission denied for AT command interface.")
            return false
        }
        val conn = usbManager.openDevice(device) ?: run {
            log("Could not open USB device for AT command.")
            return false
        }
        connection = conn
        val interfaces = candidateInterfaces(device)
        if (interfaces.isEmpty()) {
            log("No CDC/serial-style interface found on this device for AT commands.")
            conn.close()
            connection = null
            return false
        }

        var anySuccess = false
        for (intf in interfaces) {
            if (!conn.claimInterface(intf, true)) continue
            claimedInterface = intf
            val epIn = runCatching { bulkIn(intf) }.getOrNull()
            val epOut = runCatching { bulkOut(intf) }.getOrNull()
            if (epIn == null || epOut == null) {
                conn.releaseInterface(intf)
                continue
            }
            assertControlLines(conn, intf)

            for (cmd in commands) {
                val bytes = cmd.toByteArray(Charsets.US_ASCII)
                val sent = conn.bulkTransfer(epOut, bytes, bytes.size, 2000)
                log("Interface ${intf.id}: wrote '${cmd.trim()}' ($sent bytes)")
                if (sent < 0) continue

                val buf = ByteArray(READ_BUF)
                val read = conn.bulkTransfer(epIn, buf, buf.size, 1500)
                if (read > 0) {
                    val reply = String(buf, 0, read, Charsets.US_ASCII).replace("\r", " ").replace("\n", " ").trim()
                    val rejected = reply.contains("ERROR", ignoreCase = true) || reply.contains("#NG#")
                    log("Interface ${intf.id}: reply '$reply'" + if (rejected) "  <- rejected by the device" else "")
                    if (!rejected && reply.contains("OK", ignoreCase = true)) anySuccess = true
                    if (anySuccess && stopOnSuccess) break
                } else {
                    log("Interface ${intf.id}: no reply (device may already be switching modes).")
                }
                if (anySuccess && stopOnSuccess) break
                // A phone that honors a mode switch drops off the USB bus, usually WITHOUT
                // printing "OK". Count that as success so the button works on the first tap
                // (before this, the app said "did not confirm" while the phone was in fact switching).
                if (waitForDetach(device, 4000)) {
                    log("USB link dropped after '${cmd.trim()}': the device is switching mode.")
                    anySuccess = true
                    if (stopOnSuccess) break
                }
            }
            runCatching { conn.releaseInterface(intf) }
            if (anySuccess && stopOnSuccess) break
        }

        conn.close()
        connection = null
        claimedInterface = null
        return anySuccess
    }

    /** Read-only AT queries (IMEI, firmware, serial). Returns command -> cleaned reply for the
     *  first interface that answers at least one command; ERROR replies are dropped. */
    suspend fun queryReadOnly(device: UsbDevice, commands: List<String>): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        if (!ensurePermission(device)) return result
        val conn = usbManager.openDevice(device) ?: return result
        try {
            for (intf in candidateInterfaces(device)) {
                if (!conn.claimInterface(intf, true)) continue
                try {
                    val epIn = runCatching { bulkIn(intf) }.getOrNull() ?: continue
                    val epOut = runCatching { bulkOut(intf) }.getOrNull() ?: continue
                    for (cmd in commands) {
                        val reply = exchange(conn, epIn, epOut, cmd)
                        if (reply.isNotBlank() && !reply.contains("ERROR", ignoreCase = true) && !reply.contains("#NG#")) result[cmd] = reply
                    }
                } finally {
                    runCatching { conn.releaseInterface(intf) }
                }
                if (result.isNotEmpty()) break
            }
        } finally {
            conn.close()
        }
        return result
    }

    /** Sends exactly one AT command the caller supplies (used by the "Custom AT Command"
     *  field). The full raw reply is returned as-is, including OK/ERROR/#NG#, so the caller
     *  can judge success for itself -- nothing here assumes what the command does. */
    suspend fun sendCustom(device: UsbDevice, command: String): String {
        val cmd = command.trim()
        if (cmd.isEmpty()) return ""
        if (!ensurePermission(device)) return "USB permission denied for AT command interface."
        val conn = usbManager.openDevice(device) ?: return "Could not open USB device for AT command."
        try {
            for (intf in candidateInterfaces(device)) {
                if (!conn.claimInterface(intf, true)) continue
                try {
                    val epIn = runCatching { bulkIn(intf) }.getOrNull() ?: continue
                    val epOut = runCatching { bulkOut(intf) }.getOrNull() ?: continue
                    val reply = exchange(conn, epIn, epOut, cmd)
                    if (reply.isNotBlank()) return reply
                } finally {
                    runCatching { conn.releaseInterface(intf) }
                }
            }
        } finally {
            conn.close()
        }
        return "(no reply on any interface)"
    }

    private fun isAtTerminator(line: String): Boolean {
        val t = line.trim()
        return t == "OK" || t == "#OK#" || t == "ERROR" || t == "#NG#" || t == "NG" ||
            t.startsWith("+CME ERROR") || t.startsWith("+CMS ERROR")
    }

    private fun exchange(conn: UsbDeviceConnection, epIn: UsbEndpoint, epOut: UsbEndpoint, cmd: String): String {
        // Discard anything left over from a previous (slow) command so replies never shift by one.
        val drain = ByteArray(READ_BUF)
        while (conn.bulkTransfer(epIn, drain, drain.size, 120) > 0) { /* discard stale bytes */ }
        val bytes = (cmd + "\r\n").toByteArray(Charsets.US_ASCII)
        if (conn.bulkTransfer(epOut, bytes, bytes.size, 2000) < 0) return ""
        val sb = StringBuilder()
        val buf = ByteArray(1024)
        var idle = 0
        var gotData = false
        // Wait longer for the first byte (Samsung can be slow), shorter once data is flowing.
        while (sb.length < 8192) {
            val n = conn.bulkTransfer(epIn, buf, buf.size, 600)
            if (n > 0) {
                sb.append(String(buf, 0, n, Charsets.US_ASCII))
                gotData = true
                idle = 0
                val lastLine = sb.toString().replace("\r", "\n").lines().map { it.trim() }.lastOrNull { it.isNotEmpty() }.orEmpty()
                if (isAtTerminator(lastLine)) break
            } else {
                idle++
                if (idle >= (if (gotData) 2 else 3)) break
            }
        }
        val lines = sb.toString().replace("\r", "\n").lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.equals(cmd, ignoreCase = true) && it != "OK" && it != "#OK#" }
        return lines.joinToString("\n")
    }


    /** Many CDC-ACM modems ignore bulk data until DTR/RTS are asserted. */
    private fun assertControlLines(conn: UsbDeviceConnection, intf: UsbInterface) {
        val ctrlIds = if (intf.interfaceClass == UsbConstants.USB_CLASS_CDC_DATA)
            listOf(maxOf(0, intf.id - 1), intf.id) else listOf(intf.id)
        for (id in ctrlIds) {
            // SET_CONTROL_LINE_STATE (DTR | RTS)
            runCatching { conn.controlTransfer(0x21, 0x22, 0x03, id, null, 0, 200) }
            // SET_LINE_CODING: 115200 8N1
            val lc = byteArrayOf(0x00, 0xC2.toByte(), 0x01, 0x00, 0x00, 0x00, 0x08)
            runCatching { conn.controlTransfer(0x21, 0x20, 0, id, lc, lc.size, 200) }
        }
    }

    private fun deviceStillPresent(device: UsbDevice): Boolean =
        usbManager.deviceList.values.any { it.deviceName == device.deviceName && it.vendorId == device.vendorId && it.productId == device.productId }

    private suspend fun waitForDetach(device: UsbDevice, timeoutMs: Long): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (!deviceStillPresent(device)) return true
            kotlinx.coroutines.delay(250)
        }
        return !deviceStillPresent(device)
    }

    /**
     * Real reboot: sends each candidate command on each AT interface and, after each one,
     * waits up to 6 s for the phone to disconnect from USB. Only a disconnect counts as success.
     */
    suspend fun rebootVerified(device: UsbDevice, log: (String) -> Unit): Boolean {
        if (!ensurePermission(device)) { log("USB permission denied for AT command interface."); return false }
        val conn = usbManager.openDevice(device) ?: run { log("Could not open USB device for AT command."); return false }
        try {
            val interfaces = candidateInterfaces(device)
            if (interfaces.isEmpty()) {
                log("No AT/modem port is exposed in this USB mode. On Samsung dial *#0808# (or *#2767*...) and choose a mode that includes MODEM, then retry.")
                return false
            }
            for (intf in interfaces) {
                if (!conn.claimInterface(intf, true)) { log("Interface ${intf.id}: could not claim."); continue }
                try {
                    val epIn = runCatching { bulkIn(intf) }.getOrNull() ?: continue
                    val epOut = runCatching { bulkOut(intf) }.getOrNull() ?: continue
                    assertControlLines(conn, intf)
                    // Probe: make sure this really is an AT port before sending anything else.
                    val probe = exchange(conn, epIn, epOut, "AT")
                    log("Interface ${intf.id}: probe 'AT' -> '${probe.ifBlank { "(no reply)" }}'")
                    for (cmd in REBOOT_COMMANDS) {
                        val bytes = cmd.toByteArray(Charsets.US_ASCII)
                        val sent = conn.bulkTransfer(epOut, bytes, bytes.size, 2000)
                        log("Interface ${intf.id}: wrote '${cmd.trim()}' ($sent bytes)")
                        if (sent < 0) continue
                        val buf = ByteArray(READ_BUF)
                        val n = conn.bulkTransfer(epIn, buf, buf.size, 1200)
                        if (n > 0) log("   reply: '" + String(buf, 0, n, Charsets.US_ASCII).replace("\r", " ").replace("\n", " ").trim() + "'")
                        if (waitForDetach(device, 6000)) {
                            log("USB link dropped after '${cmd.trim()}': the device is rebooting.")
                            return true
                        }
                        log("   device still connected, trying next command...")
                    }
                } finally {
                    runCatching { conn.releaseInterface(intf) }
                }
            }
        } finally {
            runCatching { conn.close() }
        }
        log("The phone never disconnected: no reboot happened. This USB mode/firmware does not honor AT reboot commands.")
        return false
    }

    /**
     * Runs a list of AT commands one after the other with a fixed delay (>= 1 s) between them.
     * Returns the list of "command -> reply" lines. The last command may legitimately get no reply
     * because the phone resets itself.
     */
    suspend fun runTimedSequence(device: UsbDevice, commands: List<String>, delayMs: Long, log: (String) -> Unit): Boolean {
        if (!ensurePermission(device)) { log("USB permission denied for AT command interface."); return false }
        val conn = usbManager.openDevice(device) ?: run { log("Could not open USB device for AT command."); return false }
        try {
            for (intf in candidateInterfaces(device)) {
                if (!conn.claimInterface(intf, true)) continue
                try {
                    val epIn = runCatching { bulkIn(intf) }.getOrNull() ?: continue
                    val epOut = runCatching { bulkOut(intf) }.getOrNull() ?: continue
                    assertControlLines(conn, intf)
                    val probe = exchange(conn, epIn, epOut, "AT")
                    if (probe.contains("ERROR", true) && probe.length > 40) continue
                    log("Interface ${intf.id}: AT port selected.")
                    var allOk = true
                    commands.forEachIndexed { i, cmd ->
                        val reply = exchange(conn, epIn, epOut, cmd)
                        val bad = reply.contains("ERROR", true) || reply.contains("#NG#")
                        log("[${i + 1}/${commands.size}] $cmd  ->  ${reply.ifBlank { "(no reply / OK)" }}" + if (bad) "   <- rejected" else "")
                        if (bad) allOk = false
                        if (i < commands.size - 1) kotlinx.coroutines.delay(delayMs)
                    }
                    return allOk
                } finally {
                    runCatching { conn.releaseInterface(intf) }
                }
            }
        } finally {
            runCatching { conn.close() }
        }
        log("No usable AT interface found. Enable a USB mode with MODEM (*#0808#) and retry.")
        return false
    }

    /** Samsung serial-number rewrite sequence (5 steps, NO factory reset), then a plain reboot. */
    suspend fun changeSerialSequence(device: UsbDevice, newSerial: String, log: (String) -> Unit): Boolean {
        fun snOf(raw: String) = Regex("SN\\(([^)]*)\\)").find(raw)?.groupValues?.get(1)?.trim().orEmpty()
        val before = snOf(queryReadOnly(device, listOf("AT+DEVCONINFO"))["AT+DEVCONINFO"].orEmpty())
        log("Serial before: ${before.ifBlank { "(unreadable)" }}")
        val cmds = listOf(
            "AT+SWATD=0",
            "AT+ACTIVATE=0,0,0",
            "AT+SWATD=1",
            "AT+PARALLEL=2,0,00000;AT+DEBUGLVC=0,5",
            "AT+PARALLEL=2,0,00000;AT+SERIALNO=2,$newSerial"
        )
        val ok = runTimedSequence(device, cmds, SERIAL_STEP_DELAY_MS, log)
        kotlinx.coroutines.delay(SERIAL_STEP_DELAY_MS)
        val after = snOf(queryReadOnly(device, listOf("AT+DEVCONINFO"))["AT+DEVCONINFO"].orEmpty())
        log("Serial after write (before reboot): ${after.ifBlank { "(unreadable)" }}")
        if (after.equals(newSerial, true)) log("Serial matches the requested value.")
        else log("NOTE: the phone may only show the new serial after a reboot.")
        log("Rebooting normally (no factory reset)...")
        rebootVerified(device, log)
        return ok
    }

    /** Forces Download (Odin) mode via [DOWNLOAD_MODE_COMMANDS]. */
    suspend fun tryRebootToDownloadMode(device: UsbDevice, log: (String) -> Unit): Boolean =
        runAtSequence(device, DOWNLOAD_MODE_COMMANDS, log, stopOnSuccess = true)

    /** Forces Unisoc BROM mode via [BROM_MODE_COMMANDS]. "Success" here only means the
     *  device accepted a command and/or dropped off the bus to re-enumerate as BROM --
     *  the caller still has to re-scan USB to confirm the BROM VID/PID actually showed up. */
    suspend fun tryRebootToBrom(device: UsbDevice, log: (String) -> Unit): Boolean =
        runAtSequence(device, BROM_MODE_COMMANDS, log, stopOnSuccess = true)

    /** Reboots the device via [REBOOT_COMMANDS]. */
    suspend fun tryReboot(device: UsbDevice, log: (String) -> Unit): Boolean =
        rebootVerified(device, log)

    override fun close() {
        runCatching {
            claimedInterface?.let { connection?.releaseInterface(it) }
            connection?.close()
        }
        connection = null
        claimedInterface = null
    }
}
