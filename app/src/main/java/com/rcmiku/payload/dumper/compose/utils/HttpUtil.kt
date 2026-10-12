package com.rcmiku.payload.dumper.compose.utils

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
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

    @Volatile
    private var position: Long = 0

    @Volatile
    private var cancelled: Boolean = false
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
        url = link
        cancelled = false
        val request = Request.Builder()
            .url(link)
            .addHeader("Range", "bytes=0-0")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Failed to initialize HTTP request: ${response.code} ${response.message}")
            }
            // A server honouring the Range probe answers 206 with a total in Content-Range.
            // If it ignores Range and returns 200 we fall back to Content-Length; seeking then
            // still works because reads are re-issued with explicit ranges each time.
            val contentRange = response.header("Content-Range")
            fileLength = contentRange?.substringAfterLast('/')?.toLongOrNull()
                ?: response.header("Content-Length")?.toLongOrNull()
                ?: 0L
            if (fileLength <= 0L) {
                throw IOException("Server did not report a content length; cannot dump over HTTP")
            }
            fileName = getFileNameFromHeaders(response.headers)
        }
    }


    override fun length(): Long {
        return fileLength
    }

    override fun position(): Long {
        return position
    }

    /**
     * Fills [byteArray] completely from [position], advancing the position by the bytes read.
     *
     * Unlike the previous single-pass implementation this loops until the array is full, so a
     * partition image can never be written from a short read. Honours both coroutine cancellation
     * and [cancel].
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
                call.execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IOException("Unexpected HTTP ${response.code} fetching $currentPosition-$end")
                    }
                    val body: ResponseBody = response.body
                        ?: throw IOException("Empty response body")
                    body.byteStream().use { inputStream ->
                        var bytesRead = inputStream.read(buffer)
                        while (totalBytesRead < byteArray.size && bytesRead != -1) {
                            currentCoroutineContext().ensureActive()
                            if (cancelled) throw CancellationException("HTTP transfer cancelled")
                            val chunk = minOf(bytesRead, byteArray.size - totalBytesRead)
                            System.arraycopy(buffer, 0, byteArray, totalBytesRead, chunk)
                            totalBytesRead += chunk
                            currentPosition += chunk
                            onBytes(chunk)
                            if (totalBytesRead < byteArray.size) {
                                bytesRead = inputStream.read(buffer)
                            }
                        }
                    }
                }
            }
            position = currentPosition
            return@withContext totalBytesRead
        }

    /** Best-effort interruption of an in-flight [readFully]. */
    fun cancel() {
        cancelled = true
    }

    /** Convenience wrapper kept for callers that do not need progress callbacks. */
    suspend fun read(byteArray: ByteArray): Int = readRange(byteArray)

    override fun seek(bytePosition: Long) {
        if (bytePosition in 0 until fileLength) {
            position = bytePosition
        } else {
            throw IllegalArgumentException("Invalid seek position")
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
