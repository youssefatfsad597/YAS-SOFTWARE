package com.edl.flasher.ui

/**
 * The console is for the technician, not the developer: it only shows what the tool is doing,
 * what it has done, and any error / tip. Protocol chatter, raw commands, addresses, byte
 * counts and similar internals are dropped or rewritten here before a line is stored.
 */
object LogSanitizer {

    private val dropPatterns: List<Regex> = listOf(
        "sahara", "firehose ready", "^target ", "^serial number\\s*:", "^msm hwid", "^oem ",
        "^\\[target\\]", "^odin handshake", "^odin protocol", "^downloading pit", "^pit downloaded",
        "^odin session", "^interface \\d+", "^sending at command", "^sending '", "^computing total",
        "^total bytes", "^applying \\d+ disk patches", "^patches applied", "^embedded .*firmware detected",
        "^using embedded images", "^loading embedded", "^sparse image", "^verifying write", "^erase check",
        "^re-negotiating", "^no reply to", "^usb link dropped", "^recognized:", "^lun \\d+: read",
        "^found '.*' ->", "^flashing raw userdata", "^reboot command sent\\.?$", "^welcome to .*embedded",
        // Internal diagnostic trace and the per-window "post-write check" notice that some loaders
        // print on EVERY transfer window during a quick-format userdata write: it is non-fatal and
        // already retried/handled internally, so (same as the official PC tool) it never reaches the
        // technician's console. Genuine failures still surface as a normal ERROR line.
        "^userdata trace", "^warning: device returned a post-write check"
    ).map { Regex(it, RegexOption.IGNORE_CASE) }

    private val rewrites: List<Pair<Regex, String>> = listOf(
        "^USB device connected: (.*)$" to "Device connected: $1",
        "^USB device disconnected\\..*$" to "Device disconnected. Waiting for a device...",
        "^9008 connected automatically.*$" to "Device connected in EDL mode and ready.",
        "^ERROR: could not open the .* USB interface \\(busy\\)\\.?$" to
            "ERROR: Could not connect to the device (busy). Re-plug the cable, then tap Rescan USB.",
        "^USB permission denied for .*$" to "USB access was denied. Tap Rescan USB and allow access.",
        "^USB permission denied.*$" to "USB access was denied. Tap Rescan USB and allow access.",
        "^Uploading loader.*$" to "Preparing the device...",
        "^Reading Full GPT Partition Table\\.*$" to "Reading the partition table...",
        "^Scanning full GPT.*$" to "Scanning storage for partitions...",
        "^Full GPT Loaded: Total (\\d+) partitions ready\\.?$" to "Partition table loaded: $1 partitions found.",
        "^=== STEP 1: .*$" to "Step 1: Erasing partitions...",
        "^=== STEP 2: .*$" to "Step 2: Writing FRP, SEC_EFS and PERSISTENT...",
        "^Storage type set to (\\w+).*$" to "Storage type: $1",
        "^Detected Samsung Download-Mode device.*$" to "Samsung Download Mode device detected.",
        "^WARNING: the loader refused .*$" to
            "WARNING: The device refused the software reboot. Hold Power + Vol Down for about 10 seconds.",
        "^ERROR: Firehose not ready.*$" to "ERROR: The device is not ready. Connect it and prepare it first.",
        "^ERROR: No AT-capable.*$" to "This device does not expose a modem port, so this method is unavailable.",
        "^No CDC/serial-style interface.*$" to "This device does not expose a modem port, so this method is unavailable.",
        "^Could not open USB device for AT command\\.?$" to "Could not open the device. Re-plug the cable and try again.",
        "^WARNING: loader ACKed .*Erase had NO effect\\.?$" to
            "WARNING: The erase did not take effect (data is still present). The device may be blocking it.",
        "^Write verified OK.*$" to "Write verified successfully.",
        "^Erase verified.*$" to "Erase verified.",
        "^WARNING: .*[Ee]mbedded.*$" to
            "WARNING: Built-in firmware files are missing from this build, so the one-tap service flow is disabled.",
        "^Skipping '(.*)': no matching PIT entry.*$" to "Skipping '$1': not part of this device's layout.",
        "^Writing '(.*)' -> partition '(.*)'.*$" to "Writing '$1' to '$2'...",
        "^Quick-format mode: sending small '(.*)'.*$" to "Quick-format mode: sending a small '$1' on purpose.",
        "^Quick-format mode: sending small userdata.*$" to "Quick-format mode: sending a small userdata image on purpose."
    ).map { Regex(it.first, setOf(RegexOption.DOT_MATCHES_ALL)) to it.second }

    private val errorWrapper = Regex("^ERROR \\[([^\\]]*)\\]:\\s*(.*)$", RegexOption.DOT_MATCHES_ALL)

    /** Returns the line to show, or null when it must not appear in the console. */
    fun clean(raw: String): String? {
        val msg = raw.trim()
        if (msg.isEmpty()) return null

        errorWrapper.find(msg)?.let { m ->
            val title = m.groupValues[1].replace("Firehose", "Loader").replace("Sahara", "Loader")
            return "ERROR: $title failed. ${friendlyError(m.groupValues[2])}"
        }
        if (dropPatterns.any { it.containsMatchIn(msg) }) return null
        for ((regex, replacement) in rewrites) {
            if (regex.containsMatchIn(msg)) return scrub(regex.replace(msg, replacement))
        }
        return scrub(msg)
    }

    private fun friendlyError(detail: String): String = when {
        detail.contains("configure", true) ->
            "The device rejected the storage setup. Check the storage type (UFS / eMMC) or try another loader."
        detail.contains("disconnected", true) ->
            "The device was disconnected. Reconnect it and try again."
        detail.contains("timed out", true) || detail.contains("timeout", true) ->
            "The device stopped responding. Re-plug the cable and try again."
        detail.contains("not open", true) || detail.contains("connection not", true) ->
            "No device connection. Connect the device first."
        detail.contains("rejected", true) || detail.contains("nak", true) ->
            "The device rejected the operation (it may be locked or protected)."
        detail.contains("not found", true) -> scrub(detail)
        else -> scrub(detail).ifBlank { "Something went wrong. Re-plug the cable and try again." }
    }

    /** Removes raw addresses, XML tags, byte counts and similar internals from a line. */
    private fun scrub(s: String): String {
        var r = s
        r = r.replace(Regex("<[^>]*>"), "")
        r = r.replace(Regex("\\s*\\((?:LUN|LBA)[^)]*\\)"), "")
        r = r.replace(Regex("\\s*\\(id=\\d+[^)]*\\)"), "")
        r = r.replace(Regex("\\s*\\(\\d+ (?:bytes|sectors)\\)"), "")
        r = r.replace(Regex("\\s*\\((?:ret|VID)=[^)]*\\)"), "")
        r = r.replace(Regex("\\s*(?:on )?\\bLUN \\d+"), "")
        r = r.replace(Regex("\\s*\\[[0-9A-Fa-f]{4}:[0-9A-Fa-f]{4}\\]"), "")
        r = r.replace(Regex("\\s*0x[0-9A-Fa-f]+"), "")
        r = r.replace(Regex(" {2,}"), " ")
        return r.trim()
    }
}
