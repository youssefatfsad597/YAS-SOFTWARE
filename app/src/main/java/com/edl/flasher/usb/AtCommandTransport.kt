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
        // Unisoc/Spreadtrum: the verified AT sequence that drops a T509 straight into
        // boot-ROM (BROM) download mode from a running phone (MTP AT port). Taken
        // directly from the reference service tool's ForceBROMAsync -- every command here
        // runs, in this order, not as alternatives to try one at a time:
        //   AT+DEVCONINFO          -- wakes/identifies the engineering port
        //   AT+VERSNAME=3,2,3      -- selects the service-mode version namespace
        //   AT+REACTIVE=1,0,0      -- re-activates the engineering context
        //   AT+FRPUNLCK=3,0,0      -- unlocks FRP so the switch isn't blocked
        //   AT+SPREF="AUTODLOADER" -- requests the reboot into the Spreadtrum/Unisoc
        //                             auto-downloader (BROM) on next boot
        // If a unit doesn't honor this, the fallback stays the hardware key-combo + USB
        // plug-in. See [forceBromSequence].
        val BROM_FORCE_SEQUENCE = listOf(
            "AT+DEVCONINFO",
            "AT+VERSNAME=3,2,3",
            "AT+REACTIVE=1,0,0",
            "AT+FRPUNLCK=3,0,0",
            "AT+SPREF=\"AUTODLOADER\""
        )
        private const val BROM_FORCE_STEP_DELAY_MS = 1000L
        // Standard 3GPP "full functionality + reset" command: restarts the device/modem.
        // Tried in order. After EACH one we check whether the phone actually dropped off the
        // USB bus; an "OK" alone is not proof (AT+CFUN=1,1 often only resets the modem).
        val REBOOT_COMMANDS = listOf("AT+CFUN=1,1\r\n", "AT+CFUN=1,1\r", "AT+RESET\r\n", "AT+CRESET\r\n", "AT+REBOOT\r\n")
        // Samsung AT "format" (factory reset of /data = userdata), the same unlock steps the serial
        // sequence uses, then AT+FACTORST. The phone wipes userdata and reboots by itself; the
        // userdata partition is re-created at its full size on that boot, which is what brings
        // back the original storage size after a small (quick-format) userdata.img was flashed.
        val FORMAT_UNLOCK_COMMANDS = listOf(
            "AT+SWATD=0",
            "AT+ACTIVATE=0,0,0",
            "AT+SWATD=1",
            "AT+PARALLEL=2,0,00000;AT+DEBUGLVC=0,5"
        )
        val FORMAT_COMMANDS = listOf("AT+FACTORST=0,0", "AT+FACTORST=1,1")
        // Delay between the steps of the serial-number sequence. Each exchange() already
        // blocks until the device replies (or its own idle timeout) -- this is extra padding
        // on top of that, same as CSC_STEP_DELAY_MS. Was 1200ms, then 600ms, then 300ms; cut
        // again since the flow now does a single attempt and a quick serial-only read
        // beforehand, so every extra ms here is pure added latency on the one shot we take.
        const val SERIAL_STEP_DELAY_MS = 70L
        // Settle time after the serial write before reading it back, so the modem has a moment
        // to commit the NV value -- separate from SERIAL_STEP_DELAY_MS so it can be tuned on
        // its own. readModemSerial() retries with its own delay if this isn't quite enough.
        const val SERIAL_POST_WRITE_SETTLE_MS = 80L
        // CSC (Sales Code) writes are a handful of lightweight AT+PRECONFG/activation
        // commands with no flash-heavy work behind them, so they tolerate a shorter
        // inter-command gap than the serial sequence. Each exchange() already blocks until
        // the device replies (or its own idle timeout), so this is pure extra padding on
        // top of that -- cut in step with SERIAL_STEP_DELAY_MS for the same single-attempt,
        // fastest-possible flow.
        const val CSC_STEP_DELAY_MS = 70L
        // Wait before a CSC retry attempt (separate from the serial retry delay above).
        // Only matters if maxAttempts is ever raised above 1 again.
        const val CSC_RETRY_DELAY_MS = 150L
        // Wait before a serial-write retry attempt -- just the gap between whole attempts.
        // Only matters if maxAttempts is ever raised above 1 again.
        const val SERIAL_RETRY_DELAY_MS = 150L
        // How long to wait for the device to drop off USB after each individual command in
        // REBOOT_COMMANDS. A command that will actually cause a full reboot shows it quickly;
        // this only needs to be long enough to catch that, not to patiently wait out a command
        // that was never going to work (several of REBOOT_COMMANDS only reset the modem stack,
        // not the whole device, on a lot of units -- see rebootVerifiedWithPowerReset).
        const val REBOOT_DETACH_WAIT_MS = 1500L
        // Same idea for the alternate reboot fallback: a bit more margin since it's the last
        // resort, but still far short of the old 8s.
        const val POWRESET_DETACH_WAIT_MS = 2000L
        private const val READ_BUF = 256
    }

    /** Optional progress callback (0f..1f) so callers can show a loading bar instead of raw command logs. */
    var onProgress: ((Float) -> Unit)? = null

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
            log("USB permission denied.")
            return false
        }
        val conn = usbManager.openDevice(device) ?: run {
            log("Could not open the device.")
            return false
        }
        connection = conn
        val interfaces = candidateInterfaces(device)
        if (interfaces.isEmpty()) {
            log("This device does not expose a modem port, so this method is unavailable.")
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
                if (sent < 0) continue

                val buf = ByteArray(READ_BUF)
                val read = conn.bulkTransfer(epIn, buf, buf.size, 1500)
                if (read > 0) {
                    val reply = String(buf, 0, read, Charsets.US_ASCII).replace("\r", " ").replace("\n", " ").trim()
                    val rejected = isRejected(reply)
                    if (rejected) log(rejectionReason(reply))
                    if (!rejected && reply.contains("OK", ignoreCase = true)) anySuccess = true
                    if (anySuccess && stopOnSuccess) break
                } else {
                    // no reply: the device may already be switching modes
                }
                if (anySuccess && stopOnSuccess) break
                // A phone that honors a mode switch drops off the USB bus, usually WITHOUT
                // printing "OK". Count that as success so the button works on the first tap
                // (before this, the app said "did not confirm" while the phone was in fact switching).
                if (waitForDetach(device, 4000)) {
                    log("USB link dropped: the device is switching mode.")
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
                    assertControlLines(conn, intf)
                    // Quick existence probe first: a wrong interface is skipped in ~0.4s instead
                    // of paying the full per-command reply timeout for every command in the list.
                    val probe = exchange(conn, epIn, epOut, "AT", quick = true)
                    if (probe.contains("ERROR", true) && probe.length > 40) continue
                    for (cmd in commands) {
                        val reply = exchange(conn, epIn, epOut, cmd)
                        if (reply.isNotBlank() && !isRejected(reply)) result[cmd] = reply
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
            t.startsWith("+CME ERROR") || t.startsWith("+CMS ERROR") ||
            t.contains("Wrong Attribute", true) || t.contains("Wrong Data", true)
    }

    /** The Samsung CP's own AT-command parser answers a long list of commands --
     *  SERIALNO, PRODCODE, RFBYCODE, CPLDUCFG, and others -- with a plain
     *  "<CMD> Wrong Attribute" / "<CMD> Wrong Data" line instead of (or alongside) ERROR
     *  when a parameter is invalid; this is the modem's own error convention, not specific
     *  to one command. Treat it the same as ERROR/#NG# everywhere a reply is judged. */
    private fun isRejected(reply: String): Boolean =
        reply.contains("ERROR", true) || reply.contains("#NG#") ||
            reply.contains("Wrong Attribute", true) || reply.contains("Wrong Data", true)

    /** Turns a rejected CP reply into a short, specific reason instead of a generic one,
     *  when the modem's own wording lets us (see [isRejected]). */
    private fun rejectionReason(reply: String): String = when {
        reply.contains("Wrong Attribute", true) -> "A step was rejected by the device: wrong attribute value."
        reply.contains("Wrong Data", true) -> "A step was rejected by the device: wrong data for that step."
        else -> "A step was rejected by the device."
    }

    /** @param quick  true for a plain existence probe (is this interface an AT port at all?),
     *   where a wrong interface should be given up on fast instead of waiting out the full
     *   generous timeout a real command reply deserves. Used for the "AT" probe that runs
     *   before every real command sequence, across however many candidate interfaces the
     *   device exposes -- with the normal (non-quick) timeouts, each wrong interface skipped
     *   during that probe cost up to ~1.8s; quick cuts that to ~0.4s, and that saving applies
     *   once per candidate interface, in every AT-based operation (CSC/serial read+write,
     *   reboot, force-BROM, force-download). Real command exchanges always use quick = false. */
    private fun exchange(conn: UsbDeviceConnection, epIn: UsbEndpoint, epOut: UsbEndpoint, cmd: String, quick: Boolean = false): String {
        // Discard anything left over from a previous (slow) command so replies never shift by one.
        val drain = ByteArray(READ_BUF)
        while (conn.bulkTransfer(epIn, drain, drain.size, 120) > 0) { /* discard stale bytes */ }
        val bytes = (cmd + "\r\n").toByteArray(Charsets.US_ASCII)
        if (conn.bulkTransfer(epOut, bytes, bytes.size, 2000) < 0) return ""
        val sb = StringBuilder()
        val buf = ByteArray(1024)
        var idle = 0
        var gotData = false
        val pollTimeout = if (quick) 150 else 250
        val maxIdleNoData = if (quick) 2 else 2
        // Wait longer for the first byte (Samsung can be slow), shorter once data is flowing.
        while (sb.length < 8192) {
            val n = conn.bulkTransfer(epIn, buf, buf.size, pollTimeout)
            if (n > 0) {
                sb.append(String(buf, 0, n, Charsets.US_ASCII))
                gotData = true
                idle = 0
                val lastLine = sb.toString().replace("\r", "\n").lines().map { it.trim() }.lastOrNull { it.isNotEmpty() }.orEmpty()
                if (isAtTerminator(lastLine)) break
            } else {
                idle++
                if (idle >= (if (gotData) 2 else maxIdleNoData)) break
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
        if (!ensurePermission(device)) { log("USB permission denied."); return false }
        val conn = usbManager.openDevice(device) ?: run { log("Could not open the device."); return false }
        try {
            val interfaces = candidateInterfaces(device)
            if (interfaces.isEmpty()) {
                log("No modem port is available in this USB mode. On Samsung dial *#0808# and choose a mode that includes MODEM, then retry.")
                return false
            }
            for (intf in interfaces) {
                if (!conn.claimInterface(intf, true)) continue
                try {
                    val epIn = runCatching { bulkIn(intf) }.getOrNull() ?: continue
                    val epOut = runCatching { bulkOut(intf) }.getOrNull() ?: continue
                    assertControlLines(conn, intf)
                    // Probe: make sure this really is an AT port before sending anything else.
                    val probe = exchange(conn, epIn, epOut, "AT", quick = true)
                    for ((ci, cmd) in REBOOT_COMMANDS.withIndex()) {
                        onProgress?.invoke(ci.toFloat() / REBOOT_COMMANDS.size)
                        val bytes = cmd.toByteArray(Charsets.US_ASCII)
                        val sent = conn.bulkTransfer(epOut, bytes, bytes.size, 2000)
                                if (sent < 0) continue
                        val buf = ByteArray(READ_BUF)
                        val n = conn.bulkTransfer(epIn, buf, buf.size, 1200)
                        if (waitForDetach(device, 10000)) {
                            log("USB link dropped: the device is rebooting.")
                            return true
                        }
                    }
                } finally {
                    runCatching { conn.releaseInterface(intf) }
                }
            }
        } finally {
            runCatching { conn.close() }
        }
        log("The phone did not reboot. This USB mode does not support a remote reboot.")
        return false
    }

    /**
     * Runs a list of AT commands one after the other with a fixed delay (>= 1 s) between them.
     * Returns the list of "command -> reply" lines. The last command may legitimately get no reply
     * because the phone resets itself.
     */
    suspend fun runTimedSequence(device: UsbDevice, commands: List<String>, delayMs: Long, log: (String) -> Unit, replies: MutableList<String>? = null): Boolean {
        if (!ensurePermission(device)) { log("USB permission denied."); return false }
        val conn = usbManager.openDevice(device) ?: run { log("Could not open the device."); return false }
        try {
            for (intf in candidateInterfaces(device)) {
                if (!conn.claimInterface(intf, true)) continue
                try {
                    val epIn = runCatching { bulkIn(intf) }.getOrNull() ?: continue
                    val epOut = runCatching { bulkOut(intf) }.getOrNull() ?: continue
                    assertControlLines(conn, intf)
                    val probe = exchange(conn, epIn, epOut, "AT", quick = true)
                    if (probe.contains("ERROR", true) && probe.length > 40) continue
                    var allOk = true
                    commands.forEachIndexed { i, cmd ->
                        val reply = exchange(conn, epIn, epOut, cmd)
                        replies?.add(reply)
                        val bad = isRejected(reply)
                        if (bad) log(rejectionReason(reply))
                        onProgress?.invoke((i + 1).toFloat() / commands.size)
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
        log("No usable modem port found. Enable a USB mode with MODEM (*#0808#) and retry.")
        return false
    }

    /** Reads back the engineering serial via AT+SERIALNO? -- the same attribute that
     *  [changeSerialSequence] writes with AT+SERIALNO=2,<sn> -- so the value returned here is
     *  guaranteed to reflect that write (AT+DEVCONINFO's own "SN" field is a separate attribute
     *  and does not). Mirrors MA UNLOCKER's ReadSerial parsing: look for a "+SERIALNO:" line,
     *  strip commas and a leading attribute-index "1" if present; otherwise fall back to any
     *  bare all-caps/digits token of at least 11 characters. Retries because the modem port is
     *  often not ready on the first query. Returns "" when unreadable. */
    suspend fun readModemSerial(device: UsbDevice, attempts: Int = 2): String {
        repeat(attempts) { attempt ->
            val raw = queryReadOnly(device, listOf("AT+SERIALNO?"))["AT+SERIALNO?"].orEmpty()
            val lines = raw.split(Regex("[\\r\\n]+")).map { it.trim() }.filter { it.isNotEmpty() }
            for (line in lines) {
                if (line.contains("+SERIALNO:", ignoreCase = true)) {
                    val value = line.substringAfter(':').trim().replace(",", "")
                    val sn = if (value.startsWith("1")) value.substring(1).trim() else value
                    if (sn.isNotBlank()) return sn
                } else if (line.length >= 11 && Regex("^[A-Z0-9]+$").matches(line)) {
                    return line
                }
            }
            if (attempt < attempts - 1) kotlinx.coroutines.delay(150)
        }
        return ""
    }

    /** Reads the modem-side Sales Code (CSC) via AT+PRECONFG=1,0, retrying because the modem
     *  port is often not ready on the first query. Returns "" when unreadable. */
    suspend fun readModemCsc(device: UsbDevice, attempts: Int = 2): String {
        repeat(attempts) { attempt ->
            val raw = queryReadOnly(device, listOf("AT+PRECONFG=1,0"))["AT+PRECONFG=1,0"].orEmpty()
            val tagged = Regex("\\+PRECONFG:\\s*1,\\s*([A-Za-z]{3})").find(raw)?.groupValues?.get(1)
            val csc = tagged ?: Regex("[A-Z]{3}", RegexOption.IGNORE_CASE).find(raw)?.value
            if (!csc.isNullOrBlank()) return csc.uppercase()
            if (attempt < attempts - 1) kotlinx.coroutines.delay(150)
        }
        return ""
    }

    /**
     * Enhanced Samsung serial-number rewrite (7 steps with full init, 4 retries, post-write verify).
     * Integrates the complete service sequence from MA UNLOCKER / Samsung AP-AT firmware:
     *   1) AT+SWATD=0          – disable AT wrapper
     *   2) AT+ACTIVATE=0,0,0   – activate service mode
     *   3) AT+KSTRINGB=0,3      – unlock string/buffer access (key-string bridge)
     *   4) AT+DUMPCTRL=1,0      – enable diagnostic dump control
     *   5) AT+SWATD=1           – re-enable AT wrapper
     *   6) AT+DEBUGLVC=0,5      – set debug level (needed before engineering writes)
     *   7) AT+SERIALNO=2,<SN>   – write the new serial number
     * Then immediately reads back AT+SERIALNO? and compares with the target value.
     *
     * Runs [maxAttempts] pass(es) of the sequence (default 1 -- a single, fastest-possible
     * attempt); if a caller does raise it above 1, extra passes wait [SERIAL_RETRY_DELAY_MS]
     * apart, for the rare device that needs a second pass because the modem port hot-plugs or
     * the AP needs time to open the engineering AT handlers.
     *
     * Every command below is present verbatim in this firmware's own AP-side AT-command
     * whitelist (AT+SWATD=0/1, AT+ACTIVATE=0,0,0, AT+KSTRINGB=0,3, AT+DUMPCTRL=1,0,
     * AT+DEBUGLVC=*, AT+SERIALNO=*) so none of them are blocked by the AP before ever
     * reaching the modem. They are, however, engineering/service commands: none of them
     * appear in this device's own AT+CLAC reply (the modem's list of "publicly documented"
     * commands), so AT+CLAC must never be used to decide whether this sequence is worth
     * attempting -- its absence there is expected, not an error.
     *
     * @param maxAttempts  how many times to run the full sequence (default 1: one fast pass).
     * @return             true when the modem explicitly acknowledged the write OR the
     *                     post-read-back confirms the new serial.
     */
    suspend fun changeSerialSequence(
        device: UsbDevice,
        newSerial: String,
        log: (String) -> Unit,
        maxAttempts: Int = 1
    ): Boolean {
        var lastAckReply = ""

        for (attempt in 0 until maxAttempts) {
            if (attempt > 0) kotlinx.coroutines.delay(SERIAL_RETRY_DELAY_MS)

            // ---- Full 7-step service init sequence (from MA UNLOCKER) ----
            val cmds = buildList {
                // Step 1: Disable the AT wrapper so low-level commands reach the CP directly.
                add("AT+SWATD=0")
                // Step 2: Activate the engineering/service context.
                add("AT+ACTIVATE=0,0,0")
                // Step 3: Unlock the string buffer / key-string bridge (critical for WRITE attributes).
                add("AT+KSTRINGB=0,3")
                // Step 4: Enable diagnostic dump control.
                add("AT+DUMPCTRL=1,0")
                // Step 5: Re-enable AT wrapper.
                add("AT+SWATD=1")
                // Step 6: Set debug level so engineering writes are processed.
                add("AT+DEBUGLVC=0,5")
                // Step 7: Write the serial number (attribute 2 = engineer SN field).
                add("AT+SERIALNO=2,$newSerial")
            }

            val replies = ArrayList<String>()
            val sequenceOk = runTimedSequenceWithProgress(
                device = device,
                commands = cmds,
                delayMs = SERIAL_STEP_DELAY_MS,
                log = log,
                progressStart = 0.1f,
                progressEnd = if (attempt < maxAttempts - 1) 0.6f else 0.8f,
                replies = replies
            )

            val last = replies.lastOrNull().orEmpty()
            lastAckReply = last

            val acknowledged = sequenceOk && replies.size == cmds.size &&
                last.contains("SERIALNO", true) && last.contains("OK", true)

            // Mid-sequence rejections and an un-acknowledged write are expected on some
            // firmware and are not reported here -- the post-write read-back below is the
            // real check, so nothing is logged until that result is known.
            if (acknowledged) break

            // If we've exhausted all attempts without acknowledgment, fall through to best-effort check.
        }

        // Post-write verification: read back the serial from the device.
        kotlinx.coroutines.delay(SERIAL_POST_WRITE_SETTLE_MS)
        val after = readModemSerial(device, attempts = 2)

        val directMatch = after.trim().equals(newSerial, ignoreCase = true)
        return if (directMatch) {
            true // Read-back confirms the new serial even without explicit OK.
        } else if (lastAckReply.contains("SERIALNO", true) && lastAckReply.contains("OK", true)) {
            true // Explicit OK even if read-back didn't match yet (modem may need reboot).
        } else {
            false
        }
    }

    /**
     * Same as [runTimedSequence] but exposes per-command progress updates within the
     * progressStart to progressEnd range.
     * Useful when the caller already has a progress bar and wants the serial-write sub-sequence
     * to fill a specific portion of it.
     */
    private suspend fun runTimedSequenceWithProgress(
        device: UsbDevice,
        commands: List<String>,
        delayMs: Long,
        log: (String) -> Unit,
        progressStart: Float,
        progressEnd: Float,
        replies: MutableList<String>? = null
    ): Boolean {
        if (!ensurePermission(device)) { log("USB permission denied."); return false }
        val conn = usbManager.openDevice(device) ?: run { log("Could not open the device."); return false }
        try {
            for (intf in candidateInterfaces(device)) {
                if (!conn.claimInterface(intf, true)) continue
                try {
                    val epIn = runCatching { bulkIn(intf) }.getOrNull() ?: continue
                    val epOut = runCatching { bulkOut(intf) }.getOrNull() ?: continue
                    assertControlLines(conn, intf)

                    // Probe: confirm this really is an AT port before sending anything else.
                    val probe = exchange(conn, epIn, epOut, "AT", quick = true)
                    if (probe.contains("ERROR", true) && probe.length > 40) continue

                    var allOk = true
                    commands.forEachIndexed { i, cmd ->
                        val totalDelay = commands.size
                        val frac = (i + 1) / totalDelay.toFloat()
                        val pct = progressStart + (progressEnd - progressStart) * frac
                        onProgress?.invoke(pct)

                        val reply = exchange(conn, epIn, epOut, cmd)
                        replies?.add(reply)

                        // Mid-sequence steps (service-mode unlock commands) routinely answer
                        // with something other than a plain OK on this firmware -- that is
                        // expected, not a failure, and is never shown to the user. Only the
                        // final outcome (checked by the caller from `replies`) matters.
                        if (isRejected(reply)) allOk = false
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
        log("No usable modem port found. Enable a USB mode with MODEM (*#0808#) and retry.")
        return false
    }

    /**
     * Formats the phone (wipes userdata / factory reset) over its AT port while it is in MTP
     * mode, then makes sure it reboots. Steps: unlock commands -> AT+FACTORST -> (if the phone
     * did not drop off the USB bus by itself) a verified AT reboot.
     * Returns true when the phone accepted the format command or left the USB bus.
     * ALL USER DATA IS ERASED.
     */
    suspend fun formatAndReboot(device: UsbDevice, log: (String) -> Unit): Boolean {
        if (!ensurePermission(device)) { log("USB permission denied."); return false }
        val conn = usbManager.openDevice(device) ?: run { log("Could not open the device."); return false }
        var accepted = false
        try {
            val interfaces = candidateInterfaces(device)
            if (interfaces.isEmpty()) {
                log("No modem port is available in this USB mode. On Samsung dial *#0808# and choose a mode that includes MODEM, then retry.")
                return false
            }
            for (intf in interfaces) {
                if (!conn.claimInterface(intf, true)) continue
                try {
                    val epIn = runCatching { bulkIn(intf) }.getOrNull() ?: continue
                    val epOut = runCatching { bulkOut(intf) }.getOrNull() ?: continue
                    assertControlLines(conn, intf)
                    val probe = exchange(conn, epIn, epOut, "AT", quick = true)
                    if (probe.contains("ERROR", true) && probe.length > 40) continue

                    FORMAT_UNLOCK_COMMANDS.forEachIndexed { i, cmd ->
                        val reply = exchange(conn, epIn, epOut, cmd)
                        onProgress?.invoke(0.5f * (i + 1) / FORMAT_UNLOCK_COMMANDS.size)
                        kotlinx.coroutines.delay(SERIAL_STEP_DELAY_MS)
                    }
                    for (cmd in FORMAT_COMMANDS) {
                        val reply = exchange(conn, epIn, epOut, cmd)
                        val bad = isRejected(reply)
                        if (bad) log("${rejectionReason(reply)} Trying another method...") else onProgress?.invoke(0.7f)
                        if (!bad) { accepted = true; break }
                        kotlinx.coroutines.delay(SERIAL_STEP_DELAY_MS)
                    }
                    if (accepted) break
                } finally {
                    runCatching { conn.releaseInterface(intf) }
                }
            }
        } finally {
            runCatching { conn.close() }
        }
        if (!accepted) {
            log("The phone rejected the restore request. This firmware does not support it in MTP mode.")
            return false
        }
        // The phone normally reboots on its own to do the wipe. Give it time, then force a reboot.
        if (waitForDetach(device, 8000)) {
            log("The phone is restoring its storage and rebooting.")
            return true
        }
        log("Restore accepted. Rebooting the phone...")
        return rebootVerified(device, log)
    }

    /**
     * CSC (Sales Code) rewrite — same service sequence as [changeSerialSequence] but targets
     * the AT+PRECONFG attribute that stores the region / carrier code.
     *
     * Steps (from MA UNLOCKER C# source):
     *   1) AT+SWATD=0        – disable AT wrapper
     *   2) AT+ACTIVATE=0,0,0 – activate engineering context
     *   3) AT+SWATD=1        – re-enable wrapper
     *   4) AT+KSTRINGB=0,3   – unlock key-string bridge
     *   5) AT+PRECONFG=2,<CSC> – write the new 3-letter sales code
     *   6) AT+PRECONFG=1,0   – read back to confirm
     *
     * After the write the device normally does NOT reboot by itself (unlike serial changes).
     * The caller must reboot separately (e.g. via [rebootVerified]) so the new CSC takes effect
     * on next boot.
     *
     * @return true when the modem explicitly acknowledged the write (OK received).
     */
    suspend fun changeCscSequence(
        device: UsbDevice,
        newCsc: String,
        log: (String) -> Unit,
        maxAttempts: Int = 1
    ): Boolean {
        require(newCsc.length == 3 && newCsc.all { it in 'A'..'Z' || it in 'a'..'z' }) {
            "CSC must be exactly 3 letters (A-Z)."
        }
        val upperCsc = newCsc.uppercase()

        repeat(maxAttempts) { attempt ->
            if (attempt > 0) kotlinx.coroutines.delay(CSC_RETRY_DELAY_MS)

            val cmds = listOf(
                "AT+SWATD=0",
                "AT+ACTIVATE=0,0,0",
                "AT+SWATD=1",
                "AT+KSTRINGB=0,3",
                "AT+PRECONFG=2,$upperCsc",
                "AT+PRECONFG=1,0"   // verify-readback
            )

            val replies = ArrayList<String>()
            val sequenceOk = runTimedSequenceWithProgress(
                device = device,
                commands = cmds,
                delayMs = CSC_STEP_DELAY_MS,
                log = log,
                progressStart = 0.1f,
                progressEnd = if (attempt < maxAttempts - 1) 0.7f else 0.9f,
                replies = replies
            )

            val last = replies.lastOrNull().orEmpty()
            lastAckReply = last
            val acknowledged = sequenceOk && replies.size == cmds.size &&
                last.contains("PRECONFG", true) && last.contains(upperCsc, ignoreCase = true)

            // Mid-sequence rejections are expected on some firmware and are not reported --
            // only the final outcome below is shown to the user.
            if (acknowledged) return true
        }

        return false
    }

    /** Forces Download (Odin) mode via [DOWNLOAD_MODE_COMMANDS]. */
    suspend fun tryRebootToDownloadMode(device: UsbDevice, log: (String) -> Unit): Boolean =
        runAtSequence(device, DOWNLOAD_MODE_COMMANDS, log, stopOnSuccess = true)

    /** Forces Unisoc BROM mode by running the full verified [BROM_FORCE_SEQUENCE] over the
     *  MTP AT port -- all five commands, in order, same as the reference tool (not
     *  alternatives tried one at a time, unlike the older guessed-command approach this
     *  replaces). "Success" here only means the port accepted the commands and/or the
     *  device dropped off the bus to re-enumerate as BROM -- the caller still has to
     *  re-scan USB to confirm the BROM VID/PID actually showed up. */
    suspend fun tryRebootToBrom(device: UsbDevice, log: (String) -> Unit): Boolean =
        forceBromSequence(device, log)

    private suspend fun forceBromSequence(device: UsbDevice, log: (String) -> Unit): Boolean {
        if (!ensurePermission(device)) { log("USB permission denied."); return false }
        val conn = usbManager.openDevice(device) ?: run { log("Could not open the device."); return false }
        try {
            for (intf in candidateInterfaces(device)) {
                if (!conn.claimInterface(intf, true)) continue
                try {
                    val epIn = runCatching { bulkIn(intf) }.getOrNull() ?: continue
                    val epOut = runCatching { bulkOut(intf) }.getOrNull() ?: continue
                    assertControlLines(conn, intf)

                    val probe = exchange(conn, epIn, epOut, "AT", quick = true)
                    if (probe.contains("ERROR", true) && probe.length > 40) continue

                    log("Talking to the device...")
                    for ((i, cmd) in BROM_FORCE_SEQUENCE.withIndex()) {
                        exchange(conn, epIn, epOut, cmd)
                        onProgress?.invoke((i + 1).toFloat() / BROM_FORCE_SEQUENCE.size)
                        if (i < BROM_FORCE_SEQUENCE.size - 1) kotlinx.coroutines.delay(BROM_FORCE_STEP_DELAY_MS)
                        if (waitForDetach(device, 300)) {
                            log("USB link dropped: the device is switching mode.")
                            return true
                        }
                    }
                    log("Request sent.")
                    return true
                } finally {
                    runCatching { conn.releaseInterface(intf) }
                }
            }
        } finally {
            runCatching { conn.close() }
        }
        log("No usable modem port found. Enable a USB mode with MODEM (*#0808#) and retry.")
        return false
    }

    /**
     * Same as [tryReboot] but with an extra AT+POWRESET=0,0 step after the standard
     * reboot commands — taken directly from MA UNLOCKER's WriteSerial sequence.
     * This is important when the normal REBOOT_COMMANDS only reset the modem stack
     * instead of forcing a full hardware reboot.
     */
    suspend fun rebootVerifiedWithPowerReset(device: UsbDevice, log: (String) -> Unit): Boolean {
        if (!ensurePermission(device)) { log("USB permission denied."); return false }
        val conn = usbManager.openDevice(device) ?: run { log("Could not open the device."); return false }
        try {
            val interfaces = candidateInterfaces(device)
            if (interfaces.isEmpty()) {
                log("No modem port available. Dial *#0808# → include MODEM → retry.")
                return false
            }
            for (intf in interfaces) {
                if (!conn.claimInterface(intf, true)) continue
                try {
                    val epIn = runCatching { bulkIn(intf) }.getOrNull() ?: continue
                    val epOut = runCatching { bulkOut(intf) }.getOrNull() ?: continue
                    assertControlLines(conn, intf)

                    // Probe
                    val probe = exchange(conn, epIn, epOut, "AT", quick = true)
                    if (probe.contains("ERROR", true) && probe.length > 40) continue

                    var anySuccess = false
                    // First try standard reboot commands. A command that is actually going to
                    // drop the device off USB does so within a second or two -- waiting the old
                    // 10s per command here was the main reason a reboot (and so the whole CSC/
                    // serial change it ends) could take the better part of a minute whenever the
                    // first command or two only reset the modem stack instead of the full device
                    // (REBOOT_COMMANDS tries several commands precisely because that's common).
                    // 3s per command is enough margin for a real detach while cutting the
                    // worst case (every command but the last one failing) from ~50s to ~12s.
                    for ((ci, cmd) in REBOOT_COMMANDS.withIndex()) {
                        onProgress?.invoke(ci.toFloat() / REBOOT_COMMANDS.size)
                        val bytes = cmd.toByteArray(Charsets.US_ASCII)
                        val sent = conn.bulkTransfer(epOut, bytes, bytes.size, 2000)
                                if (sent < 0) continue
                        val buf = ByteArray(READ_BUF)
                        val n = conn.bulkTransfer(epIn, buf, buf.size, 1200)
                        if (waitForDetach(device, REBOOT_DETACH_WAIT_MS)) {
                            log("Device is rebooting.")
                            anySuccess = true
                            break
                        }
                    }
                    // If device stayed connected, try an alternate reboot request.
                    if (!anySuccess) {
                        log("Trying an alternate reboot method...")
                        val powresetBytes = "AT+POWRESET=0,0\r\n".toByteArray(Charsets.US_ASCII)
                        val sent = conn.bulkTransfer(epOut, powresetBytes, powresetBytes.size, 2000)
                        if (sent >= 0) {
                            if (waitForDetach(device, POWRESET_DETACH_WAIT_MS)) {
                                log("Device is rebooting.")
                                anySuccess = true
                            } else {
                                log("Device did not respond to the reboot request.")
                            }
                        }
                    }
                    if (anySuccess) return true
                } finally {
                    runCatching { conn.releaseInterface(intf) }
                }
            }
        } finally {
            runCatching { conn.close() }
        }
        log("The phone did not reboot. You may need to restart manually.")
        return false
    }

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
