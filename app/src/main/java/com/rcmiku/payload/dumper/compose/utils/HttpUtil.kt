package com.rcmiku.payload.dumper.compose.utils

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * A remote response that must never be retried: the server returned malformed, missing or
 * internally inconsistent range metadata, or an empty/short body. Retrying cannot fix these and
 * could mask a corrupt source, so they fail fast.
 */
class HttpDataException(message: String) : IOException(message)

/** A transient, retryable failure: a network error or an overload/timeout HTTP status. */
private class RetryableHttpException(message: String, cause: Throwable? = null) :
    IOException(message, cause)

private data class ContentRange(val start: Long, val end: Long, val total: Long)

private val CONTENT_RANGE_REGEX = Regex("""bytes\s+(\d+)-(\d+)/(\d+)""")

private fun parseContentRange(header: String): ContentRange? {
    val m = CONTENT_RANGE_REGEX.matchEntire(header.trim()) ?: return null
    val start = m.groupValues[1].toLongOrNull() ?: return null
    val end = m.groupValues[2].toLongOrNull() ?: return null
    val total = m.groupValues[3].toLongOrNull() ?: return null
    return ContentRange(start, end, total)
}

/**
 * Shared HTTP Range reader for a remote `payload.bin`. Implements [SeekableInput] so the parser
 * and extractor can treat it exactly like a local file or SAF document.
 *
 * The HTTP semantics are strict: a ranged read is only accepted when the server answers 206 with a
 * [Content-Range] whose start, end and total length match exactly what was requested and previously
 * established. A 200 to a ranged request (server ignoring Range) is rejected up front, so a body
 * can never be misread as a partial range. Only transient network errors and overload/timeout
 * status codes (408/429/5xx) are retried, with a bounded attempt count and capped backoff.
 */
object HttpUtil : SeekableInput {

    override val isLocal: Boolean = false

    /** Bounded retry: at most [MAX_ATTEMPTS] tries, capped exponential backoff. */
    private const val MAX_ATTEMPTS = 3
    private const val BACKOFF_BASE_MS = 500L
    private const val BACKOFF_MAX_MS = 4_000L

    /** Request timeout / rate limit / server overload: transient and worth retrying. */
    private val retryableStatusCodes = setOf(408, 429, 500, 502, 503, 504)

    private lateinit var url: String
    private lateinit var fileName: String

    /** Display name of the remote resource. */
    val name: String
        get() = fileName

    private var fileLength: Long = 0

    @Volatile
    private var position: Long = 0

    @Volatile
    private var cancelled: Boolean = false

    /** The in-flight call, so [cancel] can abort a blocking read instead of only flagging it. */
    @Volatile
    private var activeCall: Call? = null

    /**
     * Incremented on every [init]. A read captures this value and aborts if it changes, so a read
     * still in flight when a new transfer starts can never corrupt the new transfer's state.
     */
    @Volatile
    private var epoch: Long = 0

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(Interceptor { chain ->
            val originalRequest = chain.request()
            val newRequest = originalRequest.newBuilder()
                .header(
                    "User-Agent",
                    if (PreferencesUtil().perfGetBoolean("isCustomUserAgentEnabled") == true)
                        PreferencesUtil().perfGet("customUserAgent")
                            ?: UserAgentUtil.DEFAULT_USER_AGENT else UserAgentUtil.DEFAULT_USER_AGENT
                )
                .build()
            chain.proceed(newRequest)
        })
        .build()

    @Throws(IOException::class)
    suspend fun init(link: String) = withContext(Dispatchers.IO) {
        // Start a fresh session: any read still running against a previous URL is now superseded.
        // Cancelling the old call unblocks it so it can observe the epoch change and abort.
        epoch += 1
        activeCall?.cancel()
        url = link
        cancelled = false
        position = 0
        fileLength = 0

        val request = Request.Builder()
            .url(link)
            .addHeader("Range", "bytes=0-0")
            .build()

        var attempt = 0
        while (true) {
            attempt += 1
            try {
                probeLength(request)
                return@withContext
            } catch (e: CancellationException) {
                throw e
            } catch (e: RetryableHttpException) {
                if (attempt >= MAX_ATTEMPTS) {
                    throw IOException("Failed to reach remote payload after $attempt attempts", e)
                }
                delay(backoffMillis(attempt))
            }
            // HttpDataException and other IOExceptions propagate: they are not transient.
        }
    }

