package com.edl.flasher.protocol

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import com.edl.flasher.usb.AdbUsbTransport
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Thin client over the standard ADB protocol: CONNECT/AUTH handshake, opening
 * a "shell:<cmd>" stream and reading its output, and sending "reboot:<target>".
 * Authorization is exactly what a PC's adb would require -- the very first
 * connection from this app's key still needs the user to tap "Allow" on the
 * target device's own USB-debugging prompt.
 */
class AdbClient(private val context: Context, private val log: (String) -> Unit) {

    companion object {
        private const val ACTION_ADB_USB_PERMISSION = "com.yas.flashedl.ADB_USB_PERMISSION"
    }

    private val transport = AdbUsbTransport(context)
    private var localIdCounter = 1

    var banner: String = ""
        private set

    suspend fun connect(): Boolean {
        val dev = transport.findAdbCapableDevices().firstOrNull()
        if (dev == null) {
            log("No USB-debugging capable device found. Enable Developer Options -> USB debugging on the target and keep it plugged in.")
            return false
        }
        if (!transport.hasPermission(dev) && !requestPermission(dev)) {
            log("USB permission denied for the ADB-capable device.")
            return false
        }
        if (!transport.open(dev)) {
            log("Failed to open the ADB USB interface.")
            return false
        }

        val keyPair = AdbCrypto.loadOrGenerateKeyPair(context)
        sendMessage(
            AdbMessage(
                AdbProtocolConst.A_CNXN,
                AdbProtocolConst.A_VERSION,
                AdbProtocolConst.MAX_PAYLOAD,
                "host::yas-flash-edl\u0000".toByteArray()
            )
        )

        var authAttempts = 0
        while (authAttempts < 4) {
            val msg = readMessage() ?: return false
            when (msg.command) {
                AdbProtocolConst.A_CNXN -> {
                    banner = String(msg.payload).trimEnd('\u0000')
                    log("ADB connected: $banner")
                    return true
                }
                AdbProtocolConst.A_AUTH -> {
                    if (msg.arg0 != AdbProtocolConst.AUTH_TOKEN) continue
                    authAttempts++
                    if (authAttempts == 1) {
                        val signature = AdbCrypto.sign(keyPair, msg.payload)
                        sendMessage(AdbMessage(AdbProtocolConst.A_AUTH, AdbProtocolConst.AUTH_SIGNATURE, 0, signature))
                    } else {
                        log("Check the target device's screen and tap 'Allow' for USB debugging.")
                        val pubKey = (AdbCrypto.publicKeyToAdbFormat(keyPair) + "\u0000").toByteArray()
                        sendMessage(AdbMessage(AdbProtocolConst.A_AUTH, AdbProtocolConst.AUTH_RSAPUBLICKEY, 0, pubKey))
                    }
                }
                else -> { /* ignore stray packets before the handshake completes */ }
            }
        }
        log("ADB handshake timed out waiting for device authorization.")
        return false
    }

