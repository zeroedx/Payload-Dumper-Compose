package com.rcmiku.payload.dumper.compose

import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.MediaStore
import android.provider.MediaStore.MediaColumns.DISPLAY_NAME
import android.provider.MediaStore.MediaColumns.IS_PENDING
import android.provider.MediaStore.MediaColumns.RELATIVE_PATH
import com.rcmiku.payload.dumper.compose.utils.DownloadOutput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowContentResolver
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.file.FileAlreadyExistsException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class DownloadOutputTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val context = RuntimeEnvironment.getApplication()
    private val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    private val image = ContentUris.withAppendedId(collection, 1)
    private val versionedPath = "Download/byron os4.0.5.0/"
    private val provider = TestDownloadsProvider()
    private val bytes = ByteArray(150_000) { it.toByte() }

    @Before
    fun registerProvider() {
        val authority = requireNotNull(collection.authority)
        provider.attachInfo(context, ProviderInfo().apply { this.authority = authority })
        ShadowContentResolver.registerProviderInternal(authority, provider)
    }

    @Test
    fun usesTheFirstValidDeviceCodeAndFallsBackWhenMissing() {
        assertEquals("Download/byron/", DownloadOutput.relativePath(listOf("../custom", "byron", "other")))
        assertEquals(versionedPath, DownloadOutput.relativePath(listOf("byron"), "os4.0.5.0"))
        assertEquals("Download/byron/", DownloadOutput.relativePath(listOf("byron"), "../another"))
        assertEquals("Download/PayloadDumper/", DownloadOutput.relativePath(emptyList(), "os4.0.5.0"))
        assertEquals("Download/PayloadDumper/", DownloadOutput.relativePath(listOf("..", "/emulated/0")))
    }

    @Test
    fun publishesTheImageAfterCopyingItToTheDeviceFolder() = runBlocking {
        val path = publish()

        assertEquals("${versionedPath}system.img", path)
        assertEquals(versionedPath, provider.row?.getAsString(RELATIVE_PATH))
        assertEquals(0, provider.row?.getAsInteger(IS_PENDING))
        assertArrayEquals(bytes, provider.bytesAtPublication)
    }

    @Test
    fun existingImageInTheSameDeviceAndVersionFolderBlocksTheCopy() {
        provider.existingName = "system.img"
        assertThrows(FileAlreadyExistsException::class.java) { runBlocking { publish() } }
        assertEquals(0, provider.inserts)
    }

    @Test
    fun existingImageFromADifferentVersionDoesNotBlockTheCopy() = runBlocking {
        provider.existingName = "system.img"
        provider.existingPath = "Download/byron os4.0.4.0/"

        assertEquals("${versionedPath}system.img", publish())
        assertEquals(1, provider.publications)
    }

    @Test
    fun removesThePendingImageAfterAWriteFailure() {
        provider.output = object : OutputStream() {
            override fun write(b: Int) = throw IOException("Volume full")
        }

        assertThrows(IOException::class.java) { runBlocking { publish() } }
        assertEquals(null, provider.row)
        assertEquals(0, provider.publications)
    }

    @Test
    fun removesTheNewRowIfMediaStoreChangedTheFilename() {
        provider.publishedName = "system (1).img"

        assertThrows(FileAlreadyExistsException::class.java) { runBlocking { publish() } }
        assertEquals(null, provider.row)
    }

    @Test
    fun cancellationRemovesThePendingImage() = runBlocking {
        val copying = CountDownLatch(1)
        val unblock = CountDownLatch(1)
        provider.output = object : OutputStream() {
            override fun write(b: Int) = Unit
            override fun write(b: ByteArray, off: Int, len: Int) {
                copying.countDown()
                check(unblock.await(5, TimeUnit.SECONDS))
            }
        }
        val job = launch(Dispatchers.IO) { publish() }
        try {
            assertTrue(copying.await(5, TimeUnit.SECONDS))
            job.cancel()
        } finally {
            unblock.countDown()
        }
        job.join()

        assertEquals(null, provider.row)
        assertEquals(0, provider.publications)
    }

    private suspend fun publish(): String =
        DownloadOutput.publish(
            context, listOf("byron"), "os4.0.5.0", "system.img",
            temp.newFile().apply { writeBytes(bytes) },
        )

    private inner class TestDownloadsProvider : ContentProvider() {
        var row: ContentValues? = null
        var output: OutputStream = ByteArrayOutputStream()
        var bytesAtPublication: ByteArray? = null
        var existingName: String? = null
        var existingPath = versionedPath
        var publishedName: String? = null
        var inserts = 0
        var publications = 0

        override fun onCreate(): Boolean = true

        override fun getType(uri: Uri): String = "application/octet-stream"

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor = MatrixCursor(arrayOf(DISPLAY_NAME)).apply {
            val name = if (uri == image) {
                row?.getAsString(DISPLAY_NAME)
            } else {
                check(uri == collection)
                check(selection == "$RELATIVE_PATH = ? AND $DISPLAY_NAME = ?")
                existingName?.takeIf {
                    selectionArgs?.get(0) == existingPath && selectionArgs[1] == it
                }
            }
            name?.let { addRow(arrayOf(it)) }
        }

        override fun insert(uri: Uri, values: ContentValues?): Uri {
            check(uri == collection)
            check(values?.getAsInteger(IS_PENDING) == 1)
            inserts++
            row = ContentValues(values)
            shadowOf(requireNotNull(context).contentResolver).registerOutputStream(image, output)
            return image
        }

        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?,
        ): Int {
            check(uri == image)
            check(values?.getAsInteger(IS_PENDING) == 0)
            val pending = requireNotNull(row)
            check(pending.getAsInteger(IS_PENDING) == 1)
            publications++
            bytesAtPublication = (output as? ByteArrayOutputStream)?.toByteArray()
            pending.put(IS_PENDING, 0)
            publishedName?.let { pending.put(DISPLAY_NAME, it) }
            return 1
        }

        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
            check(uri == image)
            row = null
            return 1
        }
    }
}
