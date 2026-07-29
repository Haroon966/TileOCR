package com.paperpanorama.ocr.session

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.paperpanorama.ocr.camera.CaptureStore
import com.paperpanorama.ocr.doc.OpenCvDocumentProcessor
import com.paperpanorama.ocr.doc.QuadMath
import com.paperpanorama.ocr.domain.CaptureFrame
import com.paperpanorama.ocr.domain.CaptureMode
import com.paperpanorama.ocr.domain.DocQuad
import com.paperpanorama.ocr.domain.EnhancePreset
import com.paperpanorama.ocr.domain.SavedScan
import com.paperpanorama.ocr.domain.StitchResult
import com.paperpanorama.ocr.library.ScanLibrary
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
    /** Prepare-for-OCR */
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
)

class ScanSessionViewModel(app: Application) : AndroidViewModel(app) {
    private val store = CaptureStore(app)
    private val library = ScanLibrary(app)
    private val normalizer = OpenCvFrameNormalizer(app)
    private val stitcher = OpenCvDocumentStitcher(app, normalizer)
    private val docProcessor = OpenCvDocumentProcessor(app)

    private val _state = MutableStateFlow(ScanUiState(sessionId = store.newSessionId()))
    val state: StateFlow<ScanUiState> = _state.asStateFlow()

    private val navChannel = Channel<ScanNavEvent>(Channel.BUFFERED)
    val navEvents = navChannel.receiveAsFlow()

    private var stitchJob: Job? = null
    private var prepareJob: Job? = null
    @Volatile private var cancelStitch = false

