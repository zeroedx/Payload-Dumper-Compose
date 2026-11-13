package com.rcmiku.payload.dumper.compose.utils

import chromeos_update_engine.UpdateMetadata
import com.rcmiku.payload.dumper.compose.model.PartitionInfo
import com.rcmiku.payload.dumper.compose.model.Payload
import com.rcmiku.payload.dumper.compose.model.PayloadHeader
import com.rcmiku.payload.dumper.compose.utils.MiscUtil.toHexString
import com.rcmiku.payload.dumper.compose.utils.MiscUtil.toInt
import com.rcmiku.payload.dumper.compose.utils.MiscUtil.toLong
import com.rcmiku.payload.dumper.compose.utils.ZipFileUtil.locateCentralDirectory
import com.rcmiku.payload.dumper.compose.utils.ZipFileUtil.locateLocalFileHeader
import com.rcmiku.payload.dumper.compose.utils.ZipFileUtil.locateLocalFileOffset
import io.airlift.compress.zstd.ZstdInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

object PayloadUtil {

    private const val MAGIC_VALUE = "CrAU"
    private const val FORMAT_VERSION = 2L

    /** Sanity bound on the proto manifest size, to avoid allocating absurd buffers. */
    private const val MAX_MANIFEST_SIZE = 256L * 1024 * 1024

    /** Copy buffer for streaming decompression: bounded, independent of the output size. */
    private const val STREAM_BUFFER_SIZE = 1 shl 16

    /**
     * Parses the CrAU header and proto manifest from [source] at [payloadOffset]. Works for any
     * [SeekableInput] (file, SAF URI or HTTP), which keeps the parsing logic in one place.
     */
    suspend fun initPayload(fileName: String, source: SeekableInput, payloadOffset: Long): Payload {
        if (payloadOffset == -1L) {
            throw IOException("Invalid payload offset value")
        }

        source.seek(payloadOffset)
        val magicBytes = ByteArray(4)
        source.readFully(magicBytes)
        if (String(magicBytes, StandardCharsets.UTF_8) != MAGIC_VALUE) {
            throw IOException("Invalid payload magic value (not a CrAU payload)")
        }

        val fileFormatVersionBytes = ByteArray(8)
        source.readFully(fileFormatVersionBytes)
        val fileFormatVersion = fileFormatVersionBytes.toLong()
        if (fileFormatVersion != FORMAT_VERSION) {
            throw IOException("Unsupported payload file format version $fileFormatVersion")
        }

        val manifestSizeBytes = ByteArray(8)
        source.readFully(manifestSizeBytes)
        val manifestSize = manifestSizeBytes.toLong()
        if (manifestSize <= 0 || manifestSize > MAX_MANIFEST_SIZE) {
            throw IOException("Unreasonable manifest size: $manifestSize")
        }

        val metadataSignatureSizeBytes = ByteArray(4)
        source.readFully(metadataSignatureSizeBytes)
        val metadataSignatureSize = metadataSignatureSizeBytes.toInt()

        val manifest = ByteArray(manifestSize.toInt())
        source.readFully(manifest)
        val metadataSignatureMessage = ByteArray(metadataSignatureSize)
        source.readFully(metadataSignatureMessage)

        val deltaArchiveManifest = UpdateMetadata.DeltaArchiveManifest.parseFrom(manifest)
        val dataOffset: Long = source.position()
        val payloadHeader = PayloadHeader(fileFormatVersion, manifestSize, metadataSignatureSize)
        val payload = Payload(
            fileName,
            payloadHeader,
            deltaArchiveManifest,
            dataOffset,
            deltaArchiveManifest.blockSize,
            source.length(),
            source.isLocal
        )

        if (payload.blockSize <= 0) {
            throw IOException("Invalid block size in manifest: ${payload.blockSize}")
        }

        return payload
    }

    fun getPartitionInfoList(payload: Payload): List<PartitionInfo> {
        return payload.deltaArchiveManifest.partitionsList.map { partition ->
            val ops = partition.operationsList
            // Uncompressed size = sum of all dst extents times block size. Written-byte count
            // (last blob end - first blob start) is only a rough proxy and is wrong for
            // ZERO/DISCARD operations, which carry no blob at all.
            val rawSize = if (payload.blockSize > 0) {
                ops.sumOf { op ->
                    op.dstExtentsList.sumOf { it.numBlocks } * payload.blockSize
                }
            } else {
                0L
            }
            PartitionInfo(
                partitionName = partition.partitionName,
                size = if (partition.hasNewPartitionInfo()) partition.newPartitionInfo.size else rawSize,
                rawSize = rawSize,
                sha256 = if (partition.hasNewPartitionInfo())
                    partition.newPartitionInfo.hash.toHexString() else "",
                needsSourceImage = operationsNeedSourceImage(partition)
            )
        }
    }

