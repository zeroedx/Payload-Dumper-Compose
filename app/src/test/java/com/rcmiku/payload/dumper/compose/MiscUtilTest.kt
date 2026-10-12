package com.rcmiku.payload.dumper.compose

import com.google.protobuf.ByteString
import com.rcmiku.payload.dumper.compose.utils.MiscUtil.isValidUrl
import com.rcmiku.payload.dumper.compose.utils.MiscUtil.toHexString
import com.rcmiku.payload.dumper.compose.utils.MiscUtil.toInt
import com.rcmiku.payload.dumper.compose.utils.MiscUtil.toLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-JVM tests for [com.rcmiku.payload.dumper.compose.utils.MiscUtil]. */
class MiscUtilTest {

    @Test
    fun byteArray_toLong_isBigEndian() {
        assertEquals(0x0102L, byteArrayOf(0x01, 0x02).toLong())
        assertEquals(0L, ByteArray(0).toLong())
        assertEquals(0xFFFFFFFFL, ByteArray(4) { 0xFF.toByte() }.toLong())
        assertEquals(
            0x0102030405060708L,
            byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8).toLong()
        )
    }

    @Test
    fun byteArray_toInt_isBigEndian() {
        assertEquals(0x0102, byteArrayOf(0x01, 0x02).toInt())
        assertEquals(4096, byteArrayOf(0x00, 0x00, 0x10, 0x00).toInt())
    }

    @Test
    fun byteString_toHexString_isLowercaseAndZeroPadded() {
        assertEquals("00ff10", ByteString.copyFrom(byteArrayOf(0, 0xFF.toByte(), 0x10)).toHexString())
        assertEquals("", ByteString.EMPTY.toHexString())
        // 32-byte SHA-256 style length is preserved.
        assertEquals(64, ByteString.copyFrom(ByteArray(32) { 0xAB.toByte() }).toHexString().length)
    }

    @Test
    fun isValidUrl_acceptsHttpAndHttpsRejectsJunk() {
        assertTrue("https://example.com/ota.zip".isValidUrl())
        assertTrue("http://example.com/ota.zip".isValidUrl())
        assertFalse("".isValidUrl())
        assertFalse("/storage/emulated/0/Download/ota.zip".isValidUrl())
        assertFalse("not a url".isValidUrl())
    }
}
