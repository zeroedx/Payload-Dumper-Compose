package com.rcmiku.payload.dumper.compose.utils

import java.io.File
import java.security.MessageDigest

/** SHA-256 helpers. Kept dependency-free and pure-JVM so they are unit-testable. */
object HashUtil {

    private const val BUFFER_SIZE = 1 shl 16

    /** Streams [file] and returns its SHA-256 as a lowercase hex string. */
    fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(BUFFER_SIZE).use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    /** SHA-256 of a byte array, lowercase hex. */
    fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    /** Case-insensitive comparison of two hex digests. */
    fun matches(expectedHex: String, actualHex: String): Boolean =
        expectedHex.trim().equals(actualHex.trim(), ignoreCase = true)
}