    /** Whether applying [partition] reads from the old (source) image, i.e. it is a delta update. */
    fun operationsNeedSourceImage(partition: UpdateMetadata.PartitionUpdate): Boolean =
        partition.operationsList.any { op ->
            when (op.type) {
                UpdateMetadata.InstallOperation.Type.SOURCE_COPY,
                UpdateMetadata.InstallOperation.Type.SOURCE_BSDIFF,
                UpdateMetadata.InstallOperation.Type.BSDIFF,
                UpdateMetadata.InstallOperation.Type.BROTLI_BSDIFF,
                UpdateMetadata.InstallOperation.Type.PUFFDIFF,
                UpdateMetadata.InstallOperation.Type.ZUCCHINI,
                UpdateMetadata.InstallOperation.Type.LZ4DIFF_BSDIFF,
                UpdateMetadata.InstallOperation.Type.LZ4DIFF_PUFFDIFF -> true

                else -> false
            }
        }

    /**
     * Rejects, before any byte is written, a selected partition that this build cannot apply.
     * The diff operations (SOURCE_BSDIFF, BROTLI_BSDIFF, PUFFDIFF, ZUCCHINI, LZ4DIFF_*) are
     * declared in the manifest but not implemented here, and a MOVE op has no handler at all, so
     * extracting them would silently corrupt the image. The caller surfaces
     * [UnsupportedPayloadOperationException] to the user.
     */
    fun checkSupported(partition: UpdateMetadata.PartitionUpdate, hasSourceImage: Boolean) {
        for (op in partition.operationsList) {
            when (op.type) {
                UpdateMetadata.InstallOperation.Type.REPLACE,
                UpdateMetadata.InstallOperation.Type.REPLACE_BZ,
                UpdateMetadata.InstallOperation.Type.REPLACE_XZ,
                UpdateMetadata.InstallOperation.Type.REPLACE_ZSTD,
                UpdateMetadata.InstallOperation.Type.ZERO,
                UpdateMetadata.InstallOperation.Type.DISCARD -> Unit

                UpdateMetadata.InstallOperation.Type.SOURCE_COPY -> {
                    if (!hasSourceImage) {
                        throw MissingSourceImageException(partition.partitionName)
                    }
                }

                else -> throw UnsupportedPayloadOperationException(partition.partitionName, op.type)
            }
        }
    }

    /**
     * Writes the operation payload across all of its destination extents. A single op may target
     * several non-contiguous extents (a gap in the image), so the data must be split per extent
     * rather than streamed from the first extent onward. [input] is copied with each extent filled
     * to its full `numBlocks * blockSize`; a short payload is zero-padded and an over-long one is
     * rejected so a malformed op can never spill into the next extent.
     */
    private fun writeAcrossExtents(
        partOutput: RandomAccessFile,
        op: UpdateMetadata.InstallOperation,
        blockSize: Int,
        writeBody: (OutputStream) -> Unit,
    ) {
        val writer = ExtentOutputStream(partOutput, op.dstExtentsList, blockSize)
        writeBody(writer)
        writer.padRemainingWithZeros()
    }

