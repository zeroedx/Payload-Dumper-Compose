package com.rcmiku.payload.dumper.compose.model

import chromeos_update_engine.UpdateMetadata

data class Payload(
    val fileName: String,
    val header: PayloadHeader,
    val deltaArchiveManifest: UpdateMetadata.DeltaArchiveManifest,
    val dataOffset: Long,
    val blockSize: Int,
    val archiveSize: Long,
    val isPath: Boolean
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
    // The device model/codename. Not present in the payload manifest
    // (DeltaArchiveManifest has no device field), so it is null until a source
    // for it is added (e.g. parsing the OTA package metadata).
    val deviceModel: String? = null
)