package com.rcmiku.payload.dumper.compose.utils

/**
 * Suggests which partition an imported file belongs to, based on its display name.
 *
 * This is only a hint used to pre-fill the mapping: a name that matches nothing is kept as-is so
 * the user can assign it manually, and the app never guesses a partition from an unrelated file.
 */
object PartitionMapping {

    /** The partition a `foo.img` / `foo.bin` file most likely belongs to, or null if unclear. */
    fun partitionForFileName(fileName: String, partitionNames: Collection<String>): String? {
        val base = fileName
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .removeSuffix(".img")
            .removeSuffix(".bin")
        if (base.isEmpty()) return null
        return partitionNames.firstOrNull { it == base }
    }
}