    private fun extractOperation(
        op: UpdateMetadata.InstallOperation,
        partOutput: RandomAccessFile,
        source: SeekableInput,
        oldPartition: SeekableInput?,
        blockSize: Int,
        dataOffset: Long,
    ) {
        if (op.dstExtentsList.isEmpty()) return
        val blobOffset = dataOffset + op.dataOffset

        when (op.type) {
            UpdateMetadata.InstallOperation.Type.REPLACE -> {
                val blob = RangeInputStream(source, blobOffset, op.dataLength)
                writeAcrossExtents(partOutput, op, blockSize) { out ->
                    copyStream(blob, out)
                }
                verifyBlobHash(op, blob)
            }

            UpdateMetadata.InstallOperation.Type.REPLACE_BZ,
            UpdateMetadata.InstallOperation.Type.REPLACE_XZ,
            UpdateMetadata.InstallOperation.Type.REPLACE_ZSTD -> {
                val blob = RangeInputStream(source, blobOffset, op.dataLength)
                // BZip2 needs a mark/reset-capable stream; a BufferedInputStream provides it.
                val decompressed: InputStream = when (op.type) {
                    UpdateMetadata.InstallOperation.Type.REPLACE_BZ ->
                        BZip2CompressorInputStream(BufferedInputStream(blob))

                    UpdateMetadata.InstallOperation.Type.REPLACE_XZ ->
                        XZCompressorInputStream(blob)

                    else -> ZstdInputStream(blob)
                }
                decompressed.use { input ->
                    writeAcrossExtents(partOutput, op, blockSize) { out ->
                        copyStream(input, out)
                    }
                }
                verifyBlobHash(op, blob)
            }

            UpdateMetadata.InstallOperation.Type.ZERO,
            UpdateMetadata.InstallOperation.Type.DISCARD -> {
                // A fresh working file is already zero-filled; materialising the extents with
                // zeros keeps DISCARD deterministic instead of leaving undefined content.
                writeAcrossExtents(partOutput, op, blockSize) { /* pads zeros */ }
            }

            UpdateMetadata.InstallOperation.Type.SOURCE_COPY -> {
                val old = oldPartition
                    ?: throw MissingSourceImageException("?")
                copySourceExtents(old, op, partOutput, blockSize)
            }

            else -> throw UnsupportedPayloadOperationException("?", op.type)
        }
    }

    /** Copies the source extents of a SOURCE_COPY op into the destination extents of the new image. */
    private fun copySourceExtents(
        oldPartition: SeekableInput,
        op: UpdateMetadata.InstallOperation,
        partOutput: RandomAccessFile,
        blockSize: Int,
    ) {
        val srcExtents = op.srcExtentsList
        if (srcExtents.isEmpty()) {
            throw IOException("SOURCE_COPY operation has no source extent")
        }
        val buffer = ByteArray(STREAM_BUFFER_SIZE)
        val digest = MessageDigest.getInstance("SHA-256")
        var srcIndex = 0
        var offsetInExtent = 0L
        var remaining = srcExtents.sumOf { it.numBlocks } * blockSize

        writeAcrossExtents(partOutput, op, blockSize) { out ->
            while (remaining > 0) {
                if (srcIndex >= srcExtents.size) {
                    throw IOException("SOURCE_COPY read past its source extents")
                }
                val extent = srcExtents[srcIndex]
                val extentBytes = extent.numBlocks.toLong() * blockSize
                if (offsetInExtent >= extentBytes) {
                    srcIndex += 1
                    offsetInExtent = 0L
                    continue
                }
                val chunk = minOf(remaining, extentBytes - offsetInExtent, buffer.size.toLong()).toInt()
                oldPartition.readAt(extent.startBlock * blockSize + offsetInExtent, buffer, 0, chunk)
                digest.update(buffer, 0, chunk)
                out.write(buffer, 0, chunk)
                offsetInExtent += chunk
                remaining -= chunk
            }
        }

        verifySourceHash(op, digest.digest())
    }

    private fun verifyBlobHash(op: UpdateMetadata.InstallOperation, blob: RangeInputStream) {
        if (!op.hasDataSha256Hash()) return
        val expected = op.dataSha256Hash.toByteArray()
        if (expected.isEmpty()) return
        val actual = blob.drainAndDigest()
        if (!expected.contentEquals(actual)) {
            throw IOException(
                "Operation blob SHA-256 mismatch (expected ${expected.toHexString()}, " +
                    "got ${actual.toHexString()})"
            )
        }
    }

    private fun verifySourceHash(op: UpdateMetadata.InstallOperation, actual: ByteArray) {
        if (!op.hasSrcSha256Hash()) return
        val expected = op.srcSha256Hash.toByteArray()
        if (expected.isEmpty()) return
        if (!expected.contentEquals(actual)) {
            throw IOException(
                "Source extents SHA-256 mismatch (expected ${expected.toHexString()}, " +
                    "got ${actual.toHexString()})"
            )
        }
    }

    private fun copyStream(input: InputStream, out: OutputStream) {
        val buffer = ByteArray(STREAM_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            out.write(buffer, 0, read)
        }
    }

