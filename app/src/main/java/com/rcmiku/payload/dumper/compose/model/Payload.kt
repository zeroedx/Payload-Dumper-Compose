package com.rcmiku.payload.dumper.compose.model

import chromeos_update_engine.UpdateMetadata

data class Payload(
    val fileName: String,
    val header: PayloadHeader,
    val deltaArchiveManifest: UpdateMetadata.DeltaArchiveManifest,
    val dataOffset: Long,
    val blockSize: Int,
    val archiveSize: Long,
    val isPath: Boolean,
    // Device codename(s) from the OTA package metadata; empty for a bare payload.bin or a package
    // whose metadata is missing/corrupt. Populated by ParseUtil after the payload is parsed.
    val deviceModels: List<String> = emptyList()
) {
    /** Payload minor version. 0 means a full payload; anything else is incremental (delta). */
    val minorVersion: Int get() = deltaArchiveManifest.minorVersion

    /** Whether this is an incremental (delta) payload, which this app cannot extract. */
    val isDelta: Boolean get() = minorVersion != 0
}

data class PayloadHeader(
    val fileFormatVersion: Long,
    val manifestSize: Long,
    val metadataSignatureSize: Int,
)

data class PartitionInfo(
    val partitionName: String,
    val size: Long,
    val rawSize: Long,
    val sha256: String,
    val isDownloading: Boolean = false,
    val progress: Float = 0f,
    val verifyState: VerifyState = VerifyState.Unverified,
    val error: String? = null,
)

enum class VerifyState { Unverified, Verified, Failed }

data class FileInfo(
    val offset: Long,
    val size: Long
)

data class ArchiveInfo(
    val fileName: String,
    val fileSize: Long,
    val securityPatchLevel: String,
    // Device codenames the OTA targets (the `pre-device` value of the package metadata). The
    // payload manifest carries no device field, so this is read from the OTA ZIP metadata. It is
    // empty when the metadata is missing or has no valid device, and it can hold several entries
    // for a multi-device package. Never derived from the file name or the build fingerprint.
    val deviceModels: List<String> = emptyList()
) {
    /** Single display value: the first device, or null when none was found. */
    val deviceModel: String? get() = deviceModels.firstOrNull()
}
