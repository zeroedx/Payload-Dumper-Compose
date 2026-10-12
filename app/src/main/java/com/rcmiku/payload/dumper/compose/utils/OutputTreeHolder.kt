package com.rcmiku.payload.dumper.compose.utils

import android.content.Context
import android.net.Uri

/**
 * Restores the persisted destination folder (a SAF tree Uri) chosen by the user. Persisting the
 * string is enough because the app holds a persisted read/write grant for it.
 */
object OutputTreeHolder {

    private const val KEY = "customFolder"

    fun restore(context: Context): Uri? {
        val value = PreferencesUtil().perfGet(KEY) ?: return null
        return runCatching { Uri.parse(value) }.getOrNull()
    }
}