    suspend fun extractPartition(
        metadataPartition: UpdateMetadata.PartitionUpdate,
        source: SeekableInput,
        output: PartitionOutput,
        payload: Payload,
        oldPartition: SeekableInput? = null,
        onProgressUpdate: (Long) -> Unit
    ) {
        withContext(Dispatchers.IO) {
            // Fail before writing anything if the manifest asks for an operation we cannot apply.
            checkSupported(metadataPartition, oldPartition != null)

            // Approximate expected output size, used to make the progress bar monotonic.
            val totalBytes = metadataPartition.operationsList
                .sumOf { it.dstExtentsList.sumOf { e -> e.numBlocks } } * payload.blockSize
            var produced = 0L

            val declaredSize = if (metadataPartition.hasNewPartitionInfo()) {
                metadataPartition.newPartitionInfo.size
            } else {
                0L
            }

            try {
                RandomAccessFile(output.file, "rw").use { partOutput ->
                    metadataPartition.operationsList.forEach { operation ->
                        // Cooperative cancellation: the loop body does blocking file/network IO,
                        // so nothing else would interrupt it promptly.
                        currentCoroutineContext().ensureActive()
                        extractOperation(
                            operation,
                            partOutput,
                            source,
                            oldPartition,
                            payload.blockSize,
                            payload.dataOffset,
                        )
                        produced += operation.dstExtentsList.sumOf { e -> e.numBlocks } *
                            payload.blockSize
                        onProgressUpdate(produced)
                    }

                    // Extents are block-aligned, so the last block usually overflows the declared
                    // image size. Trim to the manifest size: the SHA-256 covers exactly those
                    // bytes, and a short write is caught below.
                    if (declaredSize in 1..partOutput.length()) {
                        partOutput.setLength(declaredSize)
                    }
                }
            } catch (t: Throwable) {
                // Never leave a half-written image behind: it would look like a real dump.
                output.cleanup()
                throw t
            }

            // If we know the expected size and the output is short, treat it as a failure.
            if (totalBytes > 0 && output.file.length() < totalBytes && declaredSize == 0L) {
                val actual = output.file.length()
                output.cleanup()
                throw IOException(
                    "Incomplete extraction: ${metadataPartition.partitionName}.img is $actual " +
                        "bytes, expected $totalBytes"
                )
            }

            // The manifest declares the final image size; a mismatch means a truncated or
            // mis-sized image that must never be published.
            if (declaredSize > 0L) {
                val actual = output.file.length()
                if (actual != declaredSize) {
                    output.cleanup()
                    throw IOException(
                        "Image length ($actual) does not match the manifest ($declaredSize)"
                    )
                }
            }
            // The image is complete; publication/verification is the caller's responsibility.
        }
    }

    /**
     * Locates the absolute byte offset of `payload.bin` inside an OTA ZIP, or 0 for a bare
     * `payload.bin`. Works from any [SeekableInput].
     */
    suspend fun getPayloadOffset(input: SeekableInput, fileName: String = "payload.bin"): Long {
        // A bare payload.bin (file or URL) starts at offset 0.
        if (fileName == "payload.bin" && input.length() >= 4) {
            val magic = ByteArray(4)
            input.seek(0)
            input.readFully(magic)
            if (String(magic, StandardCharsets.UTF_8) == MAGIC_VALUE) return 0L
        }

        val fileLength = input.length()
        if (fileLength < 4096) throw IOException("File too small to be an OTA package")

        val endBytes = ByteArray(4096)
        input.seek(fileLength - 4096)
        input.readFully(endBytes)

        val centralDirectoryInfo = locateCentralDirectory(endBytes, fileLength)
        if (centralDirectoryInfo.offset < 0 || centralDirectoryInfo.size <= 0) {
            throw IOException("Could not locate ZIP central directory")
        }

        input.seek(centralDirectoryInfo.offset)
        val centralDirectory = ByteArray(centralDirectoryInfo.size.toInt())
        input.readFully(centralDirectory)

        val localHeaderOffset = locateLocalFileHeader(centralDirectory, fileName)
        if (localHeaderOffset < 0) {
            throw IOException("$fileName not found inside the OTA package")
        }

        val localHeaderBytes = ByteArray(256)
        input.seek(localHeaderOffset)
        input.readFully(localHeaderBytes)
        return locateLocalFileOffset(localHeaderBytes) + localHeaderOffset
    }
}