    /** Issues the single-byte Range probe and records the resource length from Content-Range. */
    private fun probeLength(request: Request) {
        val call = client.newCall(request)
        activeCall = call
        try {
            call.execute().use { response ->
                when {
                    response.code == 206 -> {
                        val header = response.header("Content-Range")
                            ?: throw HttpDataException("206 response is missing a Content-Range header")
                        val range = parseContentRange(header)
                            ?: throw HttpDataException("Malformed Content-Range: $header")
                        if (range.start != 0L) {
                            throw HttpDataException("Probe Content-Range starts at ${range.start}, expected 0")
                        }
                        if (range.total <= 0L) {
                            throw HttpDataException("Remote resource has an invalid length ${range.total}")
                        }
                        fileLength = range.total
                        fileName = getFileNameFromHeaders(response.headers)
                    }

                    response.code == 200 -> {
                        // The server ignored our Range header and sent the whole body. Random
                        // access is impossible without it; reject rather than misread bytes.
                        throw HttpDataException(
                            "Server does not support HTTP Range; remote extraction is unavailable"
                        )
                    }

                    response.code in retryableStatusCodes -> {
                        throw RetryableHttpException("HTTP ${response.code} probing payload length")
                    }

                    else -> throw HttpDataException(
                        "Unexpected HTTP ${response.code} probing payload length"
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpDataException) {
            throw e
        } catch (e: RetryableHttpException) {
            throw e
        } catch (e: IOException) {
            if (cancelled) throw CancellationException("HTTP transfer cancelled", e)
            throw RetryableHttpException("Network error probing payload length", e)
        } finally {
            activeCall = null
        }
    }

    override fun length(): Long = fileLength

    override fun position(): Long = position

    /**
     * Fills [buffer] completely from [position], advancing the position by the bytes read. Each
     * request is validated (206 + matching Content-Range) before a single byte is trusted, and a
     * short/long/empty body fails fast. Transient failures are retried with bounded backoff.
     */
    override suspend fun readFully(buffer: ByteArray) {
        readRange(buffer)
    }

    suspend fun readRange(byteArray: ByteArray, onBytes: (Int) -> Unit = {}): Int =
        withContext(Dispatchers.IO) {
            val myEpoch = epoch
            var totalBytesRead = 0
            var currentPosition = position

            while (totalBytesRead < byteArray.size) {
                currentCoroutineContext().ensureActive()
                if (cancelled) throw CancellationException("HTTP transfer cancelled")
                if (epoch != myEpoch) throw CancellationException("HTTP transfer superseded by a new session")

                val end = currentPosition + (byteArray.size - totalBytesRead) - 1

                var attempt = 0
                while (true) {
                    attempt += 1
                    currentCoroutineContext().ensureActive()
                    if (cancelled) throw CancellationException("HTTP transfer cancelled")
                    if (epoch != myEpoch) throw CancellationException("HTTP transfer superseded by a new session")
                    try {
                        val read = readOneRange(currentPosition, end, byteArray, totalBytesRead, onBytes)
                        totalBytesRead += read
                        currentPosition += read
                        break
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: RetryableHttpException) {
                        if (attempt >= MAX_ATTEMPTS) {
                            throw IOException("Remote read of $currentPosition-$end failed after $attempt attempts", e)
                        }
                        delay(backoffMillis(attempt))
                    }
                    // HttpDataException and other IOExceptions propagate: not transient.
                }
            }
            position = currentPosition
            totalBytesRead
        }

    /**
     * Fetches the byte range `[pos, end]` and copies it into [out] starting at [outOffset].
     * Validates the response before trusting any byte, then reads exactly `end - pos + 1` bytes;
     * a short or long body is rejected so no partial/foreign data is ever concatenated into the
     * result. Returns the number of bytes copied.
     */
    private suspend fun readOneRange(
        pos: Long,
        end: Long,
        out: ByteArray,
        outOffset: Int,
        onBytes: (Int) -> Unit,
    ): Int {
        val request = Request.Builder()
            .url(url)
            .addHeader("Range", "bytes=$pos-$end")
            .build()
        val call = client.newCall(request)
        activeCall = call
        return try {
            call.execute().use { response ->
                when {
                    response.code == 416 -> throw HttpDataException("Range $pos-$end is not satisfiable (HTTP 416)")

                    response.code != 206 -> {
                        if (response.code in retryableStatusCodes) {
                            throw RetryableHttpException("HTTP ${response.code} fetching range $pos-$end")
                        }
                        throw HttpDataException("Unexpected HTTP ${response.code} fetching range $pos-$end")
                    }
                }

                val header = response.header("Content-Range")
                    ?: throw HttpDataException("206 response is missing a Content-Range header")
                val range = parseContentRange(header)
                    ?: throw HttpDataException("Malformed Content-Range: $header")
                if (range.start != pos) {
                    throw HttpDataException("Content-Range starts at ${range.start}, expected $pos")
                }
                if (range.end != end) {
                    throw HttpDataException("Content-Range ends at ${range.end}, expected $end")
                }
                if (range.total != fileLength) {
                    throw HttpDataException(
                        "Resource length changed mid-transfer: ${range.total} vs $fileLength"
                    )
                }

                val body = response.body ?: throw HttpDataException("206 response has no body")
                val expected = end - pos + 1
                body.byteStream().use { input ->
                    var read = 0
                    while (read < expected) {
                        currentCoroutineContext().ensureActive()
                        if (cancelled) throw CancellationException("HTTP transfer cancelled")
                        val n = input.read(out, outOffset + read, (expected - read).toInt())
                        if (n < 0) break
                        read += n
                        onBytes(n)
                    }
                    if (read < expected) {
                        throw HttpDataException("Short response: got $read of $expected bytes for $pos-$end")
                    }
                    if (input.read() != -1) {
                        throw HttpDataException("Long response: body exceeds declared range $pos-$end")
                    }
                    read
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpDataException) {
            throw e
        } catch (e: RetryableHttpException) {
            throw e
        } catch (e: IOException) {
            // Call.cancel() surfaces as an IOException ("Canceled"); it is not a network error.
            if (cancelled) throw CancellationException("HTTP transfer cancelled", e)
            throw RetryableHttpException("Network error reading range $pos-$end", e)
        } finally {
            activeCall = null
        }
    }

    /** Aborts the in-flight transfer, unblocking any pending read. */
    fun cancel() {
        cancelled = true
        activeCall?.cancel()
    }

    /** Convenience wrapper kept for callers that do not need progress callbacks. */
    suspend fun read(byteArray: ByteArray): Int = readRange(byteArray)

    override fun seek(bytePosition: Long) {
        // Seeking exactly to the end is valid (a zero-length read there is a no-op); the parser
        // seeks to `length - 4096`, but callers may also legitimately seek to `length`.
        if (bytePosition in 0..fileLength) {
            position = bytePosition
        } else {
            throw IllegalArgumentException("Invalid seek position $bytePosition (length $fileLength)")
        }
    }

    private fun backoffMillis(attempt: Int): Long =
        minOf(BACKOFF_BASE_MS shl (attempt - 1), BACKOFF_MAX_MS)

    private fun getFileNameFromHeaders(headers: Headers): String {
        val contentDisposition = headers["Content-Disposition"]
        if (!contentDisposition.isNullOrEmpty()) {
            val dispositionParts = contentDisposition.split(";")
            for (part in dispositionParts) {
                if (part.trim().startsWith("filename=")) {
                    return part.trim().substringAfter("=").replace("\"", "")
                }
            }
        }
        return url.toHttpUrlOrNull()?.pathSegments?.lastOrNull().orEmpty().ifEmpty { "payload.bin" }
    }
}
