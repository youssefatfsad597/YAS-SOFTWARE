package com.edl.flasher.protocol

data class SaharaDeviceInfo(
    val saharaVersion: Int,
    val serialNumber: UInt,
    val msmHwId: UInt,
    val oemId: UShort,
    val modelId: UShort,
    val fullHwIdHex: String,
    val pkHashHex: String,
    val sblVersion: UInt?
) {
    val serialHex: String
        get() = String.format("0x%08X", serialNumber.toInt()) + " ($serialNumber)"

    val msmIdHex: String
        get() = String.format("0x%08X", msmHwId.toInt())

    val oemName: String
        get() = KNOWN_OEM_IDS[oemId.toInt()] ?: String.format("Unknown (0x%04X)", oemId.toInt())

    val socName: String
        get() = KNOWN_MSM_IDS[msmHwId.toInt() and 0x00FFFFF1] ?: "Qualcomm Snapdragon SoC"

    companion object {
        private val KNOWN_OEM_IDS = mapOf(
            0x0000 to "Qualcomm Generic",
            0x0015 to "Huawei",
            0x0017 to "Lenovo / Motorola",
            0x0020 to "Samsung",
            0x0029 to "ASUS",
            0x0031 to "ZTE",
            0x0045 to "OnePlus / OPPO",
            0x0051 to "Xiaomi",
            0x0072 to "Xiaomi / Redmi",
            0x0073 to "Vivo / iQOO",
            0x00D8 to "Google"
        )

        private val KNOWN_MSM_IDS = mapOf(
            0x000460E1 to "MSM8953 (SD625)",
            0x0008C0E1 to "SDM660 (SD660)",
            0x0008B0E1 to "SDM845 (SD845)",
            0x0009A0E1 to "SM8150 (SD855)",
            0x000C30E1 to "SM8250 (SD865)",
            0x001350E1 to "SM8350 (SD888)",
            0x0014D0E1 to "SM7325 (SD778G)",
            0x001620E1 to "SM8450 (SD 8 Gen 1)",
            0x001920E1 to "SM8475 (SD 8+ Gen 1)",
            0x001CA0E1 to "SM8550 (SD 8 Gen 2)",
            0x002280E1 to "SM8650 (SD 8 Gen 3)"
        )

        fun normalizePkHash(rawBytes: ByteArray): String {
            if (rawBytes.isEmpty()) return "Unavailable"
            if (rawBytes.size == 64 || rawBytes.size == 96) {
                val first32 = rawBytes.copyOfRange(0, 32)
                val second32 = rawBytes.copyOfRange(32, 64)
                if (first32.contentEquals(second32) || second32.all { it == 0.toByte() }) {
                    return first32.toHexString()
                }
            }
            if (rawBytes.size == 96 || rawBytes.size == 144) {
                val first48 = rawBytes.copyOfRange(0, 48)
                val second48 = rawBytes.copyOfRange(48, 96)
                if (first48.contentEquals(second48) || second48.all { it == 0.toByte() }) {
                    return first48.toHexString()
                }
            }
            val slice = when {
                rawBytes.size == 48 -> rawBytes
                rawBytes.size > 48 && rawBytes.size % 32 == 0 -> rawBytes.copyOfRange(0, 32)
                else -> rawBytes
            }
            return slice.toHexString()
        }

        private fun ByteArray.toHexString(): String =
            joinToString("") { "%02x".format(it) }
    }
}
