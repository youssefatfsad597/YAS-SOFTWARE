package com.edl.flasher.protocol

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class PartitionEntry(
    val name: String,
    val lun: Int,
    val firstLba: Long,
    val lastLba: Long,
    val sectorSize: Int,
    val typeGuid: String
) {
    val idKey: String get() = "${lun}_${name}_${firstLba}"
    val numSectors: Long get() = (lastLba - firstLba) + 1
    val sizeInBytes: Long get() = numSectors * sectorSize
}

object GptParser {
    private const val EFI_PART_SIGNATURE = 0x5452415020494645L // "EFI PART"

    @Throws(IOException::class)
    fun readGptForLun(firehose: FirehoseProtocol, lun: Int): List<PartitionEntry> {
        val sectorSize = firehose.storageType.sectorSize
        val initialSectors = if (sectorSize == 4096) 6L else 34L

        val initialOut = ByteArrayOutputStream()
        val ok = runCatching {
            firehose.readSectors(lun, startSector = 0, numSectors = initialSectors, output = initialOut)
        }.isSuccess
        if (!ok) return emptyList()

        var raw = initialOut.toByteArray()
        if (raw.size < sectorSize * 2) return emptyList()

        val rawBuf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)

        val headerOffset = when {
            rawBuf.getLong(sectorSize) == EFI_PART_SIGNATURE -> sectorSize
            raw.size >= 4096 + 92 && rawBuf.getLong(4096) == EFI_PART_SIGNATURE -> 4096
            raw.size >= 512 + 92 && rawBuf.getLong(512) == EFI_PART_SIGNATURE -> 512
            else -> return emptyList()
        }

        val partitionEntryLba = rawBuf.getLong(headerOffset + 72).coerceAtLeast(2L)
        val numPartitionEntries = rawBuf.getInt(headerOffset + 80).coerceIn(1, 512)
        val sizeOfPartitionEntry = rawBuf.getInt(headerOffset + 84).coerceIn(128, 512)

        val entriesStartByte = (partitionEntryLba * sectorSize).toInt()
        val totalEntriesBytes = numPartitionEntries * sizeOfPartitionEntry
        val requiredTotalBytes = entriesStartByte + totalEntriesBytes

        if (requiredTotalBytes > raw.size) {
            val extraBytes = requiredTotalBytes - raw.size
            val extraSectors = ((extraBytes + sectorSize - 1) / sectorSize).toLong()
            val extraOut = ByteArrayOutputStream()
            val extraOk = runCatching {
                firehose.readSectors(
                    lun = lun,
                    startSector = initialSectors,
                    numSectors = extraSectors,
                    output = extraOut
                )
            }.isSuccess
            if (extraOk) {
                val combined = ByteArrayOutputStream(requiredTotalBytes)
                combined.write(raw)
                combined.write(extraOut.toByteArray())
                raw = combined.toByteArray()
            }
        }

        val fullBuf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        val partitions = mutableListOf<PartitionEntry>()

        for (i in 0 until numPartitionEntries) {
            val base = entriesStartByte + (i * sizeOfPartitionEntry)
            if (base + sizeOfPartitionEntry > raw.size) break

            val typeGuidLow = fullBuf.getLong(base)
            val typeGuidHigh = fullBuf.getLong(base + 8)
            if (typeGuidLow == 0L && typeGuidHigh == 0L) continue

            val uniqGuidLow = fullBuf.getLong(base + 16)
            val uniqGuidHigh = fullBuf.getLong(base + 24)
            val firstLba = fullBuf.getLong(base + 32)
            val lastLba = fullBuf.getLong(base + 40)

            val nameBytes = ByteArray(72)
            System.arraycopy(raw, base + 56, nameBytes, 0, 72)
            val name = String(nameBytes, Charsets.UTF_16LE).substringBefore('\u0000').trim()

            if (name.isNotEmpty() && firstLba > 0 && lastLba >= firstLba) {
                partitions.add(
                    PartitionEntry(
                        name = name,
                        lun = lun,
                        firstLba = firstLba,
                        lastLba = lastLba,
                        sectorSize = sectorSize,
                        typeGuid = String.format("%016X%016X", uniqGuidHigh, uniqGuidLow)
                    )
                )
            }
        }
        return partitions
    }
}
