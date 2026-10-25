package com.edl.flasher.protocol

import com.edl.flasher.usb.EdlUsbTransport

/**
 * Device-info text that a Samsung Download-Mode bootloader reports at the start of a
 * session, e.g.
 *
 *   DID 041CC29D002B
 *   RAND D+OhtrTgswx62qEHOaA6NpV6Q12y4AkTaG5p43Pu5vw
 *   TMU_TEMP NA
 *   Initialising download protocol..
 *
 * The parser accepts both "KEY VALUE" lines and "KEY=VALUE;KEY:VALUE" style replies,
 * keeps unknown keys as well, and ignores plain status lines.
 */
data class OdinDeviceInfo(val fields: LinkedHashMap<String, String>) {

    val did: String get() = fields["DID"].orEmpty()
    val rand: String get() = fields["RAND"].orEmpty()
    val tmuTemp: String get() = fields["TMU_TEMP"].orEmpty()
    val isEmpty: Boolean get() = fields.isEmpty()

    /** "Key: value" lines, the format the app's info cards already understand. */
    fun toDisplayText(): String = buildString {
        val order = listOf("DID", "RAND", "TMU_TEMP")
        order.forEach { k -> fields[k]?.let { append(k).append(": ").append(it).append('\n') } }
        fields.filterKeys { it !in order }.forEach { (k, v) -> append(k).append(": ").append(v).append('\n') }
    }.trimEnd()

    companion object {
        private val KEY = Regex("^[A-Z][A-Z0-9_]{1,23}$")

        fun parse(raw: String): OdinDeviceInfo {
            val out = LinkedHashMap<String, String>()
            raw.replace('\u0000', '\n')
                .split('\n', '\r', ';')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .forEach { line ->
                    val sep = line.indexOfFirst { it == '=' || it == ':' || it == ' ' || it == '\t' }
                    if (sep <= 0) return@forEach
                    val key = line.substring(0, sep).trim().uppercase()
                    val value = line.substring(sep + 1).trim().trimStart('=', ':', ' ', '\t')
                    if (KEY.matches(key) && value.isNotEmpty() && key !in out) out[key] = value
                }
            return OdinDeviceInfo(out)
        }

        /**
         * EXPERIMENTAL / UNTESTED on hardware: sends the 4-byte ASCII request "DVIF" (same raw style as
         * the "ODIN" handshake) and parses whatever text comes back. Never throws. The caller must close
         * and reopen the USB interface afterwards so the ODIN session starts from a clean state.
         */
        fun probe(transport: EdlUsbTransport, logger: (String) -> Unit): OdinDeviceInfo {
            return runCatching {
                transport.write("DVIF".toByteArray(Charsets.US_ASCII), timeoutMs = 2000)
                val buf = ByteArray(1024)
                val n = transport.read(buf, timeoutMs = 1500)
                if (n <= 0) {
                    logger("Device info: no reply to DVIF (this device/bootloader may not support it).")
                    OdinDeviceInfo(LinkedHashMap())
                } else {
                    parse(String(buf, 0, n, Charsets.US_ASCII)).also {
                        if (it.isEmpty) logger("Device info: reply was not in a recognised format ($n bytes).")
                    }
                }
            }.getOrElse {
                logger("Device info: probe failed (${it.message}).")
                OdinDeviceInfo(LinkedHashMap())
            }
        }
    }
}