/** Thrown when a partition uses an install operation this build does not implement. */
class UnsupportedPayloadOperationException(
    val partitionName: String,
    val type: UpdateMetadata.InstallOperation.Type,
) : IOException(
    "Partition $partitionName uses unsupported operation $type; this build supports " +
        "REPLACE, REPLACE_BZ, REPLACE_XZ, REPLACE_ZSTD, ZERO, DISCARD and SOURCE_COPY"
)

/** Thrown when a delta operation needs the old partition image but none was supplied. */
class MissingSourceImageException(val partitionName: String) :
    IOException("Partition $partitionName is a delta update; the old $partitionName.img is required")

/**
 * Destination of a single partition image. Extraction always writes to [file] with random
 * access; [onCommit] optionally publishes that file elsewhere (e.g. a SAF document) and the
 * working file is then removed.
 */
class PartitionOutput(
    val file: File,
    private val onCommit: (suspend (File) -> Unit)? = null,
) {
    fun cleanup() {
        runCatching { if (file.exists()) file.delete() }
    }

    suspend fun commit() {
        onCommit?.invoke(file)
        runCatching { if (file.exists()) file.delete() }
    }
}

/**
 * An [OutputStream] that splits the operation payload across the destination extents of a single
 * install operation, writing into the shared [RandomAccessFile] with random access.
 *
 * Multiple extents are not contiguous: when the current extent is full the stream seeks to the
 * start of the next extent, leaving any gap in the image untouched. Writing past the last extent
 * (a payload larger than the extents can hold) is rejected instead of silently corrupting the
 * following region. [close] is intentionally a no-op so a copy loop cannot close the shared file
 * descriptor.
 *
 * Progress is tracked in bytes, not blocks: a write may end mid-block and the next write must
 * continue from there, so a `chunk / blockSize` count (which loses the remainder) would drift and
 * eventually write into the wrong extent.
 */
private class ExtentOutputStream(
    private val raf: RandomAccessFile,
    private val extents: List<UpdateMetadata.Extent>,
    private val blockSize: Int,
) : OutputStream() {

    private var extentIndex = 0
    private var bytesInExtent = 0L
    private var totalWritten = 0L
    private val capacity = extents.sumOf { it.numBlocks } * blockSize

    init {
        require(extents.isNotEmpty()) { "operation has no destination extent" }
        raf.seek(extents[0].startBlock * blockSize)
    }

    private fun extentCapacity(index: Int): Long = extents[index].numBlocks.toLong() * blockSize

    override fun write(b: Int) {
        val one = byteArrayOf(b.toByte())
        write(one, 0, 1)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        var o = off
        var n = len
        while (n > 0) {
            if (extentIndex >= extents.size) {
                throw IOException(
                    "Operation writes past its destination extents ($totalWritten + $n > $capacity bytes)"
                )
            }
            val remaining = extentCapacity(extentIndex) - bytesInExtent
            if (remaining == 0L) {
                advanceExtent()
                continue
            }
            val chunk = minOf(n.toLong(), remaining).toInt()
            raf.write(b, o, chunk)
            o += chunk
            n -= chunk
            totalWritten += chunk
            bytesInExtent += chunk
            if (bytesInExtent == extentCapacity(extentIndex)) {
                advanceExtent()
            }
        }
    }

    private fun advanceExtent() {
        extentIndex += 1
        bytesInExtent = 0L
        if (extentIndex < extents.size) {
            raf.seek(extents[extentIndex].startBlock * blockSize)
        }
    }

    /**
     * Fills the remainder of the current extent, then every later extent, with zeros so all
     * destination extents are fully materialised. Later extents are sought individually, so a gap
     * between extents is never written.
     */
    fun padRemainingWithZeros() {
        val pad = ByteArray(1 shl 16)
        while (extentIndex < extents.size) {
            var remaining = extentCapacity(extentIndex) - bytesInExtent
            while (remaining > 0) {
                val chunk = minOf(remaining, pad.size.toLong()).toInt()
                raf.write(pad, 0, chunk)
                remaining -= chunk
            }
            advanceExtent()
        }
    }

    override fun close() {
        // Intentionally do not close the shared RandomAccessFile.
    }
}
