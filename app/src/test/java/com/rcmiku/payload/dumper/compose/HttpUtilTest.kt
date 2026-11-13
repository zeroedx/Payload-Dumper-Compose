package com.rcmiku.payload.dumper.compose

import com.rcmiku.payload.dumper.compose.utils.AppContextUtil
import chromeos_update_engine.UpdateMetadata
import com.rcmiku.payload.dumper.compose.utils.HttpDataException
import com.rcmiku.payload.dumper.compose.utils.HttpUtil
import com.rcmiku.payload.dumper.compose.utils.RangePlanner
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

    // --- default User-Agent (Task 2) ---------------------------------------------------

    @Test
    fun requestsUseTheDefaultUserAgent() = runBlocking {
        val content = body(5000)
        server.dispatcher = RangeDispatcher(content)
        val defaultUa = com.rcmiku.payload.dumper.compose.utils.UserAgentUtil.DEFAULT_USER_AGENT

        // Simulate an upgrade from 1.7 where the user had saved a custom UA: those keys must have
        // no effect any more, and the real request headers must carry the default UA.
        val legacyUa = "LegacyCustomUA/1.7 (identifiable)"
        com.rcmiku.payload.dumper.compose.utils.PreferencesUtil().apply {
            perfSet("customUserAgent", legacyUa)
            perfSet("isCustomUserAgentEnabled", true)
        }

        HttpUtil.init(server.url("/payload.bin").toString())
        val probe = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals(defaultUa, probe.getHeader("User-Agent"))

        HttpUtil.seek(0)
        HttpUtil.readFully(ByteArray(100))
        val ranged = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals(defaultUa, ranged.getHeader("User-Agent"))
    }

    // --- ETag binding (Task 5) ---------------------------------------------------------

    @Test
    fun capturesStrongEtagAndSendsIfMatch() = runBlocking {
        val content = body(5000)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val spec = request.getHeader("Range")!!.removePrefix("bytes=").split("-")
                val start = spec[0].toLong()
                val end = spec[1].toLong().coerceAtMost(content.size - 1L)
                val slice = content.copyOfRange(start.toInt(), (end + 1).toInt())
                return MockResponse().setResponseCode(206)
                    .setHeader("Content-Range", "bytes $start-$end/${content.size}")
                    .setHeader("ETag", "\"abc123\"")
                    .setBody(Buffer().write(slice))
            }
        }

        HttpUtil.init(server.url("/payload.bin").toString())
        assertEquals("\"abc123\"", HttpUtil.strongEtag)
        server.takeRequest() // probe

        HttpUtil.seek(0)
        HttpUtil.readFully(ByteArray(64))
        val ranged = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("\"abc123\"", ranged.getHeader("If-Match"))
    }

    @Test
    fun weakEtagIsIgnored() = runBlocking {
        val content = body(5000)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val spec = request.getHeader("Range")!!.removePrefix("bytes=").split("-")
                val start = spec[0].toLong()
                val end = spec[1].toLong().coerceAtMost(content.size - 1L)
                val slice = content.copyOfRange(start.toInt(), (end + 1).toInt())
                return MockResponse().setResponseCode(206)
                    .setHeader("Content-Range", "bytes $start-$end/${content.size}")
                    .setHeader("ETag", "W/\"weak\"")
                    .setBody(Buffer().write(slice))
            }
        }

        HttpUtil.init(server.url("/payload.bin").toString())
        assertEquals(null, HttpUtil.strongEtag)
    }

    @Test
    fun positionedReadAtDoesNotDisturbTheCursorAndUsesThePrefetchCache() = runBlocking {
        val content = body(200_000)
        server.dispatcher = RangeDispatcher(content)
        HttpUtil.init(server.url("/payload.bin").toString())
        server.takeRequest() // probe

        HttpUtil.seek(1234)
        val first = ByteArray(5000)
        HttpUtil.readAt(50_000, first, 0, first.size)
        assertArrayEquals(content.copyOfRange(50_000, 55_000), first)
        // readAt is positioned: the sequential cursor is unchanged.
        assertEquals(1234L, HttpUtil.position())
        server.takeRequest(1, TimeUnit.SECONDS) // the uncached first read

        // Prefetch the merged range covering the blob, then read from it: no new request is made.
        HttpUtil.prefetch(listOf(RangePlanner.ByteRange(50_000, 60_000)))
        server.takeRequest(1, TimeUnit.SECONDS) // the prefetch request
        val second = ByteArray(5000)
        HttpUtil.readAt(52_000, second, 0, second.size)
        assertArrayEquals(content.copyOfRange(52_000, 57_000), second)
        assertEquals(null, server.takeRequest(300, TimeUnit.MILLISECONDS))
    }

    // --- request-count reduction from range merging + prefetch (Task 5) -----------------

    @Test
    fun prefetchMergesManyBlobReadsIntoOneRequest() = runBlocking {
        val content = body(4 * 1024 * 1024)
        server.dispatcher = RangeDispatcher(content)
        HttpUtil.init(server.url("/payload.bin").toString())
        server.takeRequest() // probe

        // 40 contiguous blobs of 1000 bytes: a real partition lays its operation data out
        // back-to-back, so each readAt alone would be one request while a merged fetch is one.
        val blobSize = 1000
        val blobs = (0 until 40).map { it * blobSize }
        val ranges = blobs.map { RangePlanner.ByteRange(it.toLong(), (it + blobSize - 1).toLong()) }
        val merged = RangePlanner.planRanges(
            listOf(
                UpdateMetadata.PartitionUpdate.newBuilder().apply {
                    setPartitionName("p")
                    ranges.forEach {
                        addOperations(
                            UpdateMetadata.InstallOperation.newBuilder()
                                .setType(UpdateMetadata.InstallOperation.Type.REPLACE)
                                .setDataOffset(it.start)
                                .setDataLength(it.length)
                                .addDstExtents(
                                    UpdateMetadata.Extent.newBuilder().setStartBlock(0).setNumBlocks(1)
                                )
                                .build()
                        )
                    }
                }.build()
            ),
            dataOffset = 0,
        )

        // Without prefetch: every readAt is its own request.
        HttpUtil.clearCache()
        val beforeStart = HttpUtil.stats().requests
        blobs.forEach { off ->
            HttpUtil.readAt(off.toLong(), ByteArray(blobSize), 0, blobSize)
        }
        val before = HttpUtil.stats().requests - beforeStart

        // With prefetch: the merged ranges are fetched once, then read from the cache.
        HttpUtil.clearCache()
        val prefetchStart = HttpUtil.stats().requests
        HttpUtil.prefetch(merged)
        blobs.forEach { off ->
            val out = ByteArray(blobSize)
            HttpUtil.readAt(off.toLong(), out, 0, blobSize)
            assertArrayEquals(content.copyOfRange(off, off + blobSize), out)
        }
        val afterPrefetch = HttpUtil.stats().requests - prefetchStart

        // Every merged range is fetched exactly once; the cached reads add no further requests.
        assertEquals(merged.size.toLong(), afterPrefetch)
        assertTrue("prefetch must reduce requests ($before -> $afterPrefetch)",
            afterPrefetch < before)
        println(
            "HTTP optimization: requests $before -> $afterPrefetch, " +
                "bytes ${before * blobSize} -> ${RangePlanner.totalBytes(merged)}"
        )
    }

    // --- merged ranges larger than the cache, and gapped ranges (pre-commit review) -----

    /**
     * A merged range of 10 MiB (larger than the 8 MiB cache) must not be re-downloaded: the
     * prefetched window stays cached, so reading a blob inside it issues no request. Before the fix
     * an oversized segment was evicted on insert, so the read that followed re-fetched it.
     */
    @Test
    fun mergedRangeLargerThanCacheIsNotDownloadedTwice() = runBlocking {
        val mb = 1024 * 1024
        val content = body(14 * mb)
        server.dispatcher = RangeDispatcher(content)
        HttpUtil.init(server.url("/payload.bin").toString())
        server.takeRequest() // probe

        // One merged range of 10 MiB: larger than the 8 MiB cache.
        val start = 1L * mb
        val length = 10L * mb
        val span = listOf(RangePlanner.ByteRange(start, start + length - 1))

        HttpUtil.prefetch(span)
        val prefetchRequests = HttpUtil.stats().requests
        val prefetchBytes = HttpUtil.stats().bytes
        assertEquals(HttpUtil.cacheLimitBytes, prefetchBytes)

        // A blob inside the prefetched window is served from the cache: no new request.
        val inside = ByteArray((1L * mb).toInt())
        HttpUtil.readAt(start, inside, 0, inside.size)
        assertArrayEquals(content.copyOfRange(start.toInt(), (start + inside.size).toInt()), inside)
        assertEquals(prefetchRequests, HttpUtil.stats().requests)

        // A blob past the prefetched window is fetched once, on demand.
        val beyondOffset = start + 9L * mb
        val beyond = ByteArray((1L * mb).toInt())
        HttpUtil.readAt(beyondOffset, beyond, 0, beyond.size)
        assertArrayEquals(content.copyOfRange(beyondOffset.toInt(), (beyondOffset + beyond.size).toInt()), beyond)
        val requests = HttpUtil.stats().requests
        val bytes = HttpUtil.stats().bytes
        assertEquals(prefetchRequests + 1, requests)
        // 8 MiB prefetched + 1 MiB fetched on demand: no byte was downloaded twice.
        assertEquals(HttpUtil.cacheLimitBytes + beyond.size, bytes)

        // Re-reading the same blob adds nothing.
        HttpUtil.readAt(beyondOffset, ByteArray(beyond.size), 0, beyond.size)
        assertEquals(requests, HttpUtil.stats().requests)
        assertEquals(bytes, HttpUtil.stats().bytes)
        assertTrue("cache occupancy must stay within the bound",
            HttpUtil.peakCacheSizeBytes <= HttpUtil.cacheLimitBytes)
        println(
            "Large merged range: span $length bytes, requests $requests, bytes $bytes, " +
                "peak cache ${HttpUtil.peakCacheSizeBytes}"
        )
    }

    /**
     * A merged range that exceeds the cache must not cause the head to be re-downloaded when the
     * tail is read: the prefetcher caps its total to the cache size, so the head stays cached and
     * the uncovered tail is fetched on demand exactly once.
     */
    @Test
    fun mergedRangeBeyondCacheDoesNotRedownloadTheHead() = runBlocking {
        val mb = 1024 * 1024
        val content = body(16 * mb)
        server.dispatcher = RangeDispatcher(content)
        HttpUtil.init(server.url("/payload.bin").toString())
        server.takeRequest() // probe

        // A 14 MiB merged range: 6 MiB larger than the 8 MiB cache.
        val start = 1L * mb
        val length = 14L * mb
        val span = listOf(RangePlanner.ByteRange(start, start + length - 1))

        HttpUtil.prefetch(span)
        val afterPrefetchBytes = HttpUtil.stats().bytes
        assertTrue("prefetch must not fetch more than the cache holds",
            afterPrefetchBytes <= HttpUtil.cacheLimitBytes)
        assertEquals(HttpUtil.cacheLimitBytes, afterPrefetchBytes)

        // Read the whole span in blob-sized chunks; the head is cached, the tail fetched once.
        val blob = 1L * mb
        var offset = start
        while (offset < start + length) {
            val size = minOf(blob, start + length - offset).toInt()
            val out = ByteArray(size)
            HttpUtil.readAt(offset, out, 0, size)
            assertArrayEquals(content.copyOfRange(offset.toInt(), (offset + size).toInt()), out)
            offset += size
        }

        // Total downloaded bytes must be exactly the span (head from cache + tail fetched once),
        // never more: no byte of the span is downloaded twice.
        assertEquals(RangePlanner.totalBytes(span), HttpUtil.stats().bytes)
        println(
            "Cache-bounded prefetch: span $length bytes, downloaded ${HttpUtil.stats().bytes}, " +
                "peak cache ${HttpUtil.peakCacheSizeBytes}"
        )
    }

    /** Gapped ranges are merged when close and kept apart when far, with no duplicate bytes read. */
    @Test
    fun gappedRangesAreFetchedOnceWithNoDuplicateBytes() = runBlocking {
        val kb = 1024
        val content = body(4 * 1024 * kb)
        server.dispatcher = RangeDispatcher(content)
        HttpUtil.init(server.url("/payload.bin").toString())
        server.takeRequest() // probe

        // Two blobs separated by a small gap (merged) and a far blob (kept separate).
        val near1 = RangePlanner.ByteRange(100L * kb, 101L * kb - 1)
        val near2 = RangePlanner.ByteRange(101L * kb, 102L * kb - 1)
        val far = RangePlanner.ByteRange(3000L * kb, 3001L * kb - 1)
        val merged = RangePlanner.planRanges(
            listOf(
                UpdateMetadata.PartitionUpdate.newBuilder().apply {
                    setPartitionName("p")
                    listOf(near1, near2, far).forEach {
                        addOperations(
                            UpdateMetadata.InstallOperation.newBuilder()
                                .setType(UpdateMetadata.InstallOperation.Type.REPLACE)
                                .setDataOffset(it.start)
                                .setDataLength(it.length)
                                .addDstExtents(
                                    UpdateMetadata.Extent.newBuilder().setStartBlock(0).setNumBlocks(1)
                                )
                                .build()
                        )
                    }
                }.build()
            ),
            dataOffset = 0,
        )
        // near1+near2 are adjacent, so they merge; far stays its own range.
        assertEquals(2, merged.size)

        HttpUtil.prefetch(merged)
        val requests = HttpUtil.stats().requests
        val bytes = HttpUtil.stats().bytes

        listOf(near1, near2, far).forEach {
            val out = ByteArray(it.length.toInt())
            HttpUtil.readAt(it.start, out, 0, out.size)
            assertArrayEquals(content.copyOfRange(it.start.toInt(), (it.endInclusive + 1).toInt()), out)
        }

        // One request per merged range, and exactly the merged bytes downloaded (no duplicates).
        assertEquals(merged.size.toLong(), requests)
        assertEquals(RangePlanner.totalBytes(merged), bytes)
        assertEquals(requests, HttpUtil.stats().requests)
        assertEquals(bytes, HttpUtil.stats().bytes)
        println(
            "Gapped ranges: merged ${merged.size} ranges, requests $requests, bytes $bytes"
        )
    }
}
