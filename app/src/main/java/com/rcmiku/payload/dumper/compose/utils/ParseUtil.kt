package com.rcmiku.payload.dumper.compose.utils

import android.net.Uri
import android.widget.Toast
import com.rcmiku.payload.dumper.compose.R
import com.rcmiku.payload.dumper.compose.model.Payload
import com.rcmiku.payload.dumper.compose.utils.MiscUtil.isReadable
import com.rcmiku.payload.dumper.compose.utils.MiscUtil.isValidUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile

/**
 * Builds a [SeekableInput] for [pathOrUrl] and parses the payload manifest.
 *
 * A plain filesystem path is opened as a seekable file; a `content://` URI is opened read-only
 * through SAF; an `http(s)://` URL uses the shared [HttpUtil] Range reader.
 */
object ParseUtil {

    suspend fun loadPayload(pathOrUrl: String, onParsed: suspend (Payload) -> Unit) {
        withContext(Dispatchers.IO) {
            var openedSource: SeekableInput? = null
            try {
                val (source, fileName) = when {
                    pathOrUrl.isValidUrl() -> {
                        openedSource = HttpUtil
                        HttpUtil.init(pathOrUrl)
                        HttpUtil to HttpUtil.name
                    }

                    pathOrUrl.startsWith("content://") -> {
                        val uri = Uri.parse(pathOrUrl)
                        val input = UriSeekableInput(AppContextUtil.context, uri)
                        openedSource = input
                        val name = SafUtil.queryDisplayName(AppContextUtil.context, uri) ?: "payload.bin"
                        input to name
                    }

                    pathOrUrl.isReadable() -> {
                        val file = File(pathOrUrl)
                        val input = FileSeekableInput(RandomAccessFile(file, "r"))
                        openedSource = input
                        input to file.name
                    }

                    else -> throw IllegalArgumentException("Unsupported path or URL: $pathOrUrl")
                }

                val (payloadOffset, directory) = PayloadUtil.locatePayload(source)
                // Best-effort: a missing/corrupt metadata entry must never fail a valid parse.
                val packageInfo = directory?.let { OtaMetadataParser.readPackageInfo(source, it) }
                val payload = PayloadUtil.initPayload(fileName, source, payloadOffset).copy(
                    deviceModels = packageInfo?.deviceModels.orEmpty(),
                    systemVersion = packageInfo?.systemVersion(fileName),
                )
                InputSourceHolder.set(source)
                withContext(Dispatchers.Main) {
                    onParsed(payload)
                    toast(AppContextUtil.context, AppContextUtil.context.getString(R.string.parse_success_message))
                }
            } catch (t: Exception) {
                val source = openedSource
                if (source != null && InputSourceHolder.current === source) {
                    InputSourceHolder.clear()
                } else {
                    source?.close()
                }
                if (t is CancellationException) throw t
                val appContext = AppContextUtil.context
                withContext(Dispatchers.Main) {
                    toast(appContext, t.message ?: appContext.getString(R.string.url_error_message))
                }
            }
        }
    }

    private fun toast(context: android.content.Context, message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

}
