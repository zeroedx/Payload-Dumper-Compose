package com.rcmiku.payload.dumper.compose

import com.rcmiku.payload.dumper.compose.utils.SafUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** JVM tests for the SAF helpers (run under Robolectric for the Android Uri/DocumentsContract). */
@RunWith(RobolectricTestRunner::class)
class SafUtilTest {

    @Test
    fun downloadsTreeUri_pointsAtPrimaryDownloadRoot() {
        val uri = SafUtil.downloadsTreeUri()
        assertEquals("content", uri.scheme)
        assertEquals("com.android.externalstorage.documents", uri.authority)
        assertTrue("tree URI should carry the tree path segment", uri.pathSegments.contains("tree"))
        assertTrue(
            "default folder must be the Download root",
            uri.lastPathSegment?.contains("Download") == true
        )
    }
}
