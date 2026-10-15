package com.rcmiku.payload.dumper.compose

import com.rcmiku.payload.dumper.compose.proto.OtaPackageMetadata
import com.rcmiku.payload.dumper.compose.utils.FileSeekableInput
import com.rcmiku.payload.dumper.compose.utils.OtaMetadataParser
import com.rcmiku.payload.dumper.compose.utils.SeekableInput
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * JVM tests for [OtaMetadataParser]. Fixtures are small ZIPs built with [ZipOutputStream] so the
 * real deflate path is exercised; no multi-gigabyte OTA file is needed. The parser only reads the
 * central directory and the metadata entry, which is what these fixtures verify.
 */
class OtaMetadataParserTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** In-memory [SeekableInput] so a fixture need not touch the filesystem. */
    private class ByteArraySeekableInput(private val bytes: ByteArray) : SeekableInput {
        private var pos = 0
        override fun length(): Long = bytes.size.toLong()
        override fun seek(position: Long) { pos = position.toInt() }
        override fun position(): Long = pos.toLong()
        override suspend fun readFully(buffer: ByteArray) {
            if (pos + buffer.size > bytes.size) throw IOException("read past end")
            System.arraycopy(bytes, pos, buffer, 0, buffer.size)
            pos += buffer.size
        }
        override val isLocal: Boolean = true
    }

    private data class Entry(val name: String, val content: ByteArray, val method: Int)

    private fun zip(entries: List<Entry>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            entries.forEach { e ->
                val entry = ZipEntry(e.name)
                entry.method = e.method
                if (e.method == ZipEntry.STORED) {
                    // ZipOutputStream refuses a STORED entry without size and CRC-32.
                    val crc = CRC32()
                    crc.update(e.content)
                    entry.size = e.content.size.toLong()
                    entry.compressedSize = e.content.size.toLong()
                    entry.crc = crc.value
                }
                zos.setLevel(if (e.method == ZipEntry.STORED) 0 else 6)
                zos.putNextEntry(entry)
                zos.write(e.content)
                zos.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun read(bytes: ByteArray): List<String> =
        runBlocking { OtaMetadataParser.readDeviceModels(ByteArraySeekableInput(bytes)) }

    private fun text(preDevice: String) =
        "ota-type=AB\npre-device=$preDevice\npost-build=brand/device/device:13/TQ1A/1:user/release-keys\n"
            .toByteArray()

    private fun proto(devices: List<String>, postDevices: List<String> = emptyList()): ByteArray =
        OtaPackageMetadata.OtaMetadata.newBuilder()
            .setType(OtaPackageMetadata.OtaMetadata.OtaType.AB)
            .setPrecondition(
                OtaPackageMetadata.DeviceState.newBuilder().addAllDevice(devices).build()
            )
            .setPostcondition(
                OtaPackageMetadata.DeviceState.newBuilder().addAllDevice(postDevices).build()
            )
            .build()
            .toByteArray()

    // 1. android/metadata with a valid pre-device.
    @Test
    fun readsPreDeviceFromAndroidMetadata() {
        val bytes = zip(listOf(Entry("android/metadata", text("raven"), ZipEntry.DEFLATED)))
        assertEquals(listOf("raven"), read(bytes))
    }

    // 2. META-INF/com/android/metadata with a valid pre-device.
    @Test
    fun readsPreDeviceFromMetaInfMetadata() {
        val bytes = zip(
            listOf(Entry("META-INF/com/android/metadata", text("oriole"), ZipEntry.DEFLATED))
        )
        assertEquals(listOf("oriole"), read(bytes))
    }

    // 3. Metadata absent entirely.
    @Test
    fun returnsEmptyWhenNoMetadataEntry() {
        val bytes = zip(listOf(Entry("payload.bin", ByteArray(64), ZipEntry.DEFLATED)))
        assertEquals(emptyList<String>(), read(bytes))
    }

    // 4. Metadata present but without pre-device.
    @Test
    fun returnsEmptyWhenMetadataHasNoPreDevice() {
        val content = "ota-type=AB\npost-build=brand/device/device:13/TQ1A/1:user/release-keys\n"
            .toByteArray()
        val bytes = zip(listOf(Entry("META-INF/com/android/metadata", content, ZipEntry.DEFLATED)))
        assertEquals(emptyList<String>(), read(bytes))
    }

    // 5. pre-device present but empty / whitespace only.
    @Test
    fun returnsEmptyWhenPreDeviceBlank() {
        val bytes = zip(listOf(Entry("android/metadata", text("   "), ZipEntry.DEFLATED)))
        assertEquals(emptyList<String>(), read(bytes))
    }

    // 6. Corrupt / non-metadata content (text entry with binary junk).
    @Test
    fun returnsEmptyWhenMetadataCorrupt() {
        val junk = byteArrayOf(0x00, 0x01, 0x02, 0x03, 0xFF.toByte(), 0x7F)
        val bytes = zip(listOf(Entry("META-INF/com/android/metadata", junk, ZipEntry.DEFLATED)))
        assertEquals(emptyList<String>(), read(bytes))
    }

    // 7. pre-device with surrounding whitespace is trimmed.
    @Test
    fun trimsSurroundingWhitespace() {
        val content = "ota-type=AB\npre-device=  raven  \n".toByteArray()
        val bytes = zip(listOf(Entry("android/metadata", content, ZipEntry.DEFLATED)))
        assertEquals(listOf("raven"), read(bytes))
    }

    // 8. Multiple candidate entries: text metadata wins over protobuf.
    @Test
    fun textMetadataTakesPriorityOverProto() {
        val bytes = zip(
            listOf(
                Entry("META-INF/com/android/metadata", text("raven"), ZipEntry.DEFLATED),
                Entry("META-INF/com/android/metadata.pb", proto(listOf("oriole")), ZipEntry.DEFLATED),
            )
        )
        assertEquals(listOf("raven"), read(bytes))
    }

    // 8b. Protobuf used when the text entry is absent.
    @Test
    fun readsPreDeviceFromProtobufWhenTextMissing() {
        val bytes = zip(
            listOf(Entry("META-INF/com/android/metadata.pb", proto(listOf("oriole")), ZipEntry.DEFLATED))
        )
        assertEquals(listOf("oriole"), read(bytes))
    }

    // 8c. android/metadata is the last resort.
    @Test
    fun readsAndroidMetadataWhenOthersMissing() {
        val bytes = zip(
            listOf(
                Entry("META-INF/com/android/metadata.pb", proto(listOf("oriole")), ZipEntry.DEFLATED),
                Entry("android/metadata", text("raven"), ZipEntry.DEFLATED),
            )
        )
        assertEquals(listOf("oriole"), read(bytes))
    }

    // 9. Multi-device: `|`-separated text value.
    @Test
    fun splitsPipeSeparatedDevices() {
        val bytes = zip(listOf(Entry("android/metadata", text("raven|oriole"), ZipEntry.DEFLATED)))
        assertEquals(listOf("raven", "oriole"), read(bytes))
    }

    // 9b. Multi-device: protobuf repeated field.
    @Test
    fun readsRepeatedDevicesFromProtobuf() {
        val bytes = zip(
            listOf(
                Entry(
                    "META-INF/com/android/metadata.pb",
                    proto(listOf("raven", "oriole")),
                    ZipEntry.DEFLATED,
                )
            )
        )
        assertEquals(listOf("raven", "oriole"), read(bytes))
    }

    // 9c. Protobuf with empty precondition falls back to postcondition.
    @Test
    fun protobufFallsBackToPostcondition() {
        val bytes = zip(
            listOf(
                Entry(
                    "META-INF/com/android/metadata.pb",
                    proto(devices = emptyList(), postDevices = listOf("raven")),
                    ZipEntry.DEFLATED,
                )
            )
        )
        assertEquals(listOf("raven"), read(bytes))
    }

    // 10. Stored (uncompressed) metadata entry is supported.
    @Test
    fun readsStoredEntry() {
        val bytes = zip(listOf(Entry("android/metadata", text("raven"), ZipEntry.STORED)))
        assertEquals(listOf("raven"), read(bytes))
    }

    // 11a. A bare payload.bin has no ZIP metadata and must not throw.
    @Test
    fun barePayloadReturnsEmpty() {
        val payload = ByteArray(4096)
        "CrAU".toByteArray(Charsets.US_ASCII).copyInto(payload)
        assertEquals(emptyList<String>(), read(payload))
    }

    // 11b. A truncated file (smaller than a ZIP tail) fails safely.
    @Test
    fun truncatedFileReturnsEmpty() {
        assertEquals(emptyList<String>(), read(ByteArray(8)))
    }

    // 11c. A stored entry whose declared size exceeds the limit is rejected without allocation.
    @Test
    fun oversizedStoredEntryIsRejected() {
        // Forge a ZIP whose metadata entry declares a >4 MiB compressed size but carries no data,
        // mirroring a lying entry. The parser must bail out rather than allocate the huge buffer.
        val out = ByteArrayOutputStream()
        val name = "android/metadata".toByteArray(Charsets.US_ASCII)
        val forgedSize = 8 * 1024 * 1024L
        fun le16(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v ushr 8) and 0xFF).toByte())
        fun le32(v: Long) = byteArrayOf(
            (v and 0xFF).toByte(), ((v ushr 8) and 0xFF).toByte(),
            ((v ushr 16) and 0xFF).toByte(), ((v ushr 24) and 0xFF).toByte(),
        )
        out.write(le32(0x04034b50L)); out.write(le16(20)); out.write(le16(0)); out.write(le16(0))
        out.write(le16(0)); out.write(le16(0)); out.write(le32(0))
        out.write(le32(forgedSize)); out.write(le32(forgedSize))
        out.write(le16(name.size)); out.write(le16(0)); out.write(name)

        val centralDirOffset = out.size().toLong()
        out.write(le32(0x02014b50L)); out.write(le16(20)); out.write(le16(20)); out.write(le16(0))
        out.write(le16(0)); out.write(le16(0)); out.write(le16(0)); out.write(le32(0))
        out.write(le32(forgedSize)); out.write(le32(forgedSize))
        out.write(le16(name.size)); out.write(le16(0)); out.write(le16(0)); out.write(le16(0))
        out.write(le16(0)); out.write(le32(0)); out.write(le32(0)); out.write(name)
        val centralDirSize = out.size().toLong() - centralDirOffset
        out.write(le32(0x06054b50L)); out.write(le16(0)); out.write(le16(0))
        out.write(le16(1)); out.write(le16(1)); out.write(le32(centralDirSize))
        out.write(le32(centralDirOffset)); out.write(le16(0))

        // Pad past the 4096-byte tail the parser reads, so the size guard (not the truncation
        // guard) is what rejects the entry.
        val bytes = out.toByteArray()
        val padded = ByteArray(maxOf(4096, bytes.size))
        bytes.copyInto(padded)

        assertEquals(emptyList<String>(), read(padded))
    }

    // 12. Local file (RandomAccessFile) path, as used by ParseUtil for a picked OTA file.
    @Test
    fun readsFromLocalFile() {
        val file: File = tmp.newFile("ota.zip")
        file.writeBytes(zip(listOf(Entry("META-INF/com/android/metadata", text("raven"), ZipEntry.DEFLATED))))
        RandomAccessFile(file, "r").use { raf ->
            val devices = runBlocking { OtaMetadataParser.readDeviceModels(FileSeekableInput(raf)) }
            assertEquals(listOf("raven"), devices)
        }
    }

    // The list must be non-empty only when a real device was found; never a placeholder.
    @Test
    fun neverReturnsPlaceholderValues() {
        val bytes = zip(listOf(Entry("android/metadata", text("raven"), ZipEntry.DEFLATED)))
        val devices = read(bytes)
        assertTrue(devices.all { it.isNotBlank() })
    }
}
