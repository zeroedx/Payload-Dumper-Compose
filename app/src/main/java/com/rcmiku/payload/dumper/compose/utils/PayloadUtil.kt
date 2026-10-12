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
import java.nio.channels.Channels
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths

object PayloadUtil {

    private const val MAGIC_VALUE = "CrAU"
    private const val FORMAT_VERSION = 2L

    /** Max size of a raw REPLACE blob we are willing to buffer in memory. */
    private const val MAX_IN_MEMORY_OPERATION = 64L * 1024 * 1024

    private val muteX by lazy {
        Mutex()
    }

    private fun initPayloadLocal(
        fileName: String,
        raf: RandomAccessFile,
        payloadOffset: Long
    ): Payload {
        raf.seek(payloadOffset)
        val magicBytes = ByteArray(4)

        raf.readFully(magicBytes)
        if (String(magicBytes, StandardCharsets.UTF_8) != MAGIC_VALUE) {
            throw RuntimeException("Invalid magic value")
        }

        val fileFormatVersion = raf.readLong()
        if (fileFormatVersion != FORMAT_VERSION) {
            throw RuntimeException("Unsupported file format version")
        }

        val manifestSize = raf.readLong()
        val metadataSignatureSize = raf.readInt()

        val manifest = ByteArray(manifestSize.toInt())
        raf.readFully(manifest)

        val metadataSignatureMessage = ByteArray(metadataSignatureSize)
        raf.readFully(metadataSignatureMessage)

        val deltaArchiveManifest = UpdateMetadata.DeltaArchiveManifest.parseFrom(manifest)
        val dataOffset: Long = raf.channel.position()
        val payloadHeader = PayloadHeader(fileFormatVersion, manifestSize, metadataSignatureSize)
        return Payload(
            fileName,
            payloadHeader,
            deltaArchiveManifest,
            dataOffset,
            deltaArchiveManifest.blockSize,
            raf.length(),
            true
        )
    }

    private suspend fun initPayloadFromHttp(
        fileName: String,
        httpUtil: HttpUtil,
        payloadOffset: Long
    ): Payload {
        httpUtil.seek(payloadOffset)
        val magicBytes = ByteArray(4)
        httpUtil.read(magicBytes)
        if (String(magicBytes, StandardCharsets.UTF_8) != MAGIC_VALUE) {
            throw IOException("Invalid magic value")
        }

        val fileFormatVersionBytes = ByteArray(8)
        httpUtil.read(fileFormatVersionBytes)
        val fileFormatVersion = fileFormatVersionBytes.toLong()
        if (fileFormatVersion != FORMAT_VERSION) {
            throw IOException("Unsupported file format version")
        }

        val manifestSizeBytes = ByteArray(8)
        httpUtil.read(manifestSizeBytes)
        val manifestSize = manifestSizeBytes.toLong()

        val metadataSignatureSizeBytes = ByteArray(4)
        httpUtil.read(metadataSignatureSizeBytes)
        val metadataSignatureSize = metadataSignatureSizeBytes.toInt()

        val manifest = ByteArray(manifestSize.toInt())
        httpUtil.read(manifest)
        val metadataSignatureMessage = ByteArray(metadataSignatureSize)
        httpUtil.read(metadataSignatureMessage)
        val deltaArchiveManifest = UpdateMetadata.DeltaArchiveManifest.parseFrom(manifest)
        val dataOffset: Long = httpUtil.position()
        val payloadHeader = PayloadHeader(fileFormatVersion, manifestSize, metadataSignatureSize)
        return Payload(
            fileName,
            payloadHeader,
            deltaArchiveManifest,
            dataOffset,
            deltaArchiveManifest.blockSize,
            httpUtil.length(),
            false
        )
    }

