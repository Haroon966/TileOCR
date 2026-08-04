package com.paperpanorama.ocr.session

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.paperpanorama.ocr.BuildConfig
import com.paperpanorama.ocr.audit.StitchAuditStore
import com.paperpanorama.ocr.camera.CaptureStore
import com.paperpanorama.ocr.capture.CoverageTracker
import com.paperpanorama.ocr.capture.PageSpaceTracker
import com.paperpanorama.ocr.camera.MlKitDocumentScan
import com.paperpanorama.ocr.doc.OpenCvDocumentProcessor
import com.paperpanorama.ocr.doc.QuadMath
import com.paperpanorama.ocr.domain.CaptureFrame
import com.paperpanorama.ocr.domain.CaptureMode
import com.paperpanorama.ocr.domain.CoverageSnapshot
import com.paperpanorama.ocr.domain.DocQuad
import com.paperpanorama.ocr.domain.EnhancePreset
import com.paperpanorama.ocr.domain.SavedScan
import com.paperpanorama.ocr.domain.StitchResult
import com.paperpanorama.ocr.library.ScanLibrary
import com.paperpanorama.ocr.ocr.CleanPageRenderer
import com.paperpanorama.ocr.ocr.GoogleVisionOcrClient
import com.paperpanorama.ocr.ocr.MistralOcrClient
import com.paperpanorama.ocr.ocr.OcrLayoutMath
import com.paperpanorama.ocr.ocr.OcrPdfBuilder
import com.paperpanorama.ocr.ocr.OcrTextClean
import com.paperpanorama.ocr.orient.OpenCvFrameNormalizer
import com.paperpanorama.ocr.stitch.OpenCvDocumentStitcher
import com.paperpanorama.ocr.util.BitmapDecode
import com.paperpanorama.ocr.util.MediaSaver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

data class ScanUiState(
    val sessionId: String = "",
    val mode: CaptureMode = CaptureMode.Panorama,
    val frames: List<CaptureFrame> = emptyList(),
    val mosaicUri: Uri? = null,
    val usedFallback: Boolean = false,
    val stitchProgress: Float = 0f,
    val stitchMessage: String = "",
    val stitchFailedReason: String? = null,
    val bestFrameUri: Uri? = null,
    val showFailureSheet: Boolean = false,
    val featureWarn: String? = null,
    val isStitching: Boolean = false,
    /** Prepare page (crop / enhance) */
    val docQuad: DocQuad? = null,
    val enhancePreset: EnhancePreset = EnhancePreset.Auto,
    val isDetectingQuad: Boolean = false,
    val isPreparingPage: Boolean = false,
    val prepareError: String? = null,
    val pageUri: Uri? = null,
    val pageWidth: Int = 0,
    val pageHeight: Int = 0,
    /** Local library (recents first). */
    val library: List<SavedScan> = emptyList(),
    /** When viewing a saved scan, Adjust is unavailable without a mosaic. */
    val libraryScanId: String? = null,
    /** Guided scan coach (coverage-driven). */
    val coverage: CoverageSnapshot = CoverageSnapshot.Idle,
    val isIngestingCapture: Boolean = false,
    /** Coarse live mosaic of locked page cells (camera HUD). */
    val mosaicThumb: Bitmap? = null,
    /** null=idle, true=last still accepted, false=rejected (recapture Soft without forced pan). */
    val lastIngestAccepted: Boolean? = null,
    /** Durable copies of input tiles for stitch audit UI + adb pull. */
    val auditInputUris: List<Uri> = emptyList(),
    val auditDirHint: String? = null,
    /** Pending auto-prepare after user reviews stitch visually. */
    val pendingPrepareFullFrame: Boolean = false,
    /** Puzzle bake in progress. */
    val isBakingPuzzle: Boolean = false,
    /** Library persist in flight — gate page tools until done. */
    val isSavingPage: Boolean = false,
    /** Mistral OCR → clean white page. */
    val isRunningOcr: Boolean = false,
    val ocrStatus: String = "",
    val ocrMarkdown: String = "",
    val ocrCleanUri: Uri? = null,
    val ocrPdfUri: Uri? = null,
    val ocrError: String? = null,
    val isBuildingBatchPdf: Boolean = false,
    val batchPdfProgress: String = "",
    val batchPdfUri: Uri? = null,
    /** Google Vision OCR → searchable PDF (original scan image + invisible text layer). */
    val isRunningVisionOcr: Boolean = false,
    val visionOcrStatus: String = "",
    val visionMarkdown: String = "",
    val visionPdfUri: Uri? = null,
    /** Clean Verdana layout reference from Vision word boxes. */
    val visionCleanUri: Uri? = null,
    val visionOcrError: String? = null,
)


class ScanSessionViewModel(app: Application) : AndroidViewModel(app) {
    private val store = CaptureStore(app)
    private val library = ScanLibrary(app)
    private val auditStore = StitchAuditStore(app)
    private val coverageTracker = CoverageTracker(app)
    private val normalizer = OpenCvFrameNormalizer(app)
    private val stitcher = OpenCvDocumentStitcher(app, normalizer)
    private val docProcessor = OpenCvDocumentProcessor(app)
    private val _state = MutableStateFlow(
        ScanUiState(
            sessionId = store.newSessionId(),
        ),
    )
    val state: StateFlow<ScanUiState> = _state.asStateFlow()

    private val navChannel = Channel<ScanNavEvent>(Channel.BUFFERED)
    val navEvents = navChannel.receiveAsFlow()

    private var stitchJob: Job? = null
    private var prepareJob: Job? = null
    @Volatile private var cancelStitch = false

    init {
        refreshLibrary()
    }

    override fun onCleared() {
        super.onCleared()
    }

