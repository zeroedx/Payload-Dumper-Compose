package com.rcmiku.payload.dumper.compose.viewModel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rcmiku.payload.dumper.compose.model.ArchiveInfo
import com.rcmiku.payload.dumper.compose.model.PartitionInfo
import com.rcmiku.payload.dumper.compose.model.Payload
import com.rcmiku.payload.dumper.compose.model.VerifyState
import com.rcmiku.payload.dumper.compose.utils.DumpUtil
import com.rcmiku.payload.dumper.compose.utils.HashUtil
import com.rcmiku.payload.dumper.compose.utils.HttpUtil
import com.rcmiku.payload.dumper.compose.utils.InputSourceHolder
import com.rcmiku.payload.dumper.compose.utils.MissingSourceImageException
import com.rcmiku.payload.dumper.compose.utils.PayloadUtil
import com.rcmiku.payload.dumper.compose.utils.RangePlanner
import com.rcmiku.payload.dumper.compose.utils.ResourceChangedException
import com.rcmiku.payload.dumper.compose.utils.SourceImageStore
import com.rcmiku.payload.dumper.compose.utils.SourceImageValidator
import com.rcmiku.payload.dumper.compose.utils.UnsupportedPayloadOperationException
import com.rcmiku.payload.dumper.compose.utils.filterPartitionInfoList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** Maximum number of partitions extracted at once; bounds memory and HTTP connection pressure. */
private const val MAX_PARALLEL_PARTITIONS = 3

class PayloadDumperViewModel(application: Application) : AndroidViewModel(application) {

    private val _partitionInfoList = MutableStateFlow<List<PartitionInfo>>(emptyList())
    private val _cachePartitionInfoList = MutableStateFlow<List<PartitionInfo>>(emptyList())

    /** Full, unfiltered parsed partition list (never mutated by search). */
    val allPartitionInfoList: StateFlow<List<PartitionInfo>> = _partitionInfoList

    /** Filtered view used by the result list. */
    val cachePartitionInfoList: StateFlow<List<PartitionInfo>> = _cachePartitionInfoList

    private val _archiveInfo = MutableStateFlow<ArchiveInfo?>(null)
    val archiveInfo: StateFlow<ArchiveInfo?> = _archiveInfo
    private val _payload = MutableStateFlow<Payload?>(null)
    val payload: StateFlow<Payload?> = _payload
    private val _search = MutableStateFlow("")
    val search: StateFlow<String> = _search

    private val _isParsing = MutableStateFlow(false)

    /** Whether a parse operation is currently in progress (drives the progress indicator). */
    val isParsing: StateFlow<Boolean> = _isParsing

    /** Aggregate progress across the whole batch: completed partitions / selected partitions. */
    private val _batchProgress = MutableStateFlow(0f)
    val batchProgress: StateFlow<Float> = _batchProgress

    fun setParsing(parsing: Boolean) {
        _isParsing.value = parsing
    }

    fun initPayloadDumper(payload: Payload) {
        viewModelScope.launch {
            // A new input replaces the previous result set; clear any stale search keyword so an
            // old query can never hide the new partitions, and drop source images that belong to
            // the previous payload.
            _search.value = ""
            sourceImages.closeAll()
            sourceImages.clear()
            _sourceImagePartitions.value = emptySet()
            _payload.value = payload
            _archiveInfo.value = ArchiveInfo(
                payload.fileName,
                payload.archiveSize,
                payload.deltaArchiveManifest.securityPatchLevel,
                payload.deviceModels
            )
            _partitionInfoList.value = PayloadUtil.getPartitionInfoList(payload)
            _cachePartitionInfoList.value = _partitionInfoList.value
            _batchProgress.value = 0f
        }
    }

    fun updateProgress(partitionName: String, progress: Float) {
        _partitionInfoList.update { list ->
            list.map { if (it.partitionName == partitionName) it.copy(progress = progress) else it }
        }
    }

    fun updateDownloadState(partitionName: String, isDownloading: Boolean) {
        _partitionInfoList.update { list ->
            list.map { if (it.partitionName == partitionName) it.copy(isDownloading = isDownloading) else it }
        }
    }

    fun updateVerifyState(partitionName: String, verifyState: VerifyState) {
        _partitionInfoList.update { list ->
            list.map { if (it.partitionName == partitionName) it.copy(verifyState = verifyState) else it }
        }
    }

    private fun setError(partitionName: String, message: String?) {
        _partitionInfoList.update { list ->
            list.map { if (it.partitionName == partitionName) it.copy(error = message) else it }
        }
    }

    fun updateSearch(partitionName: String) {
        _search.value = partitionName
    }

    // --- Output folder (SAF) ----------------------------------------------------------------

    private val _outputTreeUri = MutableStateFlow<Uri?>(null)

    /** The destination folder for dumped images, or null when no folder is authorised yet. */
    val outputTreeUri: StateFlow<Uri?> = _outputTreeUri

    fun setOutputTreeUri(uri: Uri?) {
        _outputTreeUri.value = uri
    }

    // --- Old partition images (delta OTA) ---------------------------------------------------

    private val sourceImages = SourceImageStore(application)

    /** Partitions for which an old image has been supplied. */
    private val _sourceImagePartitions = MutableStateFlow<Set<String>>(emptySet())
    val sourceImagePartitions: StateFlow<Set<String>> = _sourceImagePartitions

    /** Registers an imported old image for [partitionName]. */
    fun setSourceImage(partitionName: String, uri: Uri) {
        sourceImages.put(partitionName, uri)
        _sourceImagePartitions.value = sourceImages.partitions()
    }

