package com.rcmiku.payload.dumper.compose.utils

import android.util.Log
import com.google.protobuf.InvalidProtocolBufferException
import com.rcmiku.payload.dumper.compose.proto.OtaPackageMetadata
import com.rcmiku.payload.dumper.compose.utils.ZipFileUtil.locateCentralDirectoryEntry
import kotlinx.coroutines.CancellationException
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

/**
 * Reads target device and build information from an OTA ZIP's package metadata.
 * Only the ZIP directory and the small metadata entries are read. Metadata errors
 * are best-effort and must not prevent parsing a valid payload.
 */
object OtaMetadataParser {

    private const val TAG = "OtaMetadataParser"

    /** Legacy text metadata, written by AOSP releasetools for older packages. */
    const val METADATA_TXT = "META-INF/com/android/metadata"

    /** Protobuf metadata, written by AOSP releasetools for newer packages. */
    const val METADATA_PB = "META-INF/com/android/metadata.pb"

    /** Alternative text path used by some OTA variants. */
    const val METADATA_ANDROID = "android/metadata"

    val CANDIDATE_PATHS = listOf(METADATA_TXT, METADATA_PB, METADATA_ANDROID)

    private val buildIdentifier = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
    private val vendorVersion = Regex(
        """(?<![A-Za-z0-9])(?:OS|V)[0-9]{1,3}(?:\.[0-9]{1,3}){3}(?![A-Za-z0-9]|\.[0-9])""",
        RegexOption.IGNORE_CASE,
    )

    internal data class PackageInfo(
        val deviceModels: List<String> = emptyList(),
        val targetBuild: String? = null,
    ) {
        /** Prefer the OTA's target version, then a single explicit HyperOS or MIUI filename tag. */
        fun systemVersion(fileName: String): String? {
            val metadataVersion = targetBuild?.let { vendorVersion.find(it)?.value }
            return metadataVersion?.lowercase()
                ?: vendorVersion.findAll(fileName).map { it.value.lowercase() }.distinct().singleOrNull()
                ?: targetBuild
        }
    }

    /** Devices named by a single `pre-device` value. */
    private const val DEVICE_SEPARATOR = '|'

    /** Decompression limit for a metadata entry; the real entries are a few hundred bytes. */
    private const val MAX_METADATA_BYTES = 4 * 1024 * 1024

    /**
     * Inflate without trusting the entry's declared size: the limit is enforced on the bytes
     * actually produced, so a lying header cannot drive a huge allocation.
     */
    private const val INFLATE_CHUNK = 8 * 1024

    /** Returns only the device codenames for callers that do not need the target build. */
    suspend fun readDeviceModels(input: SeekableInput): List<String> {
        val fileLength = input.length()
        if (fileLength < 22) return emptyList()

        // A bare payload.bin has no ZIP structure at all.
        if (fileLength >= 4) {
            val magic = ByteArray(4)
            runCatching {
                input.seek(0)
                input.readFully(magic)
            }.onFailure {
                if (it is CancellationException) throw it
                return emptyList()
            }
            if (String(magic, StandardCharsets.US_ASCII) == "CrAU") return emptyList()
        }

        val centralDirectory = runCatching { ZipFileUtil.readCentralDirectory(input) }
            .getOrElse { e ->
                if (e is CancellationException) throw e
                logInfo("Could not read ZIP central directory: ${e.message}")
                return emptyList()
            } ?: return emptyList()

        return readPackageInfo(input, centralDirectory).deviceModels
    }

    internal suspend fun readPackageInfo(input: SeekableInput, centralDirectory: ByteArray): PackageInfo {
        var devices = emptyList<String>()
        var targetBuild: String? = null
        for (path in CANDIDATE_PATHS) {
            val entry = runCatching { readEntry(input, centralDirectory, path) }
                .getOrElse { e ->
                    if (e is CancellationException) throw e
                    logInfo("Metadata entry $path unreadable: ${e.message}")
                    null
                } ?: continue
            if (devices.isEmpty()) devices = entry.deviceModels
            if (targetBuild == null) targetBuild = entry.targetBuild
            if (devices.isNotEmpty() && targetBuild != null) break
        }
        return PackageInfo(devices, targetBuild)
    }

    private suspend fun readEntry(
        input: SeekableInput,
        centralDirectory: ByteArray,
        path: String,
    ): PackageInfo? {
        // The central directory is authoritative for the method and the compressed size: a
        // streaming writer leaves a zero size in the local header (data descriptor).
        val entry = locateCentralDirectoryEntry(centralDirectory, path) ?: return null
        val localHeaderOffset = entry.localHeaderOffset
        if (localHeaderOffset < 0) return null

        input.seek(localHeaderOffset)
        val header = ByteArray(30)
        input.readFully(header)
        val relativeOffset = ZipFileUtil.locateLocalFileOffset(header)
        if (relativeOffset < 0) return null
        val dataStart = localHeaderOffset + relativeOffset

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
        if (content.isEmpty()) return null
        return if (path.endsWith(".pb")) parseProtobuf(content) else parseText(content)
    }

    private fun parseText(content: ByteArray): PackageInfo {
        var devices = emptyList<String>()
        var buildIncremental: String? = null
        var fingerprintBuild: String? = null
        for (line in content.toString(StandardCharsets.UTF_8).lineSequence()) {
            val trimmed = line.trim()
            val value = trimmed.substringAfter('=', missingDelimiterValue = "").trim()
            when (trimmed.substringBefore('=').trimEnd()) {
                "pre-device" -> if (devices.isEmpty()) devices = splitDevices(value)
                "post-build-incremental" -> buildIncremental = value.takeIf(buildIdentifier::matches)
                "post-build" -> fingerprintBuild = buildFromFingerprints(value.split(DEVICE_SEPARATOR))
            }
        }
        return PackageInfo(devices, buildIncremental ?: fingerprintBuild)
    }

    private fun parseProtobuf(content: ByteArray): PackageInfo? {
        val metadata = try {
            OtaPackageMetadata.OtaMetadata.parseFrom(content)
        } catch (e: InvalidProtocolBufferException) {
            logInfo("Malformed metadata.pb: ${e.message}")
            return null
        }
        val target = metadata.postcondition
        val devices = metadata.precondition.deviceList.flatMap(::splitDevices)
            .ifEmpty { target.deviceList.flatMap(::splitDevices) }
        val build = target.buildIncremental.trim().takeIf(buildIdentifier::matches)
            ?: buildFromFingerprints(target.buildList)
        return PackageInfo(devices, build)
    }

    private fun buildFromFingerprints(fingerprints: List<String>): String? =
        fingerprints.firstNotNullOfOrNull { fingerprint ->
            // brand/product/device:release/build-id/incremental:type/tags
            val incremental = fingerprint.substringAfter(':', "").split('/').getOrNull(2)
                ?.substringBefore(':')
            incremental?.takeIf(buildIdentifier::matches)
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

    private fun logInfo(message: String) {
        runCatching { Log.i(TAG, message) }
    }
}
