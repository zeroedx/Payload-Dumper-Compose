package com.rcmiku.payload.dumper.compose.utils

import android.os.Environment
import android.widget.Toast
import com.rcmiku.payload.dumper.compose.R
import com.rcmiku.payload.dumper.compose.model.Payload
import com.rcmiku.payload.dumper.compose.utils.AppContextUtil.context
import com.rcmiku.payload.dumper.compose.utils.MiscUtil.toHexString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class DumpUtil {

    suspend fun dump(
        partitionName: String,
        payload: Payload,
        isPath: Boolean,
        onProgressUpdate: (Long) -> Unit,
        onVerification: (Boolean) -> Unit = {},
        onCancelled: () -> Unit = {},
        onFailure: (Boolean) -> Unit,
    ) {
        Toast.makeText(
            context,
            context.getString(R.string.start_dump_message),
            Toast.LENGTH_SHORT
        ).show()

        val appName = context.getString(R.string.app_name)
        val fileName = payload.fileName.removeSuffix(".zip")
        val dumpDir =
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val customPath = PreferencesUtil().perfGet("customFolder")?.let {
            "$it/$fileName"
        }
        val outputDir = customPath ?: (dumpDir.path + "/${appName}/${fileName}")

        val partition = payload.deltaArchiveManifest.partitionsList
            .firstOrNull { it.partitionName == partitionName }
            ?: run {
                Toast.makeText(context, "Partition not found", Toast.LENGTH_SHORT).show()
                onFailure(false)
                return
            }

        val result = runCatching {
            PayloadUtil.extractPartition(
                partition,
                if (isPath) RandomAccessFileUtil.raf else HttpUtil,
                outputDir,
                payload,
                onProgressUpdate
            )
        }

        result.onFailure { error ->
            if (error is CancellationException) {
                // A cancelled dump is not a failure; surface it separately and rethrow so the
                // surrounding coroutine (viewModelScope job) finishes as cancelled.
                if (!isPath) HttpUtil.cancel()
                onCancelled()
                throw error
            }
            Toast.makeText(context, error.message, Toast.LENGTH_SHORT).show()
            onFailure(false)
            return
        }

        // Verify the extracted image against the manifest's expected SHA-256.
        val outputFile = File(outputDir, "$partitionName.img")
        val expected =
            if (partition.hasNewPartitionInfo()) partition.newPartitionInfo.hash.toHexString() else ""
        if (expected.isBlank()) {
            onVerification(true)
            Toast.makeText(
                context,
                context.getString(R.string.dump_success_message, outputFile.path),
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        val verified = withContext(Dispatchers.IO) {
            val actual = HashUtil.sha256Hex(outputFile)
            HashUtil.matches(expected, actual)
        }

        if (verified) {
            onVerification(true)
            Toast.makeText(
                context,
                context.getString(R.string.dump_success_message, outputFile.path),
                Toast.LENGTH_SHORT
            ).show()
        } else {
            // A mismatching image is not a valid dump: remove it rather than mislead the user.
            outputFile.delete()
            onVerification(false)
            Toast.makeText(
                context,
                context.getString(R.string.verify_failed_message),
                Toast.LENGTH_LONG
            ).show()
            onFailure(false)
        }
    }
}
