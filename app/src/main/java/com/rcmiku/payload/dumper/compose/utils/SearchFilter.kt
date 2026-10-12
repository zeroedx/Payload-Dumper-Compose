package com.rcmiku.payload.dumper.compose.utils

import com.rcmiku.payload.dumper.compose.model.PartitionInfo

/**
 * Filters [list] by a case-insensitive substring match on the partition name. A blank (or
 * whitespace-only) query returns the original list unchanged, so clearing the search box always
 * restores the full result set. Pure and side-effect free so it can be unit tested directly.
 */
fun filterPartitionInfoList(list: List<PartitionInfo>, query: String): List<PartitionInfo> {
    val keyword = query.trim()
    if (keyword.isEmpty()) return list
    return list.filter { it.partitionName.contains(keyword, ignoreCase = true) }
}
