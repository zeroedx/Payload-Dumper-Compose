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

/** Raised when a strong ETag proves the remote resource changed since it was parsed. */
class ResourceChangedException(message: String) : IOException(message)

/** HTTP request/byte counters for a session, used to report range-merging effectiveness. */
data class HttpStats(val requests: Long, val bytes: Long)

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

    /**
     * In-flight calls, so [cancel] can abort a blocking read instead of only flagging it. Several
     * reads run at once (parallel partition extraction and the range prefetcher), so every call is
     * tracked in this set rather than a single field.
     */
    private val activeCalls: MutableSet<Call> = java.util.Collections.synchronizedSet(mutableSetOf())

    /**
     * Incremented on every [init]. A read captures this value and aborts if it changes, so a read
     * still in flight when a new transfer starts can never corrupt the new transfer's state.
     */
    @Volatile
    private var epoch: Long = 0

    /**
     * Strong ETag of the remote resource, captured during [init] when the server provides one. When
     * present it binds the parsed manifest to the exact resource version: every range request is
     * sent with `If-Match`, so a resource that changed between parsing and download is rejected
     * instead of silently mixing two versions.
     */
    @Volatile
    private var etag: String? = null

    /** Strong ETag bound to this session, or null when the server provided none. */
    val strongEtag: String? get() = etag

    // --- Download statistics (for reporting the effect of range merging / prefetching) ---------

    private val requestCount = java.util.concurrent.atomic.AtomicLong(0)
    private val bytesDownloaded = java.util.concurrent.atomic.AtomicLong(0)

    /** Number of HTTP requests issued and bytes received so far this session. */
    fun stats(): HttpStats = HttpStats(requestCount.get(), bytesDownloaded.get())

    // --- Bounded read-ahead cache -------------------------------------------------------------
    // Range planning merges the selected partitions' operation blobs into a few large ranges.
    // Prefetching those ranges into this cache turns many small range requests into far fewer
    // larger ones. The cache is bounded by total bytes (LRU eviction), so memory stays flat
    // regardless of payload size; a read that is not cached falls back to a direct range read.

    private val maxCacheBytes = 8L * 1024 * 1024
    private val cacheLock = Any()

    private class Segment(val start: Long, val bytes: ByteArray) {
        val endInclusive: Long get() = start + bytes.size - 1
    }

    /**
     * Prefetched/fetched ranges keyed by start offset. Access-ordered, so eviction always drops the
     * least recently used segment first. Every stored segment is capped at [maxCacheBytes] by
     * [prefetch] and [readAt], so no single entry can exceed the bound.
     */
    private val cache = LinkedHashMap<Long, Segment>(16, 0.75f, true)
    private var cachedBytes = 0L
    private var peakCachedBytes = 0L

    /** Bytes currently held in the read-ahead cache. */
    val cacheSizeBytes: Long get() = synchronized(cacheLock) { cachedBytes }

    /** The cache bound in bytes. */
    val cacheLimitBytes: Long get() = maxCacheBytes

    /** Highest cache occupancy observed this session (never above the largest single segment). */
    val peakCacheSizeBytes: Long get() = synchronized(cacheLock) { peakCachedBytes }

    /** Stores [bytes] for [start], evicting least-recently-used segments to stay within the bound. */
    private fun putSegment(start: Long, bytes: ByteArray) {
        synchronized(cacheLock) {
            val previous = cache.put(start, Segment(start, bytes))
            cachedBytes += bytes.size - (previous?.bytes?.size ?: 0)
            val iterator = cache.entries.iterator()
            while (cachedBytes > maxCacheBytes && iterator.hasNext()) {
                val eldest = iterator.next()
                // The just-inserted segment is the most recently used, so it is evicted last; a
                // segment that exactly fills the bound therefore survives instead of thrashing.
                cachedBytes -= eldest.value.bytes.size
                iterator.remove()
            }
            if (cachedBytes > peakCachedBytes) peakCachedBytes = cachedBytes
        }
    }

    /**
     * Downloads [ranges] (already merged by [RangePlanner]) into the bounded cache so the following
     * extraction reads them without new requests.
     *
     * Two bounds keep prefetching from causing re-downloads: a single range is fetched as
     * consecutive cache-sized chunks (caching one oversized segment would evict it immediately), and
     * the total prefetched bytes are capped at the cache size. Prefetching more than the cache can
     * hold would evict the earliest segments before they are read, so those bytes would be
     * downloaded twice; the uncached remainder is instead fetched on demand (and cached) by
     * [readAt]. Ranges are fetched one at a time (bounded concurrency of one) and a failure is
     * ignored: the cache is an optimisation, and the read path falls back to a direct read.
     */
    fun prefetch(ranges: List<RangePlanner.ByteRange>) {
        val myEpoch = epoch
        var budget = maxCacheBytes
        for (range in ranges) {
            var start = range.start
            while (start <= range.endInclusive && budget > 0L) {
                if (cancelled || epoch != myEpoch) return
                val end = minOf(range.endInclusive, start + maxCacheBytes - 1, start + budget - 1)
                val size = end - start + 1
                if (size <= 0L || size > Int.MAX_VALUE) {
                    start = end + 1
                    continue
                }
                val bytes = ByteArray(size.toInt())
                try {
                    fetchRangeWithRetryBlocking(start, end, bytes, 0, myEpoch)
                } catch (e: CancellationException) {
                    return
                } catch (e: IOException) {
                    start = end + 1
                    continue
                }
                putSegment(start, bytes)
                budget -= bytes.size
                start = end + 1
            }
        }
    }

    /** Clears the prefetch cache (used when the resource version changes). */
    fun clearCache() {
        synchronized(cacheLock) {
            cache.clear()
            cachedBytes = 0L
        }
    }

    private fun cachedSegment(pos: Long): Segment? = synchronized(cacheLock) {
        cache.values.firstOrNull { pos >= it.start && pos <= it.endInclusive }
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(Interceptor { chain ->
            val originalRequest = chain.request()
            val newRequest = originalRequest.newBuilder()
                .header("User-Agent", UserAgentUtil.DEFAULT_USER_AGENT)
                .build()
            chain.proceed(newRequest)
        })
        .build()

    @Throws(IOException::class)
    suspend fun init(link: String) = withContext(Dispatchers.IO) {
        // Start a fresh session: any read still running against a previous URL is now superseded.
        // Cancelling the old call unblocks it so it can observe the epoch change and abort.
        epoch += 1
        cancelActiveCalls()
        url = link
        cancelled = false
        position = 0
        fileLength = 0
        etag = null
        clearCache()
        peakCachedBytes = 0L
        requestCount.set(0)
        bytesDownloaded.set(0)

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
        activeCalls.add(call)
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
                        // Only a strong ETag (not W/"...") binds a version reliably.
                        val rawEtag = response.header("ETag")
                        etag = rawEtag?.takeIf { it.isNotBlank() && !it.trimStart().startsWith("W/") }
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
            activeCalls.remove(call)
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
                val read = fetchRangeWithRetry(
                    pos = currentPosition,
                    end = end,
                    out = byteArray,
                    outOffset = totalBytesRead,
                    myEpoch = myEpoch,
                    onBytes = onBytes,
                )
                totalBytesRead += read
                currentPosition += read
            }
            position = currentPosition
            totalBytesRead
        }

    /**
     * Positioned read backing parallel extraction: fills `dest[destOffset, destOffset + length)`
     * from [offset] without touching [position]. Blocking (OkHttp is synchronous), so it must run
     * off the main thread; parallel callers share the source safely because each request carries
     * its own absolute range and no cursor state is mutated.
     */
    override fun readAt(offset: Long, dest: ByteArray, destOffset: Int, length: Int) {
        val myEpoch = epoch
        var done = 0
        while (done < length) {
            if (cancelled) throw CancellationException("HTTP transfer cancelled")
            if (epoch != myEpoch) throw CancellationException("HTTP transfer superseded by a new session")
            val pos = offset + done
            val segment = cachedSegment(pos)
            if (segment != null) {
                val inSegment = (pos - segment.start).toInt()
                val chunk = minOf(length - done, segment.bytes.size - inSegment)
                System.arraycopy(segment.bytes, inSegment, dest, destOffset + done, chunk)
                done += chunk
            } else {
                // Cache miss: read a bounded chunk directly and cache it, so a blob the prefetcher
                // did not cover (or that fell outside a merged range) is downloaded once, not once
                // per read.
                val end = minOf(
                    pos + (length - done) - 1,
                    pos + maxCacheBytes - 1,
                    fileLength - 1,
                )
                val chunk = (end - pos + 1).toInt()
                val fetched = ByteArray(chunk)
                fetchRangeWithRetryBlocking(pos, end, fetched, 0, myEpoch)
                putSegment(pos, fetched)
                System.arraycopy(fetched, 0, dest, destOffset + done, chunk)
                done += chunk
            }
        }
    }

    /** Fetches one validated range with the bounded retry policy shared by every read path. */
    private suspend fun fetchRangeWithRetry(
        pos: Long,
        end: Long,
        out: ByteArray,
        outOffset: Int,
        myEpoch: Long,
        onBytes: (Int) -> Unit = {},
    ): Int {
        var attempt = 0
        while (true) {
            attempt += 1
            currentCoroutineContext().ensureActive()
            if (cancelled) throw CancellationException("HTTP transfer cancelled")
            if (epoch != myEpoch) throw CancellationException("HTTP transfer superseded by a new session")
            try {
                return readOneRange(pos, end, out, outOffset, onBytes)
            } catch (e: CancellationException) {
                throw e
            } catch (e: RetryableHttpException) {
                if (attempt >= MAX_ATTEMPTS) {
                    throw IOException("Remote read of $pos-$end failed after $attempt attempts", e)
                }
                delay(backoffMillis(attempt))
            }
            // HttpDataException and other IOExceptions propagate: not transient.
        }
    }

    /**
     * Blocking variant of [fetchRangeWithRetry] for [readAt], which cannot suspend. Retries are
     * immediate (no backoff) because the caller runs on a worker thread and cannot be cancelled
     * from the main thread mid-sleep; the bounded attempt count still applies.
     */
    private fun fetchRangeWithRetryBlocking(
        pos: Long,
        end: Long,
        out: ByteArray,
        outOffset: Int,
        myEpoch: Long,
    ): Int {
        var attempt = 0
        while (true) {
            attempt += 1
            if (cancelled) throw CancellationException("HTTP transfer cancelled")
            if (epoch != myEpoch) throw CancellationException("HTTP transfer superseded by a new session")
            try {
                return readOneRangeBlocking(pos, end, out, outOffset)
            } catch (e: CancellationException) {
                throw e
            } catch (e: RetryableHttpException) {
                if (attempt >= MAX_ATTEMPTS) {
                    throw IOException("Remote read of $pos-$end failed after $attempt attempts", e)
                }
            }
        }
    }

    /**
     * Fetches the byte range `[pos, end]` and copies it into [out] starting at [outOffset].
     * Validates the response before trusting any byte, then reads exactly `end - pos + 1` bytes;
     * a short or long body is rejected so no partial/foreign data is ever concatenated into the
     * result. Returns the number of bytes copied.
     */
    private fun readOneRangeBlocking(
        pos: Long,
        end: Long,
        out: ByteArray,
        outOffset: Int,
    ): Int {
        val request = buildRangeRequest(pos, end)
        val call = client.newCall(request)
        activeCalls.add(call)
        return try {
            recordRequest()
            call.execute().use { response ->
                val expected = validateRangeResponse(response, pos, end)
                recordBytes(expected)
                val body = response.body ?: throw HttpDataException("206 response has no body")
                body.byteStream().use { input ->
                    var read = 0
                    while (read < expected) {
                        if (cancelled) throw CancellationException("HTTP transfer cancelled")
                        val n = input.read(out, outOffset + read, (expected - read).toInt())
                        if (n < 0) break
                        read += n
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
            activeCalls.remove(call)
        }
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
        val request = buildRangeRequest(pos, end)
        val call = client.newCall(request)
        activeCalls.add(call)
        return try {
            recordRequest()
            call.execute().use { response ->
                val expected = validateRangeResponse(response, pos, end)
                recordBytes(expected)
                val body = response.body ?: throw HttpDataException("206 response has no body")
                body.byteStream().use { input ->
                    readExactly(input, out, outOffset, expected, pos, end, onBytes)
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
            activeCalls.remove(call)
        }
    }

    /** Aborts the in-flight transfer, unblocking any pending read. */
    fun cancel() {
        cancelled = true
        cancelActiveCalls()
    }

    private fun cancelActiveCalls() {
        synchronized(activeCalls) { activeCalls.toList() }.forEach { it.cancel() }
    }

    /** Convenience wrapper kept for callers that do not need progress callbacks. */
    suspend fun read(byteArray: ByteArray): Int = readRange(byteArray)

    /**
     * Validates a ranged response before a single byte is trusted and returns the expected body
     * length (`end - pos + 1`). Shared by the suspend and blocking read paths.
     */
    private fun validateRangeResponse(
        response: okhttp3.Response,
        pos: Long,
        end: Long,
    ): Long {
        when {
            response.code == 416 -> throw HttpDataException("Range $pos-$end is not satisfiable (HTTP 416)")

            // 412 Precondition Failed means the ETag no longer matches: the resource changed.
            response.code == 412 -> throw ResourceChangedException(
                "The remote payload changed since it was parsed (ETag no longer matches)"
            )

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
        return end - pos + 1
    }

    /**
     * Reads exactly [expected] bytes into `out[outOffset, …)`, reporting progress and honouring
     * cancellation between chunks. A short or long body is rejected so no partial/foreign data is
     * ever accepted. Shared by the suspend and blocking read paths.
     */
    private suspend fun readExactly(
        input: java.io.InputStream,
        out: ByteArray,
        outOffset: Int,
        expected: Long,
        pos: Long,
        end: Long,
        onBytes: (Int) -> Unit,
    ): Int {
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
        return read
    }

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

    /**
     * Builds a ranged GET for `[pos, end]`, adding `If-Match` when a strong ETag was captured, so
     * a resource that changed since parsing fails with 412 instead of returning mismatched bytes.
     */
    private fun buildRangeRequest(pos: Long, end: Long): Request {
        val builder = Request.Builder()
            .url(url)
            .addHeader("Range", "bytes=$pos-$end")
        etag?.let { builder.addHeader("If-Match", it) }
        return builder.build()
    }

    private fun recordRequest() {
        requestCount.incrementAndGet()
    }

    private fun recordBytes(n: Long) {
        bytesDownloaded.addAndGet(n)
    }

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
