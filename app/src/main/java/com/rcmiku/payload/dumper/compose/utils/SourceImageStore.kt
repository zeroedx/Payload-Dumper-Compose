package com.rcmiku.payload.dumper.compose.utils

import android.content.Context
import android.net.Uri
import java.io.IOException

/**
 * Holds the old partition images a delta extraction reads from, one per partition, and opens each
 * on demand as a read-only [SeekableInput].
 *
 * Each image is opened lazily and cached for the session; [closeAll] releases every handle. Only
 * `content://` URIs are accepted, because a delta source must be reachable through the persisted
 * SAF read grant across process restarts.
 */
class SourceImageStore(private val context: Context) {

    private val uris = mutableMapOf<String, Uri>()
    private val opened = mutableMapOf<String, SeekableInput>()

    /** Registers (or replaces) the image for [partitionName]. */
    @Synchronized
    fun put(partitionName: String, uri: Uri) {
        if (uri.scheme != "content") {
            throw IllegalArgumentException("Source image must be a content:// URI, was $uri")
        }
        uris[partitionName]?.let { previous ->
            if (previous != uri) opened.remove(partitionName)?.close()
        }
        uris[partitionName] = uri
    }

    @Synchronized
    fun remove(partitionName: String) {
        uris.remove(partitionName)
        opened.remove(partitionName)?.close()
    }

    @Synchronized
    fun has(partitionName: String): Boolean = uris.containsKey(partitionName)

    /** Alias of [has] used at call sites that read more naturally as a containment check. */
    @Synchronized
    fun contains(partitionName: String): Boolean = uris.containsKey(partitionName)

    @Synchronized
    fun size(): Int = uris.size

    /** The registered partitions (for display). */
    @Synchronized
    fun partitions(): Set<String> = uris.keys.toSet()

    /** Opens (and caches) the read-only source for [partitionName]. */
    @Synchronized
    fun open(partitionName: String): SeekableInput {
        opened[partitionName]?.let { return it }
        val uri = uris[partitionName]
            ?: throw MissingSourceImageException(partitionName)
        val input = UriSeekableInput(context, uri)
        opened[partitionName] = input
        return input
    }

    /** Length of the registered image for [partitionName] in bytes. */
    @Synchronized
    fun lengthOf(partitionName: String): Long {
        val uri = uris[partitionName]
            ?: throw MissingSourceImageException(partitionName)
        val size = SafUtil.queryDisplaySize(context, uri)
        if (size < 0) {
            throw IOException("Cannot determine the size of the source image for $partitionName")
        }
        return size
    }

    @Synchronized
    fun closeAll() {
        opened.values.forEach { it.close() }
        opened.clear()
    }

    @Synchronized
    fun clear() {
        uris.clear()
        opened.clear()
    }
}
