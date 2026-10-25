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
        val order = listOf("MODEL", "VER", "PRODUCT", "SALES", "VENDOR", "CAPA", "FWVER", "UN", "DID", "RAND", "TMU_TEMP")
        order.forEach { k -> fields[k]?.let { append(k).append(": ").append(it).append('\n') } }
        fields.filterKeys { it !in order }.forEach { (k, v) -> append(k).append(": ").append(v).append('\n') }
    }.trimEnd()

    companion object {
        private val KEY = Regex("^[A-Z][A-Z0-9_]{1,23}$")

        // Keys seen in a real Download-Mode reply (SM-T505N). Plain "KEY VALUE" lines are only accepted
        // for these, so ordinary status text such as "Odin session initialised" is never mistaken for a field.
        private val KNOWN = setOf("MODEL", "UN", "CAPA", "VENDOR", "FWVER", "PRODUCT", "SALES", "VER", "DID", "RAND", "TMU_TEMP", "PROV")

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
                    val spaceSeparated = line[sep] == ' ' || line[sep] == '\t'
                    if (spaceSeparated && key !in KNOWN) return@forEach
                    if (KEY.matches(key) && value.isNotEmpty() && key !in out) out[key] = value
                }
            return OdinDeviceInfo(out)
        }

        private fun readAvailable(transport: EdlUsbTransport, firstTimeoutMs: Int, idleTimeoutMs: Int): String {
            val sb = StringBuilder()
            val buf = ByteArray(1024)
            var timeout = firstTimeoutMs
            while (sb.length < 8192) {
                val n = try { transport.read(buf, timeoutMs = timeout) } catch (e: java.io.IOException) { -1 }
                if (n <= 0) break
                sb.append(String(buf, 0, n, Charsets.US_ASCII))
                timeout = idleTimeoutMs // the reply can span several USB packets
            }
            return sb.toString()
        }

        private fun stripEcho(text: String): String {
            val t = text.trimStart('\u0000', ' ', '\r', '\n')
            return if (t.startsWith("DVIF")) t.substring(4) else t
        }

        /**
         * Sends the raw 4-byte ASCII request "DVIF" and parses the text that comes back
         * (MODEL / UN / CAPA / VENDOR / FWVER / PRODUCT / SALES / VER / DID / RAND / TMU_TEMP ...).
         *
         * IMPORTANT: this must be the FIRST thing sent on a freshly opened USB handle, BEFORE the
         * "ODIN" -> "LOKE" handshake and before any PIT download. Once the 1024-byte ODIN request framing
         * has started, the bootloader treats "DVIF" as a malformed request and stays silent -- which is
         * why the old flow (open session, then probe) always reported "no reply".
         *
         * Also: anything the bootloader pushes on its own right after enumeration is kept and merged in,
         * the reply is accumulated across several USB packets, and the request is retried a few times.
         * Never throws.
         */
        fun probe(transport: EdlUsbTransport, logger: (String) -> Unit): OdinDeviceInfo {
            return runCatching {
                val merged = LinkedHashMap<String, String>()
                // 1) unsolicited banner / stale bytes
                val stale = readAvailable(transport, firstTimeoutMs = 200, idleTimeoutMs = 120)
                if (stale.isNotBlank()) parse(stale).fields.forEach { (k, v) -> merged.putIfAbsent(k, v) }

                // 2) explicit request, up to 3 attempts
                for (attempt in 1..3) {
                    transport.write("DVIF".toByteArray(Charsets.US_ASCII), timeoutMs = 3000)
                    val reply = readAvailable(transport, firstTimeoutMs = 2500, idleTimeoutMs = 350)
                    if (reply.isNotBlank()) {
                        val info = parse(stripEcho(reply))
                        if (!info.isEmpty) {
                            info.fields.forEach { (k, v) -> merged.putIfAbsent(k, v) }
                            break
                        }
                        logger("Device info: reply #$attempt not recognised (${reply.length} bytes).")
                    } else {
                        logger("Device info: no reply to DVIF (attempt $attempt/3).")
                    }
                    Thread.sleep(300)
                }
                OdinDeviceInfo(merged)
            }.getOrElse {
                logger("Device info: probe failed (${it.message}).")
                OdinDeviceInfo(LinkedHashMap())
            }
        }
    }
}
