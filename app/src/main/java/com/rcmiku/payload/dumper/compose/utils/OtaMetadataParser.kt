package com.rcmiku.payload.dumper.compose.utils

import android.util.Log
import com.google.protobuf.InvalidProtocolBufferException
import com.rcmiku.payload.dumper.compose.proto.OtaPackageMetadata
import com.rcmiku.payload.dumper.compose.utils.ZipFileUtil.locateCentralDirectory
import com.rcmiku.payload.dumper.compose.utils.ZipFileUtil.locateCentralDirectoryEntry
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

/**
 * Reads the target device codename(s) from an OTA ZIP's package metadata.
 *
 * Only the ZIP central directory and the (tiny) metadata entries are touched, so an OTA package of
 * any size is handled without downloading or unpacking it as a whole. A bare `payload.bin` (no ZIP
 * wrapper) simply has no metadata and yields an empty list.
 *
 * The metadata can appear as plain text (`META-INF/com/android/metadata`, `android/metadata`) with a
 * `pre-device=<value>` line, or as protobuf (`META-INF/com/android/metadata.pb`) where the value is
 * `precondition.device` (repeated). A value containing `|` is split into the individual devices the
 * package targets. Anything that is missing, malformed, unreadable or oversized is treated as "no
 * device": the caller must never fail the payload parse because of metadata, and a device is never
 * guessed from the file name, URL or build fingerprint.
 */
object OtaMetadataParser {

    private const val TAG = "OtaMetadataParser"

    /** Legacy text metadata, written by AOSP releasetools for older packages. */
    const val METADATA_TXT = "META-INF/com/android/metadata"

    /** Protobuf metadata, written by AOSP releasetools for newer packages. */
    const val METADATA_PB = "META-INF/com/android/metadata.pb"

    /** Alternative text path used by some OTA variants. */
    const val METADATA_ANDROID = "android/metadata"

    /**
     * Candidate entries, most authoritative first. The text metadata is preferred because its
     * `pre-device` is exactly the value this feature targets; the protobuf is the primary format
     * for newer packages and is used when the text entry is absent. Kept in one ordered list so the
     * resolution order is explicit and testable.
     */
    val CANDIDATE_PATHS = listOf(METADATA_TXT, METADATA_PB, METADATA_ANDROID)

    /** Devices named by a single `pre-device` value. */
    private const val DEVICE_SEPARATOR = '|'

    /** Local file header signature (`PK\003\004`). */
    private const val LOCSIG = 0x04034b50L

    /** Decompression limit for a metadata entry; the real entries are a few hundred bytes. */
    private const val MAX_METADATA_BYTES = 4 * 1024 * 1024

    /**
     * Inflate without trusting the entry's declared size: the limit is enforced on the bytes
     * actually produced, so a lying header cannot drive a huge allocation.
     */
    private const val INFLATE_CHUNK = 8 * 1024

    /**
     * Returns the device codename(s) the OTA targets, or an empty list when the metadata is absent,
     * has no `pre-device`, is malformed, unreadable or unsupported. Never throws for a metadata
     * problem; unexpected I/O errors from the ZIP directory are also swallowed so the caller's
     * payload parse is unaffected.
     */
    suspend fun readDeviceModels(input: SeekableInput): List<String> {
        val fileLength = input.length()
        if (fileLength < 22) return emptyList()

        // A bare payload.bin has no ZIP structure at all.
        if (fileLength >= 4) {
            val magic = ByteArray(4)
            runCatching {
                input.seek(0)
                input.readFully(magic)
            }.onFailure { return emptyList() }
            if (String(magic, StandardCharsets.US_ASCII) == "CrAU") return emptyList()
        }

        val centralDirectory = runCatching { readCentralDirectory(input, fileLength) }
            .getOrElse { e ->
                logInfo("Could not read ZIP central directory: ${e.message}")
                return emptyList()
            } ?: return emptyList()

        for (path in CANDIDATE_PATHS) {
            val devices = runCatching { readEntry(input, centralDirectory, path) }
                .getOrElse { e ->
                    logInfo("Metadata entry $path unreadable: ${e.message}")
                    emptyList()
                }
            if (devices.isNotEmpty()) return devices
        }
        return emptyList()
    }

    private class CentralDirectory(val offset: Long, val bytes: ByteArray)

    /** Reads the ZIP central directory (or null when the ZIP layout is unusable). */
    private suspend fun readCentralDirectory(
        input: SeekableInput,
        fileLength: Long,
    ): CentralDirectory? {
        // The EOCD (and its optional comment) sits at the very end; a 4096-byte tail is more than
        // enough for any real package. Shrinking the window for a small file keeps this correct for
        // any ZIP, not just multi-gigabyte ones.
        val tailSize = minOf(4096L, fileLength).toInt()
        if (tailSize < 22) return null
        val tail = ByteArray(tailSize)
        input.seek(fileLength - tailSize)
        input.readFully(tail)
        val info = locateCentralDirectory(tail, fileLength)
        if (info.offset < 0 || info.size <= 0 || info.offset + info.size > fileLength) return null
        val bytes = ByteArray(info.size.toInt())
        input.seek(info.offset)
        input.readFully(bytes)
        return CentralDirectory(info.offset, bytes)
    }

