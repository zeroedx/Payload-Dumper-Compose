package com.rcmiku.payload.dumper.compose

import android.net.Uri
import com.rcmiku.payload.dumper.compose.utils.SafUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadows.ShadowContentResolver
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.file.FileAlreadyExistsException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Regression tests for [SafUtil.publishIntoTree] driven through a real [FakeDocumentsProvider]
 * registered with Robolectric's content resolver, so the genuine `DocumentsContract` calls run.
 *
 * They assert the property that matters for user data: an existing same-name image is never
 * destroyed, and a rename/cancel/write failure leaves it byte-for-byte intact while the temporary
 * document is removed.
 */
@RunWith(RobolectricTestRunner::class)
class SafPublishTest {

    private val context = RuntimeEnvironment.getApplication()
    private lateinit var provider: FakeDocumentsProvider

    private val treeUri = Uri.parse("content://test.documents/tree/primary%3ADownload")
    private val rootDocId = "primary:Download"

    @Before
    fun setUp() {
        provider = FakeDocumentsProvider()
        val info = android.content.pm.ProviderInfo().apply { authority = "test.documents" }
        provider.attachInfo(context, info)
        ShadowContentResolver.registerProviderInternal("test.documents", provider)
    }

    private fun sourceFile(bytes: ByteArray): File =
        File.createTempFile("src", ".img").apply { writeBytes(bytes) }

    private fun newBytes(size: Int) = ByteArray(size) { (it and 0x7F).toByte() }

    // --- happy path ---------------------------------------------------------------------

    @Test
    fun publishesIntoAnEmptyTree() = runBlocking {
        val bytes = newBytes(100_000)
        val out = provider.documentUri("$rootDocId/system.img")

        val result = SafUtil.publishIntoTree(context, treeUri, "system.img", sourceFile(bytes))

        assertEquals(out, result)
        assertArrayEquals(bytes, provider.contentOf(out))
        assertEquals(1, provider.docs.size)
    }

    // --- same-name conflict: the old image must survive ---------------------------------

    @Test
    fun sameNameConflictKeepsTheExistingImageAndThrows() {
        val oldBytes = "PREVIOUS EXTRACTION".toByteArray()
        val existing = provider.seed(rootDocId, "system.img", oldBytes)

        val error = assertThrows(FileAlreadyExistsException::class.java) {
            runBlocking { SafUtil.publishIntoTree(context, treeUri, "system.img", sourceFile(newBytes(4096))) }
        }
        assertTrue(error.message!!.contains("system.img"))

        // The existing image is byte-for-byte untouched, and no temp document was left behind.
        assertArrayEquals(oldBytes, provider.contentOf(existing))
        assertEquals(1, provider.docs.size)
        assertNull(provider.documentNamed(".system.img.part"))
    }

    // --- rename failure: old image intact, temp cleaned up ------------------------------

    @Test
    fun renameFailureKeepsExistingFilesAndCleansTemp() {
        val otherBytes = "UNRELATED EXTRACTION".toByteArray()
        val other = provider.seed(rootDocId, "boot.img", otherBytes)
        provider.failRename = true

        assertThrows(IOException::class.java) {
            runBlocking { SafUtil.publishIntoTree(context, treeUri, "system.img", sourceFile(newBytes(4096))) }
        }

        assertArrayEquals(otherBytes, provider.contentOf(other))
        assertNull(provider.documentNamed("system.img"))
        assertEquals(1, provider.docs.size)
        assertNull(provider.documentNamed(".system.img.part"))
    }

    // --- write failure: existing files intact, temp cleaned up --------------------------

    @Test
    fun writeFailureKeepsExistingFilesAndCleansTemp() {
        val otherBytes = "UNRELATED EXTRACTION".toByteArray()
        val other = provider.seed(rootDocId, "boot.img", otherBytes)
        val tempUri = provider.documentUri("$rootDocId/.system.img.part")
        provider.outputStreamFactory = { uri ->
            if (uri == tempUri) FailingOutputStream(failAfter = 1024) else ByteArrayOutputStream()
        }

        assertThrows(IOException::class.java) {
            runBlocking {
                SafUtil.publishIntoTree(context, treeUri, "system.img", sourceFile(newBytes(100_000)))
            }
        }

        assertArrayEquals(otherBytes, provider.contentOf(other))
        assertNull(provider.documentNamed("system.img"))
        assertEquals(1, provider.docs.size)
        assertNull(provider.documentNamed(".system.img.part"))
    }

    // --- cancellation: existing files intact, temp cleaned up ---------------------------

    @Test
    fun cancellationKeepsExistingFilesAndCleansTemp() = runBlocking {
        val otherBytes = "UNRELATED EXTRACTION".toByteArray()
        val other = provider.seed(rootDocId, "boot.img", otherBytes)
        val tempUri = provider.documentUri("$rootDocId/.system.img.part")
        val entered = CountDownLatch(1)
        provider.outputStreamFactory = { uri ->
            if (uri == tempUri) BlockingOutputStream(entered) else ByteArrayOutputStream()
        }

        val job: Job = launch(Dispatchers.IO) {
            SafUtil.publishIntoTree(context, treeUri, "system.img", sourceFile(newBytes(200_000)))
        }
        // The copy is blocked inside the write; cancel it and let it unwind.
        assertTrue("copy did not reach the output stream", entered.await(5, TimeUnit.SECONDS))
        job.cancelAndJoin()

        assertTrue(job.isCancelled)
        assertArrayEquals(otherBytes, provider.contentOf(other))
        assertNull(provider.documentNamed("system.img"))
        assertEquals(1, provider.docs.size)
        assertNull(provider.documentNamed(".system.img.part"))
    }

    /** An [OutputStream] that fails after accepting [failAfter] bytes, like a full volume. */
    private class FailingOutputStream(private val failAfter: Int) : OutputStream() {
        private var written = 0
        override fun write(b: Int) = throw IOException("no space left on device")
        override fun write(b: ByteArray, off: Int, len: Int) {
            if (written >= failAfter) throw IOException("no space left on device")
            written += len
        }
    }

    /** An [OutputStream] whose write blocks, so the copy can be cancelled mid-write. */
    private class BlockingOutputStream(private val entered: CountDownLatch) : OutputStream() {
        override fun write(b: Int) = Unit
        override fun write(b: ByteArray, off: Int, len: Int) {
            entered.countDown()
            try {
                Thread.sleep(30_000)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("interrupted", e)
            }
        }
    }
}
