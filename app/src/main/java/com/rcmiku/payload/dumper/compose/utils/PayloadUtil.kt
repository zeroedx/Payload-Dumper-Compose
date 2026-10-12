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

    /** Number of blocks this operation produces (total across all destination extents). */
    private fun UpdateMetadata.InstallOperation.dstBlockCount(): Long =
        dstExtentsList.sumOf { it.numBlocks }

    /** Byte offset of the first destination extent. */
    private fun UpdateMetadata.InstallOperation.firstDstOffset(blockSize: Int): Long =
        if (dstExtentsList.isEmpty()) 0L else dstExtentsList[0].startBlock * blockSize

    /**
     * Writes decompressed [input] into the destination extents, padding the final block with
     * zeros. Uses a non-closing output stream wrapper so the shared [RandomAccessFile] (and its
     * file descriptor) survives across the operations of the same partition.
     */
    private fun writeDecompressed(
        partOutput: RandomAccessFile,
        input: CompressorInputStream,
        op: UpdateMetadata.InstallOperation,
        blockSize: Int,
    ) {
        val targetSize = op.dstBlockCount() * blockSize
        val nonClosing = NonClosingOutputStream(partOutput)
        IOUtils.copy(input, nonClosing)
        val written = nonClosing.bytesWritten
        if (written < targetSize) {
            val pad = ByteArray(blockSize)
            var remaining = targetSize - written
            while (remaining > 0) {
                val chunk = minOf(remaining, blockSize.toLong()).toInt()
                partOutput.write(pad, 0, chunk)
                remaining -= chunk
            }
        }
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
            partOutput.seek(op.firstDstOffset(blockSize))

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
                    decompressed.use { writeDecompressed(partOutput, it, op, blockSize) }
                }

                UpdateMetadata.InstallOperation.Type.REPLACE -> {
                    val data = readBlob(op, source)
                    partOutput.write(data)
                }

                UpdateMetadata.InstallOperation.Type.ZERO -> {
                    val targetSize = op.dstBlockCount() * blockSize
                    writeZeros(partOutput, targetSize)
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

    private fun writeZeros(partOutput: RandomAccessFile, size: Long) {
        val pad = ByteArray(1024 * 1024)
        var remaining = size
        while (remaining > 0) {
            val chunk = minOf(remaining, pad.size.toLong()).toInt()
            partOutput.write(pad, 0, chunk)
            remaining -= chunk
        }
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
                }
            } catch (t: Throwable) {
                // Never leave a half-written image behind: it would look like a real dump.
                output.cleanup()
                throw t
            }

            // If we know the expected size and the output is short, treat it as a failure.
            if (totalBytes > 0 && output.file.length() < totalBytes) {
                val actual = output.file.length()
                output.cleanup()
                throw IOException(
                    "Incomplete extraction: ${metadataPartition.partitionName}.img is $actual " +
                        "bytes, expected $totalBytes"
                )
            }

            // The manifest declares the final image size; an op writing past its dst extent would
            // silently inflate the file. Reject any such mismatch.
            if (metadataPartition.hasNewPartitionInfo()) {
                val declared = metadataPartition.newPartitionInfo.size
                val actual = output.file.length()
                if (declared > 0 && actual != declared) {
                    output.cleanup()
                    throw IOException(
                        "Image length ($actual) does not match the manifest ($declared)"
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
 * An [OutputStream] that forwards writes to a shared [RandomAccessFile] but ignores [close].
 * The original code wrapped `partOutput.fd` in a `FileOutputStream`, and `IOUtils.copy` closed
 * that stream, which also closed the shared file descriptor and corrupted every later operation.
 */
private class NonClosingOutputStream(
    private val raf: RandomAccessFile,
) : OutputStream() {

    var bytesWritten: Long = 0
        private set

    override fun write(b: Int) {
        raf.write(b)
        bytesWritten += 1
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        raf.write(b, off, len)
        bytesWritten += len.toLong()
    }

    override fun close() {
        // Intentionally do not close the shared RandomAccessFile.
    }
}