    /** Extracts the device list from one metadata entry, or an empty list if it has none. */
    private suspend fun readEntry(
        input: SeekableInput,
        centralDirectory: CentralDirectory,
        path: String,
    ): List<String> {
        // The central directory is authoritative for the method and the compressed size: a
        // streaming writer leaves a zero size in the local header (data descriptor).
        val entry = locateCentralDirectoryEntry(centralDirectory.bytes, path) ?: return emptyList()
        val localHeaderOffset = entry.localHeaderOffset
        if (localHeaderOffset < 0) return emptyList()

        input.seek(localHeaderOffset)
        val header = ByteArray(30)
        input.readFully(header)
        if (le32(header, 0) != LOCSIG) return emptyList()
        val fileNameLength = le16(header, 26)
        val extraFieldLength = le16(header, 28)
        val dataStart = localHeaderOffset + 30 + fileNameLength + extraFieldLength

        val compressed = if (entry.compressedSize > 0) {
            if (entry.compressedSize > MAX_METADATA_BYTES) {
                throw IOException("Metadata entry too large (${entry.compressedSize} bytes)")
            }
            ByteArray(entry.compressedSize.toInt()).also {
                input.seek(dataStart)
                input.readFully(it)
            }
        } else {
            ByteArray(0)
        }

        val content = when (entry.method) {
            0 -> compressed
            8 -> inflate(compressed)
            else -> throw IOException("Unsupported compression method ${entry.method}")
        }
        return extractDevices(content, path.endsWith(".pb"))
    }

    /**
     * Decodes [content] and pulls the device list out of it. `.pb` entries are decoded as protobuf;
     * anything else as text. The entry name decides which, because the two formats share neither a
     * magic nor a reliable structure.
     */
    private fun extractDevices(content: ByteArray, isProtobuf: Boolean): List<String> {
        if (content.isEmpty()) return emptyList()
        return if (isProtobuf) extractDevicesFromProtobuf(content) else extractDevicesFromText(content)
    }

    /** `pre-device=<value>` line; value may itself be a `|`-joined list of devices. */
    private fun extractDevicesFromText(content: ByteArray): List<String> {
        val text = content.toString(StandardCharsets.UTF_8)
        for (line in text.lineSequence()) {
            val trimmed = line.trim()
            if (!trimmed.startsWith("pre-device")) continue
            val value = trimmed.substringAfter('=', missingDelimiterValue = "").trim()
            val devices = splitDevices(value)
            if (devices.isNotEmpty()) return devices
        }
        return emptyList()
    }

    /**
     * `precondition.device` (repeated). A malformed payload is not "parsed successfully": it is
     * logged and yields no device. `pre-device` is preferred; `postcondition.device` is only a
     * fallback for packages whose precondition is empty.
     */
    private fun extractDevicesFromProtobuf(content: ByteArray): List<String> {
        val metadata = try {
            OtaPackageMetadata.OtaMetadata.parseFrom(content)
        } catch (e: InvalidProtocolBufferException) {
            logInfo("Malformed metadata.pb: ${e.message}")
            return emptyList()
        }
        val precondition = metadata.precondition.deviceList.flatMap { splitDevices(it) }
        if (precondition.isNotEmpty()) return precondition
        return metadata.postcondition.deviceList.flatMap { splitDevices(it) }
    }

    private fun splitDevices(value: String): List<String> =
        value.split(DEVICE_SEPARATOR).map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    /** Inflates a stored-deflate entry with the size limit enforced on the produced bytes. */
    private fun inflate(compressed: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        // ZIP entries carry raw deflate (no zlib wrapper), so the inflater must run in nowrap mode.
        val inflater = Inflater(true)
        try {
            InflaterInputStream(compressed.inputStream(), inflater, INFLATE_CHUNK).use { stream ->
                val buffer = ByteArray(INFLATE_CHUNK)
                var total = 0
                while (true) {
                    val read = stream.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_METADATA_BYTES) {
                        throw IOException("Metadata entry expands beyond $MAX_METADATA_BYTES bytes")
                    }
                    out.write(buffer, 0, read)
                }
            }
        } finally {
            // The stream does not own the inflater we passed, so release its native buffer here.
            inflater.end()
        }
        return out.toByteArray()
    }

    private fun le16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private fun le32(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toLong() and 0xFF) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 8) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 3].toLong() and 0xFF) shl 24)

    private fun logInfo(message: String) {
        runCatching { Log.i(TAG, message) }
    }
}
