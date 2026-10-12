package com.rcmiku.payload.dumper.compose.utils

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** Small helpers around the Storage Access Framework (documents and trees). */
object SafUtil {

    private const val MIME_OCTET = "application/octet-stream"

    /** Best-effort display name for a document [uri]. */
    fun queryDisplayName(context: Context, uri: Uri): String? {
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) {
                    return cursor.getString(index)
                }
            }
        }
        return uri.lastPathSegment
    }

    /**
     * Writes [source] into the tree identified by [treeUri] under [displayName] using
     * `DocumentsContract.createDocument`. Returns the created document Uri.
     */
    suspend fun writeIntoTree(
        context: Context,
        treeUri: Uri,
        displayName: String,
        source: File,
    ): Uri = withContext(Dispatchers.IO) {
        val parentDocId = DocumentsContract.getTreeDocumentId(treeUri)
        val parentDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentDocId)

        val created = DocumentsContract.createDocument(
            context.contentResolver,
            parentDocUri,
            MIME_OCTET,
            displayName,
        ) ?: throw IOException("Could not create $displayName under $treeUri")

        try {
            context.contentResolver.openOutputStream(created, "wt")!!.use { out ->
                source.inputStream().buffered().use { it.copyTo(out) }
            }
        } catch (t: Throwable) {
            runCatching { DocumentsContract.deleteDocument(context.contentResolver, created) }
            throw t
        }
        created
    }

    /** Copies [source] to [dest]. */
    suspend fun writeToFile(source: File, dest: File) = withContext(Dispatchers.IO) {
        dest.parentFile?.mkdirs()
        source.inputStream().buffered().use { input ->
            dest.outputStream().buffered().use { input.copyTo(it) }
        }
    }
}
