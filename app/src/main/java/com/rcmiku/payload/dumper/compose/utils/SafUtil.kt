package com.rcmiku.payload.dumper.compose.utils

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.FileAlreadyExistsException

/** Small helpers around the Storage Access Framework (documents and trees). */
object SafUtil {

    private const val MIME_OCTET = "application/octet-stream"

    /**
     * Tree URI of the public Download directory (`/storage/emulated/0/Download`), used as the
     * picker's initial location so first use is guided to authorise the default output folder.
     */
    fun downloadsTreeUri(): Uri =
        DocumentsContract.buildTreeDocumentUri(
            "com.android.externalstorage.documents",
            "primary:Download"
        )

    /** Whether [uri] still holds a persisted write grant for this app. */
    fun hasWritePermission(context: Context, uri: Uri): Boolean =
        context.contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isWritePermission
        }

    /** Whether [uri] still holds a persisted read grant for this app. */
    fun hasReadPermission(context: Context, uri: Uri): Boolean =
        context.contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission
        }

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

    /** Best-effort size of a document [uri], or -1 when the provider does not report one. */
    fun queryDisplaySize(context: Context, uri: Uri): Long {
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (index >= 0 && cursor.moveToFirst() && !cursor.isNull(index)) {
                    return cursor.getLong(index)
                }
            }
        }
        return -1L
    }

    /**
     * Publishes [source] into the tree [treeUri] as [displayName], atomically and without ever
     * destroying an existing image.
     *
     * A file that already carries [displayName] is treated as a conflict: this never deletes or
     * overwrites it, so publishing a new dump can never destroy the user's previous extraction. The
     * caller surfaces [FileAlreadyExistsException] so the user can rename or remove the old file.
     *
     * The bytes are otherwise written to a sibling temporary document first; only once that write
     * has fully succeeded is the temporary document renamed onto [displayName]. If anything fails —
     * a cancelled write, a full volume, a provider error — the temporary document is deleted and
     * the destination is left untouched. This is what makes "publish only after validation" safe.
     */
    suspend fun publishIntoTree(
        context: Context,
        treeUri: Uri,
        displayName: String,
        source: File,
    ): Uri = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val parentDocId = DocumentsContract.getTreeDocumentId(treeUri)
        val parentDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentDocId)

        // Same-name conflict: keep the existing image and fail loudly. Deleting it here would let a
        // cancelled or unwanted dump destroy a previously extracted image.
        findChild(resolver, treeUri, parentDocId, displayName)?.let {
            throw FileAlreadyExistsException(displayName)
        }

        val tempName = ".$displayName.part"
        // A stale temp from an earlier crash must not block the rename.
        findChild(resolver, treeUri, parentDocId, tempName)?.let {
            runCatching { DocumentsContract.deleteDocument(resolver, it) }
        }

        val tempUri = DocumentsContract.createDocument(resolver, parentDocUri, MIME_OCTET, tempName)
            ?: throw IOException("Could not create a temporary document for $displayName")

        try {
            resolver.openOutputStream(tempUri, "wt")!!.use { out ->
                source.inputStream().buffered().use { input ->
                    copyCancellable(input, out)
                }
            }
        } catch (t: Throwable) {
            // Never leave a half-written document behind, and keep any existing image intact.
            runCatching { DocumentsContract.deleteDocument(resolver, tempUri) }
            throw t
        }

        // The new bytes are safely on disk; only now may the destination be created.
        try {
            DocumentsContract.renameDocument(resolver, tempUri, displayName)
                ?: throw IOException("Could not finalise $displayName")
        } catch (t: Throwable) {
            runCatching { DocumentsContract.deleteDocument(resolver, tempUri) }
            throw t
        }
    }

    /**
     * Copies [input] to [out] in fixed-size chunks, checking for cancellation between chunks so a
     * cancelled dump stops promptly and its temporary document is cleaned up by the caller.
     */
    private suspend fun copyCancellable(input: java.io.InputStream, out: java.io.OutputStream) {
        val buffer = ByteArray(64 * 1024)
        while (true) {
            currentCoroutineContext().ensureActive()
            val read = input.read(buffer)
            if (read < 0) break
            out.write(buffer, 0, read)
        }
    }

    /** Finds a direct child document of [parentDocId] with the given [displayName]. */
    private fun findChild(
        resolver: ContentResolver,
        treeUri: Uri,
        parentDocId: String,
        displayName: String,
    ): Uri? {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        resolver.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            ),
            null,
            null,
            null,
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            while (cursor.moveToNext()) {
                if (cursor.getString(nameColumn) == displayName) {
                    return DocumentsContract.buildDocumentUriUsingTree(treeUri, cursor.getString(idColumn))
                }
            }
        }
        return null
    }

    /** Copies [source] to [dest]. */
    suspend fun writeToFile(source: File, dest: File) = withContext(Dispatchers.IO) {
        dest.parentFile?.mkdirs()
        source.inputStream().buffered().use { input ->
            dest.outputStream().buffered().use { input.copyTo(it) }
        }
    }
}
