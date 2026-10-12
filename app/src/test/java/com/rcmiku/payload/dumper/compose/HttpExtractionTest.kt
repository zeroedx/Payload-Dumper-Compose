package com.rcmiku.payload.dumper.compose

import chromeos_update_engine.UpdateMetadata
import com.rcmiku.payload.dumper.compose.utils.AppContextUtil
import com.rcmiku.payload.dumper.compose.utils.HttpUtil
import com.rcmiku.payload.dumper.compose.utils.PartitionOutput
import com.rcmiku.payload.dumper.compose.utils.PayloadUtil
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

/**
 * End-to-end check that a remote payload whose data region keeps failing transiently (exhausting
 * the retry budget) fails extraction and leaves no partial image behind. Runs the real OkHttp read
 * path against [MockWebServer] and the real [PayloadUtil.extractPartition] path.
 */
@RunWith(RobolectricTestRunner::class)
class HttpExtractionTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer

    /** Serves manifest/header ranges correctly, but 500s every range in the data region. */
    private class DataFailsDispatcher(
        private val content: ByteArray,
        private val dataOffset: Long,
    ) : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val range = request.getHeader("Range") ?: return MockResponse().setResponseCode(400)
            val spec = range.removePrefix("bytes=").split("-")
            val start = spec[0].toLong()
            val end = spec[1].toLong()
            if (start >= dataOffset) {
                return MockResponse().setResponseCode(500)
            }
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
        runCatching { server.shutdown() }
    }

    private fun writeLongBE(out: ByteArrayOutputStream, v: Long) {
        for (i in 7 downTo 0) out.write(((v ushr (i * 8)) and 0xFF).toInt())
    }

    private fun writeIntBE(out: ByteArrayOutputStream, v: Int) {
        for (i in 3 downTo 0) out.write(((v ushr (i * 8)) and 0xFF))
    }

    @Test
    fun retryExhaustionDuringExtraction_cleansPartialImage() {
        val blockSize = 4096
        val op = UpdateMetadata.InstallOperation.newBuilder()
            .setType(UpdateMetadata.InstallOperation.Type.REPLACE)
            .setDataOffset(0)
            .setDataLength(blockSize.toLong())
            .addDstExtents(UpdateMetadata.Extent.newBuilder().setStartBlock(0).setNumBlocks(1))
            .build()
        val partition = UpdateMetadata.PartitionUpdate.newBuilder()
            .setPartitionName("boot")
            .addOperations(op)
            .setNewPartitionInfo(UpdateMetadata.PartitionInfo.newBuilder().setSize(blockSize.toLong()))
            .build()
        val manifest = UpdateMetadata.DeltaArchiveManifest.newBuilder()
            .setMinorVersion(0)
            .setBlockSize(blockSize)
            .addPartitions(partition)
            .build()

        val manifestBytes = manifest.toByteArray()
        val out = ByteArrayOutputStream()
        out.write("CrAU".toByteArray(Charsets.US_ASCII))
        writeLongBE(out, 2L)
        writeLongBE(out, manifestBytes.size.toLong())
        writeIntBE(out, 0)
        out.write(manifestBytes)
        out.write(ByteArray(blockSize) { 0x11 }) // data blob, never successfully served
        val content = out.toByteArray()
        val dataOffset = 24L + manifestBytes.size

        server.dispatcher = DataFailsDispatcher(content, dataOffset)

        val output = File(tmp.root, "boot.img")
        assertThrows(IOException::class.java) {
            runBlocking {
                HttpUtil.init(server.url("/payload.bin").toString())
                val payload = PayloadUtil.initPayload("payload.bin", HttpUtil, 0L)
                PayloadUtil.extractPartition(
                    payload.deltaArchiveManifest.partitionsList[0],
                    HttpUtil,
                    PartitionOutput(output),
                    payload,
                ) {}
            }
        }
        assertFalse("partial image must be cleaned up after retry exhaustion", output.exists())
    }
}
