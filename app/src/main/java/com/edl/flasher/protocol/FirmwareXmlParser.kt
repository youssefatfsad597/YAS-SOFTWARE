package com.edl.flasher.protocol

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream

data class RawProgramOp(
    val label: String,
    val filename: String,
    val physicalPartitionNumber: Int,
    val startSector: Long,
    val startSectorRaw: String,
    val numPartitionSectors: Long,
    val sectorSizeInBytes: Int
)

object FirmwareXmlParser {

    fun parseRawProgramXml(input: InputStream): List<RawProgramOp> {
        val ops = mutableListOf<RawProgramOp>()
        val parser = Xml.newPullParser()
        parser.setInput(input, "UTF-8")

        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG && parser.name == "program") {
                val filename = parser.getAttributeValue(null, "filename").orEmpty().trim()
                val label = parser.getAttributeValue(null, "label").orEmpty()
                val lun = parser.getAttributeValue(null, "physical_partition_number")?.toIntOrNull() ?: 0
                val startSectorRaw = parser.getAttributeValue(null, "start_sector").orEmpty().trim()
                val startSector = startSectorRaw.toLongOrNull() ?: -1L
                val numSectors = parser.getAttributeValue(null, "num_partition_sectors")?.toLongOrNull() ?: 0L
                val sectorSize = parser.getAttributeValue(null, "SECTOR_SIZE_IN_BYTES")?.toIntOrNull() ?: 4096

                if (filename.isNotEmpty()) {
                    ops.add(
                        RawProgramOp(
                            label = label,
                            filename = filename,
                            physicalPartitionNumber = lun,
                            startSector = startSector,
                            startSectorRaw = startSectorRaw,
                            numPartitionSectors = numSectors,
                            sectorSizeInBytes = sectorSize
                        )
                    )
                }
            }
            eventType = parser.next()
        }
        return ops
    }

    fun parsePatchXml(input: InputStream): List<Map<String, String>> {
        val patches = mutableListOf<Map<String, String>>()
        val parser = Xml.newPullParser()
        parser.setInput(input, "UTF-8")

        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG && parser.name == "patch") {
                val attrs = mutableMapOf<String, String>()
                for (i in 0 until parser.attributeCount) {
                    attrs[parser.getAttributeName(i)] = parser.getAttributeValue(i)
                }
                if (attrs["filename"] == "DISK") {
                    patches.add(attrs)
                }
            }
            eventType = parser.next()
        }
        return patches
    }
}
