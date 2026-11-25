package com.rcmiku.payload.dumper.compose.utils

import com.rcmiku.payload.dumper.compose.model.FileInfo
import java.nio.ByteBuffer
import java.nio.ByteOrder

object ZipFileUtil {

    private const val CENSIG = 0x02014b50L   // "PK\001\002"
    private const val LOCSIG = 0x04034b50L   // "PK\003\004"
    private const val ENDSIG = 0x06054b50L // "PK\005\006"
    private const val ENDHDR = 22
    private const val ZIP64_ENDSIG = 0x06064b50L  // "PK\006\006"
    private const val ZIP64_LOCSIG = 0x07064b50L  // "PK\006\007"
    private const val ZIP64_LOCHDR = 20
    private const val ZIP64_MAGICVAL = 0xFFFFFFFFL

    internal suspend fun readCentralDirectory(input: SeekableInput): ByteArray? {
        val fileLength = input.length()
        val tailSize = minOf(4096L, fileLength)
        if (tailSize < ENDHDR) return null
        val tailStart = fileLength - tailSize
        val tail = ByteArray(tailSize.toInt())
        input.seek(tailStart)
        input.readFully(tail)
        val info = locateCentralDirectory(tail, fileLength)
        if (info.offset < 0 || info.size !in 1..Int.MAX_VALUE.toLong() ||
            info.size > fileLength - info.offset
        ) return null

        if (info.offset >= tailStart) {
            val start = (info.offset - tailStart).toInt()
            return tail.copyOfRange(start, start + info.size.toInt())
        }

        val directory = ByteArray(info.size.toInt())
        input.seek(info.offset)
        input.readFully(directory)
        return directory
    }

    fun locateCentralDirectory(byteArray: ByteArray, fileLength: Long): FileInfo {
        val byteBuffer = ByteBuffer.wrap(byteArray).order(ByteOrder.LITTLE_ENDIAN)
        val offset = byteBuffer.capacity() - ENDHDR
        var cenSize: Long = -1
        var cenOffset: Long = -1

        for (i in 0..byteBuffer.capacity() - ENDHDR) {
            byteBuffer.position(offset - i)
            if (byteBuffer.getInt().toLong() == ENDSIG) {
                val endSigOffset = byteBuffer.position()
                byteBuffer.position(byteBuffer.position() + 12)

                if (byteBuffer.getInt().toUInt().toLong() == ZIP64_MAGICVAL) {
                    byteBuffer.position(endSigOffset - ZIP64_LOCHDR - 4)

                    if (byteBuffer.getInt().toLong() == ZIP64_LOCSIG) {
                        byteBuffer.position(byteBuffer.position() + 4)
                        val zip64EndSigOffset = byteBuffer.getLong()
                        // The window may be shorter than 4096 bytes for a small file; use its real
                        // capacity rather than a fixed size so the seek stays in bounds.
                        byteBuffer.position(byteBuffer.capacity() - (fileLength - zip64EndSigOffset).toInt())

                        if (byteBuffer.getInt().toLong() == ZIP64_ENDSIG) {
                            byteBuffer.position(byteBuffer.position() + 36)
                            cenSize = byteBuffer.getLong().toULong().toLong()
                            cenOffset = byteBuffer.getLong().toULong().toLong()
                        }
                    }
                } else {
                    byteBuffer.position(endSigOffset + 8)
                    cenSize = byteBuffer.getInt().toUInt().toLong()
                    cenOffset = byteBuffer.getInt().toUInt().toLong()
                    break
                }
            }
        }

        return FileInfo(cenOffset, cenSize)
    }

    fun locateLocalFileHeader(byteArray: ByteArray, fileName: String): Long {
        return locateCentralDirectoryEntry(byteArray, fileName)?.localHeaderOffset ?: -1L
    }

    fun locateLocalFileOffset(byteArray: ByteArray): Long {
        if (byteArray.size < 30) return -1
        val byteBuffer = ByteBuffer.wrap(byteArray).order(ByteOrder.LITTLE_ENDIAN)
        if (byteBuffer.int.toLong() != LOCSIG) return -1
        val fileNameLength = byteBuffer.getShort(26).toUShort().toInt()
        val extraFieldLength = byteBuffer.getShort(28).toUShort().toInt()
        return 30L + fileNameLength + extraFieldLength
    }

    /**
     * Compression method and size of a central-directory entry, resolved by [fileName].
     *
     * The central directory is authoritative: a DEFLATED entry written by a streaming writer
     * (e.g. Java's `ZipOutputStream`) carries a data descriptor, so its *local* header size is 0.
     * Reading the size from here is therefore the only correct source.
     */
    data class CentralDirectoryEntry(
        val localHeaderOffset: Long,
        val method: Int,
        val compressedSize: Long,
    )

    /**
     * Finds the central-directory entry for [fileName], or null when it is absent. Guards every
     * read against the buffer end so a truncated directory yields null rather than throwing.
     */
    fun locateCentralDirectoryEntry(
        byteArray: ByteArray,
        fileName: String,
    ): CentralDirectoryEntry? {
        val byteBuffer = ByteBuffer.wrap(byteArray).order(ByteOrder.LITTLE_ENDIAN)

        val name = fileName.toByteArray(Charsets.UTF_8)
        while (byteBuffer.remaining() >= 46) {
            if (byteBuffer.getInt().toLong() != CENSIG) break
            byteBuffer.position(byteBuffer.position() + 4) // version made by + version needed
            byteBuffer.position(byteBuffer.position() + 2) // general purpose flags
            val method = byteBuffer.getShort().toUShort().toInt()
            byteBuffer.position(byteBuffer.position() + 8) // mod time + date + crc32
            val compressedSize = byteBuffer.getInt().toUInt().toLong()
            byteBuffer.position(byteBuffer.position() + 4) // uncompressed size
            val fileNameLength = byteBuffer.getShort().toUShort().toInt()
            val extraFieldLength = byteBuffer.getShort().toUShort().toInt()
            val fileCommentLength = byteBuffer.getShort().toUShort().toInt()
            byteBuffer.position(byteBuffer.position() + 8) // disk + internal + external attrs
            val localHeaderOffset = byteBuffer.getInt().toUInt().toLong()

            val entrySize = fileNameLength + extraFieldLength + fileCommentLength
            if (entrySize > byteBuffer.remaining()) break
            val nameStart = byteBuffer.position()
            if (fileNameLength == name.size && name.indices.all { name[it] == byteArray[nameStart + it] }) {
                return CentralDirectoryEntry(localHeaderOffset, method, compressedSize)
            }
            byteBuffer.position(nameStart + entrySize)
        }

        return null
    }
}