package com.rcmiku.payload.dumper.compose

import com.rcmiku.payload.dumper.compose.model.PartitionInfo
import com.rcmiku.payload.dumper.compose.utils.filterPartitionInfoList
import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure-JVM tests for the parsed-result search filter. */
class SearchFilterTest {

    private fun partition(name: String) = PartitionInfo(partitionName = name, size = 0L, rawSize = 0L, sha256 = "")

    private val sample = listOf(partition("system"), partition("vendor"), partition("Boot"), partition("dtbo"))

    @Test
    fun blankQuery_returnsFullList() {
        assertEquals(sample, filterPartitionInfoList(sample, ""))
        assertEquals(sample, filterPartitionInfoList(sample, "   "))
    }

    @Test
    fun matchesSubstring() {
        assertEquals(listOf(partition("system")), filterPartitionInfoList(sample, "sys"))
    }

    @Test
    fun matchesCaseInsensitively() {
        // "boot" must match "Boot" regardless of case.
        assertEquals(listOf(partition("Boot")), filterPartitionInfoList(sample, "boot"))
        assertEquals(listOf(partition("Boot")), filterPartitionInfoList(sample, "BOOT"))
    }

    @Test
    fun trimsSurroundingWhitespace() {
        assertEquals(listOf(partition("system")), filterPartitionInfoList(sample, "  system  "))
    }

    @Test
    fun noMatch_returnsEmpty() {
        assertEquals(emptyList<PartitionInfo>(), filterPartitionInfoList(sample, "nonexistent"))
    }

    @Test
    fun emptyInput_returnsEmpty() {
        assertEquals(emptyList<PartitionInfo>(), filterPartitionInfoList(emptyList(), "sys"))
        assertEquals(emptyList<PartitionInfo>(), filterPartitionInfoList(emptyList(), ""))
    }

    @Test
    fun filtering_doesNotMutateOriginalList() {
        val original = sample.toList()
        filterPartitionInfoList(sample, "sys")
        // The source list must be untouched: search only affects the derived view.
        assertEquals(original, sample)
    }
}
