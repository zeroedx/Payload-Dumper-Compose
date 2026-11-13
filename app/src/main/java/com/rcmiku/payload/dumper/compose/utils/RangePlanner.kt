package com.rcmiku.payload.dumper.compose.utils

import chromeos_update_engine.UpdateMetadata

/**
 * Plans the HTTP byte ranges needed to extract a set of partitions and merges nearby ones.
 *
 * Only the operation blobs of the selected partitions are considered, so a partial extraction does
 * not download the whole payload. Blobs separated by less than [mergeGap] are coalesced into a
 * single range: the gap is fetched too, but that costs far less than the extra round trips many
 * tiny ranges would need. A very large gap is left as a separate range so unrelated regions are
 * never downloaded.
 */
object RangePlanner {

    const val DEFAULT_MERGE_GAP = 256L * 1024

    data class ByteRange(val start: Long, val endInclusive: Long) {
        val length: Long get() = endInclusive - start + 1
    }

    /** Merged ranges covering every operation blob of [partitions], in ascending order. */
    fun planRanges(
        partitions: List<UpdateMetadata.PartitionUpdate>,
        dataOffset: Long,
        mergeGap: Long = DEFAULT_MERGE_GAP,
    ): List<ByteRange> {
        val ranges = mutableListOf<ByteRange>()
        for (partition in partitions) {
            for (op in partition.operationsList) {
                if (op.dataLength <= 0L) continue
                val start = dataOffset + op.dataOffset
                ranges += ByteRange(start, start + op.dataLength - 1)
            }
        }
        if (ranges.isEmpty()) return emptyList()
        ranges.sortBy { it.start }

        val merged = mutableListOf<ByteRange>()
        var current = ranges.first()
        for (next in ranges.drop(1)) {
            if (next.start - current.endInclusive - 1 <= mergeGap) {
                current = ByteRange(current.start, maxOf(current.endInclusive, next.endInclusive))
            } else {
                merged += current
                current = next
            }
        }
        merged += current
        return merged
    }

    /** Total bytes a planned set of ranges covers (including merged-in gaps). */
    fun totalBytes(ranges: List<ByteRange>): Long = ranges.sumOf { it.length }
}
