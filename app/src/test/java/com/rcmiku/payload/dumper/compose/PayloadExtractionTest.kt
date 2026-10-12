package com.rcmiku.payload.dumper.compose

import chromeos_update_engine.UpdateMetadata
import com.rcmiku.payload.dumper.compose.utils.FileSeekableInput
import com.rcmiku.payload.dumper.compose.utils.HashUtil
import com.rcmiku.payload.dumper.compose.utils.PartitionOutput
import com.rcmiku.payload.dumper.compose.utils.PayloadUtil
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * JVM end-to-end extraction tests over a hand-built full-OTA payload.bin. Exercises the real
 * [PayloadUtil.extractPartition] path (REPLACE, ZERO, multi-op) without any Android framework.
 */
class PayloadExtractionTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun writeLongBE(out: ByteArrayOutputStream, v: Long) {
        for (i in 7 downTo 0) out.write(((v ushr (i * 8)) and 0xFF).toInt())
    }

    private fun writeIntBE(out: ByteArrayOutputStream, v: Int) {
        for (i in 3 downTo 0) out.write(((v ushr (i * 8)) and 0xFF))
    }

    /** Builds a payload.bin whose data section is [data]. */
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

    private fun replaceOp(dataOffset: Long, dataLength: Long, startBlock: Long, numBlocks: Long) =
        UpdateMetadata.InstallOperation.newBuilder()
            .setType(UpdateMetadata.InstallOperation.Type.REPLACE)
            .setDataOffset(dataOffset)
            .setDataLength(dataLength)
            .addDstExtents(
                UpdateMetadata.Extent.newBuilder().setStartBlock(startBlock).setNumBlocks(numBlocks)
            )
            .build()

    private fun partition(name: String, ops: List<UpdateMetadata.InstallOperation>) =
        UpdateMetadata.PartitionUpdate.newBuilder()
            .setPartitionName(name)
            .addAllOperations(ops)
            .build()

    private fun partition(
        name: String,
        ops: List<UpdateMetadata.InstallOperation>,
        declaredSize: Long,
    ) = UpdateMetadata.PartitionUpdate.newBuilder()
        .setPartitionName(name)
        .addAllOperations(ops)
        .setNewPartitionInfo(
            UpdateMetadata.PartitionInfo.newBuilder().setSize(declaredSize)
        )
        .build()

    @Test
    fun extractReplace_writesExactlyTheDataAndPadsToBlockBoundary() = runBlocking {
        val blockSize = 4096
        val data = ByteArray(100) { (it and 0xFF).toByte() }.copyOf(blockSize)
        val payload = buildPayload(blockSize, listOf(partition("boot", listOf(replaceOp(0, blockSize.toLong(), 0, 1)))), data)

        RandomAccessFile(payload, "r").use { raf ->
            val source = FileSeekableInput(raf)
            val parsed = PayloadUtil.initPayload("payload.bin", source, 0L)
            val out = File(tmp.root, "boot.img")
            PayloadUtil.extractPartition(parsed.deltaArchiveManifest.partitionsList[0], source, PartitionOutput(out), parsed) {}

            assertTrue(out.exists())
            assertEquals(blockSize.toLong(), out.length())
            assertArrayEquals(data, out.readBytes())
        }
    }

    @Test
    fun extractMultipleOps_positionedByDstExtentAndZerosAreMaterialised() = runBlocking {
        val blockSize = 4096
        // ops target destination blocks 1, 0 and 2 respectively.
        // data section: first block is 0x0A, second block is 0x0C.
        val lowA = ByteArray(blockSize) { 0x0A }
        val lowC = ByteArray(blockSize) { 0x0C }
        val data = lowA + lowC
        // Data offsets are relative to the data section start.
        val ops = listOf(
            // Reads the second data block (0x0C) and writes it to destination block 1.
            replaceOp(blockSize.toLong(), blockSize.toLong(), 1, 1),
            UpdateMetadata.InstallOperation.newBuilder()
                .setType(UpdateMetadata.InstallOperation.Type.ZERO)
                .addDstExtents(UpdateMetadata.Extent.newBuilder().setStartBlock(0).setNumBlocks(1))
                .build(),
            // Reads the first data block (0x0A) and writes it to destination block 2.
            replaceOp(0, blockSize.toLong(), 2, 1),
        )
        val payload = buildPayload(blockSize, listOf(partition("system", ops)), data)

        RandomAccessFile(payload, "r").use { raf ->
            val source = FileSeekableInput(raf)
            val parsed = PayloadUtil.initPayload("payload.bin", source, 0L)
            val out = File(tmp.root, "system.img")
            PayloadUtil.extractPartition(parsed.deltaArchiveManifest.partitionsList[0], source, PartitionOutput(out), parsed) {}

            val bytes = out.readBytes()
            assertEquals(3 * blockSize, bytes.size)
            assertArrayEquals(ByteArray(blockSize), bytes.copyOfRange(0, blockSize)) // zeros
            assertArrayEquals(lowC, bytes.copyOfRange(blockSize, 2 * blockSize))
            assertArrayEquals(lowA, bytes.copyOfRange(2 * blockSize, 3 * blockSize))
        }
    }

    @Test
    fun extractPartition_trimsBlockPaddingToDeclaredImageSize() = runBlocking {
        val blockSize = 4096
        val realSize = 1500L
        val data = ByteArray(blockSize) { 0x5A }
        val op = replaceOp(0, blockSize.toLong(), 0, 1)
        // Manifest says the real partition is 1500 bytes though the extent covers a full block.
        val payload = buildPayload(
            blockSize,
            listOf(partition("boot", listOf(op), declaredSize = realSize)),
            data,
        )

        RandomAccessFile(payload, "r").use { raf ->
            val source = FileSeekableInput(raf)
            val parsed = PayloadUtil.initPayload("payload.bin", source, 0L)
            val out = File(tmp.root, "boot.img")
            PayloadUtil.extractPartition(
                parsed.deltaArchiveManifest.partitionsList[0], source, PartitionOutput(out), parsed
            ) {}

            assertEquals(realSize, out.length())
            // The retained bytes are a prefix of the block that was written.
            assertArrayEquals(data.copyOf(realSize.toInt()), out.readBytes())
        }
    }

    @Test
    fun extractPartition_trimsAndVerifiesAgainstDeclaredSha256() = runBlocking {
        val blockSize = 4096
        val realSize = 1000L
        val data = ByteArray(blockSize) { (it * 7 and 0xFF).toByte() }
        // SHA-256 of the published (trimmed) image must match the manifest hash.
        val expectedHash = HashUtil.sha256Hex(data.copyOf(realSize.toInt()))
        val op = replaceOp(0, blockSize.toLong(), 0, 1)
        val partition = UpdateMetadata.PartitionUpdate.newBuilder()
            .setPartitionName("boot")
            .addOperations(op)
            .setNewPartitionInfo(
                UpdateMetadata.PartitionInfo.newBuilder()
                    .setSize(realSize)
                    .setHash(com.google.protobuf.ByteString.copyFrom(hexToBytes(expectedHash)))
            )
            .build()

        val payload = buildPayload(blockSize, listOf(partition), data)

        RandomAccessFile(payload, "r").use { raf ->
            val source = FileSeekableInput(raf)
            val parsed = PayloadUtil.initPayload("payload.bin", source, 0L)
            val out = File(tmp.root, "boot.img")
            PayloadUtil.extractPartition(
                parsed.deltaArchiveManifest.partitionsList[0], source, PartitionOutput(out), parsed
            ) {}

            assertEquals(realSize, out.length())
            assertEquals(expectedHash, HashUtil.sha256Hex(out))
        }
    }

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    @Test
    fun extractMultiExtent_writesEachContiguousRunToItsOwnExtent() = runBlocking {
        val blockSize = 4096
        val blockA = ByteArray(blockSize) { 0xAA.toByte() }
        val blockB = ByteArray(blockSize) { 0xBB.toByte() }
        // One op contributes two blocks that must land in extents 0 and 3 (a gap in between).
        val op = UpdateMetadata.InstallOperation.newBuilder()
            .setType(UpdateMetadata.InstallOperation.Type.REPLACE)
            .setDataOffset(0)
            .setDataLength((2 * blockSize).toLong())
            .addDstExtents(UpdateMetadata.Extent.newBuilder().setStartBlock(0).setNumBlocks(1))
            .addDstExtents(UpdateMetadata.Extent.newBuilder().setStartBlock(3).setNumBlocks(1))
            .build()
        val payload = buildPayload(blockSize, listOf(partition("system", listOf(op))), blockA + blockB)

        RandomAccessFile(payload, "r").use { raf ->
            val source = FileSeekableInput(raf)
            val parsed = PayloadUtil.initPayload("payload.bin", source, 0L)
            val out = File(tmp.root, "system.img")
            PayloadUtil.extractPartition(
                parsed.deltaArchiveManifest.partitionsList[0], source, PartitionOutput(out), parsed
            ) {}

            val bytes = out.readBytes()
            // Last written block is index 3, so the file must be 4 blocks long.
            assertEquals(4 * blockSize, bytes.size)
            assertArrayEquals(blockA, bytes.copyOfRange(0, blockSize))
            // The untouched gap must stay zero, not receive block B.
            assertArrayEquals(ByteArray(blockSize), bytes.copyOfRange(blockSize, 2 * blockSize))
            assertArrayEquals(ByteArray(blockSize), bytes.copyOfRange(2 * blockSize, 3 * blockSize))
            assertArrayEquals(blockB, bytes.copyOfRange(3 * blockSize, 4 * blockSize))
        }
    }

    @Test
    fun extractOperation_rejectsDataThatOverflowsItsExtents() = runBlocking {
        val blockSize = 4096
        // dst extent holds one block, but the op claims two blocks of data.
        val data = ByteArray(2 * blockSize) { 0x11 }
        val op = UpdateMetadata.InstallOperation.newBuilder()
            .setType(UpdateMetadata.InstallOperation.Type.REPLACE)
            .setDataOffset(0)
            .setDataLength((2 * blockSize).toLong())
            .addDstExtents(UpdateMetadata.Extent.newBuilder().setStartBlock(0).setNumBlocks(1))
            .build()
        val payload = buildPayload(blockSize, listOf(partition("boot", listOf(op))), data)

        RandomAccessFile(payload, "r").use { raf ->
            val source = FileSeekableInput(raf)
            val parsed = PayloadUtil.initPayload("payload.bin", source, 0L)
            val out = File(tmp.root, "boot.img")
            assertThrows(IOException::class.java) {
                runBlocking {
                    PayloadUtil.extractPartition(
                        parsed.deltaArchiveManifest.partitionsList[0], source, PartitionOutput(out), parsed
                    ) {}
                }
            }
            assertFalse("partial image must be removed", out.exists())
        }
    }

    @Test
    fun extractPartition_deletesPartialFileWhenOperationFails() = runBlocking {
        val blockSize = 4096
        // op claims dataLength 4096 but the data section is empty -> readFully hits EOF.
        val payload = buildPayload(blockSize, listOf(partition("vendor", listOf(replaceOp(0, blockSize.toLong(), 0, 1)))), ByteArray(0))

        RandomAccessFile(payload, "r").use { raf ->
            val source = FileSeekableInput(raf)
            val parsed = PayloadUtil.initPayload("payload.bin", source, 0L)
            val out = File(tmp.root, "vendor.img")
            assertThrows(IOException::class.java) {
                runBlocking {
                    PayloadUtil.extractPartition(parsed.deltaArchiveManifest.partitionsList[0], source, PartitionOutput(out), parsed) {}
                }
            }
            assertFalse(out.exists())
        }
    }
}
