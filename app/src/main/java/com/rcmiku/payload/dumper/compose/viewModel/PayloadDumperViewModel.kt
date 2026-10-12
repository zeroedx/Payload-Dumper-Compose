package com.rcmiku.payload.dumper.compose.viewModel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rcmiku.payload.dumper.compose.model.ArchiveInfo
import com.rcmiku.payload.dumper.compose.model.PartitionInfo
import com.rcmiku.payload.dumper.compose.model.Payload
import com.rcmiku.payload.dumper.compose.model.VerifyState
import android.net.Uri
import com.rcmiku.payload.dumper.compose.utils.DumpUtil
import com.rcmiku.payload.dumper.compose.utils.HttpUtil
import com.rcmiku.payload.dumper.compose.utils.InputSourceHolder
import com.rcmiku.payload.dumper.compose.utils.PayloadUtil
import com.rcmiku.payload.dumper.compose.utils.filterPartitionInfoList
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class PayloadDumperViewModel : ViewModel() {

    private val _partitionInfoList = MutableStateFlow<List<PartitionInfo>>(emptyList())
    private val _cachePartitionInfoList = MutableStateFlow<List<PartitionInfo>>(emptyList())

    /** Full, unfiltered parsed partition list (never mutated by search). */
    val allPartitionInfoList: StateFlow<List<PartitionInfo>> = _partitionInfoList

    /** Filtered view used by the result list. */
    val cachePartitionInfoList: StateFlow<List<PartitionInfo>> = _cachePartitionInfoList

    private var _archiveInfo = MutableStateFlow<ArchiveInfo?>(null)
    val archiveInfo: StateFlow<ArchiveInfo?> = _archiveInfo
    private var _payload = MutableStateFlow<Payload?>(null)
    val payload: StateFlow<Payload?> = _payload
    private val _search = MutableStateFlow("")
    val search: StateFlow<String> = _search

    fun initPayloadDumper(payload: Payload) {
        viewModelScope.launch {
            // A new input replaces the previous result set; clear any stale search keyword so an
            // old query can never hide the new partitions.
            _search.value = ""
            _payload.value = payload
            _archiveInfo.value = ArchiveInfo(
                payload.fileName,
                payload.archiveSize,
                payload.deltaArchiveManifest.securityPatchLevel
            )
            _partitionInfoList.value = PayloadUtil.getPartitionInfoList(payload)
            _cachePartitionInfoList.value = _partitionInfoList.value
        }
    }

    fun updateProgress(partitionName: String, progress: Float) {
        _partitionInfoList.value = _partitionInfoList.value.map {
            if (it.partitionName == partitionName) {
                it.copy(progress = progress)
            } else {
                it
            }
        }
    }

    fun updateDownloadState(partitionName: String, isDownloading: Boolean) {
        _partitionInfoList.value = _partitionInfoList.value.map {
            if (it.partitionName == partitionName) {
                it.copy(isDownloading = isDownloading)
            } else {
                it
            }
        }
    }

    fun updateVerifyState(partitionName: String, verifyState: VerifyState) {
        _partitionInfoList.value = _partitionInfoList.value.map {
            if (it.partitionName == partitionName) {
                it.copy(verifyState = verifyState)
            } else {
                it
            }
        }
    }

    fun updateSearch(partitionName: String) {
        _search.value = partitionName
    }

    // --- Dump orchestration -------------------------------------------------------------
    // Extraction runs in viewModelScope, so it survives configuration changes but is cancelled
    // automatically when the ViewModel is cleared. Jobs are tracked per partition so a user can
    // cancel an individual extraction.

    private val dumpJobs = mutableMapOf<String, Job>()

    /** Destination SAF folder for dumped images, shared across dumps in this session. */
    private var outputTreeUri: Uri? = null

    fun setOutputTreeUri(uri: Uri?) {
        outputTreeUri = uri
    }

    fun outputTreeUri(): Uri? = outputTreeUri

    /**
     * Starts extraction of [partitionName]. Returns false if a dump for that partition is already
     * running (the caller can then offer to cancel it instead).
     */
    fun startDump(partitionName: String): Boolean {
        if (dumpJobs[partitionName]?.isActive == true) return false
        val payload = _payload.value ?: return false

        updateDownloadState(partitionName, true)
        updateVerifyState(partitionName, VerifyState.Unverified)
        updateProgress(partitionName, 0f)

        dumpJobs[partitionName] = viewModelScope.launch {
            try {
                DumpUtil().dump(
                    partitionName = partitionName,
                    payload = payload,
                    source = InputSourceHolder.require(),
                    outputTreeUri = outputTreeUri,
                    onProgressUpdate = { produced ->
                        val total = _partitionInfoList.value
                            .firstOrNull { it.partitionName == partitionName }?.size ?: 0L
                        if (total > 0) {
                            // Progress is monotonic: never let the bar move backwards.
                            val fraction = (produced.toFloat() / total).coerceIn(0f, 1f)
                            val current = _partitionInfoList.value
                                .firstOrNull { it.partitionName == partitionName }?.progress ?: 0f
                            updateProgress(partitionName, maxOf(current, fraction))
                        }
                    },
                    onVerification = { ok ->
                        updateVerifyState(
                            partitionName,
                            if (ok) VerifyState.Verified else VerifyState.Failed
                        )
                    },
                    onCancelled = { updateDownloadState(partitionName, false) },
                    onFailure = { updateDownloadState(partitionName, false) }
                )
            } finally {
                dumpJobs.remove(partitionName)
            }
        }
        return true
    }

    fun isDumping(partitionName: String): Boolean = dumpJobs[partitionName]?.isActive == true

    fun cancelDump(partitionName: String) {
        dumpJobs[partitionName]?.cancel()
        dumpJobs.remove(partitionName)
        updateDownloadState(partitionName, false)
    }

    fun cancelAllDumps() {
        dumpJobs.values.forEach { it.cancel() }
        dumpJobs.clear()
    }

    override fun onCleared() {
        // Stop any in-flight dump before releasing the source, then close the parsed source so
        // its file descriptor / HTTP connection does not leak once the ViewModel is gone.
        cancelAllDumps()
        HttpUtil.cancel()
        InputSourceHolder.clear()
        super.onCleared()
    }

    private fun updateBySearch() {
        viewModelScope.launch {
            combine(_partitionInfoList, _search) { partitionList, search ->
                filterPartitionInfoList(partitionList, search)
            }.collect { filterList ->
                _cachePartitionInfoList.value = filterList
            }
        }
    }

    init {
        updateBySearch()
    }
}