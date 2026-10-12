package com.rcmiku.payload.dumper.compose

import com.rcmiku.payload.dumper.compose.utils.HashUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HashUtilTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun sha256Hex_matchesKnownVectorForEmptyString() {
        // Well-known SHA-256 of the empty input.
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            HashUtil.sha256Hex(ByteArray(0))
        )
    }

    @Test
    fun sha256Hex_matchesKnownVectorForAbc() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            HashUtil.sha256Hex("abc".toByteArray())
        )
    }

    @Test
    fun sha256Hex_ofFile_matchesByteArrayVersion() {
        val content = ByteArray(200_000) { (it % 251).toByte() }
        val file = tmp.newFile("blob.bin")
        file.writeBytes(content)

        assertEquals(HashUtil.sha256Hex(content), HashUtil.sha256Hex(file))
    }

    @Test
    fun matches_isCaseInsensitiveAndTrims() {
        val hex = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        assertTrue(HashUtil.matches(hex, hex.uppercase()))
        assertTrue(HashUtil.matches("  $hex  ", hex))
        assertFalse(HashUtil.matches(hex, hex.dropLast(1) + "0"))
    }
}
