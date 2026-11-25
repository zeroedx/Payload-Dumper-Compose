package com.rcmiku.payload.dumper.compose.utils

import java.io.InputStream
import java.security.MessageDigest

/**
 * A bounded, forward-only [InputStream] over the byte range `[offset, offset + length)` of a
 * [SeekableInput].
 *
 * Bytes are pulled from the source in [chunkSize] steps through the positioned
 * [SeekableInput.readAt], so a decompressor can consume an arbitrarily large operation blob with
 * constant memory: the whole blob is never buffered. When [hash] is enabled, every byte handed out is fed into a SHA-256
 * digest, so [drainAndDigest] can verify the operation's `data_sha256_hash` in the same pass that
 * decompresses the data (any bytes the consumer left unread are drained first, so the digest always
 * covers the full range).
 */
class RangeInputStream(
    private val source: SeekableInput,
    private val offset: Long,
    private val length: Long,
    private val chunkSize: Int = DEFAULT_CHUNK_SIZE,
    hash: Boolean = true,
) : InputStream() {

    companion object {
        const val DEFAULT_CHUNK_SIZE = 64 * 1024
    }

    private val digest = if (hash) MessageDigest.getInstance("SHA-256") else null
    private val single = ByteArray(1)
    private var consumed = 0L

    override fun read(): Int {
        val n = read(single, 0, 1)
        return if (n <= 0) -1 else single[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (consumed >= length) return -1
        val toRead = minOf(len.toLong(), length - consumed, chunkSize.toLong()).toInt()
        source.readAt(offset + consumed, b, off, toRead)
        digest?.update(b, off, toRead)
        consumed += toRead
        return toRead
    }

    /** Reads any bytes the consumer left unread (hashing them) and returns the SHA-256 digest. */
    fun drainAndDigest(buffer: ByteArray = ByteArray(chunkSize)): ByteArray {
        require(buffer.isNotEmpty())
        val hasher = checkNotNull(digest) { "SHA-256 is disabled for this range" }
        while (consumed < length) {
            read(buffer)
        }
        return hasher.digest()
    }

    override fun close() {
        // The source is shared with other readers; closing it here would break them.
    }
}
