package com.rcmiku.payload.dumper.compose.utils

import chromeos_update_engine.UpdateMetadata
import com.rcmiku.payload.dumper.compose.model.PartitionInfo
import com.rcmiku.payload.dumper.compose.model.Payload
import com.rcmiku.payload.dumper.compose.model.PayloadHeader
import com.rcmiku.payload.dumper.compose.utils.MiscUtil.toBufferedInputStream
import com.rcmiku.payload.dumper.compose.utils.MiscUtil.toHexString
import com.rcmiku.payload.dumper.compose.utils.MiscUtil.toInt
import com.rcmiku.payload.dumper.compose.utils.MiscUtil.toLong
import com.rcmiku.payload.dumper.compose.utils.ZipFileUtil.locateCentralDirectory
import com.rcmiku.payload.dumper.compose.utils.ZipFileUtil.locateLocalFileHeader
import com.rcmiku.payload.dumper.compose.utils.ZipFileUtil.locateLocalFileOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.apache.commons.compress.compressors.CompressorInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import org.apache.commons.io.IOUtils
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets

object PayloadUtil {

    private const val MAGIC_VALUE = "CrAU"
    private const val FORMAT_VERSION = 2L

    /** Max size of a raw REPLACE blob we are willing to buffer in memory. */
    private const val MAX_IN_MEMORY_OPERATION = 64L * 1024 * 1024

    /** Sanity bound on the proto manifest size, to avoid allocating absurd buffers. */
    private const val MAX_MANIFEST_SIZE = 256L * 1024 * 1024

    private val muteX by lazy {
        Mutex()
    }

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

        // Reject incremental (delta) payloads up front, before anything is extracted.
        // Extracting one would silently produce a partial image; this app only supports full OTA.
        if (payload.isDelta) {
            throw UnsupportedDeltaPayloadException(payload.minorVersion)
        }

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
                    partition.newPartitionInfo.hash.toHexString() else ""
            )
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
        val extents = op.dstExtentsList
        val writer = ExtentOutputStream(partOutput, extents, blockSize)
        writeBody(writer)
        writer.padRemainingWithZeros()
    }

    private suspend fun extractOperation(
        op: UpdateMetadata.InstallOperation,
        partOutput: RandomAccessFile,
        source: SeekableInput,
        blockSize: Int,
        offset: Long,
    ) {
        muteX.withLock {
            if (op.dstExtentsList.isEmpty()) return@withLock
            source.seek(offset + op.dataOffset)

            when (op.type) {
                UpdateMetadata.InstallOperation.Type.REPLACE_XZ,
                UpdateMetadata.InstallOperation.Type.REPLACE_BZ -> {
                    val data = readBlob(op, source)
                    // BZip2 needs a mark/reset-capable stream; BufferedInputStream provides it.
                    val raw = if (op.type == UpdateMetadata.InstallOperation.Type.REPLACE_BZ) {
                        BufferedInputStream(data.toBufferedInputStream())
                    } else {
                        data.toBufferedInputStream()
                    }
                    val decompressed = when (op.type) {
                        UpdateMetadata.InstallOperation.Type.REPLACE_XZ -> XZCompressorInputStream(raw)
                        else -> BZip2CompressorInputStream(raw)
                    }
                    decompressed.use { writeAcrossExtents(partOutput, op, blockSize) { out -> IOUtils.copy(it, out) } }
                }

                UpdateMetadata.InstallOperation.Type.REPLACE -> {
                    val data = readBlob(op, source)
                    writeAcrossExtents(partOutput, op, blockSize) { out -> out.write(data) }
                }

                UpdateMetadata.InstallOperation.Type.ZERO -> {
                    writeAcrossExtents(partOutput, op, blockSize) { /* nothing to write; pads zeros */ }
                }

                else -> {
                    throw RuntimeException("Unsupported operation type ${op.type}")
                }
            }
        }
    }

    /** Reads the compressed blob for [op] from [source], enforcing the in-memory size limit. */
    private suspend fun readBlob(
        op: UpdateMetadata.InstallOperation,
        source: SeekableInput,
    ): ByteArray {
        ensureSmallEnoughToBuffer(op.dataLength)
        val data = ByteArray(op.dataLength.toInt())
        source.readFully(data)
        return data
    }

    /** Guards against allocating huge in-memory buffers for raw REPLACE operations. */
    private fun ensureSmallEnoughToBuffer(dataLength: Long) {
        if (dataLength < 0 || dataLength > MAX_IN_MEMORY_OPERATION) {
            throw IOException(
                "REPLACE operation of $dataLength bytes exceeds the in-memory limit " +
                    "($MAX_IN_MEMORY_OPERATION bytes)"
            )
        }
    }

    suspend fun extractPartition(
        metadataPartition: UpdateMetadata.PartitionUpdate,
        source: SeekableInput,
        output: PartitionOutput,
        payload: Payload,
        onProgressUpdate: (Long) -> Unit
    ) {
        withContext(Dispatchers.IO) {
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

/** Thrown when a delta/incremental payload (minor_version != 0) is parsed. */
class UnsupportedDeltaPayloadException(val minorVersion: Int) :
    IOException("Incremental (delta) payload not supported (minor_version=$minorVersion)")

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
 * following region. [close] is intentionally a no-op so `IOUtils.copy` cannot close the shared
 * file descriptor (a bug in the original code that corrupted every later operation).
 */
private class ExtentOutputStream(
    private val raf: RandomAccessFile,
    extents: List<UpdateMetadata.Extent>,
    private val blockSize: Int,
) : OutputStream() {

    private val extents = extents
    private var extentIndex = 0
    private var blockInExtent = 0
    private var totalWritten = 0L
    private val capacity = extents.sumOf { it.numBlocks } * blockSize

    init {
        require(extents.isNotEmpty()) { "operation has no destination extent" }
        raf.seek(extents[0].startBlock * blockSize)
    }

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
            val extent = extents[extentIndex]
            val remainingBlocks = extent.numBlocks - blockInExtent
            val remainingBytes = remainingBlocks * blockSize
            val chunk = minOf(n.toLong(), remainingBytes.toLong()).toInt()

            raf.write(b, o, chunk)
            o += chunk
            n -= chunk
            totalWritten += chunk
            blockInExtent += chunk / blockSize
            val leftover = chunk % blockSize
            if (leftover != 0) {
                // A partial block: only the final extent of a payload may end mid-block, and the
                // pad pass fills the rest with zeros (no seek, so the write position stays put).
                blockInExtent += 1
            }
            if (blockInExtent >= extent.numBlocks) {
                extentIndex += 1
                blockInExtent = 0
                if (extentIndex < extents.size) {
                    raf.seek(extents[extentIndex].startBlock * blockSize)
                }
            }
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
            val extent = extents[extentIndex]
            var remaining = (extent.numBlocks - blockInExtent).toLong() * blockSize
            while (remaining > 0) {
                val chunk = minOf(remaining, pad.size.toLong()).toInt()
                raf.write(pad, 0, chunk)
                remaining -= chunk
            }
            extentIndex += 1
            blockInExtent = 0
            if (extentIndex < extents.size) {
                raf.seek(extents[extentIndex].startBlock * blockSize)
            }
        }
    }

    override fun close() {
        // Intentionally do not close the shared RandomAccessFile.
    }
}
