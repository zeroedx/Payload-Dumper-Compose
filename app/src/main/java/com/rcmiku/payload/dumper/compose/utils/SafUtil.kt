package com.rcmiku.payload.dumper.compose.utils

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns

/** Metadata helpers for OTA packages and source images opened through SAF. */
object SafUtil {

    /** Best-effort display name for a document [uri]. */
    fun queryDisplayName(context: Context, uri: Uri): String? {
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) {
                    return cursor.getString(index)
                }
            }
        }
        return uri.lastPathSegment
    }

    /** Best-effort size of a document [uri], or -1 when the provider does not report one. */
    fun queryDisplaySize(context: Context, uri: Uri): Long {
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (index >= 0 && cursor.moveToFirst() && !cursor.isNull(index)) {
                    return cursor.getLong(index)
                }
            }
        }
        return -1L
    }
}
