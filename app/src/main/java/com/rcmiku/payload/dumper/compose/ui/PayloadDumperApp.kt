package com.rcmiku.payload.dumper.compose.ui

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.rcmiku.payload.dumper.compose.R
import com.rcmiku.payload.dumper.compose.model.PartitionInfo
import com.rcmiku.payload.dumper.compose.ui.component.AboutDialog
import com.rcmiku.payload.dumper.compose.ui.component.PartitionDialog
import com.rcmiku.payload.dumper.compose.ui.component.PartitionInfoItem
import com.rcmiku.payload.dumper.compose.ui.component.RomInfoCard
import com.rcmiku.payload.dumper.compose.ui.component.SourceImageSection
import com.rcmiku.payload.dumper.compose.utils.AppContextUtil
import com.rcmiku.payload.dumper.compose.utils.MiscUtil.getItemShape
import com.rcmiku.payload.dumper.compose.utils.MiscUtil.isValidUrl
import com.rcmiku.payload.dumper.compose.utils.PreferencesUtil
import com.rcmiku.payload.dumper.compose.viewModel.PayloadDumperViewModel
import dev.chrisbanes.haze.ExperimentalHazeApi
import dev.chrisbanes.haze.HazeEffectScope
import dev.chrisbanes.haze.HazeInputScale
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.LazyColumn
import top.yukonga.miuix.kmp.basic.ListPopup
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.ListPopupDefaults
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SearchBar
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.extra.DropdownImpl
import top.yukonga.miuix.kmp.extra.SuperArrow
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.icons.ImmersionMore
import top.yukonga.miuix.kmp.icon.icons.Search
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.MiuixPopupUtil.Companion.dismissPopup