    init {
        refreshLibrary()
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

    fun setMode(mode: CaptureMode) {
        _state.update { it.copy(mode = mode) }
    }

    fun startFreshSession() {
        stitchJob?.cancel()
        prepareJob?.cancel()
        cancelStitch = false
        val id = store.newSessionId()
        _state.value = ScanUiState(
            sessionId = id,
            mode = _state.value.mode,
            library = _state.value.library,
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
            val scan = withContext(Dispatchers.IO) { library.get(id) } ?: return@launch
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
            navChannel.send(ScanNavEvent.ToOcrReady)
        }
    }

    /**
     * Manual crop: use the stitched mosaic when available, otherwise the current
     * page image (library re-crop). Detects edges then opens Prepare.
     */
    fun beginCrop() {
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

    fun deleteScan(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            library.delete(id)
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

    fun addCapturedFile(file: java.io.File, displayRotation: Int) {
        val index = _state.value.frames.size
        val frame = store.frameFromFile(index, file, displayRotation)
        val warn = if (frame.featureCount in 0 until MIN_FEATURES) {
            "Need more text or texture in frame"
        } else {
            null
        }
        _state.update {
            it.copy(
                frames = it.frames + frame,
                featureWarn = warn,
            )
        }
        if (_state.value.mode == CaptureMode.Single) {
            beginStitch()
        }
    }

    fun removeFrameAt(index: Int) {
        _state.update { s ->
            s.copy(frames = s.frames.filterIndexed { i, _ -> i != index }.mapIndexed { i, f -> f.copy(index = i) })
        }
    }

    fun moveFrame(from: Int, to: Int) {
        _state.update { s ->
            if (from !in s.frames.indices || to !in s.frames.indices) return@update s
            val list = s.frames.toMutableList()
            val item = list.removeAt(from)
            list.add(to, item)
            s.copy(frames = list.mapIndexed { i, f -> f.copy(index = i) })
        }
    }

    fun onPanoramaDone() {
        if (_state.value.frames.size < 2) {
            viewModelScope.launch {
                navChannel.send(ScanNavEvent.Snackbar("Capture at least 2 overlapping tiles"))
            }
            return
        }
        beginStitch()
    }

    fun beginStitch() {
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
                stitcher.stitch(
                    frames = normalized,
                    onProgress = { f, msg ->
                        _state.update { it.copy(stitchProgress = f, stitchMessage = msg) }
                    },
                    isCancelled = { cancelStitch },
                )
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                StitchResult.Failed(t.message ?: "Stitch crashed", normalized.firstOrNull()?.uri)
            }
            when (result) {
                is StitchResult.Ok -> {
                    _state.update {
                        it.copy(
                            mosaicUri = result.mosaicUri,
                            usedFallback = result.usedFallback,
                            stitchProgress = 1f,
                        )
                    }
                    autoPrepare(result.mosaicUri)
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
     */
    private suspend fun autoPrepare(mosaicUri: Uri) {
        _state.update { it.copy(stitchMessage = "Cropping page…", isDetectingQuad = true) }
        val quad = runCatching { docProcessor.detectQuad(mosaicUri) }.getOrElse {
            val (w, h) = mosaicUri.path?.let { p -> BitmapDecode.bounds(p) } ?: (1000 to 1000)
            QuadMath.fullFrame(w, h)
        }
        _state.update { it.copy(docQuad = quad, isDetectingQuad = false) }
        try {
            val result = docProcessor.prepareForOcr(mosaicUri, quad, _state.value.enhancePreset)
            _state.update {
                it.copy(
                    isStitching = false,
                    pageUri = result.pageUri,
                    pageWidth = result.width,
                    pageHeight = result.height,
                )
            }
            navChannel.send(ScanNavEvent.ToOcrReady)
            saveResultsToGallery(mosaicUri, result.pageUri)
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            _state.update { it.copy(isStitching = false) }
            navChannel.send(ScanNavEvent.ToPrepare)
        }
    }

    /** Persist to gallery + durable local library so Home can list results. */
    private fun saveResultsToGallery(mosaicUri: Uri?, pageUri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            val stamp = System.currentTimeMillis()
            val pageOk = MediaSaver.saveToGallery(app, pageUri, "page_$stamp.jpg")
            mosaicUri?.let { MediaSaver.saveToGallery(app, it, "mosaic_$stamp.jpg") }
            val w = _state.value.pageWidth
            val h = _state.value.pageHeight
            val existingId = _state.value.libraryScanId
            val saved = if (existingId != null) {
                library.updatePage(existingId, pageUri, w, h)
            } else {
                library.save(pageUri, mosaicUri, w, h)
            }
            val scans = library.list()
            withContext(Dispatchers.Main) {
                _state.update {
                    it.copy(
                        library = scans,
                        libraryScanId = saved?.id ?: it.libraryScanId,
                    )
                }
            }
            if (pageOk || saved != null) {
                val msg = if (existingId != null) "Crop saved" else "Saved to library"
                navChannel.send(ScanNavEvent.Snackbar(msg))
            }
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
                    )
                }
                navChannel.send(ScanNavEvent.ToOcrReady)
                saveResultsToGallery(mosaic, result.pageUri)
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                _state.update {
                    it.copy(
                        isPreparingPage = false,
                        prepareError = t.message ?: "Could not prepare page",
                    )
                }
            }
        }
    }

    /** Handwriting orientation is ambiguous to auto-detect; one tap fixes it. */
    fun rotateOcrPage() {
        val page = _state.value.pageUri ?: return
        viewModelScope.launch {
            val rotated = docProcessor.rotateMosaic90(page)
            if (rotated != page) {
                val newW = _state.value.pageHeight
                val newH = _state.value.pageWidth
                val libId = _state.value.libraryScanId
                _state.update {
                    it.copy(pageUri = rotated, pageWidth = newW, pageHeight = newH)
                }
                launch(Dispatchers.IO) {
                    MediaSaver.saveToGallery(
                        getApplication(),
                        rotated,
                        "page_${System.currentTimeMillis()}_rot.jpg",
                    )
                    if (libId != null) {
                        library.updatePage(libId, rotated, newW, newH)
                        val scans = library.list()
                        withContext(Dispatchers.Main) {
                            _state.update { it.copy(library = scans) }
                        }
                    }
                }
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