    suspend fun initPayload(fileName: String, input: Any, payloadOffset: Long): Payload {
        if (payloadOffset == -1L) {
            throw IOException("Invalid payload offset value")
        }

        val payload = when (input) {
            is RandomAccessFile -> {
                initPayloadLocal(fileName, input, payloadOffset)
            }

            is HttpUtil -> {
                initPayloadFromHttp(fileName, input, payloadOffset)
            }

            else -> throw IllegalArgumentException("Unsupported input type: ${input.javaClass.name}.")
        }

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

    private suspend fun extractFromLocal(
        op: UpdateMetadata.InstallOperation,
        partOutput: RandomAccessFile,
        raf: RandomAccessFile,
        blockSize: Int,
        offset: Long,
    ) {
        muteX.withLock {
            if (op.dstExtentsList.isEmpty()) return@withLock
            raf.seek(offset + op.dataOffset)
            partOutput.seek(op.firstDstOffset(blockSize))

            val otaInputStream = Channels.newInputStream(raf.channel)

            when (op.type) {
                UpdateMetadata.InstallOperation.Type.REPLACE_XZ -> {
                    val xz = XZCompressorInputStream(otaInputStream)
                    xz.use { writeDecompressed(partOutput, it, op, blockSize) }
                }

                UpdateMetadata.InstallOperation.Type.REPLACE_BZ -> {
                    val bz = BZip2CompressorInputStream(BufferedInputStream(otaInputStream))
                    bz.use { writeDecompressed(partOutput, it, op, blockSize) }
                }

                UpdateMetadata.InstallOperation.Type.REPLACE -> {
                    ensureSmallEnoughToBuffer(op.dataLength)
                    val data = ByteArray(op.dataLength.toInt())
                    raf.readFully(data)
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

    private suspend fun extractFromHttp(
        op: UpdateMetadata.InstallOperation,
        partOutput: RandomAccessFile,
        httpUtil: HttpUtil,
        blockSize: Int,
        offset: Long,
    ) {
        muteX.withLock {
            if (op.dstExtentsList.isEmpty()) return@withLock
            httpUtil.seek(offset + op.dataOffset)
            partOutput.seek(op.firstDstOffset(blockSize))

            when (op.type) {
                UpdateMetadata.InstallOperation.Type.REPLACE_XZ -> {
                    val data = ByteArray(op.dataLength.toInt()); httpUtil.read(data)
                    val xz = XZCompressorInputStream(data.toBufferedInputStream())
                    xz.use { writeDecompressed(partOutput, it, op, blockSize) }
                }

                UpdateMetadata.InstallOperation.Type.REPLACE_BZ -> {
                    val data = ByteArray(op.dataLength.toInt()); httpUtil.read(data)
                    val bz = BZip2CompressorInputStream(BufferedInputStream(data.toBufferedInputStream()))
                    bz.use { writeDecompressed(partOutput, it, op, blockSize) }
                }

                UpdateMetadata.InstallOperation.Type.REPLACE -> {
                    ensureSmallEnoughToBuffer(op.dataLength)
                    val data = ByteArray(op.dataLength.toInt()); httpUtil.read(data)
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
        if (dataLength > MAX_IN_MEMORY_OPERATION) {
            throw IOException(
                "REPLACE operation of $dataLength bytes exceeds the in-memory limit " +
                    "($MAX_IN_MEMORY_OPERATION bytes)"
            )
        }
    }

    suspend fun extractPartition(
        metadataPartition: UpdateMetadata.PartitionUpdate,
        input: Any,
        outputDir: String,
        payload: Payload,
        onProgressUpdate: (Long) -> Unit
    ) {
        withContext(Dispatchers.IO) {
            val downloadDir = Paths.get(outputDir)
            if (!Files.exists(downloadDir)) {
                Files.createDirectories(downloadDir)
            }

            val outputFile = File(downloadDir.toFile(), "${metadataPartition.partitionName}.img")
            // Approximate expected output size, used to make the progress bar monotonic.
            val totalBytes = metadataPartition.operationsList
                .sumOf { it.dstExtentsList.sumOf { e -> e.numBlocks } } * payload.blockSize
            var produced = 0L

            try {
                RandomAccessFile(outputFile, "rw").use { partOutput ->
                    metadataPartition.operationsList.forEach { operation ->
                        when (input) {
                            is RandomAccessFile -> {
                                extractFromLocal(
                                    operation,
                                    partOutput,
                                    input,
                                    payload.blockSize,
                                    payload.dataOffset,
                                )
                            }

                            is HttpUtil -> {
                                extractFromHttp(
                                    operation,
                                    partOutput,
                                    input,
                                    payload.blockSize,
                                    payload.dataOffset
                                )
                            }
                        }
                        produced += operation.dstExtentsList.sumOf { e -> e.numBlocks } *
                            payload.blockSize
                        onProgressUpdate(produced)
                    }
                }
            } catch (t: Throwable) {
                // Never leave a half-written image behind: it would look like a real dump.
                outputFile.delete()
                throw t
            }

            // If we know the expected size and the output is short, treat it as a failure.
            if (totalBytes > 0 && outputFile.length() < totalBytes) {
                outputFile.delete()
                throw IOException(
                    "Incomplete extraction: ${outputFile.name} is ${outputFile.length()} bytes, " +
                        "expected $totalBytes"
                )
            }
        }
    }

    suspend fun getPayloadOffset(pathOrUrl: String): Long {
        var payloadOffset = -1L
        val endBytes = ByteArray(4096)
        val fileName = "payload.bin"
        if (pathOrUrl.endsWith(".bin")) {
            payloadOffset = 0
        } else if (!pathOrUrl.startsWith("http://") && !pathOrUrl.startsWith("https://")) {
            val zipFile = File(pathOrUrl)
            withContext(Dispatchers.IO) {
                RandomAccessFile(zipFile, "r").use {
                    it.seek(it.length() - 4096)
                    it.readFully(endBytes)
                    val centralDirectoryInfo = locateCentralDirectory(endBytes, it.length())
                    it.seek(centralDirectoryInfo.offset)
                    val centralDirectory = ByteArray(centralDirectoryInfo.size.toInt())
                    it.readFully(centralDirectory)
                    val localHeaderOffset = locateLocalFileHeader(centralDirectory, fileName)
                    if (localHeaderOffset < 0) {
                        throw IOException("payload.bin not found inside the OTA package")
                    }
                    val localHeaderBytes = ByteArray(256)
                    it.seek(localHeaderOffset)
                    it.readFully(localHeaderBytes)
                    payloadOffset = locateLocalFileOffset(localHeaderBytes) + localHeaderOffset
                }
            }
        } else if (pathOrUrl.startsWith("http://") || pathOrUrl.startsWith("https://")) {
            HttpUtil.seek(HttpUtil.length() - 4096)
            HttpUtil.read(endBytes)
            val centralDirectoryInfo = locateCentralDirectory(endBytes, HttpUtil.length())
            HttpUtil.seek(centralDirectoryInfo.offset)
            val centralDirectory = ByteArray(centralDirectoryInfo.size.toInt())
            HttpUtil.read(centralDirectory)
            val localHeaderOffset = locateLocalFileHeader(centralDirectory, fileName)
            val localHeaderBytes = ByteArray(256)
            HttpUtil.seek(localHeaderOffset)
            HttpUtil.read(localHeaderBytes)
            payloadOffset = locateLocalFileOffset(localHeaderBytes) + localHeaderOffset
        }
        return payloadOffset
    }
}

/** Thrown when a delta/incremental payload (minor_version != 0) is parsed. */
class UnsupportedDeltaPayloadException(val minorVersion: Int) :
    IOException("Incremental (delta) payload not supported (minor_version=$minorVersion)")

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
