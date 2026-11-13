package com.rcmiku.payload.dumper.compose

import chromeos_update_engine.UpdateMetadata
import com.google.protobuf.ByteString
import com.rcmiku.payload.dumper.compose.utils.PartitionMapping
import com.rcmiku.payload.dumper.compose.utils.RangePlanner
import com.rcmiku.payload.dumper.compose.utils.SourceImageException
import com.rcmiku.payload.dumper.compose.utils.SourceImageValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.MessageDigest

class SourceImageValidatorTest {

    private fun extent(start: Long, blocks: Long) =
        UpdateMetadata.Extent.newBuilder().setStartBlock(start).setNumBlocks(blocks).build()

    private fun partition(
        name: String = "system",
        oldSize: Long? = null,
        oldHash: ByteArray? = null,
        srcExtents: List<UpdateMetadata.Extent> = emptyList(),
    ): UpdateMetadata.PartitionUpdate {
        val op = UpdateMetadata.InstallOperation.newBuilder()
            .setType(UpdateMetadata.InstallOperation.Type.SOURCE_COPY)
            .addAllSrcExtents(srcExtents)
            .addDstExtents(extent(0, 1))
        val builder = UpdateMetadata.PartitionUpdate.newBuilder().setPartitionName(name).addOperations(op)
        if (oldSize != null || oldHash != null) {
            val info = UpdateMetadata.PartitionInfo.newBuilder()
            oldSize?.let { info.setSize(it) }
            oldHash?.let { info.setHash(ByteString.copyFrom(it)) }
            builder.setOldPartitionInfo(info)
        }
        return builder.build()
    }

    @Test
    fun acceptsImageWhenSizeAndExtentsFit() {
        val p = partition(oldSize = 8192, srcExtents = listOf(extent(0, 2)))
        SourceImageValidator.validate(p, imageLength = 8192, blockSize = 4096) { ByteArray(0) }
    }

    @Test
    fun rejectsImageWithWrongSize() {
        val p = partition(oldSize = 8192, srcExtents = listOf(extent(0, 1)))
        assertThrows(SourceImageException::class.java) {
            SourceImageValidator.validate(p, imageLength = 4096, blockSize = 4096) { ByteArray(0) }
        }
    }

    @Test
    fun rejectsExtentBeyondImageLength() {
        val p = partition(oldSize = null, srcExtents = listOf(extent(10, 1)))
        assertThrows(SourceImageException::class.java) {
            SourceImageValidator.validate(p, imageLength = 4096, blockSize = 4096) { ByteArray(0) }
        }
    }

    @Test
    fun rejectsMismatchingHash() {
        val data = ByteArray(8192) { 1 }
        val wrong = ByteArray(32) { 0x11 }
        val p = partition(oldSize = 8192, oldHash = wrong, srcExtents = listOf(extent(0, 2)))
        assertThrows(SourceImageException::class.java) {
            SourceImageValidator.validate(p, imageLength = 8192, blockSize = 4096) {
                MessageDigest.getInstance("SHA-256").digest(data)
            }
        }
    }

    @Test
    fun acceptsMatchingHash() {
        val data = ByteArray(8192) { 7 }
        val good = MessageDigest.getInstance("SHA-256").digest(data)
        val p = partition(oldSize = 8192, oldHash = good, srcExtents = listOf(extent(0, 2)))
        SourceImageValidator.validate(p, imageLength = 8192, blockSize = 4096) {
            MessageDigest.getInstance("SHA-256").digest(data)
        }
    }

    @Test
    fun rejectsUnknownImageLength() {
        val p = partition(srcExtents = listOf(extent(0, 1)))
        assertThrows(SourceImageException::class.java) {
            SourceImageValidator.validate(p, imageLength = -1, blockSize = 4096) { ByteArray(0) }
        }
    }
}

class PartitionMappingTest {

    private val partitions = setOf("boot", "system", "vendor")

    @Test
    fun mapsImgAndBinSuffixes() {
        assertEquals("system", PartitionMapping.partitionForFileName("system.img", partitions))
        assertEquals("boot", PartitionMapping.partitionForFileName("boot.bin", partitions))
    }

    @Test
    fun returnsNullForUnknownName() {
        assertNull(PartitionMapping.partitionForFileName("super.img", partitions))
        assertNull(PartitionMapping.partitionForFileName(".img", partitions))
    }
}

class RangePlannerTest {

    private fun op(offset: Long, length: Long): UpdateMetadata.InstallOperation =
        UpdateMetadata.InstallOperation.newBuilder()
            .setType(UpdateMetadata.InstallOperation.Type.REPLACE)
            .setDataOffset(offset)
            .setDataLength(length)
            .addDstExtents(UpdateMetadata.Extent.newBuilder().setStartBlock(0).setNumBlocks(1))
            .build()

    private fun partition(vararg ops: UpdateMetadata.InstallOperation) =
        UpdateMetadata.PartitionUpdate.newBuilder().setPartitionName("p").addAllOperations(ops.toList()).build()

    @Test
    fun mergesNearbyRanges() {
        // Two blobs 100 KiB apart merge into one range; the gap is fetched too but is small.
        val ranges = RangePlanner.planRanges(
            listOf(partition(op(0, 1000), op(100_000, 1000))),
            dataOffset = 0,
        )
        assertEquals(1, ranges.size)
        assertEquals(0, ranges[0].start)
        assertEquals(100_999, ranges[0].endInclusive)
    }

    @Test
    fun keepsFarApartRangesSeparate() {
        val ranges = RangePlanner.planRanges(
            listOf(partition(op(0, 1000), op(10_000_000, 1000))),
            dataOffset = 0,
        )
        assertEquals(2, ranges.size)
    }

    @Test
    fun appliesDataOffsetAndSkipsEmptyBlobs() {
        val ranges = RangePlanner.planRanges(
            listOf(partition(op(0, 0), op(10, 5))),
            dataOffset = 1000,
        )
        assertEquals(1, ranges.size)
        assertEquals(1010, ranges[0].start)
        assertEquals(1014, ranges[0].endInclusive)
    }
}
