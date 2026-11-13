package com.rcmiku.payload.dumper.compose

import android.content.Intent
import android.net.Uri
import com.rcmiku.payload.dumper.compose.utils.AppContextUtil
import com.rcmiku.payload.dumper.compose.utils.OutputTreeHolder
import com.rcmiku.payload.dumper.compose.utils.PreferencesUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Tests for the persisted output-folder holder: the write grant is honoured, a value saved under
 * the legacy key is migrated, and a folder whose grant is gone is dropped so first-use guidance
 * runs again.
 */
@RunWith(RobolectricTestRunner::class)
class OutputTreeHolderTest {

    private val context = RuntimeEnvironment.getApplication()

    private fun grantWrite(uri: Uri) {
        context.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
    }

    @Before
    fun setUp() {
        AppContextUtil.init(context)
        OutputTreeHolder.clear()
        PreferencesUtil().perfRemove("customFolder")
    }

    @Test
    fun restoresAFolderThatStillHasAWriteGrant() {
        val uri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ADownload")
        grantWrite(uri)
        OutputTreeHolder.save(uri)

        assertEquals(uri, OutputTreeHolder.restore(context))
    }

    @Test
    fun migratesAValueSavedUnderTheLegacyKey() {
        val uri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ADownload")
        grantWrite(uri)
        PreferencesUtil().perfSet("customFolder", uri.toString())

        assertEquals(uri, OutputTreeHolder.restore(context))
        // The legacy key is gone and the new key now holds the value.
        assertNull(PreferencesUtil().perfGet("customFolder"))
        assertEquals(uri.toString(), PreferencesUtil().perfGet("outputTreeUri"))
    }

    @Test
    fun dropsAFolderWhoseGrantIsGone() {
        val uri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ADownload")
        OutputTreeHolder.save(uri) // saved without a persisted grant

        assertNull(OutputTreeHolder.restore(context))
        assertNull(PreferencesUtil().perfGet("outputTreeUri"))
    }
}
