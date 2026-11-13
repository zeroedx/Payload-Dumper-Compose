package com.rcmiku.payload.dumper.compose.utils

import chromeos_update_engine.UpdateMetadata
import java.io.IOException
import java.security.MessageDigest

/**
 * Validates a user-supplied old partition image against the manifest before it is used as the
 * source of a delta operation.
 *
 * The whole-image SHA-256 is only computed when the manifest actually declares one and the file is
 * not larger than [MAX_HASH_BYTES]; hashing a multi-gigabyte image is a real cost, so it is skipped
 * when there is nothing to compare against. Every declared `src_extents` range must also fall
 * inside the image, otherwise a later read would fail (or read the wrong bytes) mid-extraction.
 */
object SourceImageValidator {

    /** Upper bound for whole-image hashing; a larger image is size-checked only. */
    const val MAX_HASH_BYTES = 8L * 1024 * 1024 * 1024

    /**
     * @param partition the manifest partition whose old image is being validated
     * @param imageLength the actual length of the supplied image in bytes (must be known)
     * @param blockSize the payload block size
     * @param hashProvider streams the image and returns its SHA-256, only called when needed
     */
    fun validate(
        partition: UpdateMetadata.PartitionUpdate,
        imageLength: Long,
        blockSize: Int,
        hashProvider: () -> ByteArray,
    ) {
        if (imageLength < 0) {
            throw SourceImageException(
                partition.partitionName,
                "the size of the source image could not be determined"
            )
        }
        if (blockSize <= 0) {
            throw SourceImageException(partition.partitionName, "invalid payload block size $blockSize")
        }

        val oldInfo = if (partition.hasOldPartitionInfo()) partition.oldPartitionInfo else null
        if (oldInfo != null && oldInfo.hasSize() && oldInfo.size > 0) {
            if (imageLength != oldInfo.size) {
                throw SourceImageException(
                    partition.partitionName,
                    "the source image is $imageLength bytes but the manifest expects ${oldInfo.size}"
                )
            }
        }

        // Every source extent must be addressable within the supplied image.
        for (op in partition.operationsList) {
            for (extent in op.srcExtentsList) {
                val end = extent.startBlock * blockSize + extent.numBlocks * blockSize
                if (end > imageLength) {
                    throw SourceImageException(
                        partition.partitionName,
                        "a source extent ends at byte $end, beyond the ${imageLength}-byte image"
                    )
                }
            }
        }

        val expectedHash = oldInfo?.takeIf { it.hasHash() }?.hash?.toByteArray()
        if (expectedHash != null && expectedHash.isNotEmpty() && imageLength <= MAX_HASH_BYTES) {
            val actual = hashProvider()
            if (!MessageDigest.isEqual(expectedHash, actual)) {
                throw SourceImageException(
                    partition.partitionName,
                    "the source image SHA-256 does not match the manifest"
                )
            }
        }
    }
}

/** Thrown when a supplied old partition image fails manifest validation. */
class SourceImageException(val partitionName: String, reason: String) :
    IOException("Source image for $partitionName is invalid: $reason")
