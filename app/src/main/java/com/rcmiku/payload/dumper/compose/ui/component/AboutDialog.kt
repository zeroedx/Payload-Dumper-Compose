package com.rcmiku.payload.dumper.compose.ui.component

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.ClickableText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.accompanist.drawablepainter.rememberDrawablePainter
import com.rcmiku.payload.dumper.compose.BuildConfig
import com.rcmiku.payload.dumper.compose.R
import com.rcmiku.payload.dumper.compose.utils.AppContextUtil
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.extra.SuperDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.MiuixPopupUtil.Companion.dismissDialog

/** Link annotation tag used to make the repository URL clickable. */
private const val URL_TAG = "URL"

/** Opens [url] in an external viewer; does nothing (and never crashes) if none is available. */
private fun openExternalUrl(url: String) {
    runCatching {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        AppContextUtil.context.startActivity(intent)
    }
}

@Composable
fun AboutDialog(showDialog: MutableState<Boolean>) {

    val context = AppContextUtil.context
    val packageManager: PackageManager = context.packageManager
    val applicationInfo = context.applicationInfo
    val icon = packageManager.getApplicationIcon(applicationInfo)

    val repositoryUrl = stringResource(R.string.repository_url)
    val repositoryLabel = stringResource(R.string.repository_label)

    SuperDialog(
        show = showDialog,
        title = stringResource(R.string.about_dialog_title),
        onDismissRequest = {
            dismissDialog(showDialog)
        }
    ) {
        Row(
            modifier = Modifier.padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Image(
                painter = rememberDrawablePainter(icon),
                contentDescription = null,
                modifier = Modifier.size(48.dp)
            )
            Column {
                Text(
                    text = stringResource(R.string.app_name),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "v${BuildConfig.VERSION_NAME}",
                )
            }
        }
        val descriptionString = buildAnnotatedString {
            append(stringResource(R.string.developer)); append(stringResource(R.string.author))
            append("\n")
            append(stringResource(R.string.source_code))
            pushStringAnnotation(tag = URL_TAG, annotation = repositoryUrl)
            pushStyle(
                SpanStyle(
                    color = MiuixTheme.colorScheme.primary,
                    textDecoration = TextDecoration.Underline,
                    fontWeight = FontWeight.Medium
                )
            )
            append(repositoryLabel)
            pop()
            pop()
            append("\n\n")
            append(stringResource(R.string.app_info))
        }
        ClickableText(
            modifier = Modifier.padding(top = 10.dp),
            text = descriptionString,
            style = MiuixTheme.textStyles.body1,
            onClick = { offset ->
                descriptionString.getStringAnnotations(URL_TAG, offset, offset)
                    .firstOrNull()
                    ?.let { openExternalUrl(it.item) }
            }
        )
    }
}