package com.rcmiku.payload.dumper.compose.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.rcmiku.payload.dumper.compose.R
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Shown only for delta payloads: lets the user import the old partition images a delta extraction
 * reads from, one per partition, and shows which partitions still need one.
 */
@Composable
fun SourceImageSection(
    neededPartitions: List<String>,
    suppliedPartitions: Set<String>,
    onImport: (String) -> Unit,
    onRemove: (String) -> Unit,
) {
    if (neededPartitions.isEmpty()) return
    Card(modifier = Modifier.padding(bottom = 12.dp)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = stringResource(R.string.delta_source_title),
                style = MiuixTheme.textStyles.title4,
            )
            Text(
                modifier = Modifier.padding(top = 4.dp),
                text = stringResource(R.string.delta_source_summary),
                style = MiuixTheme.textStyles.subtitle,
                color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
            )
            neededPartitions.forEach { name ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        modifier = Modifier.weight(1f),
                        text = name,
                        style = MiuixTheme.textStyles.body1,
                    )
                    if (name in suppliedPartitions) {
                        TextButton(
                            text = stringResource(R.string.delta_source_remove),
                            onClick = { onRemove(name) },
                        )
                    } else {
                        TextButton(
                            text = stringResource(R.string.delta_source_import),
                            onClick = { onImport(name) },
                        )
                    }
                }
            }
        }
    }
}
