package com.rcmiku.payload.dumper.compose.utils

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

object HttpUtil : SeekableInput {

    override val isLocal: Boolean = false

    private lateinit var url: String
    private lateinit var fileName: String

    /** Display name of the remote resource. */
    val name: String
        get() = fileName

    private var fileLength: Long = 0

    /** Whether the server answered the Range probe with 206 (i.e. supports HTTP Range). */
    @Volatile
    private var rangeSupported: Boolean = false

    @Volatile
    private var position: Long = 0

    @Volatile
    private var cancelled: Boolean = false

    /** The in-flight call, so [cancel] can abort a blocking read instead of only flagging it. */
    @Volatile
    private var activeCall: Call? = null
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
        // Reset per-transfer state so a second init (new URL) cannot inherit a stale position,
        // length or cancellation flag from a previous run.
        url = link
        cancelled = false
        position = 0
        fileLength = 0
        rangeSupported = false

        val request = Request.Builder()
            .url(link)
            .addHeader("Range", "bytes=0-0")
            .build()

        val call = client.newCall(request)
        activeCall = call
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("Failed to initialize HTTP request: ${response.code} ${response.message}")
                }
                // A server honouring the Range probe answers 206 with a total in Content-Range.
                // If it ignores Range and returns 200 we fall back to Content-Length; seeking then
                // still works because reads are re-issued with explicit ranges each time.
                rangeSupported = response.code == 206
                val contentRange = response.header("Content-Range")
                fileLength = contentRange?.substringAfterLast('/')?.toLongOrNull()
                    ?: response.header("Content-Length")?.toLongOrNull()
                    ?: 0L
                if (!rangeSupported) {
                    // Ignoring the Range header means the server returned the whole body for a
                    // 1-byte probe; the advertised length is the resource size in that case.
                    fileLength = response.header("Content-Length")?.toLongOrNull() ?: fileLength
                }
                if (fileLength <= 0L) {
                    throw IOException("Server did not report a content length; cannot dump over HTTP")
                }
                fileName = getFileNameFromHeaders(response.headers)
            }
        } finally {
            activeCall = null
        }
    }


    override fun length(): Long {
        return fileLength
    }

    override fun position(): Long {
        return position
    }

    /**
     * Fills [buffer] completely from [position], advancing the position by the bytes read. Loops
     * until the array is full so a partition image can never be written from a short read, and
     * fails fast if the server stops making progress. Honours both coroutine cancellation and
     * [cancel].
     */
    override suspend fun readFully(buffer: ByteArray) {
        readRange(buffer)
    }

    suspend fun readRange(byteArray: ByteArray, onBytes: (Int) -> Unit = {}): Int =
        withContext(Dispatchers.IO) {
            val buffer = ByteArray(64 * 1024)
            var totalBytesRead = 0
            var currentPosition = position

            while (totalBytesRead < byteArray.size) {
                currentCoroutineContext().ensureActive()
                if (cancelled) throw CancellationException("HTTP transfer cancelled")

                val end = currentPosition + (byteArray.size - totalBytesRead) - 1
                val request = Request.Builder()
                    .url(url)
                    .addHeader("Range", "bytes=$currentPosition-$end")
                    .build()

                val call = client.newCall(request)
                activeCall = call
                val bytesThisResponse = try {
                    call.execute().use { response ->
                        if (!response.isSuccessful) {
                            throw IOException("Unexpected HTTP ${response.code} fetching $currentPosition-$end")
                        }
                        // A 200 to a ranged request means the server ignored Range and sent the
                        // whole resource. Only accept it for a from-zero read; bail out otherwise
                        // rather than silently copying the wrong bytes.
                        if (!rangeSupported && currentPosition != 0L) {
                            throw IOException(
                                "Server does not support HTTP Range; cannot fetch offset $currentPosition"
                            )
                        }
                        val body: ResponseBody = response.body
                            ?: throw IOException("Empty response body")
                        body.byteStream().use { inputStream ->
                            var localRead = 0
                            var bytesRead = inputStream.read(buffer)
                            while (totalBytesRead < byteArray.size && bytesRead != -1) {
                                currentCoroutineContext().ensureActive()
                                if (cancelled) throw CancellationException("HTTP transfer cancelled")
                                val chunk = minOf(bytesRead, byteArray.size - totalBytesRead)
                                System.arraycopy(buffer, 0, byteArray, totalBytesRead, chunk)
                                totalBytesRead += chunk
                                currentPosition += chunk
                                localRead += chunk
                                onBytes(chunk)
                                if (totalBytesRead < byteArray.size) {
                                    bytesRead = inputStream.read(buffer)
                                }
                            }
                            localRead
                        }
                    }
                } finally {
                    activeCall = null
                }

                if (bytesThisResponse == 0) {
                    // No progress: the server returned an empty body for this range. Looping
                    // would spin forever, so fail fast.
                    throw IOException("HTTP response for range $currentPosition- was empty")
                }
            }
            position = currentPosition
            return@withContext totalBytesRead
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