    fun removeSourceImage(partitionName: String) {
        sourceImages.remove(partitionName)
        _sourceImagePartitions.value = sourceImages.partitions()
    }

    fun hasSourceImage(partitionName: String): Boolean = sourceImages.has(partitionName)

    // --- Dump orchestration -----------------------------------------------------------------
    // Extraction runs in viewModelScope, so it survives configuration changes but is cancelled
    // automatically when the ViewModel is cleared. Jobs are tracked per partition so a user can
    // cancel an individual extraction; several partitions run concurrently under a bounded
    // semaphore. Each partition writes to its own working file and reads the shared source through
    // positioned reads, so parallel extraction is safe.

    private val dumpJobs = mutableMapOf<String, Job>()
    private val dumpSemaphore = Semaphore(MAX_PARALLEL_PARTITIONS)

    /** Starts extraction of [partitionName]; returns false if a dump is already running. */
    fun startDump(partitionName: String): Boolean {
        if (dumpJobs[partitionName]?.isActive == true) return false
        val payload = _payload.value ?: return false

        updateDownloadState(partitionName, true)
        updateVerifyState(partitionName, VerifyState.Unverified)
        setError(partitionName, null)
        updateProgress(partitionName, 0f)

        dumpJobs[partitionName] = viewModelScope.launch {
            try {
                dumpSemaphore.withPermit {
                    // Re-check: the job may have been cancelled while waiting for a permit.
                    if (dumpJobs[partitionName]?.isCancelled == true) return@withPermit
                    runDump(partitionName, payload)
                }
            } finally {
                dumpJobs.remove(partitionName)
                updateDownloadState(partitionName, false)
                recomputeBatchProgress()
            }
        }
        return true
    }

    private suspend fun runDump(partitionName: String, payload: Payload) {
        val partition = payload.deltaArchiveManifest.partitionsList
            .firstOrNull { it.partitionName == partitionName } ?: return
        val total = _partitionInfoList.value.firstOrNull { it.partitionName == partitionName }?.size ?: 0L

        // Reject unsupported operations (e.g. a MOVE op or an unimplemented diff) before writing a
        // single byte, and require an old image when the partition reads from one.
        try {
            PayloadUtil.checkSupported(partition, hasSourceImage = sourceImages.contains(partitionName))
        } catch (e: UnsupportedPayloadOperationException) {
            setError(partitionName, e.message)
            updateDownloadState(partitionName, false)
            return
        } catch (e: MissingSourceImageException) {
            setError(partitionName, e.message)
            updateDownloadState(partitionName, false)
            return
        }

        // Open and validate the old image first when this partition needs one, so a bad source is
        // reported before extraction starts. Validation hashes the image, so it runs off Main.
        var oldInput: com.rcmiku.payload.dumper.compose.utils.SeekableInput? = null
        if (PayloadUtil.operationsNeedSourceImage(partition)) {
            oldInput = withContext(Dispatchers.IO) {
                val store = sourceImages
                val opened = store.open(partitionName)
                SourceImageValidator.validate(
                    partition = partition,
                    imageLength = store.lengthOf(partitionName),
                    blockSize = payload.blockSize,
                    hashProvider = { HashUtil.sha256Bytes(opened) },
                )
                opened
            }
        }

        // Remote source: prefetch the merged ranges covering this partition's blobs so extraction
        // issues few large requests instead of many tiny ones. Prefetching is best-effort.
        val source = InputSourceHolder.require()
        if (!source.isLocal) {
            withContext(Dispatchers.IO) {
                val ranges = RangePlanner.planRanges(
                    listOf(partition),
                    dataOffset = payload.dataOffset,
                )
                (source as? com.rcmiku.payload.dumper.compose.utils.HttpUtil)?.prefetch(ranges)
            }
        }

        try {
            DumpUtil().dump(
                partitionName = partitionName,
                payload = payload,
                source = source,
                outputTreeUri = _outputTreeUri.value,
                oldPartition = oldInput,
                onProgressUpdate = { produced ->
                    if (total > 0) {
                        val fraction = (produced.toFloat() / total).coerceIn(0f, 1f)
                        val current = _partitionInfoList.value
                            .firstOrNull { it.partitionName == partitionName }?.progress ?: 0f
                        updateProgress(partitionName, maxOf(current, fraction))
                    }
                },
                onVerification = { ok ->
                    updateVerifyState(partitionName, if (ok) VerifyState.Verified else VerifyState.Failed)
                },
                onCancelled = { updateDownloadState(partitionName, false) },
                onFailure = { updateDownloadState(partitionName, false) },
            )
        } catch (e: com.rcmiku.payload.dumper.compose.utils.ResourceChangedException) {
            // The remote resource changed under us: drop every cached source image and tell the
            // user to parse again, so no two versions of the payload are ever mixed.
            sourceImages.closeAll()
            sourceImages.clear()
            _sourceImagePartitions.value = emptySet()
            setError(partitionName, e.message)
            updateDownloadState(partitionName, false)
        }
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

    private fun recomputeBatchProgress() {
        val list = _partitionInfoList.value
        val active = list.filter { it.isDownloading }
        if (active.isEmpty()) {
            _batchProgress.value = 0f
            return
        }
        // Average per-partition progress, so the aggregate reflects every running extraction.
        _batchProgress.value = active.map { it.progress }.average().toFloat()
    }

    override fun onCleared() {
        // Stop any in-flight dump before releasing the source, then close the parsed source so
        // its file descriptor / HTTP connection does not leak once the ViewModel is gone.
        cancelAllDumps()
        HttpUtil.cancel()
        InputSourceHolder.clear()
        sourceImages.closeAll()
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
