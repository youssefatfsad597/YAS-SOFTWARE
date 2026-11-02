package com.edl.flasher.protocol

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class SparseImageHeader(
    val magic: Int,
    val majorVersion: Short,
    val minorVersion: Short,
    val fileHdrSz: Short,
    val chunkHdrSz: Short,
    val blkSz: Int,
    val totalBlks: Int,
    val totalChunks: Int
) {
    companion object {
        const val SPARSE_HEADER_MAGIC = 0xED26FF3A.toInt()
        const val CHUNK_TYPE_RAW = 0xCAC1
        const val CHUNK_TYPE_FILL = 0xCAC2
        const val CHUNK_TYPE_DONT_CARE = 0xCAC3
        const val CHUNK_TYPE_CRC32 = 0xCAC4

        fun parse(bytes: ByteArray): SparseImageHeader? {
            if (bytes.size < 28) return null
            val buf = ByteBuffer.wrap(bytes, 0, 28).order(ByteOrder.LITTLE_ENDIAN)
            val magic = buf.int
            if (magic != SPARSE_HEADER_MAGIC) return null
            return SparseImageHeader(
                magic = magic,
                majorVersion = buf.short,
                minorVersion = buf.short,
                fileHdrSz = buf.short,
                chunkHdrSz = buf.short,
                blkSz = buf.int,
                totalBlks = buf.int,
                totalChunks = buf.int
            )
        }
    }
}

data class SparseChunkHeader(
    val chunkType: Int,
    val chunkSz: Int,
    val totalSz: Int
) {
    companion object {
        fun readFrom(input: InputStream, chunkHdrSz: Int): SparseChunkHeader {
            val hdrBytes = ByteArray(chunkHdrSz)
            var read = 0
            while (read < chunkHdrSz) {
                val r = input.read(hdrBytes, read, chunkHdrSz - read)
                if (r < 0) throw IllegalStateException("Unexpected EOF reading sparse chunk header")
                read += r
            }
            val buf = ByteBuffer.wrap(hdrBytes).order(ByteOrder.LITTLE_ENDIAN)
            val type = buf.short.toInt() and 0xFFFF
            buf.short
            val chunkSz = buf.int
            val totalSz = buf.int
            return SparseChunkHeader(type, chunkSz, totalSz)
        }
    }
}
