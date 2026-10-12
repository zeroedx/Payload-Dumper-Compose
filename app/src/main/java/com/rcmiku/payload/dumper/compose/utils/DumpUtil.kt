package com.rcmiku.payload.dumper.compose.utils

import android.net.Uri
import android.widget.Toast
import com.rcmiku.payload.dumper.compose.R
import com.rcmiku.payload.dumper.compose.model.Payload
import com.rcmiku.payload.dumper.compose.utils.AppContextUtil.context
import com.rcmiku.payload.dumper.compose.utils.MiscUtil.toHexString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Orchestrates one partition dump: extract into a private working file, verify its SHA-256, then
 * publish it (copy to the destination) and clean up.
 *
 * Writing to a private working file means a cancelled or failed dump never leaves a partial .img
 * in the user's chosen destination.
 */
class DumpUtil {

    suspend fun dump(
        partitionName: String,
        payload: Payload,
        source: SeekableInput,
        outputTreeUri: Uri?,
        onProgressUpdate: (Long) -> Unit,
        onVerification: (Boolean) -> Unit = {},
        onCancelled: () -> Unit = {},
        onFailure: (Boolean) -> Unit,
    ) {
        Toast.makeText(context, context.getString(R.string.start_dump_message), Toast.LENGTH_SHORT)
            .show()

        val partition = payload.deltaArchiveManifest.partitionsList
            .firstOrNull { it.partitionName == partitionName }
            ?: run {
                Toast.makeText(context, "Partition not found", Toast.LENGTH_SHORT).show()
                onFailure(false)
                return
            }

        val workingFile = File(workingDir(), "$partitionName.img")
        val output = PartitionOutput(workingFile)

        val result = runCatching {
            PayloadUtil.extractPartition(
                partition,
                source,
                output,
                payload,
                onProgressUpdate,
            )
        }

        result.onFailure { error ->
            output.cleanup()
            if (error is CancellationException) {
                output.cleanup()
                onCancelled()
                throw error
            }
            Toast.makeText(context, error.message, Toast.LENGTH_SHORT).show()
            onFailure(false)
            return
        }

        // Verification happens on the working file, before anything is published.
        val expected = if (partition.hasNewPartitionInfo()) {
            partition.newPartitionInfo.hash.toHexString()
        } else {
            ""
        }
        val verified = if (expected.isBlank()) {
            true
        } else {
            withContext(Dispatchers.IO) { HashUtil.matches(expected, HashUtil.sha256Hex(workingFile)) }
        }

        if (!verified) {
            // A mismatching image is not a valid dump: never publish it.
            output.cleanup()
            onVerification(false)
            Toast.makeText(context, context.getString(R.string.dump_removed_message), Toast.LENGTH_LONG)
                .show()
            onFailure(false)
            return
        }
        onVerification(true)

        val published = runCatching {
            publish(workingFile, partitionName, outputTreeUri)
        }
        output.cleanup()

        published.onFailure { error ->
            Toast.makeText(context, error.message, Toast.LENGTH_SHORT).show()
            onFailure(false)
            return
        }

        Toast.makeText(
            context,
            context.getString(R.string.dump_success_message, published.getOrThrow().toString()),
            Toast.LENGTH_SHORT
        ).show()
    }

    private suspend fun publish(workingFile: File, partitionName: String, treeUri: Uri?): Uri = withContext(Dispatchers.IO) {
        if (treeUri != null) {
            SafUtil.writeIntoTree(context, treeUri, "$partitionName.img", workingFile)
        } else {
            // No SAF destination chosen yet: fall back to the app-private files dir so the dump
            // always succeeds and can be exported later. Copy to a temporary sibling and rename,
            // so a copy interrupted midway never leaves a truncated .img at the final path.
            val dest = File(context.getExternalFilesDir(null) ?: context.filesDir, "$partitionName.img")
            val temp = File(dest.parentFile, "${dest.name}.part")
            try {
                SafUtil.writeToFile(workingFile, temp)
                if (dest.exists()) dest.delete()
                if (!temp.renameTo(dest)) {
                    // Fall back to a direct copy if rename is not permitted on this volume.
                    SafUtil.writeToFile(temp, dest)
                    temp.delete()
                }
            } catch (t: Throwable) {
                temp.delete()
                throw t
            }
            Uri.fromFile(dest)
        }
    }

    private fun workingDir(): File {
        val dir = File(context.cacheDir, "dumps")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }
}