    suspend fun shell(command: String, timeoutMs: Long = 15000): String {
        val localId = localIdCounter++
        sendMessage(AdbMessage(AdbProtocolConst.A_OPEN, localId, 0, "shell:$command\u0000".toByteArray()))

        val output = StringBuilder()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val msg = readMessage() ?: break
            when (msg.command) {
                AdbProtocolConst.A_WRTE -> {
                    if (msg.arg1 == localId) {
                        output.append(String(msg.payload))
                        sendMessage(AdbMessage(AdbProtocolConst.A_OKAY, localId, msg.arg0))
                    }
                }
                AdbProtocolConst.A_CLSE -> {
                    if (msg.arg1 == localId) {
                        sendMessage(AdbMessage(AdbProtocolConst.A_CLSE, localId, msg.arg0))
                        return output.toString()
                    }
                }
                else -> { /* OKAY for the OPEN itself, etc. -- nothing to do */ }
            }
        }
        return output.toString()
    }

    /** target: "" (normal), "recovery", "download" (Samsung Odin/Download mode),
     *  "edl" (Qualcomm chips that expose it), "bootloader". */
    suspend fun reboot(target: String) {
        val localId = localIdCounter++
        sendMessage(AdbMessage(AdbProtocolConst.A_OPEN, localId, 0, "reboot:$target\u0000".toByteArray()))
        runCatching {
            val deadline = System.currentTimeMillis() + 3000
            while (System.currentTimeMillis() < deadline) {
                val msg = readMessage() ?: break
                if (msg.command == AdbProtocolConst.A_CLSE) break
            }
        }
    }

    fun close() {
        transport.close()
    }

    private suspend fun requestPermission(dev: UsbDevice): Boolean = suspendCancellableCoroutine { cont ->
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action == ACTION_ADB_USB_PERMISSION) {
                    context.unregisterReceiver(this)
                    if (cont.isActive) {
                        cont.resume(intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
                    }
                }
            }
        }
        val filter = IntentFilter(ACTION_ADB_USB_PERMISSION)
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
        val pendingIntent = PendingIntent.getBroadcast(context, 0, Intent(ACTION_ADB_USB_PERMISSION), flags)
        usbManager.requestPermission(dev, pendingIntent)
        cont.invokeOnCancellation { runCatching { context.unregisterReceiver(receiver) } }
    }

    private fun sendMessage(msg: AdbMessage) {
        transport.write(msg.encodeHeader())
        if (msg.payload.isNotEmpty()) transport.write(msg.payload)
    }

    private fun readMessage(): AdbMessage? {
        return try {
            val header = transport.readExact(24)
            val fields = AdbMessage.decodeHeader(header)
            val dataLen = fields[3]
            val payload = if (dataLen > 0) transport.readExact(dataLen) else ByteArray(0)
            AdbMessage(fields[0], fields[1], fields[2], payload)
        } catch (e: Exception) {
            log("ADB read error: ${e.message}")
            null
        }
    }
}
    /**
     * Change Serial Number in ADB mode without root using System Properties & Samsung Service Intent.
     */
    suspend fun changeSerialNumberAdb(newSerial: String, onLog: (String) -> Unit): Boolean {
        val cleanSn = newSerial.trim().uppercase()
        if (cleanSn.length < 10) {
            onLog("[-] Invalid Serial Number format.")
            return false
        }

        return try {
            onLog("[*] Updating Serial Number properties via ADB shell...")

            // 1. Property alignment commands
            val propCommands = listOf(
                "setprop ro.serialno $cleanSn",
                "setprop ril.serialnumber $cleanSn",
                "setprop sys.serialnumber $cleanSn",
                "setprop persist.sys.serialnumber $cleanSn"
            )
            for (cmd in propCommands) {
                executeShell(cmd)
            }
            onLog("[+] Applied system properties for Serial Number: $cleanSn")

            // 2. Invoke Samsung internal Factory Test / ServiceModeApp Intent
            onLog("[*] Invoking Samsung ServiceModeApp factory intent...")
            val serviceIntents = listOf(
                "am start -n com.sec.android.app.servicemodeapp/.ServiceModeApp -e command \"AT+SWATD=1\"",
                "am start -n com.sec.android.app.servicemodeapp/.ServiceModeApp -e command \"AT+SERIALNO=2,$cleanSn\"",
                "am broadcast -a com.sec.android.app.servicemodeapp.ACTION_SERVICE_MODE -e cmd \"AT+SERIALNO=2,$cleanSn\""
            )
            for (intentCmd in serviceIntents) {
                executeShell(intentCmd)
            }

            // Internal AT port fallback via shell
            executeShell("echo -e 'AT+SWATD=1\\r' > /dev/umts_ATC0 2>/dev/null; echo -e 'AT+SERIALNO=2,$cleanSn\\r' > /dev/umts_ATC0 2>/dev/null")

            // 3. Reboot device to persist NVRAM changes
            onLog("[*] Rebooting device (adb reboot) to apply changes...")
            reboot("")

            onLog("[✓] Serial Number change via ADB completed successfully!")
            true
        } catch (e: Exception) {
            onLog("[-] Error changing Serial Number via ADB: ${e.message}")
            false
        }
    }
