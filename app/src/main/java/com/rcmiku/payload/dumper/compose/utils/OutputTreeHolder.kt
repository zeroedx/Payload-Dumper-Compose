package com.rcmiku.payload.dumper.compose.utils

import android.content.Context
import android.net.Uri

/**
 * Persists and restores the destination folder (a SAF tree Uri) chosen by the user.
 *
 * Persisting the string is enough because the app also holds a persisted read/write grant for it;
 * [restore] verifies that grant is still present and clears the entry if it is not, so a stale or
 * revoked folder can never be silently reused. A folder saved by an older build under the legacy
 * key is migrated on first restore so the upgrade does not force a re-pick.
 */
object OutputTreeHolder {

    private const val KEY = "outputTreeUri"
    private const val LEGACY_KEY = "customFolder"

    fun save(uri: Uri) {
        PreferencesUtil().perfSet(KEY, uri.toString())
    }

    fun clear() {
        PreferencesUtil().perfRemove(KEY)
    }

    fun restore(context: Context): Uri? {
        val stored = PreferencesUtil().perfGet(KEY)
            ?: PreferencesUtil().perfGet(LEGACY_KEY)?.also { migrate(it) }
            ?: return null
        return validUri(context, stored)
    }

    private fun migrate(legacyValue: String) {
        PreferencesUtil().perfSet(KEY, legacyValue)
        PreferencesUtil().perfRemove(LEGACY_KEY)
    }

    private fun validUri(context: Context, value: String): Uri? {
        val uri = runCatching { Uri.parse(value) }.getOrNull()
        if (uri == null || !SafUtil.hasWritePermission(context, uri)) {
            // The entry is malformed or its grant has been revoked/expired: drop it so first-use
            // guidance (and the "select output folder" prompt) runs again.
            clear()
            return null
        }
        return uri
    }
}
