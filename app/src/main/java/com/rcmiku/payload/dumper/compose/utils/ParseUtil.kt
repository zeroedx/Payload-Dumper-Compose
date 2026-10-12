package com.rcmiku.payload.dumper.compose.utils

import android.net.Uri
import android.widget.Toast
import com.rcmiku.payload.dumper.compose.R
import com.rcmiku.payload.dumper.compose.model.Payload
import com.rcmiku.payload.dumper.compose.utils.MiscUtil.isReadable
import com.rcmiku.payload.dumper.compose.utils.MiscUtil.isValidUrl
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
            val source: SeekableInput
            val fileName: String
            when {
                pathOrUrl.isValidUrl() -> {
                    HttpUtil.init(pathOrUrl)
                    source = HttpUtil
                    fileName = HttpUtil.name
                }

                pathOrUrl.startsWith("content://") -> {
                    val uri = Uri.parse(pathOrUrl)
                    val input = UriSeekableInput(AppContextUtil.context, uri)
                    source = input
                    fileName = SafUtil.queryDisplayName(AppContextUtil.context, uri)
                        ?: "payload.bin"
                }

                pathOrUrl.isReadable() -> {
                    // Read-only: we only ever read from the OTA package here.
                    val file = File(pathOrUrl)
                    source = FileSeekableInput(RandomAccessFile(file, "r"))
                    fileName = file.name
                }

                else -> throw IllegalArgumentException("Unsupported path or URL: $pathOrUrl")
            }

            try {
                val payloadOffset = PayloadUtil.getPayloadOffset(source)
                val payload = PayloadUtil.initPayload(fileName, source, payloadOffset)
                InputSourceHolder.set(source)
                withContext(Dispatchers.Main) {
                    onParsed(payload)
                    toast(AppContextUtil.context, AppContextUtil.context.getString(R.string.parse_success_message))
                }
            } catch (t: Throwable) {
                if (InputSourceHolder.current === source) InputSourceHolder.clear()
                source.close()
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
