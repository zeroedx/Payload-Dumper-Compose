package com.rcmiku.payload.dumper.compose.utils

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import android.provider.MediaStore.MediaColumns.DISPLAY_NAME
import android.provider.MediaStore.MediaColumns.IS_PENDING
import android.provider.MediaStore.MediaColumns.MIME_TYPE
import android.provider.MediaStore.MediaColumns.RELATIVE_PATH
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.FileAlreadyExistsException

/** Publishes verified partition images to the shared Downloads collection. */
internal object DownloadOutput {
    private val pathComponent = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")

    fun relativePath(deviceModels: List<String>, systemVersion: String? = null): String {
        val device = deviceModels.firstOrNull(pathComponent::matches)
        val version = systemVersion?.takeIf(pathComponent::matches)
        val folder = when {
            device == null -> "PayloadDumper"
            version == null || device.length + version.length > 254 -> device
            else -> "$device $version"
        }
        return "${Environment.DIRECTORY_DOWNLOADS}/$folder/"
    }

    suspend fun publish(
        context: Context,
        deviceModels: List<String>,
        systemVersion: String?,
        displayName: String,
        source: File,
    ): String = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val path = relativePath(deviceModels, systemVersion)
        val downloads = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val exists = resolver.query(
            downloads, arrayOf(DISPLAY_NAME), "$RELATIVE_PATH = ? AND $DISPLAY_NAME = ?",
            arrayOf(path, displayName), null,
        )?.use { it.moveToFirst() } == true
        if (exists) throw FileAlreadyExistsException(displayName)

        val values = ContentValues().apply {
            put(DISPLAY_NAME, displayName)
            put(MIME_TYPE, "application/octet-stream")
            put(RELATIVE_PATH, path)
            put(IS_PENDING, 1)
        }
        val uri = resolver.insert(downloads, values)
            ?: throw IOException("Could not create $path$displayName")

        try {
            val output = resolver.openOutputStream(uri, "w")
                ?: throw IOException("Could not open $path$displayName for writing")
            output.use { out ->
                source.inputStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                    }
                }
            }
            currentCoroutineContext().ensureActive()
            val published = ContentValues().apply { put(IS_PENDING, 0) }
            if (resolver.update(uri, published, null, null) != 1) {
                throw IOException("Could not finalise $path$displayName")
            }

            // MediaStore can assign a numbered suffix if a file already occupies the target path.
            val actualName = resolver.query(uri, arrayOf(DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }
                ?: throw IOException("Could not verify the name of $path$displayName")
            if (actualName != displayName) throw FileAlreadyExistsException(displayName)
            path + actualName
        } catch (error: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw error
        }
    }
}