    fun onNewScanClicked(hasCameraPermission: Boolean) {
        if (!hasCameraPermission) {
            viewModelScope.launch { navChannel.send(ScanNavEvent.RequestPermission) }
            return
        }
        startFreshSession()
        viewModelScope.launch { navChannel.send(ScanNavEvent.ToCamera) }
    }

    fun onPermissionGranted() {
        startFreshSession()
        viewModelScope.launch { navChannel.send(ScanNavEvent.ToCamera) }
    }

    fun onPermissionDenied() {
        viewModelScope.launch {
            navChannel.send(ScanNavEvent.Snackbar("Camera permission is required to scan"))
        }
    }

    /** Shared with LivePageAnalyzer so live seed lock = ingest registration. */
    fun pageSpaceTracker(): PageSpaceTracker = coverageTracker.spaceTracker

    fun setMode(mode: CaptureMode) {
        _state.update { it.copy(mode = mode) }
        if (mode == CaptureMode.Single) {
            coverageTracker.reset()
            _state.update {
                it.copy(
                    coverage = CoverageSnapshot.Idle,
                    mosaicThumb = null,
                )
            }
        } else if (_state.value.frames.isEmpty()) {
            coverageTracker.reset()
            _state.update {
                it.copy(
                    coverage = CoverageSnapshot.Idle,
                    mosaicThumb = null,
                )
            }
        }
    }

    fun startFreshSession() {
        stitchJob?.cancel()
        prepareJob?.cancel()
        cancelStitch = false
        coverageTracker.reset()
        val oldId = _state.value.sessionId
        if (oldId.isNotBlank()) {
            store.clearSession(oldId)
        }
        val id = store.newSessionId()
        _state.value = ScanUiState(
            sessionId = id,
            mode = CaptureMode.Panorama,
            library = _state.value.library,
            coverage = CoverageSnapshot.Idle,
        )
    }

    fun refreshLibrary() {
        viewModelScope.launch(Dispatchers.IO) {
            val scans = library.list()
            withContext(Dispatchers.Main) {
                _state.update { it.copy(library = scans) }
            }
        }
    }

    fun openScan(id: String) {
        viewModelScope.launch {
            applyLibraryScan(id) ?: return@launch
            navChannel.send(ScanNavEvent.ToOcrReady)
        }
    }

    /** Swipe between gallery pages — update viewer without re-navigating. */
    fun selectLibraryScan(id: String) {
        if (_state.value.libraryScanId == id) return
        viewModelScope.launch {
            applyLibraryScan(id)
        }
    }

    private suspend fun applyLibraryScan(id: String): SavedScan? {
        val scan = withContext(Dispatchers.IO) { library.get(id) } ?: return null
        _state.update {
            it.copy(
                pageUri = scan.pageUri,
                pageWidth = scan.width,
                pageHeight = scan.height,
                mosaicUri = scan.mosaicUri,
                libraryScanId = scan.id,
                docQuad = null,
                prepareError = null,
            )
        }
        return scan
    }

    /**
     * Manual crop: use the stitched mosaic when available, otherwise the current
     * page image (library re-crop). Detects edges then opens Prepare.
     */
    fun beginCrop() {
        if (_state.value.isSavingPage) return
        val source = _state.value.mosaicUri ?: _state.value.pageUri ?: return
        viewModelScope.launch {
            _state.update {
                it.copy(
                    mosaicUri = source,
                    isDetectingQuad = true,
                    prepareError = null,
                )
            }
            navChannel.send(ScanNavEvent.ToPrepare)
            val quad = runCatching { docProcessor.detectQuad(source) }.getOrElse {
                val path = source.path
                val (w, h) = path?.let { BitmapDecode.bounds(it) } ?: (1000 to 1000)
                QuadMath.fullFrame(w, h)
            }
            _state.update { it.copy(docQuad = quad, isDetectingQuad = false) }
        }
    }

    /** Re-run Auto enhance on current mosaic (or page) without opening crop UI. */
    fun reEnhancePage() {
        if (_state.value.isSavingPage || _state.value.isPreparingPage) return
        val mosaic = _state.value.mosaicUri
        val page = _state.value.pageUri ?: return
        prepareJob?.cancel()
        prepareJob = viewModelScope.launch {
            _state.update {
                it.copy(isPreparingPage = true, prepareError = null, enhancePreset = EnhancePreset.Auto)
            }
            try {
                val source = mosaic ?: page
                val path = source.path
                val (w, h) = path?.let { BitmapDecode.bounds(it) } ?: (1000 to 1000)
                val quad = when {
                    mosaic != null && _state.value.docQuad != null -> _state.value.docQuad!!
                    mosaic != null -> runCatching { docProcessor.detectQuad(mosaic) }
                        .getOrElse { QuadMath.fullFrame(w, h) }
                    else -> QuadMath.fullFrame(w, h)
                }
                val result = docProcessor.prepareForOcr(source, quad, EnhancePreset.Auto)
                _state.update {
                    it.copy(
                        isPreparingPage = false,
                        pageUri = result.pageUri,
                        pageWidth = result.width,
                        pageHeight = result.height,
                        docQuad = quad,
                        isSavingPage = true,
                    )
                }
                persistPage(mosaic, result.pageUri)
                navChannel.send(ScanNavEvent.Snackbar("Enhanced"))
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                _state.update {
                    it.copy(
                        isPreparingPage = false,
                        isSavingPage = false,
                        prepareError = t.message ?: "Enhance failed",
                    )
                }
                navChannel.send(ScanNavEvent.Snackbar(t.message ?: "Enhance failed"))
            }
        }
    }

