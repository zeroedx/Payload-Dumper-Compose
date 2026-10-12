package com.rcmiku.payload.dumper.compose

import com.rcmiku.payload.dumper.compose.utils.ZipFileUtil.locateCentralDirectory
import com.rcmiku.payload.dumper.compose.utils.ZipFileUtil.locateLocalFileHeader
import com.rcmiku.payload.dumper.compose.utils.ZipFileUtil.locateLocalFileOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * Pure-JVM tests for the ZIP offset math in
 * [com.rcmiku.payload.dumper.compose.utils.ZipFileUtil].
 *
 * The fixtures are built by hand so the tests do not depend on [java.util.zip], which would
 * otherwise hide bugs in our own parser.
 */
class ZipFileUtilTest {

    private fun le16(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v ushr 8) and 0xFF).toByte())
    private fun le32(v: Long) =
        byteArrayOf(
            (v and 0xFF).toByte(),
            ((v ushr 8) and 0xFF).toByte(),
            ((v ushr 16) and 0xFF).toByte(),
            ((v ushr 24) and 0xFF).toByte()
        )

    /** A single-entry ZIP containing `payload.bin`, laid out by hand. */
    private data class ZipFixture(
        val bytes: ByteArray,
        val centralDirOffset: Long,
        val centralDirSize: Long,
        val localHeaderOffset: Long,
        val payloadDataOffset: Long
    )

    private fun buildZip(entryName: String, content: ByteArray): ZipFixture {
        val nameBytes = entryName.toByteArray(Charsets.UTF_8)
        val out = ByteArrayOutputStream()

        // --- Local file header ---
        val localHeaderOffset = 0L
        out.write(le32(0x04034b50L)) // LOCSIG
        out.write(le16(20))          // version needed
        out.write(le16(0))           // flags
        out.write(le16(0))           // method = stored
        out.write(le16(0)); out.write(le16(0)) // time/date
        out.write(le32(0))           // crc32 (unused here)
        out.write(le32(content.size.toLong())) // compressed size
        out.write(le32(content.size.toLong())) // uncompressed size
        out.write(le16(nameBytes.size))        // file name length
        out.write(le16(0))                     // extra field length
        out.write(nameBytes)
        val payloadDataOffset = out.size().toLong()
        out.write(content)

        // --- Central directory ---
        val centralDirOffset = out.size().toLong()
        out.write(le32(0x02014b50L)) // CENSIG
        out.write(le16(20))          // version made by
        out.write(le16(20))          // version needed
        out.write(le16(0))           // flags
        out.write(le16(0))           // method
        out.write(le16(0)); out.write(le16(0)) // time/date
        out.write(le32(0))           // crc32
        out.write(le32(content.size.toLong()))
        out.write(le32(content.size.toLong()))
        out.write(le16(nameBytes.size))
        out.write(le16(0))           // extra
        out.write(le16(0))           // comment
        out.write(le16(0))           // disk number start
        out.write(le16(0))           // internal attrs
        out.write(le32(0))           // external attrs
        out.write(le32(localHeaderOffset)) // relative offset of local header
        out.write(nameBytes)
        val centralDirSize = out.size().toLong() - centralDirOffset

        // --- End of central directory ---
        out.write(le32(0x06054b50L)) // ENDSIG
        out.write(le16(0)); out.write(le16(0)) // disk numbers
        out.write(le16(1)); out.write(le16(1)) // entries on disk / total
        out.write(le32(centralDirSize))
        out.write(le32(centralDirOffset))
        out.write(le16(0))           // comment length

        return ZipFixture(
            bytes = out.toByteArray(),
            centralDirOffset = centralDirOffset,
            centralDirSize = centralDirSize,
            localHeaderOffset = localHeaderOffset,
            payloadDataOffset = payloadDataOffset
        )
    }

    private fun tail4096(bytes: ByteArray): ByteArray = bytes.copyOfRange(bytes.size - 4096, bytes.size)

    private fun pad(bytes: ByteArray, totalSize: Int): ByteArray {
        val result = ByteArray(totalSize)
        System.arraycopy(bytes, 0, result, 0, bytes.size)
        return result
    }

    @Test
    fun locateCentralDirectory_findsOffsetAndSize() {
        val content = "payload-content".toByteArray()
        val fixture = pad(buildZip("payload.bin", content).bytes, 4096)
        val tail = tail4096(fixture)

        val info = locateCentralDirectory(tail, fixture.size.toLong())

        assertEquals(
            buildZip("payload.bin", content).centralDirOffset,
            info.offset
        )
        assertTrue("cenSize must be > 0", info.size > 0)
    }

    @Test
    fun locateLocalFileHeader_resolvesPayloadEntry() {
        val content = "PAYLOAD".toByteArray()
        val zip = buildZip("payload.bin", content)
        val fixture = pad(zip.bytes, 4096)

        val cenInfo = locateCentralDirectory(tail4096(fixture), fixture.size.toLong())
        val centralDir = fixture.copyOfRange(
            cenInfo.offset.toInt(),
            (cenInfo.offset + cenInfo.size).toInt()
        )

        val localHeaderOffset = locateLocalFileHeader(centralDir, "payload.bin")
        assertEquals(zip.localHeaderOffset, localHeaderOffset)
    }

    @Test
    fun locateLocalFileHeader_returnsMinusOneWhenMissing() {
        val zip = pad(buildZip("payload.bin", "x".toByteArray()).bytes, 4096)
        val cenInfo = locateCentralDirectory(tail4096(zip), zip.size.toLong())
        val centralDir = zip.copyOfRange(
            cenInfo.offset.toInt(),
            (cenInfo.offset + cenInfo.size).toInt()
        )

        assertEquals(-1L, locateLocalFileHeader(centralDir, "does-not-exist.bin"))
    }

    @Test
    fun locateLocalFileOffset_pointsAtEntryData() {
        val content = "0123456789".toByteArray()
        val zip = buildZip("payload.bin", content)

        // locateLocalFileOffset reads 22 + name + extra bytes; the caller passes a 256-byte window.
        val localHeaderBytes = zip.bytes.copyOfRange(0, minOf(256, zip.bytes.size))
        val dataOffset = locateLocalFileOffset(localHeaderBytes)

        assertEquals(zip.payloadDataOffset, dataOffset)
        // And the bytes at that offset really are our content.
        assertEquals(
            "0123456789",
            String(zip.bytes.copyOfRange(dataOffset.toInt(), dataOffset.toInt() + content.size))
        )
    }

    @Test
    fun endToEnd_offsetComputation_matchesHandBuiltFixture() {
        val content = "hello-payload".toByteArray()
        val zip = buildZip("payload.bin", content)
        val fixture = pad(zip.bytes, 4096)

        // Mirror PayloadUtil.getPayloadOffset's local-file algorithm.
        val endBytes = tail4096(fixture)
        val cenInfo = locateCentralDirectory(endBytes, fixture.size.toLong())
        val centralDir = fixture.copyOfRange(
            cenInfo.offset.toInt(),
            (cenInfo.offset + cenInfo.size).toInt()
        )
        val localHeaderOffset = locateLocalFileHeader(centralDir, "payload.bin")
        val localHeaderBytes = fixture.copyOfRange(
            localHeaderOffset.toInt(),
            localHeaderOffset.toInt() + 256
        )
        val payloadOffset = locateLocalFileOffset(localHeaderBytes) + localHeaderOffset

        assertEquals(zip.payloadDataOffset, payloadOffset)
        assertEquals(
            "hello-payload",
            String(fixture.copyOfRange(payloadOffset.toInt(), payloadOffset.toInt() + content.size))
        )
    }
}
