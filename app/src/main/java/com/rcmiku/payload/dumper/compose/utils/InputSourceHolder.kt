package com.rcmiku.payload.dumper.compose.utils

/**
 * Holds the [SeekableInput] backing the currently parsed payload so the ViewModel's dump jobs can
 * read from it. Local sources (file or SAF URI) keep a seekable handle for the session; HTTP
 * sources use the shared [HttpUtil] singleton.
 */
object InputSourceHolder {

    @Volatile
    var current: SeekableInput? = null
        private set

    /** Replaces the current source, closing the previous one if it is a different instance. */
    @Synchronized
    fun set(source: SeekableInput) {
        val previous = current
        if (previous !== source) previous?.close()
        current = source
    }

    /** The seekable input for a local or remote payload. */
    fun require(): SeekableInput =
        current ?: throw IllegalStateException("No payload source has been parsed")

    @Synchronized
    fun clear() {
        current?.close()
        current = null
    }
}