    fun deleteScan(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            library.delete(id)
            MediaSaver.deleteByDisplayName(getApplication(), MediaSaver.pageDisplayName(id))
            val scans = library.list()
            withContext(Dispatchers.Main) {
                _state.update { s ->
                    s.copy(
                        library = scans,
                        libraryScanId = if (s.libraryScanId == id) null else s.libraryScanId,
                    )
                }
            }
        }
    }

    /**
     * Delete from gallery viewer: show neighbor page, or return home if library empty.
     */
    fun deleteLibraryScanFromViewer(id: String) {
        viewModelScope.launch {
            val before = _state.value.library
            val idx = before.indexOfFirst { it.id == id }
            withContext(Dispatchers.IO) {
                library.delete(id)
                MediaSaver.deleteByDisplayName(getApplication(), MediaSaver.pageDisplayName(id))
            }
            val scans = withContext(Dispatchers.IO) { library.list() }
            if (scans.isEmpty()) {
                _state.update {
                    it.copy(
                        library = emptyList(),
                        libraryScanId = null,
                        pageUri = null,
                        mosaicUri = null,
                        pageWidth = 0,
                        pageHeight = 0,
                    )
                }
                navChannel.send(ScanNavEvent.ToHome)
                return@launch
            }
            val next = when {
                idx < 0 -> scans.first()
                idx < scans.size -> scans[idx]
                else -> scans.last()
            }
            applyLibraryScan(next.id)
            _state.update { it.copy(library = scans) }
        }
    }

    /**
     * Google Document Scanner finished — import pages, then run app pipeline:
     * normalize (rotation) → per-page crop polish → geometric stitch / component stack.
     */
    fun onMlKitDocumentPages(uris: List<Uri>) {
        if (uris.isEmpty()) {
            viewModelScope.launch {
                navChannel.send(ScanNavEvent.Snackbar("No pages scanned"))
            }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(isIngestingCapture = true, featureWarn = null) }
            val sessionId = _state.value.sessionId
            val frames = try {
                withContext(Dispatchers.IO) {
                    store.importPageUris(sessionId, uris.take(MlKitDocumentScan.PAGE_LIMIT))
                }
            } catch (t: Throwable) {
                _state.update { it.copy(isIngestingCapture = false) }
                navChannel.send(ScanNavEvent.Snackbar(t.message ?: "Could not import scanned pages"))
                return@launch
            }
            val mode = if (frames.size == 1) CaptureMode.Single else CaptureMode.Panorama
            _state.update {
                it.copy(
                    mode = mode,
                    frames = frames,
                    coverage = CoverageSnapshot.Idle,
                    mosaicThumb = null,
                    isIngestingCapture = false,
                )
            }
            // Full smart stitch (crop + align + multi-component stack), not blind vertical dump.
            beginStitch(combineDocumentPages = false)
        }
    }

    fun onMlKitDocumentCancelled() {
        // Stay on home / current screen — no-op beyond optional coach.
    }

    fun addCapturedFile(file: java.io.File, displayRotation: Int, forTileIndex: Int? = null) {
        if (_state.value.mode == CaptureMode.Single) {
            val index = _state.value.frames.size
            val frame = store.frameFromFile(index, file, displayRotation)
            _state.update {
                it.copy(
                    frames = listOf(frame),
                    featureWarn = null,
                    coverage = CoverageSnapshot.Idle,
                )
            }
            beginStitch()
            return
        }

        viewModelScope.launch {
            _state.update { it.copy(isIngestingCapture = true) }
            val result = withContext(Dispatchers.Default) {
                coverageTracker.ingest(Uri.fromFile(file))
            }
            if (!result.accepted) {
                file.delete()
                _state.update {
                    it.copy(
                        isIngestingCapture = false,
                        coverage = result.snapshot,
                        featureWarn = result.snapshot.nextHint,
                        mosaicThumb = coverageTracker.mosaicThumbnail(),
                        lastIngestAccepted = false,
                    )
                }
                return@launch
            }
            val frame = withContext(Dispatchers.IO) {
                store.frameFromFile(_state.value.frames.size, file, displayRotation)
            }
            _state.update { s ->
                val next = s.frames + frame.copy(index = s.frames.size)
                s.copy(
                    frames = next,
                    featureWarn = null,
                    coverage = result.snapshot,
                    isIngestingCapture = false,
                    mosaicThumb = coverageTracker.mosaicThumbnail(),
                    lastIngestAccepted = true,
                )
            }
        }
    }

    fun consumeIngestResult() {
        _state.update { it.copy(lastIngestAccepted = null) }
    }

    fun onLivePageMapped() {
        val pr = coverageTracker.spaceTracker.pageRectSeed()
        coverageTracker.markPageMappedFromLive(pr.minX, pr.minY, pr.maxX, pr.maxY)
        _state.update { it.copy(coverage = coverageTracker.snapshot()) }
    }

    fun removeFrameAt(listIndex: Int) {
        _state.update { s ->
            val frames = s.frames.toMutableList()
            if (listIndex !in frames.indices) return@update s
            frames.removeAt(listIndex)
            s.copy(frames = frames.mapIndexed { i, f -> f.copy(index = i) })
        }
        if (_state.value.mode == CaptureMode.Panorama) {
            viewModelScope.launch(Dispatchers.Default) {
                val uris = _state.value.frames.map { it.uri }
                val snap = coverageTracker.rebuild(uris)
                withContext(Dispatchers.Main) {
                    _state.update {
                        it.copy(
                            coverage = snap,
                            mosaicThumb = coverageTracker.mosaicThumbnail(),
                        )
                    }
                }
            }
        }
    }

    fun moveFrame(from: Int, to: Int) {
        _state.update { s ->
            if (from !in s.frames.indices || to !in s.frames.indices || from == to) return@update s
            val frames = s.frames.toMutableList()
            val item = frames.removeAt(from)
            frames.add(to, item)
            s.copy(frames = frames.mapIndexed { i, f -> f.copy(index = i) })
        }
    }

    /** User taps Done — every cell Locked. */
    fun onPanoramaDone(force: Boolean = false) {
        val frames = _state.value.frames
        val ready = _state.value.coverage.readyToFinish
        if (frames.isEmpty()) {
            viewModelScope.launch {
                navChannel.send(ScanNavEvent.Snackbar("Capture sharp tiles until the grid is locked"))
            }
            return
        }
        if (!force && !ready) {
            viewModelScope.launch {
                navChannel.send(ScanNavEvent.ConfirmEarlyFinish)
            }
            return
        }
        beginStitch()
    }

    fun confirmEarlyFinish() {
        if (_state.value.frames.isEmpty()) return
        beginStitch()
    }

    fun beginStitch(combineDocumentPages: Boolean = false) {
        if (_state.value.frames.isEmpty()) return
        cancelStitch = false
        stitchJob?.cancel()
        stitchJob = viewModelScope.launch {
            _state.update {
                it.copy(
                    isStitching = true,
                    stitchProgress = 0f,
                    stitchMessage = "Starting…",
                    showFailureSheet = false,
                    stitchFailedReason = null,
                    pageUri = null,
                    docQuad = null,
                )
            }
            navChannel.send(ScanNavEvent.ToStitching)

            val normalized = _state.value.frames.map { normalizer.normalizeForStitch(it) }
            _state.update { it.copy(frames = normalized) }

            val result = try {
                if (combineDocumentPages) {
                    stitcher.combineDocumentPages(
                        frames = normalized,
                        onProgress = { f, msg ->
                            _state.update { it.copy(stitchProgress = f, stitchMessage = msg) }
                        },
                        isCancelled = { cancelStitch },
                    )
                } else {
                    stitcher.stitch(
                        frames = normalized,
                        onProgress = { f, msg ->
                            _state.update { it.copy(stitchProgress = f, stitchMessage = msg) }
                        },
                        isCancelled = { cancelStitch },
                    )
                }
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                StitchResult.Failed(t.message ?: "Stitch crashed", normalized.firstOrNull()?.uri)
            }
            when (result) {
                is StitchResult.Ok -> {
                    val sessionId = _state.value.sessionId
                    val auditInputs = withContext(Dispatchers.IO) {
                        auditStore.saveInputs(sessionId, normalized)
                    }
                    val auditMosaic = withContext(Dispatchers.IO) {
                        auditStore.saveMosaic(sessionId, result.mosaicUri)
                    }
                    val bundle = auditStore.latestBundle(sessionId)
                    _state.update {
                        it.copy(
                            mosaicUri = auditMosaic ?: result.mosaicUri,
                            usedFallback = result.usedFallback,
                            stitchProgress = 1f,
                            isStitching = false,
                            auditInputUris = auditInputs.ifEmpty {
                                normalized.map { f -> f.uri }
                            },
                            auditDirHint = bundle?.dir?.absolutePath
                                ?: "Android/data/com.paperpanorama.ocr/files/audit/$sessionId",
                            // Full-frame prepare when we stacked pages (no panorama).
                            pendingPrepareFullFrame = result.usedFallback,
                        )
                    }
                    // Visual review before prepare — inputs + result side by side.
                    navChannel.send(ScanNavEvent.ToStitchReview)
                }
                is StitchResult.Failed -> {
                    if (result.reason == "Cancelled") {
                        _state.update { it.copy(isStitching = false) }
                        navChannel.send(ScanNavEvent.ToCamera)
                    } else {
                        _state.update {
                            it.copy(
                                isStitching = false,
                                stitchFailedReason = result.reason,
                                bestFrameUri = result.bestFrameUri,
                                showFailureSheet = true,
                            )
                        }
                    }
                }
            }
        }
    }

    /** User finished looking at inputs vs mosaic — prepare page. */
    fun onStitchReviewContinue() {
        val mosaic = _state.value.mosaicUri ?: return
        val fullFrame = _state.value.pendingPrepareFullFrame
        viewModelScope.launch {
            _state.update { it.copy(isStitching = true, stitchMessage = "Preparing page…") }
            navChannel.send(ScanNavEvent.ToStitching)
            autoPrepare(mosaic, preferFullFrame = fullFrame)
        }
    }

    fun onStitchReviewBack() {
        viewModelScope.launch { navChannel.send(ScanNavEvent.ToHome) }
    }

    fun cancelStitch() {
        cancelStitch = true
        stitchJob?.cancel()
        _state.update { it.copy(isStitching = false) }
        viewModelScope.launch { navChannel.send(ScanNavEvent.ToCamera) }
    }

    fun useBestFrame() {
        val uri = _state.value.bestFrameUri ?: return
        _state.update {
            it.copy(
                mosaicUri = uri,
                usedFallback = true,
                showFailureSheet = false,
                isStitching = true,
                stitchProgress = 1f,
            )
        }
        viewModelScope.launch {
            navChannel.send(ScanNavEvent.ToStitching)
            autoPrepare(uri)
        }
    }

    /**
     * Hands-off path: detect the paper, crop + warp + enhance, land on the final page.
     * Falls back to the manual Prepare screen only if the warp itself fails.
     * [preferFullFrame] for ML Kit stacked pages — edge detect finds one page only.
     */
    private suspend fun autoPrepare(mosaicUri: Uri, preferFullFrame: Boolean = false) {
        _state.update { it.copy(stitchMessage = "Cropping page…", isDetectingQuad = true) }
        val path = mosaicUri.path
        val bounds = path?.let { BitmapDecode.bounds(it) }
        val tallStack = bounds != null && bounds.second > bounds.first * 2
        val quad = if (preferFullFrame || tallStack) {
            val (w, h) = bounds ?: (1000 to 1000)
            QuadMath.fullFrame(w, h)
        } else {
            runCatching { docProcessor.detectQuad(mosaicUri) }.getOrElse {
                val (w, h) = bounds ?: (1000 to 1000)
                QuadMath.fullFrame(w, h)
            }
        }
        _state.update { it.copy(docQuad = quad, isDetectingQuad = false) }
        try {
            val result = docProcessor.prepareForOcr(mosaicUri, quad, _state.value.enhancePreset)
            withContext(Dispatchers.IO) {
                auditStore.savePage(_state.value.sessionId, result.pageUri)
            }
            _state.update {
                it.copy(
                    isStitching = false,
                    pageUri = result.pageUri,
                    pageWidth = result.width,
                    pageHeight = result.height,
                    pendingPrepareFullFrame = false,
                    isSavingPage = true,
                )
            }
            val saved = persistPage(mosaicUri, result.pageUri)
            navChannel.send(ScanNavEvent.ToOcrReady)
            if (saved != null) {
                navChannel.send(ScanNavEvent.Snackbar("Saved to library"))
            }
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            _state.update { it.copy(isStitching = false, isSavingPage = false) }
            navChannel.send(ScanNavEvent.ToPrepare)
        }
    }

    /**
     * Durable library first, then one gallery file named by scan id (no mosaic dual-write).
     * Updates state so [pageUri] points at filesDir, not cache.
     */
    private suspend fun persistPage(mosaicUri: Uri?, pageUri: Uri): SavedScan? {
        return withContext(Dispatchers.IO) {
            val app = getApplication<Application>()
            val w = _state.value.pageWidth
            val h = _state.value.pageHeight
            val existingId = _state.value.libraryScanId
            val saved = if (existingId != null) {
                library.updatePage(existingId, pageUri, w, h)
            } else {
                library.save(pageUri, mosaicUri, w, h)
            }
            saved?.let {
                MediaSaver.saveToGallery(app, it.pageUri, MediaSaver.pageDisplayName(it.id))
            }
            val scans = library.list()
            withContext(Dispatchers.Main) {
                _state.update {
                    it.copy(
                        library = scans,
                        libraryScanId = saved?.id ?: it.libraryScanId,
                        pageUri = saved?.pageUri ?: pageUri,
                        pageWidth = saved?.width ?: w,
                        pageHeight = saved?.height ?: h,
                        isSavingPage = false,
                    )
                }
            }
            saved
        }
    }

    fun dismissFailureRetake() {
        _state.update { it.copy(showFailureSheet = false) }
        viewModelScope.launch { navChannel.send(ScanNavEvent.ToCamera) }
    }

    private fun startQuadDetection(uri: Uri) {
        viewModelScope.launch {
            _state.update { it.copy(isDetectingQuad = true) }
            val quad = runCatching { docProcessor.detectQuad(uri) }.getOrElse {
                val path = uri.path
                val (w, h) = path?.let { BitmapDecode.bounds(it) } ?: (1000 to 1000)
                QuadMath.fullFrame(w, h)
            }
            _state.update { it.copy(docQuad = quad, isDetectingQuad = false) }
        }
    }

    fun updateCorner(index: Int, x: Float, y: Float) {
        _state.update { s ->
            val q = s.docQuad ?: return@update s
            val path = s.mosaicUri?.path
            val (w, h) = path?.let { BitmapDecode.bounds(it) } ?: (Int.MAX_VALUE to Int.MAX_VALUE)
            val nx = x.coerceIn(0f, (w - 1).toFloat())
            val ny = y.coerceIn(0f, (h - 1).toFloat())
            s.copy(docQuad = q.withPoint(index, nx, ny), prepareError = null)
        }
    }

    fun resetQuad() {
        val uri = _state.value.mosaicUri ?: return
        startQuadDetection(uri)
    }

    fun setEnhancePreset(preset: EnhancePreset) {
        _state.update { it.copy(enhancePreset = preset) }
    }

    fun rotatePrepare90() {
        val uri = _state.value.mosaicUri ?: return
        viewModelScope.launch {
            val path = uri.path
            val (oldW, oldH) = path?.let { BitmapDecode.bounds(it) } ?: return@launch
            val next = docProcessor.rotateMosaic90(uri)
            val quad = _state.value.docQuad?.let { QuadMath.rotate90Cw(it, oldW, oldH) }
            _state.update { it.copy(mosaicUri = next, docQuad = quad) }
            if (quad == null) startQuadDetection(next)
        }
    }

    fun confirmPrepare() {
        val mosaic = _state.value.mosaicUri ?: return
        val quad = _state.value.docQuad ?: return
        val preset = _state.value.enhancePreset
        prepareJob?.cancel()
        prepareJob = viewModelScope.launch {
            _state.update { it.copy(isPreparingPage = true, prepareError = null) }
            try {
                val result = docProcessor.prepareForOcr(mosaic, quad, preset)
                _state.update {
                    it.copy(
                        isPreparingPage = false,
                        pageUri = result.pageUri,
                        pageWidth = result.width,
                        pageHeight = result.height,
                        isSavingPage = true,
                    )
                }
                val saved = persistPage(mosaic, result.pageUri)
                navChannel.send(ScanNavEvent.ToOcrReady)
                if (saved != null) {
                    navChannel.send(ScanNavEvent.Snackbar("Crop saved"))
                }
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                _state.update {
                    it.copy(
                        isPreparingPage = false,
                        isSavingPage = false,
                        prepareError = t.message ?: "Could not prepare page",
                    )
                }
            }
        }
    }

    /** Handwriting orientation is ambiguous to auto-detect; one tap fixes it. */
    fun rotateOcrPage() {
        if (_state.value.isSavingPage) return
        val page = _state.value.pageUri ?: return
        viewModelScope.launch {
            _state.update { it.copy(isSavingPage = true) }
            val rotated = docProcessor.rotateMosaic90(page)
            if (rotated != page) {
                val newW = _state.value.pageHeight
                val newH = _state.value.pageWidth
                _state.update {
                    it.copy(pageUri = rotated, pageWidth = newW, pageHeight = newH)
                }
                persistPage(_state.value.mosaicUri, rotated)
            } else {
                _state.update { it.copy(isSavingPage = false) }
            }
        }
    }

    /**
     * Call Mistral OCR on current page → white Verdana layout (stretched into OCR boxes).
     */
    fun runMistralOcr() {
        if (_state.value.isRunningOcr) return
        val page = _state.value.pageUri ?: run {
            viewModelScope.launch {
                navChannel.send(ScanNavEvent.Snackbar("No page to OCR"))
            }
            return
        }
        val key = BuildConfig.MISTRAL_API_KEY
        if (key.isBlank()) {
            viewModelScope.launch {
                navChannel.send(ScanNavEvent.Snackbar("Set mistral_api_key in .env"))
            }
            return
        }
        viewModelScope.launch {
            _state.update {
                it.copy(
                    isRunningOcr = true,
                    ocrStatus = "Calling Mistral…",
                    ocrError = null,
                    ocrCleanUri = null,
                    ocrPdfUri = null,
                    ocrMarkdown = "",
                )
            }
            navChannel.send(ScanNavEvent.ToOcrResult)
            val app = getApplication<Application>()
            val path = page.path
            if (path.isNullOrBlank()) {
                _state.update {
                    it.copy(isRunningOcr = false, ocrStatus = "", ocrError = "Page path missing")
                }
                return@launch
            }
            val client = MistralOcrClient(key)
            val apiResult = client.processJpegFile(File(path))
            when (apiResult) {
                is MistralOcrClient.Result.Err -> {
                    _state.update {
                        it.copy(
                            isRunningOcr = false,
                            ocrStatus = "",
                            ocrError = apiResult.message,
                        )
                    }
                }
                is MistralOcrClient.Result.Ok -> {
                    try {
                        _state.update { it.copy(ocrStatus = "Building page…") }
                        val pageW = _state.value.pageWidth.coerceAtLeast(1)
                        val pageH = _state.value.pageHeight.coerceAtLeast(1)
                        var blocks = apiResult.page.blocks
                        val usedFallbackLayout = blocks.isEmpty() && apiResult.page.markdown.isNotBlank()
                        if (usedFallbackLayout) {
                            blocks = OcrTextClean.markdownFallbackBlocks(apiResult.page.markdown)
                            navChannel.send(
                                ScanNavEvent.Snackbar("No box layout from API — layout estimated"),
                            )
                        }
                        blocks = OcrLayoutMath.expandMultilineToLineBlocks(blocks)
                        val pageMarkdown = apiResult.page.markdown
                        val clean = withContext(Dispatchers.Default) {
                            CleanPageRenderer(app).render(
                                pageW = pageW,
                                pageH = pageH,
                                blocks = blocks,
                                apiPageW = apiResult.page.pageWidth,
                                apiPageH = apiResult.page.pageHeight,
                                markdown = pageMarkdown,
                            )
                        }
                        val outDir = File(app.cacheDir, "ocr_clean").also { it.mkdirs() }
                        val outFile = File(outDir, "clean_${System.currentTimeMillis()}.jpg")
                        withContext(Dispatchers.IO) {
                            outFile.outputStream().use { os ->
                                clean.compress(Bitmap.CompressFormat.JPEG, 92, os)
                            }
                        }
                        clean.recycle()

                        // Build PDF: text at exact positions + image blocks cropped from original
                        _state.update { it.copy(ocrStatus = "Building PDF…") }
                        val pdfFile = File(outDir, "ocr_${System.currentTimeMillis()}.pdf")
                        val originalPageFile = path.let { File(it) }
                        val renderer = CleanPageRenderer(app)
                        val regular = renderer.regularTypeface()
                        val semibold = renderer.semiboldTypeface()
                        withContext(Dispatchers.IO) {
                            OcrPdfBuilder.build(
                                blocks = blocks,
                                apiPageW = apiResult.page.pageWidth,
                                apiPageH = apiResult.page.pageHeight,
                                originalImageFile = originalPageFile,
                                regularTypeface = regular,
                                semiboldTypeface = semibold,
                                outFile = pdfFile,
                            )
                        }

                        _state.update {
                            it.copy(
                                isRunningOcr = false,
                                ocrStatus = "",
                                ocrMarkdown = OcrTextClean.stripMarkdown(apiResult.page.markdown)
                                    .ifBlank { apiResult.page.markdown },
                                ocrCleanUri = Uri.fromFile(outFile),
                                ocrPdfUri = if (pdfFile.exists()) Uri.fromFile(pdfFile) else null,
                                ocrError = null,
                            )
                        }
                    } catch (t: Throwable) {
                        _state.update {
                            it.copy(
                                isRunningOcr = false,
                                ocrStatus = "",
                                ocrError = t.message ?: "Could not build clean page",
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * Call Google Vision (DOCUMENT_TEXT_DETECTION) on the current page → searchable PDF that
     * keeps the original scan image visible, with an invisible OCR text layer on top.
     */
    fun runGoogleVisionSearchablePdf() {
        if (_state.value.isRunningVisionOcr) return
        val page = _state.value.pageUri ?: run {
            viewModelScope.launch {
                navChannel.send(ScanNavEvent.Snackbar("No page to OCR"))
            }
            return
        }
        val key = BuildConfig.GOOGLE_VISION_API_KEY
        if (key.isBlank()) {
            viewModelScope.launch {
                navChannel.send(ScanNavEvent.Snackbar("Set GOOGLE_VISION_API_KEY in .env"))
            }
            return
        }
        viewModelScope.launch {
            _state.update {
                it.copy(
                    isRunningVisionOcr = true,
                    visionOcrStatus = "Calling Google Vision…",
                    visionOcrError = null,
                    visionPdfUri = null,
                    visionCleanUri = null,
                    visionMarkdown = "",
                )
            }
            navChannel.send(ScanNavEvent.ToVisionResult)
            val app = getApplication<Application>()
            val path = page.path
            if (path.isNullOrBlank()) {
                _state.update {
                    it.copy(isRunningVisionOcr = false, visionOcrStatus = "", visionOcrError = "Page path missing")
                }
                return@launch
            }
            val client = GoogleVisionOcrClient(key)
            val apiResult = client.processJpegFile(File(path))
            when (apiResult) {
                is GoogleVisionOcrClient.Result.Err -> {
                    _state.update {
                        it.copy(
                            isRunningVisionOcr = false,
                            visionOcrStatus = "",
                            visionOcrError = apiResult.message,
                        )
                    }
                }
                is GoogleVisionOcrClient.Result.Ok -> {
                    try {
                        val outDir = File(app.cacheDir, "ocr_clean").also { it.mkdirs() }
                        val blocks = apiResult.page.blocks
                        val usedFallbackLayout = blocks.isEmpty() && apiResult.page.markdown.isNotBlank()
                        val layoutBlocks = if (usedFallbackLayout) {
                            navChannel.send(
                                ScanNavEvent.Snackbar("No box layout from API — layout estimated"),
                            )
                            OcrTextClean.markdownFallbackBlocks(apiResult.page.markdown)
                        } else {
                            blocks
                        }
                        val renderBlocks = OcrLayoutMath.expandMultilineToLineBlocks(layoutBlocks)

                        _state.update { it.copy(visionOcrStatus = "Building clean layout…") }
                        val pageW = _state.value.pageWidth.coerceAtLeast(1)
                        val pageH = _state.value.pageHeight.coerceAtLeast(1)
                        val clean = withContext(Dispatchers.Default) {
                            CleanPageRenderer(app).render(
                                pageW = pageW,
                                pageH = pageH,
                                blocks = renderBlocks,
                                apiPageW = apiResult.page.pageWidth,
                                apiPageH = apiResult.page.pageHeight,
                            )
                        }
                        val cleanFile = File(outDir, "vision_clean_${System.currentTimeMillis()}.jpg")
                        withContext(Dispatchers.IO) {
                            cleanFile.outputStream().use { os ->
                                clean.compress(Bitmap.CompressFormat.JPEG, 92, os)
                            }
                        }
                        clean.recycle()

                        _state.update { it.copy(visionOcrStatus = "Building searchable PDF…") }
                        val pdfFile = File(outDir, "vision_${System.currentTimeMillis()}.pdf")
                        withContext(Dispatchers.IO) {
                            OcrPdfBuilder.buildSearchable(
                                blocks = blocks,
                                apiPageW = apiResult.page.pageWidth,
                                apiPageH = apiResult.page.pageHeight,
                                originalImageFile = File(path),
                                outFile = pdfFile,
                            )
                        }
                        _state.update {
                            it.copy(
                                isRunningVisionOcr = false,
                                visionOcrStatus = "",
                                visionMarkdown = apiResult.page.markdown,
                                visionPdfUri = if (pdfFile.exists()) Uri.fromFile(pdfFile) else null,
                                visionCleanUri = if (cleanFile.exists()) Uri.fromFile(cleanFile) else null,
                                visionOcrError = null,
                            )
                        }
                    } catch (t: Throwable) {
                        _state.update {
                            it.copy(
                                isRunningVisionOcr = false,
                                visionOcrStatus = "",
                                visionOcrError = t.message ?: "Could not build searchable PDF",
                            )
                        }
                    }
                }
            }
        }
    }

    fun copyVisionMarkdown(context: Context) {
        val text = _state.value.visionMarkdown
        if (text.isBlank()) return
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("OCR", text))
        viewModelScope.launch {
            navChannel.send(ScanNavEvent.Snackbar("Copied"))
        }
    }

    fun saveVisionPdf() {
        val uri = _state.value.visionPdfUri ?: return
        val app = getApplication<Application>()
        viewModelScope.launch {
            val pdfFile = uri.path?.let { File(it) } ?: return@launch
            val name = "TileOCR_Searchable_${System.currentTimeMillis()}.pdf"
            val saved = withContext(Dispatchers.IO) {
                MediaSaver.savePdfToDownloads(app, pdfFile, name)
            }
            navChannel.send(
                ScanNavEvent.Snackbar(if (saved != null) "PDF saved to Downloads" else "Could not save PDF"),
            )
        }
    }

    fun copyOcrMarkdown(context: Context) {
        val text = _state.value.ocrMarkdown
        if (text.isBlank()) return
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("OCR", text))
        viewModelScope.launch {
            navChannel.send(ScanNavEvent.Snackbar("Copied"))
        }
    }

    fun saveOcrPdf() {
        val uri = _state.value.ocrPdfUri ?: return
        val app = getApplication<Application>()
        viewModelScope.launch {
            val pdfFile = uri.path?.let { File(it) } ?: return@launch
            val name = "TileOCR_${System.currentTimeMillis()}.pdf"
            val saved = withContext(Dispatchers.IO) {
                MediaSaver.savePdfToDownloads(app, pdfFile, name)
            }
            navChannel.send(
                ScanNavEvent.Snackbar(if (saved != null) "PDF saved to Downloads" else "Could not save PDF"),
            )
        }
    }

    fun downloadPageImage() {
        val uri = _state.value.pageUri ?: return
        val app = getApplication<Application>()
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                MediaSaver.saveToGallery(
                    app,
                    uri,
                    "page_${System.currentTimeMillis()}.jpg",
                )
            }
            navChannel.send(
                ScanNavEvent.Snackbar(if (ok) "Saved to gallery" else "Could not save"),
            )
        }
    }

    fun saveActiveImage(isClean: Boolean) {
        val uri = when {
            isClean && _state.value.ocrCleanUri != null -> _state.value.ocrCleanUri
            isClean && _state.value.visionCleanUri != null -> _state.value.visionCleanUri
            else -> _state.value.pageUri
        }
        uri ?: return
        val app = getApplication<Application>()
        viewModelScope.launch {
            val name = if (isClean) {
                "ocr_clean_${System.currentTimeMillis()}.jpg"
            } else {
                "ocr_original_${System.currentTimeMillis()}.jpg"
            }
            val ok = withContext(Dispatchers.IO) {
                MediaSaver.saveToGallery(app, uri, name)
            }
            navChannel.send(
                ScanNavEvent.Snackbar(if (ok) "Saved to gallery" else "Could not save"),
            )
        }
    }

    fun renameScan(id: String, newTitle: String) {
        viewModelScope.launch(Dispatchers.IO) {
            library.updateTitle(id, newTitle)
            val scans = library.list()
            withContext(Dispatchers.Main) {
                _state.update { it.copy(library = scans) }
            }
        }
    }

    fun buildCombinedPdf(scanIds: List<String>) {
        if (scanIds.size < 2) return
        if (_state.value.isBuildingBatchPdf) return
        val key = BuildConfig.MISTRAL_API_KEY
        if (key.isBlank()) {
            viewModelScope.launch { navChannel.send(ScanNavEvent.Snackbar("Set mistral_api_key in .env")) }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(isBuildingBatchPdf = true, batchPdfProgress = "", batchPdfUri = null) }
            try {
                val app = getApplication<Application>()
                val client = MistralOcrClient(key)
                val renderer = CleanPageRenderer(app)
                val regular = renderer.regularTypeface()
                val semibold = renderer.semiboldTypeface()
                val inputs = mutableListOf<OcrPdfBuilder.PdfPageInput>()

                scanIds.forEachIndexed { idx, id ->
                    _state.update { it.copy(batchPdfProgress = "Page ${idx + 1} / ${scanIds.size}") }
                    val scan = withContext(Dispatchers.IO) { library.get(id) } ?: return@forEachIndexed
                    val path = scan.pageUri.path ?: return@forEachIndexed
                    val file = File(path)
                    if (!file.exists()) return@forEachIndexed
                    val result = client.processJpegFile(file)
                    if (result is MistralOcrClient.Result.Ok) {
                        var blocks = result.page.blocks
                        if (blocks.isEmpty() && result.page.markdown.isNotBlank()) {
                            blocks = OcrTextClean.markdownFallbackBlocks(result.page.markdown)
                            navChannel.send(
                                ScanNavEvent.Snackbar(
                                    "Page ${idx + 1}: no box layout from API — layout estimated",
                                ),
                            )
                        }
                        blocks = OcrLayoutMath.expandMultilineToLineBlocks(blocks)
                        inputs.add(
                            OcrPdfBuilder.PdfPageInput(
                                blocks = blocks,
                                apiPageW = result.page.pageWidth,
                                apiPageH = result.page.pageHeight,
                                originalImageFile = file,
                            ),
                        )
                    }
                }

                if (inputs.isEmpty()) {
                    _state.update { it.copy(isBuildingBatchPdf = false, batchPdfProgress = "") }
                    navChannel.send(ScanNavEvent.Snackbar("No pages could be processed"))
                    return@launch
                }

                _state.update { it.copy(batchPdfProgress = "Writing PDF…") }
                val outDir = File(app.cacheDir, "ocr_clean").also { it.mkdirs() }
                val pdfFile = File(outDir, "batch_${System.currentTimeMillis()}.pdf")
                withContext(Dispatchers.IO) {
                    OcrPdfBuilder.buildMultiPage(
                        pages = inputs,
                        regularTypeface = regular,
                        semiboldTypeface = semibold,
                        outFile = pdfFile,
                    )
                }
                val name = "TileOCR_${System.currentTimeMillis()}.pdf"
                val saved = withContext(Dispatchers.IO) {
                    MediaSaver.savePdfToDownloads(app, pdfFile, name)
                }
                _state.update {
                    it.copy(isBuildingBatchPdf = false, batchPdfProgress = "", batchPdfUri = saved)
                }
                navChannel.send(
                    ScanNavEvent.Snackbar(if (saved != null) "PDF saved to Downloads" else "Could not save PDF"),
                )
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                _state.update { it.copy(isBuildingBatchPdf = false, batchPdfProgress = "") }
                navChannel.send(ScanNavEvent.Snackbar(t.message ?: "Failed to build PDF"))
            }
        }
    }

    fun retake() {
        startFreshSession()
        viewModelScope.launch { navChannel.send(ScanNavEvent.ToCamera) }
    }

    fun clearFeatureWarn() {
        _state.update { it.copy(featureWarn = null) }
    }

    fun goHome() {
        refreshLibrary()
        viewModelScope.launch { navChannel.send(ScanNavEvent.ToHome) }
    }

    companion object {
        const val MIN_FEATURES = 40
    }
}
