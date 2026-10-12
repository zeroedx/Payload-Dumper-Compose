package com.rcmiku.payload.dumper.compose.utils

import android.content.Context
import android.net.Uri
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile

/**
 * A random-access byte source that the payload parser and extractor can read from. This abstracts
 * the three backing stores the app supports: a plain file, a SAF `content://` URI and an HTTP(S)
 * resource ([HttpUtil]).
 */
interface SeekableInput {

    /** Total number of bytes available. */
    fun length(): Long

    /** Absolute offsets before this call are interpreted relative to the start of the source. */
    fun seek(position: Long)

    /** Current absolute offset. */
    fun position(): Long

    /** Reads exactly [buffer].size bytes or throws. */
    suspend fun readFully(buffer: ByteArray)

    /** Whether this source is a local file (used to decide the persisted `isPath` flag). */
    val isLocal: Boolean

    /** Releases any resources held by this source. Safe to call more than once. */
    fun close() {}
}

/** [SeekableInput] backed by a [RandomAccessFile]. */
class FileSeekableInput(private val raf: RandomAccessFile) : SeekableInput {

    override fun length(): Long = raf.length()

    override fun seek(position: Long) = raf.seek(position)

    override fun position(): Long = raf.filePointer

    override suspend fun readFully(buffer: ByteArray) = raf.readFully(buffer)

    override val isLocal: Boolean = true

    override fun close() {
        runCatching { raf.close() }
    }
}

/** [SeekableInput] backed by a SAF `content://` URI opened read-only. */
class UriSeekableInput(context: Context, private val uri: Uri) : SeekableInput {

    private val appContext = context.applicationContext
    private val pfd = appContext.contentResolver.openFileDescriptor(uri, "r")
        ?: throw IOException("Unable to open $uri")
    private val fis = java.io.FileInputStream(pfd.fileDescriptor)
    private val stream = NonClosingInputStream(fis)
    private val totalLength: Long = pfd.statSize.let { if (it >= 0) it else -1L }
        .takeIf { it >= 0 }
        ?: appContext.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
        ?: throw IOException("Cannot determine size of $uri")

    override fun length(): Long = totalLength

    override fun seek(position: Long) {
        fis.channel.position(position)
    }

    override fun position(): Long = fis.channel.position()

    override suspend fun readFully(buffer: ByteArray) {
        var read = 0
        while (read < buffer.size) {
            val n = stream.read(buffer, read, buffer.size - read)
            if (n < 0) throw IOException("Unexpected end of $uri")
            read += n
        }
    }

    override val isLocal: Boolean = true

    override fun close() {
        runCatching { fis.close() }
        runCatching { pfd.close() }
    }
}

/** Wraps [delegate] so callers that `use {}` it do not close the shared underlying stream. */
class NonClosingInputStream(private val delegate: InputStream) : InputStream() {
    override fun read(): Int = delegate.read()
    override fun read(b: ByteArray, off: Int, len: Int): Int = delegate.read(b, off, len)
    override fun available(): Int = delegate.available()
    override fun close() {
        // Intentionally do not close the shared stream.
    }
}
