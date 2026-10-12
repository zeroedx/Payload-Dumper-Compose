package com.rcmiku.payload.dumper.compose

import chromeos_update_engine.UpdateMetadata
import com.rcmiku.payload.dumper.compose.utils.FileSeekableInput
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
