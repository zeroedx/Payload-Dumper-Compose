package com.rcmiku.payload.dumper.compose

import chromeos_update_engine.UpdateMetadata
import com.google.protobuf.ByteString
import com.rcmiku.payload.dumper.compose.utils.FileSeekableInput
import com.rcmiku.payload.dumper.compose.utils.HashUtil
import com.rcmiku.payload.dumper.compose.utils.PartitionOutput
import com.rcmiku.payload.dumper.compose.utils.PayloadUtil
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.Base64

/**
 * JVM end-to-end tests for the extraction engine's operation handling: byte-exact extent writes
 * across non-block-aligned payloads, DISCARD, REPLACE_ZSTD (against a real libzstd frame),
 * SOURCE_COPY from an old image and per-operation hash verification. Runs the real
 * [PayloadUtil.extractPartition] path with no Android framework.
 */
class ExtractionEngineTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun writeLongBE(out: ByteArrayOutputStream, v: Long) {
        for (i in 7 downTo 0) out.write(((v ushr (i * 8)) and 0xFF).toInt())
    }

    private fun writeIntBE(out: ByteArrayOutputStream, v: Int) {
        for (i in 3 downTo 0) out.write(((v ushr (i * 8)) and 0xFF))
    }

    private fun buildPayload(
        blockSize: Int,
        partitions: List<UpdateMetadata.PartitionUpdate>,
        data: ByteArray,
        minorVersion: Int = 0,
    ): File {
        val manifest = UpdateMetadata.DeltaArchiveManifest.newBuilder()
            .setMinorVersion(minorVersion)
            .setBlockSize(blockSize)
            .addAllPartitions(partitions)
            .build()
        val manifestBytes = manifest.toByteArray()
        val out = ByteArrayOutputStream()
        out.write("CrAU".toByteArray(Charsets.US_ASCII))
        writeLongBE(out, 2L)
        writeLongBE(out, manifestBytes.size.toLong())
        writeIntBE(out, 0)
        out.write(manifestBytes)
        out.write(data)
        val file = tmp.newFile("payload.bin")
        file.writeBytes(out.toByteArray())
        return file
    }

    private fun extent(startBlock: Long, numBlocks: Long) =
        UpdateMetadata.Extent.newBuilder().setStartBlock(startBlock).setNumBlocks(numBlocks).build()

    private fun partition(name: String, ops: List<UpdateMetadata.InstallOperation>) =
        UpdateMetadata.PartitionUpdate.newBuilder()
            .setPartitionName(name)
            .addAllOperations(ops)
            .build()

    private suspend fun extract(payloadFile: File, name: String, out: File, old: File? = null) {
        RandomAccessFile(payloadFile, "r").use { raf ->
            val source = FileSeekableInput(raf)
            val parsed = PayloadUtil.initPayload("payload.bin", source, 0L)
            val partitionUpdate = parsed.deltaArchiveManifest.partitionsList.first { it.partitionName == name }
            if (old != null) {
                RandomAccessFile(old, "r").use { oldRaf ->
                    PayloadUtil.extractPartition(
                        partitionUpdate, source, PartitionOutput(out), parsed, FileSeekableInput(oldRaf)
                    ) {}
                }
            } else {
                PayloadUtil.extractPartition(partitionUpdate, source, PartitionOutput(out), parsed) {}
            }
        }
    }

    @Test
    fun nonBlockAlignedReplaceIsPaddedAndCountedByBytes() = runBlocking {
        val blockSize = 4096
        // 1.5 blocks of data into a 2-block destination extent.
        val data = ByteArray(blockSize + blockSize / 2) { (it and 0xFF).toByte() }
        val op = UpdateMetadata.InstallOperation.newBuilder()
            .setType(UpdateMetadata.InstallOperation.Type.REPLACE)
            .setDataOffset(0)
            .setDataLength(data.size.toLong())
            .addDstExtents(extent(0, 2))
            .build()
        val payload = buildPayload(blockSize, listOf(partition("boot", listOf(op))), data)
        val out = File(tmp.root, "boot.img")

        extract(payload, "boot", out)

        // The old block-count accounting dropped the 2048-byte remainder and left the file short.
        assertEquals((2 * blockSize).toLong(), out.length())
        val bytes = out.readBytes()
        assertArrayEquals(data, bytes.copyOfRange(0, data.size))
        assertArrayEquals(ByteArray(blockSize / 2), bytes.copyOfRange(data.size, 2 * blockSize))
    }

    @Test
    fun midBlockWriteContinuesAcrossExtentsWithAGap() = runBlocking {
        val blockSize = 4096
        // 1.5 blocks written into two 1-block extents (block 0 and block 3): the second half of
        // the payload must continue into the next extent, and its tail must be zero-padded.
        val data = ByteArray(blockSize + blockSize / 2) { ((it * 3) and 0xFF).toByte() }
        val op = UpdateMetadata.InstallOperation.newBuilder()
            .setType(UpdateMetadata.InstallOperation.Type.REPLACE)
            .setDataOffset(0)
            .setDataLength(data.size.toLong())
            .addDstExtents(extent(0, 1))
            .addDstExtents(extent(3, 1))
            .build()
        val payload = buildPayload(blockSize, listOf(partition("system", listOf(op))), data)
        val out = File(tmp.root, "system.img")

        extract(payload, "system", out)

        val bytes = out.readBytes()
        assertEquals((4 * blockSize).toLong(), out.length())
        assertArrayEquals(data.copyOfRange(0, blockSize), bytes.copyOfRange(0, blockSize))
        // The gap between the two extents stays zero.
        assertArrayEquals(ByteArray(2 * blockSize), bytes.copyOfRange(blockSize, 3 * blockSize))
        assertArrayEquals(
            data.copyOfRange(blockSize, data.size),
            bytes.copyOfRange(3 * blockSize, 3 * blockSize + blockSize / 2)
        )
        assertArrayEquals(ByteArray(blockSize / 2), bytes.copyOfRange(3 * blockSize + blockSize / 2, 4 * blockSize))
    }

    @Test
    fun discardMaterialisesZeros() = runBlocking {
        val blockSize = 4096
        val op = UpdateMetadata.InstallOperation.newBuilder()
            .setType(UpdateMetadata.InstallOperation.Type.DISCARD)
            .addDstExtents(extent(0, 2))
            .build()
        val payload = buildPayload(blockSize, listOf(partition("vendor", listOf(op))), ByteArray(0))
        val out = File(tmp.root, "vendor.img")

        extract(payload, "vendor", out)

        assertEquals((2 * blockSize).toLong(), out.length())
        assertArrayEquals(ByteArray(2 * blockSize), out.readBytes())
    }

    @Test
    fun replaceZstdDecompressesARealLibzstdFrame() = runBlocking {
        val blockSize = 4096
        val decompressed = ByteArray(2 * blockSize) { ((it * 7) and 0xFF).toByte() }
        val frame = ZSTD_FRAME_2_BLOCKS
        val op = UpdateMetadata.InstallOperation.newBuilder()
            .setType(UpdateMetadata.InstallOperation.Type.REPLACE_ZSTD)
            .setDataOffset(0)
            .setDataLength(frame.size.toLong())
            .addDstExtents(extent(0, 2))
            .build()
        val payload = buildPayload(blockSize, listOf(partition("product", listOf(op))), frame)
        val out = File(tmp.root, "product.img")

        extract(payload, "product", out)

        assertArrayEquals(decompressed, out.readBytes())
    }

    @Test
    fun replaceZstdWithMismatchingDataHashFailsAndCleansUp() = runBlocking {
        val blockSize = 4096
        val frame = ZSTD_FRAME_2_BLOCKS
        val op = UpdateMetadata.InstallOperation.newBuilder()
            .setType(UpdateMetadata.InstallOperation.Type.REPLACE_ZSTD)
            .setDataOffset(0)
            .setDataLength(frame.size.toLong())
            .addDstExtents(extent(0, 2))
            // A deliberately wrong blob hash.
            .setDataSha256Hash(ByteString.copyFrom(ByteArray(32) { 0x5A }))
            .build()
        val payload = buildPayload(blockSize, listOf(partition("product", listOf(op))), frame)
        val out = File(tmp.root, "product.img")

        assertThrows(IOException::class.java) { runBlocking { extract(payload, "product", out) } }
        assertTrue("failed extraction must not leave a partial image", !out.exists())
    }

    @Test
    fun replaceWithCorrectDataHashSucceeds() = runBlocking {
        val blockSize = 4096
        val data = ByteArray(blockSize) { (it and 0xFF).toByte() }
        val expected = ByteArray(32)
        // Compute the real SHA-256 of the blob.
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(data)
        digest.copyInto(expected)
        val op = UpdateMetadata.InstallOperation.newBuilder()
            .setType(UpdateMetadata.InstallOperation.Type.REPLACE)
            .setDataOffset(0)
            .setDataLength(data.size.toLong())
            .addDstExtents(extent(0, 1))
            .setDataSha256Hash(ByteString.copyFrom(expected))
            .build()
        val payload = buildPayload(blockSize, listOf(partition("boot", listOf(op))), data)
        val out = File(tmp.root, "boot.img")

        extract(payload, "boot", out)

        assertArrayEquals(data, out.readBytes())
        assertEquals(HashUtil.sha256Hex(data), HashUtil.sha256Hex(out))
    }

    @Test
    fun sourceCopyReadsTheOldPartitionImage() = runBlocking {
        val blockSize = 4096
        // The old system.img has a recognisable block 1.
        val oldImage = File(tmp.root, "old_system.img")
        val oldContent = ByteArray(3 * blockSize) { ((it * 5) and 0xFF).toByte() }
        oldImage.writeBytes(oldContent)

        // New image: copy old block 1 into new block 0.
        val op = UpdateMetadata.InstallOperation.newBuilder()
            .setType(UpdateMetadata.InstallOperation.Type.SOURCE_COPY)
            .addSrcExtents(extent(1, 1))
            .addDstExtents(extent(0, 1))
            .build()
        val payload = buildPayload(blockSize, listOf(partition("system", listOf(op))), ByteArray(0))
        val out = File(tmp.root, "system.img")

        extract(payload, "system", out, old = oldImage)

        val bytes = out.readBytes()
        assertEquals(blockSize.toLong(), out.length())
        assertArrayEquals(oldContent.copyOfRange(blockSize, 2 * blockSize), bytes)
    }

    @Test
    fun sourceCopyWithoutAnOldImageIsRejectedBeforeWriting() = runBlocking {
        val blockSize = 4096
        val op = UpdateMetadata.InstallOperation.newBuilder()
            .setType(UpdateMetadata.InstallOperation.Type.SOURCE_COPY)
            .addSrcExtents(extent(0, 1))
            .addDstExtents(extent(0, 1))
            .build()
        val payload = buildPayload(blockSize, listOf(partition("system", listOf(op))), ByteArray(0), minorVersion = 2)
        val out = File(tmp.root, "system.img")

        assertThrows(IOException::class.java) { runBlocking { extract(payload, "system", out) } }
        assertTrue("no image must be created", !out.exists())
    }

    @Test
    fun sourceCopyAcrossNonContiguousSourceExtents() = runBlocking {
        val blockSize = 4096
        val oldImage = File(tmp.root, "old_vendor.img")
        val oldContent = ByteArray(6 * blockSize) { ((it * 11) and 0xFF).toByte() }
        oldImage.writeBytes(oldContent)

        // Copy old blocks 0 and 4 into new blocks 0 and 1: the two source runs are contiguous in
        // the destination but have a gap in the source.
        val op = UpdateMetadata.InstallOperation.newBuilder()
            .setType(UpdateMetadata.InstallOperation.Type.SOURCE_COPY)
            .addSrcExtents(extent(0, 1))
            .addSrcExtents(extent(4, 1))
            .addDstExtents(extent(0, 2))
            .build()
        val payload = buildPayload(blockSize, listOf(partition("vendor", listOf(op))), ByteArray(0))
        val out = File(tmp.root, "vendor.img")

        extract(payload, "vendor", out, old = oldImage)

        val bytes = out.readBytes()
        assertArrayEquals(oldContent.copyOfRange(0, blockSize), bytes.copyOfRange(0, blockSize))
        assertArrayEquals(oldContent.copyOfRange(4 * blockSize, 5 * blockSize), bytes.copyOfRange(blockSize, 2 * blockSize))
    }

    companion object {
        /**
         * A genuine libzstd frame (`zstd` CLI, magic 28 b5 2f fd) whose decompressed output is
         * 8192 bytes of `(i * 7) & 0xFF`. Embedding a real frame proves the decoder handles the
         * standard format, not just the library's own round-trip.
         */
        val ZSTD_FRAME_2_BLOCKS: ByteArray = Base64.getDecoder().decode(
            "KLUv/WQAH1UIAAQQAAcOFRwjKjE4P0ZNVFtiaXB3foWMk5qhqK+2vcTL0tng5+71/AMKERgfJi00O0JJ" +
                "UFdeZWxzeoGIj5adpKuyucDHztXc4+rx+P8GDRQbIikwNz5FTFNaYWhvdn2Ei5KZoKeutbzDytHY3+bt" +
                "9PsCCRAXHiUsMzpBSE9WXWRrcnmAh46VnKOqsbi/xs3U2+Lp8Pf+BQwTGiEoLzY9REtSWWBnbnV8g4qR" +
                "mJ+mrbS7wsnQ197l7PP6AQgPFh0kKzI5QEdOVVxjanF4f4aNlJuiqbC3vsXM09rh6O/2/QQLEhkgJy41" +
                "PENKUVhfZm10e4KJkJeepayzusHIz9bd5Ovy+QEAAP0+0K8pkUVDMQ=="
        )
    }
}
