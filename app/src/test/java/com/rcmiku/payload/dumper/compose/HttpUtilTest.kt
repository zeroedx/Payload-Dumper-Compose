package com.rcmiku.payload.dumper.compose

import com.rcmiku.payload.dumper.compose.utils.AppContextUtil
import com.rcmiku.payload.dumper.compose.utils.HttpDataException
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
 * these run under Robolectric. A [MockWebServer] serves the real bytes, exercising the genuine
 * OkHttp read path (no mock of the transport). Every assertion checks the exact bytes read, not
 * merely that a call returned.
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

    /** A correct 206 for `bytes=start-end` of [content]. */
    private fun rangeResponse(content: ByteArray, start: Long, end: Long): MockResponse =
        MockResponse().setResponseCode(206)
            .setHeader("Content-Range", "bytes $start-$end/${content.size}")
            .setBody(Buffer().write(content.copyOfRange(start.toInt(), (end + 1).toInt())))

    /** The single-byte probe answer for a resource of [content]. */
    private fun probeResponse(content: ByteArray): MockResponse = rangeResponse(content, 0, 0)

    // --- happy path: Range reads and random seek ---------------------------------------

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

    // --- retry and backoff -------------------------------------------------------------

    @Test
    fun readRange_retriesTransientFailureThenSucceeds() = runBlocking {
        val content = body(50_000)
        server.enqueue(probeResponse(content))
        server.enqueue(MockResponse().setResponseCode(500)) // transient failure
        server.enqueue(rangeResponse(content, 10_000, 29_999))

        HttpUtil.init(server.url("/payload.bin").toString())
        HttpUtil.seek(10_000)
        val out = ByteArray(20_000)
        HttpUtil.readFully(out)

        assertArrayEquals(content.copyOfRange(10_000, 30_000), out)
        assertEquals(30_000L, HttpUtil.position())
    }

    @Test
    fun init_retriesTransientFailureThenSucceeds() = runBlocking {
        val content = body(5000)
        server.enqueue(MockResponse().setResponseCode(503)) // transient failure on the probe
        server.enqueue(probeResponse(content))

        HttpUtil.init(server.url("/payload.bin").toString())
        assertEquals(5000L, HttpUtil.length())
    }

    @Test
    fun readRange_failsAfterExceedingRetryLimit() {
        val content = body(10_000)
        server.enqueue(probeResponse(content))
        // Three attempts, all failing: the retry budget is exactly MAX_ATTEMPTS.
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setResponseCode(500))

        assertThrows(IOException::class.java) {
            runBlocking {
                HttpUtil.init(server.url("/payload.bin").toString())
                HttpUtil.seek(0)
                HttpUtil.readFully(ByteArray(1000))
            }
        }
    }

    // --- HTTP status handling: 200 / 416 / 5xx -----------------------------------------

    @Test
    fun init_rejectsNonRangeServer() {
        // 200 to a ranged probe means the server ignored Range; random access is impossible.
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setHeader("Content-Length", 1000)
                .setBody(Buffer().write(body(1000)))
        )
        assertThrows(HttpDataException::class.java) {
            runBlocking { HttpUtil.init(server.url("/payload.bin").toString()) }
        }
    }

    @Test
    fun readRange_rejects416RangeNotSatisfiable() {
        server.enqueue(probeResponse(body(1000)))
        server.enqueue(MockResponse().setResponseCode(416))
        assertThrows(HttpDataException::class.java) {
            runBlocking {
                HttpUtil.init(server.url("/payload.bin").toString())
                HttpUtil.seek(0)
                HttpUtil.readFully(ByteArray(10))
            }
        }
    }

    // --- Content-Range validation ------------------------------------------------------

    @Test
    fun init_throwsWhenServerOmitsLength() {
        server.enqueue(MockResponse().setResponseCode(206)) // no Content-Range / Content-Length
        assertThrows(HttpDataException::class.java) {
            runBlocking { HttpUtil.init(server.url("/payload.bin").toString()) }
        }
    }

    @Test
    fun readRange_rejectsMissingContentRange() {
        server.enqueue(probeResponse(body(1000)))
        server.enqueue(MockResponse().setResponseCode(206).setBody(Buffer().write(ByteArray(10))))
        assertThrows(HttpDataException::class.java) {
            runBlocking {
                HttpUtil.init(server.url("/payload.bin").toString())
                HttpUtil.seek(0)
                HttpUtil.readFully(ByteArray(10))
            }
        }
    }

    @Test
    fun readRange_rejectsWrongContentRangeStart() {
        server.enqueue(probeResponse(body(1000)))
        server.enqueue(
            MockResponse().setResponseCode(206)
                .setHeader("Content-Range", "bytes 5-14/1000")
                .setBody(Buffer().write(ByteArray(10)))
        )
        assertThrows(HttpDataException::class.java) {
            runBlocking {
                HttpUtil.init(server.url("/payload.bin").toString())
                HttpUtil.seek(0)
                HttpUtil.readFully(ByteArray(10))
            }
        }
    }

    @Test
    fun readRange_rejectsChangedResourceLength() {
        server.enqueue(probeResponse(body(1000))) // establishes fileLength = 1000
        server.enqueue(
            MockResponse().setResponseCode(206)
                .setHeader("Content-Range", "bytes 0-9/2000")
                .setBody(Buffer().write(ByteArray(10)))
        )
        assertThrows(HttpDataException::class.java) {
            runBlocking {
                HttpUtil.init(server.url("/payload.bin").toString())
                HttpUtil.seek(0)
                HttpUtil.readFully(ByteArray(10))
            }
        }
    }

    // --- empty / short / long bodies ---------------------------------------------------

    @Test
    fun readRange_rejectsEmptyBody() {
        server.enqueue(probeResponse(body(1000)))
        server.enqueue(
            MockResponse().setResponseCode(206)
                .setHeader("Content-Range", "bytes 0-9/1000")
                .setBody(Buffer())
        )
        assertThrows(HttpDataException::class.java) {
            runBlocking {
                HttpUtil.init(server.url("/payload.bin").toString())
                HttpUtil.seek(0)
                HttpUtil.readFully(ByteArray(10))
            }
        }
    }

    @Test
    fun readRange_rejectsShortBody() {
        server.enqueue(probeResponse(body(1000)))
        server.enqueue(
            MockResponse().setResponseCode(206)
                .setHeader("Content-Range", "bytes 0-9/1000")
                .setBody(Buffer().write(ByteArray(5)))
        )
        assertThrows(HttpDataException::class.java) {
            runBlocking {
                HttpUtil.init(server.url("/payload.bin").toString())
                HttpUtil.seek(0)
                HttpUtil.readFully(ByteArray(10))
            }
        }
    }

    // --- cancellation and task isolation ------------------------------------------------

    @Test
    fun cancel_abortsInFlightRead() = runBlocking {
        val content = body(5000)
        server.enqueue(probeResponse(content))
        server.enqueue(rangeResponse(content, 0, 4999).setBodyDelay(3, TimeUnit.SECONDS))

        HttpUtil.init(server.url("/payload.bin").toString())
        HttpUtil.seek(0)
        val job = CoroutineScope(Dispatchers.IO).launch {
            runCatching { HttpUtil.readFully(ByteArray(5000)) }
        }
        delay(300)
        HttpUtil.cancel()
        // If cancel() does not abort the blocking call this join times out and the test fails.
        withTimeout(3000) { job.join() }
        assertTrue(job.isCompleted)
    }

    @Test
    fun newInit_abortsSupersededInFlightRead() = runBlocking {
        val contentA = body(5000)
        server.enqueue(probeResponse(contentA))
        server.enqueue(rangeResponse(contentA, 0, 4999).setBodyDelay(3, TimeUnit.SECONDS))

        HttpUtil.init(server.url("/payload.bin").toString())
        HttpUtil.seek(0)
        val job = CoroutineScope(Dispatchers.IO).launch {
            runCatching { HttpUtil.readFully(ByteArray(5000)) }
        }
        delay(300) // the read is now blocked on the delayed body of resource A

        // Starting a new transfer must supersede the old one, which has to abort.
        val contentB = body(3000)
        server.enqueue(probeResponse(contentB))
        server.enqueue(rangeResponse(contentB, 0, 2999))
        HttpUtil.init(server.url("/payload.bin").toString())

        withTimeout(3000) { job.join() }
        assertTrue(job.isCompleted)

        HttpUtil.seek(0)
        val out = ByteArray(3000)
        HttpUtil.readFully(out)
        assertArrayEquals(contentB, out)
    }

    @Test
    fun cancel_thenNewTransfer_readsCorrectBytes() = runBlocking {
        val content = body(5000)
        server.enqueue(probeResponse(content))
        server.enqueue(rangeResponse(content, 0, 4999).setBodyDelay(3, TimeUnit.SECONDS))

        HttpUtil.init(server.url("/payload.bin").toString())
        HttpUtil.seek(0)
        val job = CoroutineScope(Dispatchers.IO).launch {
            runCatching { HttpUtil.readFully(ByteArray(5000)) }
        }
        delay(200)
        HttpUtil.cancel()
        withTimeout(3000) { job.join() }

        // A fresh transfer on the same server must read correct bytes, not stale state.
        server.enqueue(probeResponse(content))
        server.enqueue(rangeResponse(content, 1000, 1999))
        HttpUtil.init(server.url("/payload.bin").toString())
        HttpUtil.seek(1000)
        val out = ByteArray(1000)
        HttpUtil.readFully(out)
        assertArrayEquals(content.copyOfRange(1000, 2000), out)
    }
}