@OptIn(ExperimentalHazeApi::class)
@Composable
fun PayloadDumperApp(viewModel: PayloadDumperViewModel) {

    val scrollBehavior = MiuixScrollBehavior()
    val hazeState = remember { HazeState() }
    val hazeStyleTopAppBar = HazeStyle(
        blurRadius = 25.dp,
        backgroundColor = if (scrollBehavior.state.heightOffset > -1) Color.Transparent else MiuixTheme.colorScheme.background,
        tint = HazeTint(
            MiuixTheme.colorScheme.background.copy(
                if (scrollBehavior.state.heightOffset > -1) 1f
                else lerp(1f, 0.67f, (scrollBehavior.state.heightOffset + 1) / -143f)
            )
        )
    )
    val coroutineScope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val pathOrUrl =
        rememberSaveable { mutableStateOf(PreferencesUtil().perfGet("pathOrUrl") ?: "") }
    val partitionInfoList by viewModel.cachePartitionInfoList.collectAsState()
    val allPartitionInfoList by viewModel.allPartitionInfoList.collectAsState()
    val archiveInfo = viewModel.archiveInfo.collectAsState()
    val payload = viewModel.payload.collectAsState()
    val selectedPartitionInfo = remember { mutableStateOf<PartitionInfo?>(null) }
    val showDialog = remember { mutableStateOf(false) }
    val showAboutDialog = remember { mutableStateOf(false) }
    val sourceImagePartitions by viewModel.sourceImagePartitions.collectAsState()
    val batchProgress by viewModel.batchProgress.collectAsState()
    // Partitions the user has checked for batch extraction. Kept in the composable (not the
    // ViewModel) because it is pure UI state; the ViewModel only learns about it on "Dump Selected".
    var selection by remember { mutableStateOf(setOf<String>()) }
    // Partition awaiting an imported old image (drives the OpenDocument launcher).
    var pendingSourcePartition by remember { mutableStateOf<String?>(null) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    val showTopPopup = rememberSaveable { mutableStateOf(false) }
    val isTopPopupExpanded = rememberSaveable { mutableStateOf(false) }
    val searchValue = viewModel.search.collectAsState()
    val isParsing by viewModel.isParsing.collectAsState()

    // Whether the link/file input section is visible. Hidden once a payload has been parsed
    // successfully, restored when the user asks to change the input.
    var showInput by rememberSaveable { mutableStateOf(true) }
    // Whether the inline URL text field is shown. Tapping "From URL" reveals it in place; the parse
    // button sits in the same area so no extra navigation is needed. Kept apart from [showInput]:
    // [showInput] controls the whole section (shown after "Change OTA link or file"), this one only
    // the text field inside it.
    var showUrlField by rememberSaveable { mutableStateOf(false) }
    // Draft of the URL being typed. The committed value stays in [pathOrUrl] (persisted) until a
    // successful parse, so an abandoned/edited draft never becomes the parsed input by accident.
    var urlDraft by rememberSaveable { mutableStateOf("") }

    // Pick an OTA ZIP / payload.bin via SAF and remember the content Uri directly (no all-files
    // access needed).
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri: Uri? ->
            uri?.let {
                val flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                runCatching {
                    AppContextUtil.context.contentResolver.takePersistableUriPermission(it, flags)
                }
                pathOrUrl.value = it.toString()
                PreferencesUtil().perfSet("pathOrUrl", pathOrUrl.value)
                // A picked file replaces the URL flow; hide the text field so the input section
                // (and the "Change OTA link or file" action) is restored next time.
                showUrlField = false
            }
        }
    )

    // Import an old partition image for delta extraction; remember which partition it is for.
    val sourceImageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        val partition = pendingSourcePartition
        pendingSourcePartition = null
        if (uri != null && partition != null) {
            runCatching {
                AppContextUtil.context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            viewModel.setSourceImage(partition, uri)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                color = Color.Transparent,
                modifier = Modifier
                    .hazeEffect(state = hazeState,
                        style = hazeStyleTopAppBar,
                        block = fun HazeEffectScope.() {
                            inputScale = HazeInputScale.Auto
                        }),
                title = stringResource(R.string.app_name),
                actions = {
                    if (isTopPopupExpanded.value) {
                        ListPopup(
                            show = showTopPopup,
                            popupPositionProvider = ListPopupDefaults.ContextMenuPositionProvider,
                            alignment = PopupPositionProvider.Align.TopRight,
                            onDismissRequest = {
                                isTopPopupExpanded.value = false
                            }
                        ) {
                            ListPopupColumn {
                                DropdownImpl(
                                    text = stringResource(R.string.about_dialog_title),
                                    optionSize = 1,
                                    isSelected = false,
                                    onSelectedIndexChange = {
                                        dismissPopup(showTopPopup)
                                        isTopPopupExpanded.value = false
                                        showAboutDialog.value = true
                                    },
                                    index = 0,
                                )
                            }
                        }
                        showTopPopup.value = true
                    }
                    IconButton(
                        modifier = Modifier
                            .padding(end = 18.dp)
                            .size(40.dp),
                        onClick = {
                            isTopPopupExpanded.value = true
                        }
                    ) {
                        Icon(
                            imageVector = MiuixIcons.ImmersionMore,
                            contentDescription = "Menu"
                        )
                    }
                },
                scrollBehavior = scrollBehavior
            )
        },
        contentWindowInsets = WindowInsets.safeDrawing
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxHeight()
                .hazeSource(state = hazeState),
            topAppBarScrollBehavior = scrollBehavior,
            contentPadding = padding
        ) {
            item {
                SearchBar(
                    modifier = Modifier
                        .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 6.dp),
                    inputField = {
                        InputField(
                            query = searchValue.value,
                            onQueryChange = {
                                viewModel.updateSearch(it)
                            },
                            onSearch = {
                                keyboardController?.hide()
                            },
                            expanded = expanded,
                            onExpandedChange = { expanded = it },
                            label = stringResource(R.string.search),
                            leadingIcon = {
                                Icon(
                                    modifier = Modifier.padding(
                                        start = 12.dp,
                                        end = 8.dp,
                                        top = 14.dp,
                                        bottom = 14.dp
                                    ),
                                    imageVector = MiuixIcons.Search,
                                    tint = MiuixTheme.colorScheme.onSurfaceContainer,
                                    contentDescription = "Search"
                                )
                            },
                        )
                    },
                    outsideRightAction = {
                        Text(
                            modifier = Modifier
                                .padding(start = 12.dp)
                                .clickable(
                                    interactionSource = null,
                                    indication = null
                                ) {
                                    expanded = false
                                    viewModel.updateSearch("")
                                },
                            text = stringResource(R.string.cancel),
                            color = MiuixTheme.colorScheme.primary
                        )
                    },
                    expanded = expanded,
                    onExpandedChange = {
                        expanded = it
                    }
                ) {
                }
                AnimatedVisibility(
                    visible = !expanded,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    Column(
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .padding(top = 12.dp)
                    ) {
                        if (showInput) {
                            Card(
                                modifier = Modifier.padding(bottom = 12.dp)
                            ) {
                                SuperArrow(
                                    leftAction = {
                                        Box(
                                            contentAlignment = Alignment.TopStart,
                                            modifier = Modifier.padding(end = 16.dp)
                                        ) {
                                            Icon(
                                                painter = painterResource(R.drawable.ic_local),
                                                contentDescription = "local",
                                                tint = Color.Unspecified
                                            )
                                        }
                                    },
                                    enabled = !isParsing,
                                    title = stringResource(R.string.from_local),
                                    onClick = {
                                        launcher.launch(arrayOf("application/zip", "application/octet-stream"))
                                    }
                                )
                                SuperArrow(
                                    leftAction = {
                                        Box(
                                            contentAlignment = Alignment.TopStart,
                                            modifier = Modifier.padding(end = 16.dp)
                                        ) {
                                            Icon(
                                                painter = painterResource(R.drawable.ic_url),
                                                contentDescription = "url",
                                                tint = Color.Unspecified
                                            )
                                        }
                                    },
                                    enabled = !isParsing,
                                    title = stringResource(R.string.from_url),
                                    onClick = {
                                        // Reveal the inline URL field in place; no dialog, no navigation.
                                        urlDraft = pathOrUrl.value
                                        showUrlField = true
                                    }
                                )
                            }

                            // One input + parse area: the URL field in link mode, or the picked
                            // file in local mode, with the parse button in the same card so input
                            // and submit are a single interaction.
                            if (showUrlField || pathOrUrl.value.isNotEmpty()) {
                                Card(
                                    modifier = Modifier.padding(bottom = 12.dp),
                                    insideMargin = PaddingValues(horizontal = 12.dp)
                                ) {
                                    if (showUrlField) {
                                        TextField(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(top = 12.dp)
                                                .padding(bottom = 12.dp),
                                            value = urlDraft,
                                            onValueChange = { urlDraft = it },
                                            // Long OTA links with query strings wrap instead of
                                            // scrolling out of view; the value is stored verbatim,
                                            // never split or rewritten.
                                            minLines = 2,
                                            enabled = !isParsing,
                                            label = stringResource(R.string.ota_url)
                                        )
                                    } else {
                                        // A local file was picked; show it read-only so the user can
                                        // confirm the source before parsing.
                                        Text(
                                            modifier = Modifier
                                                .padding(top = 12.dp)
                                                .padding(bottom = 12.dp),
                                            text = pathOrUrl.value,
                                            style = MiuixTheme.textStyles.body1,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                    TextButton(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(bottom = 12.dp),
                                        text = stringResource(
                                            if (isParsing) R.string.parsing_message else R.string.parse
                                        ),
                                        enabled = !isParsing,
                                        onClick = {
                                            if (viewModel.isParsing.value) return@TextButton
                                            val parsingUrl = showUrlField
                                            val input = if (parsingUrl) urlDraft else pathOrUrl.value
                                            if (input.isBlank() || (parsingUrl && !input.isValidUrl())) {
                                                Toast.makeText(
                                                    AppContextUtil.context,
                                                    AppContextUtil.context.getString(R.string.url_error_message),
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                                return@TextButton
                                            }

                                            focusManager.clearFocus()
                                            keyboardController?.hide()
                                            coroutineScope.launch {
                                                viewModel.parsePayload(input) {
                                                    if (parsingUrl) {
                                                        pathOrUrl.value = input
                                                        PreferencesUtil().perfSet("pathOrUrl", input)
                                                    }
                                                    selection = emptySet()
                                                    showInput = false
                                                    showUrlField = false
                                                }
                                            }
                                        },
                                        colors = ButtonDefaults.textButtonColorsPrimary()
                                    )
                                }
                            }

                            if (isParsing) {
                                LinearProgressIndicator(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                    color = MiuixTheme.colorScheme.primary,
                                    trackColor = MiuixTheme.colorScheme.secondaryVariant,
                                )
                            }
                        } else {
                            // The input section is hidden after a successful parse; this entry
                            // brings it back so the user can change the OTA link or file.
                            SuperArrow(
                                leftAction = {
                                    Box(
                                        contentAlignment = Alignment.TopStart,
                                        modifier = Modifier.padding(end = 16.dp)
                                    ) {
                                        Icon(
                                            painter = painterResource(R.drawable.ic_url),
                                            contentDescription = "change link",
                                            tint = Color.Unspecified
                                        )
                                    }
                                },
                                title = stringResource(R.string.change_link),
                                onClick = {
                                    // Reset to the neutral input section: clear the committed source
                                    // so an old URL/file is never silently reused, and let the user
                                    // pick local or link again.
                                    pathOrUrl.value = ""
                                    PreferencesUtil().perfSet("pathOrUrl", "")
                                    showUrlField = false
                                    showInput = true
                                }
                            )
                        }
                        archiveInfo.value?.let { RomInfoCard(archiveInfo = it) }
                        val deltaPartitions = allPartitionInfoList
                            .filter { it.needsSourceImage }
                            .map { it.partitionName }
                        SourceImageSection(
                            neededPartitions = deltaPartitions,
                            suppliedPartitions = sourceImagePartitions,
                            onImport = { name ->
                                pendingSourcePartition = name
                                sourceImageLauncher.launch(arrayOf("application/octet-stream"))
                            },
                            onRemove = { name -> viewModel.removeSourceImage(name) },
                        )
                    }
                }
                if (partitionInfoList.isNotEmpty())
                    SmallTitle(stringResource(R.string.image_list))
            }

            // Batch toolbar: select all / clear, and dump the checked partitions in one action.
            if (allPartitionInfoList.isNotEmpty()) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val allNames = allPartitionInfoList.map { it.partitionName }
                            TextButton(
                                modifier = Modifier.weight(1f),
                                text = if (selection.size == allNames.size && allNames.isNotEmpty())
                                    stringResource(R.string.clear_selection)
                                else stringResource(R.string.select_all),
                                onClick = {
                                    selection = if (selection.size == allNames.size && allNames.isNotEmpty()) {
                                        emptySet()
                                    } else {
                                        allNames.toSet()
                                    }
                                },
                            )
                            TextButton(
                                modifier = Modifier.weight(1f),
                                text = stringResource(R.string.dump_selected, selection.size),
                                enabled = selection.isNotEmpty(),
                                colors = ButtonDefaults.textButtonColorsPrimary(),
                                onClick = {
                                    selection.forEach { viewModel.startDump(it) }
                                },
                            )
                        }
                        // Overall progress across the partitions currently being extracted.
                        if (batchProgress > 0f && batchProgress < 1f) {
                            LinearProgressIndicator(
                                progress = { batchProgress },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }

            itemsIndexed(partitionInfoList) { index, partitionInfo ->
                val shape = getItemShape(
                    prevItem = partitionInfoList.getOrNull(index - 1),
                    nextItem = partitionInfoList.getOrNull(index + 1),
                    corner = 16.dp,
                    subCorner = 4.dp
                )
                androidx.compose.material3.Card(
                    shape = shape,
                    modifier = Modifier
                        .heightIn(min = 64.dp)
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 4.dp),
                    colors = CardDefaults.cardColors()
                        .copy(containerColor = MiuixTheme.colorScheme.surface)
                )
                {
                    PartitionInfoItem(
                        partitionInfo = partitionInfo,
                        selected = partitionInfo.partitionName in selection,
                        onClick = {
                            selectedPartitionInfo.value = partitionInfo
                            showDialog.value = true
                        },
                        onSelectedChange = { checked ->
                            selection = if (checked) {
                                selection + partitionInfo.partitionName
                            } else {
                                selection - partitionInfo.partitionName
                            }
                        },
                        onDownload = {
                            viewModel.startDump(partitionInfo.partitionName)
                        },
                        onCancel = {
                            viewModel.cancelDump(partitionInfo.partitionName)
                        })
                }
            }

            // Empty state: results exist but the active search keyword matches none of them.
            if (searchValue.value.isNotBlank() &&
                allPartitionInfoList.isNotEmpty() &&
                partitionInfoList.isEmpty()
            ) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.search_no_results),
                            color = MiuixTheme.colorScheme.onSecondaryVariant
                        )
                    }
                }
            }
        }
    }
    AboutDialog(showAboutDialog)
    selectedPartitionInfo.value?.let { PartitionDialog(showDialog, it) }
}