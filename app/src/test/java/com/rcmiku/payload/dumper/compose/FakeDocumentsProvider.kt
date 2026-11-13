package com.rcmiku.payload.dumper.compose

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import org.robolectric.Shadows.shadowOf
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.OutputStream
import java.nio.file.FileAlreadyExistsException

/**
 * A minimal in-memory `DocumentsProvider`-shaped [ContentProvider] used to exercise the real
 * [DocumentsContract] calls in [com.rcmiku.payload.dumper.compose.utils.SafUtil] under Robolectric.
 * It models just enough of the SAF document contract — child listing, create, delete and rename —
 * to reproduce a real provider's behaviour on the publish path.
 *
 * The document-operation methods are delivered through the legacy [call] entry point (which is what
 * `DocumentsContract` uses), so this provider implements them there.
 */
class FakeDocumentsProvider : ContentProvider() {

    data class Doc(
        val docId: String,
        val name: String,
        val parentDocId: String,
        val stream: ByteArrayOutputStream,
    )

    /** Documents by document id, insertion-ordered. */
    val docs = LinkedHashMap<String, Doc>()

    /** When true, a rename call fails, as a provider that cannot rename would. */
    var failRename = false

    /** Overrides the output stream used for a created document; lets tests inject a failing one. */
    var outputStreamFactory: ((Uri) -> OutputStream)? = null

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String = "application/octet-stream"

    /** Adds an existing document with [bytes], as a previously extracted image would be. */
    fun seed(parentDocId: String, name: String, bytes: ByteArray): Uri {
        val docId = "$parentDocId/$name"
        val stream = ByteArrayOutputStream().apply { write(bytes) }
        docs[docId] = Doc(docId, name, parentDocId, stream)
        return documentUri(docId)
    }

    /** Current bytes of the document at [uri], or null when it does not exist. */
    fun contentOf(uri: Uri): ByteArray? =
        docs[DocumentsContract.getDocumentId(uri)]?.stream?.toByteArray()

    /** The URI of the first document named [name], or null. */
    fun documentNamed(name: String): Uri? =
        docs.values.firstOrNull { it.name == name }?.let { documentUri(it.docId) }

    /** The document URI a document with [docId] would have. */
    fun documentUri(docId: String): Uri =
        DocumentsContract.buildDocumentUriUsingTree(treeUri(), docId)

    override fun insert(uri: Uri, values: ContentValues?): Uri = throw UnsupportedOperationException()

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        val docId = DocumentsContract.getDocumentId(uri)
        return if (docs.remove(docId) != null) 1 else 0
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val segments = uri.pathSegments
        // Children listing: .../document/<parentId>/children
        if (segments.lastOrNull() == "children") {
            val parentDocId = Uri.decode(segments[segments.size - 2])
            val cursor = MatrixCursor(
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                )
            )
            docs.values.filter { it.parentDocId == parentDocId }
                .forEach { cursor.addRow(arrayOf(it.docId, it.name)) }
            return cursor
        }
        throw FileNotFoundException("Unsupported query $uri")
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val uri = extras?.getParcelable(KEY_URI, Uri::class.java)
            ?: throw FileNotFoundException("No uri for $method")
        return when (method) {
            METHOD_CREATE -> handleCreate(uri, extras)
            METHOD_DELETE -> {
                delete(uri, null, null)
                Bundle()
            }
            METHOD_RENAME -> handleRename(uri, extras.getString(KEY_DISPLAY_NAME))
            else -> throw FileNotFoundException("Unsupported call $method")
        }
    }

    private fun handleCreate(parentUri: Uri, extras: Bundle): Bundle {
        val parentDocId = DocumentsContract.getDocumentId(parentUri)
        val name = extras.getString(KEY_DISPLAY_NAME)
            ?: throw IOException("create without a display name")
        if (docs.values.any { it.parentDocId == parentDocId && it.name == name }) {
            throw FileAlreadyExistsException(name)
        }
        val docId = "$parentDocId/$name"
        val uri = documentUri(docId)
        val backing = ByteArrayOutputStream()
        val stream = outputStreamFactory?.invoke(uri) ?: backing
        docs[docId] = Doc(docId, name, parentDocId, backing)
        // The shadow resolves openOutputStream through this registration, mirroring a provider
        // whose openDocument returned a real stream.
        shadowOf(context!!.contentResolver).registerOutputStream(uri, stream)
        return Bundle().apply { putParcelable(KEY_URI, uri) }
    }

    private fun handleRename(uri: Uri, newName: String?): Bundle {
        if (failRename) throw IOException("provider refuses to rename")
        val docId = DocumentsContract.getDocumentId(uri)
        val doc = docs[docId] ?: throw FileNotFoundException("No document $docId")
        val name = newName ?: throw IOException("rename without a name")
        if (docs.values.any { it.parentDocId == doc.parentDocId && it.name == name && it.docId != docId }) {
            throw FileAlreadyExistsException(name)
        }
        docs.remove(docId)
        val newDocId = "${doc.parentDocId}/$name"
        docs[newDocId] = doc.copy(docId = newDocId, name = name)
        return Bundle().apply { putParcelable(KEY_URI, documentUri(newDocId)) }
    }

    private fun treeUri(): Uri = Uri.parse("content://$AUTHORITY/tree/primary%3ADownload")

    private companion object {
        const val AUTHORITY = "test.documents"

        // DocumentsContract method names and Bundle keys are not part of the public SDK surface,
        // so they are spelled out here exactly as DocumentsContract sends them.
        const val METHOD_CREATE = "android:createDocument"
        const val METHOD_DELETE = "android:deleteDocument"
        const val METHOD_RENAME = "android:renameDocument"
        const val KEY_URI = "uri"
        const val KEY_DISPLAY_NAME = "_display_name"
    }
}
