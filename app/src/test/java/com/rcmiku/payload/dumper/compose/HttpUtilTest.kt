package com.rcmiku.payload.dumper.compose

import com.rcmiku.payload.dumper.compose.utils.AppContextUtil
import com.rcmiku.payload.dumper.compose.utils.HttpUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * JVM tests for the HTTP Range reader. [HttpUtil] reads preferences through [AppContextUtil], so
 * these run under Robolectric. A [MockWebServer] with a real Range-aware dispatcher serves the
 * bytes, exercising the genuine OkHttp read path (no mock of the transport).
 */
@RunWith(RobolectricTestRunner::class)
class HttpUtilTest {

    private lateinit var server: MockWebServer

    /** Serves 206 partial content for `Range: bytes=start-end`, like a real CDN. */
    private class RangeDispatcher(private val content: ByteArray) : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val range = request.getHeader("Range")
            if (range == null) {
                return MockResponse().setResponseCode(200)
                    .setHeader("Content-Length", content.size)
                    .setBody(Buffer().write(content))
            }
            val spec = range.removePrefix("bytes=").split("-")
            val start = spec[0].toLong()
            val end = spec[1].toLong().coerceAtMost(content.size - 1L)
            val slice = content.copyOfRange(start.toInt(), (end + 1).toInt())
            return MockResponse().setResponseCode(206)
                .setHeader("Content-Range", "bytes $start-$end/${content.size}")
                .setBody(Buffer().write(slice))
        }
    }

    @Before
    fun setUp() {
        AppContextUtil.init(RuntimeEnvironment.getApplication())
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        // A cancelled read can leave a delayed response sleeping inside MockWebServer; do not let
        // that abort the test result.
        runCatching { server.shutdown() }
    }

    private fun body(byteCount: Int) = ByteArray(byteCount) { (it and 0xFF).toByte() }

    @Test
    fun init_readsTotalSizeFromContentRange() = runBlocking {
        val content = body(5000)
        server.dispatcher = RangeDispatcher(content)

        HttpUtil.init(server.url("/payload.bin").toString())
        assertEquals(5000L, HttpUtil.length())
        assertEquals("payload.bin", HttpUtil.name)
    }

    @Test
    fun readFully_fetchesRequestedWindow() = runBlocking {
        val content = body(100_000)
        server.dispatcher = RangeDispatcher(content)
        HttpUtil.init(server.url("/payload.bin").toString())

        HttpUtil.seek(40_000)
        val out = ByteArray(20_000)
        HttpUtil.readFully(out)

        assertArrayEquals(content.copyOfRange(40_000, 60_000), out)
        assertEquals(60_000L, HttpUtil.position())
    }

    @Test
    fun seek_backwards_reReadsEarlierBytes() = runBlocking {
        val content = body(10_000)
        server.dispatcher = RangeDispatcher(content)
        HttpUtil.init(server.url("/payload.bin").toString())

        HttpUtil.seek(8000)
        val first = ByteArray(1000)
        HttpUtil.readFully(first)

        HttpUtil.seek(100)
        val second = ByteArray(1000)
        HttpUtil.readFully(second)

        assertArrayEquals(content.copyOfRange(8000, 9000), first)
        assertArrayEquals(content.copyOfRange(100, 1100), second)
    }

    @Test
    fun init_throwsWhenServerOmitsLength() {
        // 206 with neither Content-Range nor Content-Length: the size is unknowable.
        server.enqueue(MockResponse().setResponseCode(206))
        assertThrows(IOException::class.java) {
            runBlocking { HttpUtil.init(server.url("/payload.bin").toString()) }
        }
    }

    @Test
    fun readRange_failsFastWhenBodyIsEmpty() {
        // Probe reports a total, but every ranged GET returns an empty 206 body.
        server.enqueue(
            MockResponse().setResponseCode(206)
                .setHeader("Content-Range", "bytes 0-0/1000")
                .setBody(Buffer().write(ByteArray(1)))
        )
        repeat(4) { server.enqueue(MockResponse().setResponseCode(206).setBody(Buffer())) }

        assertThrows(IOException::class.java) {
            runBlocking {
                HttpUtil.init(server.url("/payload.bin").toString())
                HttpUtil.seek(0)
                HttpUtil.readFully(ByteArray(10))
            }
        }
    }

    @Test
    fun readRange_rejectsNonRangeServerForNonZeroOffset() {
        // Probe returns 200 (no Range support) with a total length; a non-zero read must refuse
        // rather than silently return bytes from offset 0.
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setHeader("Content-Length", 1000)
                .setBody(Buffer().write(body(1000)))
        )
        assertThrows(IOException::class.java) {
            runBlocking {
                HttpUtil.init(server.url("/payload.bin").toString())
                HttpUtil.seek(500)
                HttpUtil.readFully(ByteArray(10))
            }
        }
    }

    /** Answers the probe immediately, then stalls every subsequent body for [delaySeconds]. */
    private class ProxyThenStallDispatcher(private val delaySeconds: Long) : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val range = request.getHeader("Range")
            val start = range?.removePrefix("bytes=")?.substringBefore("-")?.toLongOrNull() ?: 0L
            val end = range?.removePrefix("bytes=")?.substringAfter("-")?.toLongOrNull() ?: 0L
            val resp = MockResponse().setResponseCode(206)
                .setHeader("Content-Range", "bytes $start-$end/1000")
                .setBody(Buffer().write(ByteArray((end - start + 1).toInt())))
            if (start != 0L || end != 0L) {
                resp.setBodyDelay(delaySeconds, TimeUnit.SECONDS)
            }
            return resp
        }
    }

    @Test
    fun cancel_abortsInFlightRead() = runBlocking {
        server.dispatcher = ProxyThenStallDispatcher(delaySeconds = 3)

        HttpUtil.init(server.url("/payload.bin").toString())
        HttpUtil.seek(0)
        val job = CoroutineScope(Dispatchers.IO).launch {
            runCatching { HttpUtil.readFully(ByteArray(1000)) }
        }
        delay(300)
        HttpUtil.cancel()
        // If cancel() does not abort the blocking call this join times out and the test fails.
        withTimeout(3000) { job.join() }
        assertTrue(job.isCompleted)
    }
}
