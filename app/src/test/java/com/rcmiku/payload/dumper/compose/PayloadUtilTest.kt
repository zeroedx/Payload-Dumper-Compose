package com.rcmiku.payload.dumper.compose

import chromeos_update_engine.UpdateMetadata
import com.rcmiku.payload.dumper.compose.utils.FileSeekableInput
import com.rcmiku.payload.dumper.compose.utils.PayloadUtil
import com.rcmiku.payload.dumper.compose.utils.UnsupportedDeltaPayloadException
import kotlinx.coroutines.runBlocking
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
 * JVM tests for [PayloadUtil] payload-header parsing, delta detection and partition summary.
 * No Android framework is required for these code paths.
 */
class PayloadUtilTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun writeCrAU(
        manifest: UpdateMetadata.DeltaArchiveManifest,
        metadataSig: ByteArray = ByteArray(0),
    ): File {
        val manifestBytes = manifest.toByteArray()
        val out = ByteArrayOutputStream()
        out.write("CrAU".toByteArray(Charsets.US_ASCII))
        writeLongBE(out, 2L) // file_format_version
        writeLongBE(out, manifestBytes.size.toLong()) // manifest_size
        writeIntBE(out, metadataSig.size) // metadata_signature_size
        out.write(manifestBytes)
        out.write(metadataSig)
        val file = tmp.newFile("payload.bin")
        file.writeBytes(out.toByteArray())
        return file
    }

    private fun writeLongBE(out: ByteArrayOutputStream, v: Long) {
        for (i in 7 downTo 0) out.write(((v ushr (i * 8)) and 0xFF).toInt())
    }

    private fun writeIntBE(out: ByteArrayOutputStream, v: Int) {
        for (i in 3 downTo 0) out.write(((v ushr (i * 8)) and 0xFF))
    }

    private fun manifest(
        minorVersion: Int,
        partitions: List<UpdateMetadata.PartitionUpdate>,
    ): UpdateMetadata.DeltaArchiveManifest =
        UpdateMetadata.DeltaArchiveManifest.newBuilder()
            .setMinorVersion(minorVersion)
            .setBlockSize(4096)
            .addAllPartitions(partitions)
            .build()

    private fun <T> withRaf(file: File, block: suspend (FileSeekableInput) -> T): T =
        RandomAccessFile(file, "r").use { raf ->
            runBlocking { block(FileSeekableInput(raf)) }
        }

    @Test
    fun initPayload_rejectsDeltaPayloadBeforeAnyExtraction() {
        val file = writeCrAU(manifest(1, emptyList()))
        withRaf(file) { source ->
            val ex = assertThrows(UnsupportedDeltaPayloadException::class.java) {
                runBlocking { PayloadUtil.initPayload("payload.bin", source, 0L) }
            }
            assertEquals(1, ex.minorVersion)
        }
    }

    @Test
    fun initPayload_acceptsFullPayload() {
        val file = writeCrAU(manifest(0, emptyList()))
        withRaf(file) { source ->
            val payload = runBlocking { PayloadUtil.initPayload("payload.bin", source, 0L) }
            assertFalse(payload.isDelta)
            assertEquals(0, payload.minorVersion)
            assertEquals(4096, payload.blockSize)
            assertTrue(payload.isPath)
        }
    }

    @Test
    fun initPayload_throwsOnInvalidMagic() {
        val file = tmp.newFile("bad.bin")
        file.writeBytes(ByteArray(64) { 0 })
        withRaf(file) { source ->
            assertThrows(IOException::class.java) {
                runBlocking { PayloadUtil.initPayload("bad.bin", source, 0L) }
            }
        }
    }

    @Test
    fun initPayload_rejectsUnreasonableManifestSize() {
        val out = ByteArrayOutputStream()
        out.write("CrAU".toByteArray(Charsets.US_ASCII))
        writeLongBE(out, 2L)
        writeLongBE(out, Long.MAX_VALUE) // absurd manifest size
        writeIntBE(out, 0)
        val file = tmp.newFile("huge.bin")
        file.writeBytes(out.toByteArray())
        withRaf(file) { source ->
            assertThrows(IOException::class.java) {
                runBlocking { PayloadUtil.initPayload("huge.bin", source, 0L) }
            }
        }
    }

    @Test
    fun getPartitionInfoList_computesRawSizeFromDstExtentsAndToleratesMissingInfo() {
        val op1 = UpdateMetadata.InstallOperation.newBuilder()
            .setType(UpdateMetadata.InstallOperation.Type.REPLACE_XZ)
            .setDataOffset(0).setDataLength(10)
            .addDstExtents(UpdateMetadata.Extent.newBuilder().setStartBlock(0).setNumBlocks(2))
            .build()
        val op2 = UpdateMetadata.InstallOperation.newBuilder()
            .setType(UpdateMetadata.InstallOperation.Type.ZERO)
            .addDstExtents(UpdateMetadata.Extent.newBuilder().setStartBlock(2).setNumBlocks(3))
            .build()
        val partition = UpdateMetadata.PartitionUpdate.newBuilder()
            .setPartitionName("system")
            .addOperations(op1)
            .addOperations(op2)
            .build()

        val payload = withRaf(writeCrAU(manifest(0, listOf(partition)))) { source ->
            runBlocking { PayloadUtil.initPayload("payload.bin", source, 0L) }
        }

        val info = PayloadUtil.getPartitionInfoList(payload)
        assertEquals(1, info.size)
        // (2 + 3) blocks * 4096 = 20480 bytes of uncompressed output.
        assertEquals(20480L, info[0].rawSize)
        assertEquals("", info[0].sha256)
        assertEquals(20480L, info[0].size)
    }
}
