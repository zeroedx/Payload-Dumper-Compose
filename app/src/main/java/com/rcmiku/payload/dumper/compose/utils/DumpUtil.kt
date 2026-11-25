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
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Orchestrates one partition dump: extract into a private working file, verify its SHA-256, then
 * publish it and clean up.
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
        oldPartition: SeekableInput? = null,
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

        val destination = if (outputTreeUri == null) {
            File(context.getExternalFilesDir(null) ?: context.filesDir, "$partitionName.img")
        } else {
            null
        }
        var createdFile: File? = null
        val workingFile = try {
            withContext(Dispatchers.IO) {
                File.createTempFile("dump-", ".part", destination?.parentFile ?: workingDir())
                    .also { createdFile = it }
            }
        } catch (error: CancellationException) {
            createdFile?.delete()
            onCancelled()
            throw error
        } catch (error: Exception) {
            Toast.makeText(context, error.message, Toast.LENGTH_SHORT).show()
            onFailure(false)
            return
        }
        val output = PartitionOutput(workingFile)

        val result = runCatching {
            PayloadUtil.extractPartition(
                partition,
                source,
                output,
                payload,
                oldPartition,
                onProgressUpdate,
            )
        }

        result.onFailure { error ->
            output.cleanup()
            if (error is CancellationException) {
                onCancelled()
                throw error
            }
            // A changed resource cannot be fixed by retrying; surface it so the caller can prompt
            // a re-parse instead of leaving a half-extracted image around.
            if (error is ResourceChangedException) {
                onFailure(false)
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
        val verified = try {
            expected.isBlank() || withContext(Dispatchers.IO) {
                HashUtil.matches(expected, HashUtil.sha256Hex(workingFile))
            }
        } catch (error: Exception) {
            output.cleanup()
            if (error is CancellationException) {
                onCancelled()
                throw error
            }
            Toast.makeText(context, error.message, Toast.LENGTH_SHORT).show()
            onFailure(false)
            return
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
            publish(workingFile, partitionName, outputTreeUri, destination)
        }
        output.cleanup()

        published.onFailure { error ->
            if (error is CancellationException) {
                onCancelled()
                throw error
            }
            val message = if (error is FileAlreadyExistsException) {
                context.getString(R.string.file_already_exists_message, "$partitionName.img")
            } else {
                error.message
            }
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            onFailure(false)
            return
        }

        Toast.makeText(
            context,
            context.getString(R.string.dump_success_message, published.getOrThrow().toString()),
            Toast.LENGTH_SHORT
        ).show()
    }

    private suspend fun publish(
        workingFile: File,
        partitionName: String,
        treeUri: Uri?,
        destination: File?,
    ): Uri = withContext(Dispatchers.IO) {
        if (treeUri != null) {
            SafUtil.publishIntoTree(context, treeUri, "$partitionName.img", workingFile)
        } else {
            val dest = requireNotNull(destination)
            // The verified working file is already on the destination filesystem.
            Files.move(
                workingFile.toPath(), dest.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING,
            )
            Uri.fromFile(dest)
        }
    }

    private fun workingDir(): File {
        val dir = File(context.cacheDir, "dumps")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }
}
