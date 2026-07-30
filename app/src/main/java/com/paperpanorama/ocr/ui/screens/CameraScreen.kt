package com.paperpanorama.ocr.ui.screens

import android.graphics.Bitmap
import android.provider.Settings
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.OrientationEventListener
import android.view.ViewGroup
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.GridOff
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.compose.AsyncImage
import com.paperpanorama.ocr.camera.LivePageAnalyzer
import com.paperpanorama.ocr.camera.StabilityMonitor
import com.paperpanorama.ocr.capture.PageSpaceTracker
import com.paperpanorama.ocr.domain.BandScanState
import com.paperpanorama.ocr.domain.CaptureFrame
import com.paperpanorama.ocr.domain.CaptureMode
import com.paperpanorama.ocr.domain.CoverageSnapshot
import com.paperpanorama.ocr.domain.LivePageHint
import com.paperpanorama.ocr.ui.theme.CameraChrome
import com.paperpanorama.ocr.ui.theme.PaperPanoramaTheme
import com.paperpanorama.ocr.ui.theme.Ink
import com.paperpanorama.ocr.ui.theme.Lime400
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.Executors

/** How long the phone must stay still before auto-shutter. */
private const val STABLE_HOLD_MS = 450L

@Composable
fun CameraScreen(
    mode: CaptureMode,
    frames: List<CaptureFrame>,
    featureWarn: String?,
    coverage: CoverageSnapshot,
    mosaicThumb: Bitmap? = null,
    isIngestingCapture: Boolean,
    lastIngestAccepted: Boolean? = null,
    pageSpaceTracker: PageSpaceTracker? = null,
    sessionDir: File,
    onModeChange: (CaptureMode) -> Unit,
    onCaptured: (File, Int, Int) -> Unit,
    onRemoveFrame: (Int) -> Unit,
    onMoveFrame: (Int, Int) -> Unit,
    onDonePanorama: () -> Unit,
    onBack: () -> Unit,
    onClearWarn: () -> Unit,
    onLivePageMapped: () -> Unit = {},
    onConsumeIngestResult: () -> Unit = {},
) {
    PaperPanoramaTheme(cameraChrome = true) {
        val context = LocalContext.current
        val view = LocalView.current
        val lifecycleOwner = LocalLifecycleOwner.current
        val snackbar = remember { SnackbarHostState() }
        val scope = rememberCoroutineScope()
        val mainExecutor = remember { ContextCompat.getMainExecutor(context) }
        val reduceMotion = remember {
            runCatching {
                Settings.Global.getFloat(
                    context.contentResolver,
                    Settings.Global.ANIMATOR_DURATION_SCALE,
                    1f,
                ) == 0f
            }.getOrDefault(false)
        }

        var flashOn by remember { mutableStateOf(false) }
        var gridOn by remember { mutableStateOf(true) }
        var targetRotation by remember { mutableIntStateOf(android.view.Surface.ROTATION_0) }
        var shutterScale by remember { mutableFloatStateOf(1f) }
        var focusPoint by remember { mutableStateOf<Offset?>(null) }
        var pendingDeleteIndex by remember { mutableStateOf<Int?>(null) }
        val shutterAnim by animateFloatAsState(
            targetValue = shutterScale,
            animationSpec = tween(if (reduceMotion) 0 else 150),
            label = "shutter",
        )

        val imageCapture = remember {
            ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .build()
        }
        val imageAnalysis = remember {
            ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build()
        }
        var camera by remember { mutableStateOf<Camera?>(null) }
        var cameraBound by remember { mutableStateOf(false) }
        val cameraExecutor = remember { Executors.newSingleThreadExecutor() }

        val stability = remember { StabilityMonitor(context) }
        var shaky by remember { mutableStateOf(false) }
        var needsMove by remember { mutableStateOf(false) }
        var autoCapturing by remember { mutableStateOf(false) }
        var pendingTile by remember { mutableIntStateOf(-1) }
        var liveHint by remember { mutableStateOf(LivePageHint.Idle) }
        val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
        val liveAnalyzer = remember {
            LivePageAnalyzer { hint ->
                mainExecutor.execute { liveHint = hint }
            }
        }

        LaunchedEffect(pageSpaceTracker) {
            liveAnalyzer.setPageSpaceTracker(pageSpaceTracker)
        }
        DisposableEffect(Unit) {
            onDispose { liveAnalyzer.setPageSpaceTracker(null) }
        }
        LaunchedEffect(coverage.activeTileIndex) {
            liveAnalyzer.setActiveTile(coverage.activeTileIndex)
        }
        LaunchedEffect(coverage.pageMapped) {
            liveAnalyzer.setPageMapped(coverage.pageMapped)
        }
        LaunchedEffect(liveHint.pageMapped) {
            if (liveHint.pageMapped && !coverage.pageMapped) onLivePageMapped()
        }
        LaunchedEffect(lastIngestAccepted) {
            when (lastIngestAccepted) {
                true -> {
                    stability.markCapturePoint()
                    needsMove = true
                    onConsumeIngestResult()
                }
                false -> {
                    stability.allowImmediateCapture()
                    needsMove = false
                    onConsumeIngestResult()
                }
                null -> Unit
            }
        }

        fun gatesOk(live: LivePageHint, requireMove: Boolean): Boolean {
            if (coverage.readyToFinish) return false
            if (stability.isShaky) return false
            if (requireMove && !stability.movedSinceMark) return false
            if (!live.pageMapped) return false
            if (live.tileQuads.size < CoverageSnapshot.TILE_COUNT) return false
            if (live.trackingLost) return false
            if (!live.featuresOk) return false
            if (!live.iouStable) return false
            if (!live.activeTileAligned) return false
            if (live.pullBack && !coverage.pageMapped) return false
            if (!coverage.pageMapped && !live.pageMapped) return false
            return true
        }

        fun fireCapture() {
            if (autoCapturing || isIngestingCapture) return
            autoCapturing = true
            val tileAtShutter = coverage.activeTileIndex
            pendingTile = tileAtShutter
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            if (!reduceMotion) {
                shutterScale = 0.88f
                scope.launch {
                    delay(120)
                    shutterScale = 1f
                }
            }
            sessionDir.mkdirs()
            val out = File(sessionDir, "tile_${System.currentTimeMillis()}.jpg")
            val opts = ImageCapture.OutputFileOptions.Builder(out).build()
            val rotationSnapshot = targetRotation
            imageCapture.takePicture(
                opts,
                cameraExecutor,
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                        mainExecutor.execute {
                            // Keep autoCapturing true until ingest clears isIngestingCapture.
                            onCaptured(out, rotationSnapshot, tileAtShutter)
                            pendingTile = -1
                            // Do NOT markCapturePoint here — ViewModel signals accept vs recapture.
                            autoCapturing = false
                        }
                    }

                    override fun onError(exception: ImageCaptureException) {
                        Log.e("CameraScreen", "Capture failed", exception)
                        mainExecutor.execute {
                            pendingTile = -1
                            autoCapturing = false
                            stability.allowImmediateCapture()
                            scope.launch { snackbar.showSnackbar("Capture failed — hold still and retry") }
                        }
                    }
                },
            )
        }

        DisposableEffect(Unit) {
            stability.start()
            stability.allowImmediateCapture()
            val ticker = scope.launch {
                while (true) {
                    shaky = stability.isShaky
                    if (stability.movedSinceMark) needsMove = false
                    delay(100)
                }
            }
            val orientationListener = object : OrientationEventListener(context) {
                override fun onOrientationChanged(orientation: Int) {
                    if (orientation == OrientationEventListener.ORIENTATION_UNKNOWN) return
                    val rotation = UseCase.snapToSurfaceRotation(orientation)
                    targetRotation = rotation
                    imageCapture.targetRotation = rotation
                    imageAnalysis.targetRotation = rotation
                }
            }
            orientationListener.enable()
            onDispose {
                ticker.cancel()
                stability.stop()
                orientationListener.disable()
                runCatching {
                    ProcessCameraProvider.getInstance(context).get().unbindAll()
                }
                cameraBound = false
                camera = null
                cameraExecutor.shutdown()
                analysisExecutor.shutdown()
            }
        }

        // Stable auto-capture loop (keys do not include flickering live flags).
        LaunchedEffect(mode) {
            if (mode != CaptureMode.Panorama) return@LaunchedEffect
            stability.allowImmediateCapture()
            needsMove = false
            while (isActive && mode == CaptureMode.Panorama) {
                if (coverage.readyToFinish) {
                    delay(200)
                    continue
                }
                if (isIngestingCapture || autoCapturing) {
                    delay(80)
                    continue
                }
                val softActive =
                    coverage.cells.getOrNull(coverage.activeTileIndex) == BandScanState.Soft
                val requireMove =
                    coverage.acceptedTiles > 0 && !softActive && !coverage.lastShotBlurry
                if (requireMove && !stability.movedSinceMark) {
                    needsMove = true
                    delay(120)
                    continue
                }
                needsMove = false
                if (!gatesOk(liveHint, requireMove = requireMove)) {
                    delay(100)
                    continue
                }
                // Hold still while gates remain true.
                val holdStart = System.currentTimeMillis()
                var ok = true
                while (isActive && System.currentTimeMillis() - holdStart < STABLE_HOLD_MS) {
                    if (!gatesOk(liveHint, requireMove = requireMove) || stability.isShaky) {
                        ok = false
                        break
                    }
                    delay(40)
                }
                if (!ok || !isActive) continue
                if (isIngestingCapture || autoCapturing || coverage.readyToFinish) continue
                if (!gatesOk(liveHint, requireMove = requireMove)) continue
                fireCapture()
                // Wait for shutter + ingest to finish (autoCapturing cleared after onCaptured).
                while (isActive && autoCapturing) delay(40)
                val waitStart = System.currentTimeMillis()
                while (isActive && !isIngestingCapture && System.currentTimeMillis() - waitStart < 2500) {
                    delay(40)
                }
                while (isActive && isIngestingCapture) delay(40)
                delay(300)
            }
        }

        LaunchedEffect(featureWarn) {
            featureWarn?.let {
                snackbar.showSnackbar(it)
                onClearWarn()
            }
        }

        LaunchedEffect(flashOn) {
            camera?.cameraControl?.enableTorch(flashOn)
        }

        LaunchedEffect(focusPoint) {
            if (focusPoint != null) {
                delay(700)
                focusPoint = null
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            AndroidView(
                factory = { ctx ->
                    PreviewView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                        setOnTouchListener { v, event ->
                            if (event.action == android.view.MotionEvent.ACTION_UP) {
                                val factory = meteringPointFactory
                                val point = factory.createPoint(event.x, event.y)
                                val action = FocusMeteringAction.Builder(point).build()
                                camera?.cameraControl?.startFocusAndMetering(action)
                                focusPoint = Offset(event.x, event.y)
                                v.performClick()
                            }
                            true
                        }
                    }
                },
                modifier = Modifier.fillMaxSize(),
                update = { previewView ->
                    if (cameraBound) {
                        // Keep surface linked; do not rebind.
                        return@AndroidView
                    }
                    val providerFuture = ProcessCameraProvider.getInstance(context)
                    providerFuture.addListener({
                        if (cameraBound) return@addListener
                        val provider = providerFuture.get()
                        val preview = Preview.Builder().build().also {
                            it.surfaceProvider = previewView.surfaceProvider
                        }
                        imageAnalysis.setAnalyzer(analysisExecutor, liveAnalyzer)
                        provider.unbindAll()
                        camera = provider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            imageCapture,
                            imageAnalysis,
                        )
                        cameraBound = true
                        camera?.cameraControl?.enableTorch(flashOn)
                    }, mainExecutor)
                },
            )

            if (gridOn) {
                DocumentGridOverlay(modifier = Modifier.fillMaxSize())
            }

            if (mode == CaptureMode.Panorama) {
                PageScanMapOverlay(
                    coverage = coverage,
                    live = liveHint,
                    mosaic = mosaicThumb,
                    reduceMotion = reduceMotion,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            focusPoint?.let { pt ->
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawCircle(
                        color = Lime400.copy(alpha = 0.95f),
                        radius = 28.dp.toPx(),
                        center = pt,
                        style = Stroke(width = 2.dp.toPx()),
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Close camera",
                            tint = Lime400,
                        )
                    }
                    Row {
                        IconButton(
                            onClick = { flashOn = !flashOn },
                            modifier = Modifier.size(48.dp),
                        ) {
                            Icon(
                                if (flashOn) Icons.Filled.FlashOn else Icons.Filled.FlashOff,
                                contentDescription = if (flashOn) "Flash on" else "Flash off",
                                tint = Lime400,
                            )
                        }
                        IconButton(
                            onClick = { gridOn = !gridOn },
                            modifier = Modifier.size(48.dp),
                        ) {
                            Icon(
                                if (gridOn) Icons.Filled.GridOn else Icons.Filled.GridOff,
                                contentDescription = if (gridOn) "Hide grid" else "Show grid",
                                tint = Lime400,
                            )
                        }
                    }
                }

                if (shaky && mode == CaptureMode.Panorama && !needsMove && !autoCapturing && !isIngestingCapture) {
                    Surface(
                        color = Ink.copy(alpha = 0.95f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .padding(8.dp),
                    ) {
                        Text(
                            text = "Hold still…",
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.labelLarge,
                            color = Lime400,
                        )
                    }
                }

                if (mode == CaptureMode.Panorama) {
                    val coachHint = when {
                        coverage.readyToFinish ->
                            "All cells locked — tap Done to stitch"
                        autoCapturing || isIngestingCapture -> "Capturing…"
                        liveHint.activeTileAligned ->
                            "Hold still — capturing ${CoverageSnapshot.tileLabel(coverage.activeTileIndex)}"
                        liveHint.pullBack -> liveHint.hint ?: "Pull back so the whole page shows"
                        liveHint.hint != null -> liveHint.hint!!
                        needsMove && coverage.acceptedTiles > 0 ->
                            "Move to ${CoverageSnapshot.tileLabel(coverage.activeTileIndex)}"
                        shaky -> "Hold still…"
                        else -> coverage.nextHint
                    }
                    GuidedCoachBanner(
                        coverage = coverage,
                        frameCount = frames.size,
                        hintOverride = coachHint,
                        reduceMotion = reduceMotion,
                    )
                }

                Spacer(modifier = Modifier.weight(1f))

                if (mode == CaptureMode.Panorama && frames.isNotEmpty()) {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(96.dp),
                    ) {
                        itemsIndexed(frames, key = { _, f -> f.uri.toString() }) { index, frame ->
                            AnimatedVisibility(
                                visible = true,
                                enter = if (reduceMotion) {
                                    fadeIn(tween(0))
                                } else {
                                    fadeIn(tween(180)) + scaleIn(tween(180), initialScale = 0.85f)
                                },
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Box {
                                        AsyncImage(
                                            model = frame.uri,
                                            contentDescription = "Tile ${index + 1}",
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier
                                                .size(64.dp)
                                                .clip(RoundedCornerShape(6.dp))
                                                .border(1.dp, Lime400, RoundedCornerShape(6.dp)),
                                        )
                                        Icon(
                                            Icons.Filled.Close,
                                            contentDescription = "Delete tile ${index + 1}",
                                            tint = Lime400,
                                            modifier = Modifier
                                                .align(Alignment.TopEnd)
                                                .size(22.dp)
                                                .clickable { pendingDeleteIndex = index },
                                        )
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        IconButton(
                                            onClick = { if (index > 0) onMoveFrame(index, index - 1) },
                                            enabled = index > 0,
                                            modifier = Modifier.size(36.dp),
                                        ) {
                                            Icon(
                                                Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                                                contentDescription = "Move tile left",
                                                tint = Lime400,
                                            )
                                        }
                                        IconButton(
                                            onClick = {
                                                if (index < frames.lastIndex) onMoveFrame(index, index + 1)
                                            },
                                            enabled = index < frames.lastIndex,
                                            modifier = Modifier.size(36.dp),
                                        ) {
                                            Icon(
                                                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                                contentDescription = "Move tile right",
                                                tint = Lime400,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                if (mode == CaptureMode.Panorama && frames.isNotEmpty()) {
                    Text(
                        text = if (coverage.readyToFinish) {
                            "Page grid locked — tap Done when you want to stitch"
                        } else {
                            "${frames.size} photo${if (frames.size == 1) "" else "s"} — tap Done anytime to stitch"
                        },
                        style = MaterialTheme.typography.labelLarge,
                        color = Lime400,
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ModeChip("Scan page", mode == CaptureMode.Panorama) {
                        onModeChange(CaptureMode.Panorama)
                    }
                    ModeChip("One photo", mode == CaptureMode.Single) {
                        onModeChange(CaptureMode.Single)
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    if (mode == CaptureMode.Panorama && frames.isNotEmpty()) {
                        Button(
                            onClick = onDonePanorama,
                            enabled = !isIngestingCapture && !autoCapturing,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Lime400,
                                contentColor = Ink,
                            ),
                        ) {
                            Text("Done")
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (mode == CaptureMode.Single) {
                        Box(
                            modifier = Modifier
                                .size(72.dp)
                                .scale(shutterAnim)
                                .clip(CircleShape)
                                .border(4.dp, Lime400, CircleShape)
                                .semantics { contentDescription = "Capture" }
                                .clickable(enabled = !isIngestingCapture) {
                                    if (shaky) {
                                        scope.launch { snackbar.showSnackbar("Hold still") }
                                        return@clickable
                                    }
                                    fireCapture()
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Surface(
                                color = Ink,
                                shape = CircleShape,
                                modifier = Modifier.size(56.dp),
                            ) {}
                        }
                    } else {
                        // No manual shutter — status only.
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(
                                modifier = Modifier
                                    .size(72.dp)
                                    .border(4.dp, Lime400.copy(alpha = 0.7f), CircleShape),
                                contentAlignment = Alignment.Center,
                            ) {
                                when {
                                    autoCapturing || isIngestingCapture -> {
                                        CircularProgressIndicator(
                                            color = Lime400,
                                            strokeWidth = 3.dp,
                                            modifier = Modifier.size(36.dp),
                                        )
                                    }
                                    needsMove && frames.isNotEmpty() -> {
                                        Text(
                                            "MOVE",
                                            style = MaterialTheme.typography.labelLarge,
                                            color = Lime400,
                                        )
                                    }
                                    else -> {
                                        Text(
                                            "AUTO",
                                            style = MaterialTheme.typography.labelLarge,
                                            color = Lime400,
                                        )
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = when {
                                    coverage.readyToFinish -> "Ready — tap Done"
                                    autoCapturing || isIngestingCapture -> "Capturing…"
                                    needsMove && frames.isNotEmpty() -> "Move camera"
                                    else -> "Hold still to capture"
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = Lime400.copy(alpha = 0.9f),
                            )
                        }
                    }
                }
            }

            SnackbarHost(
                hostState = snackbar,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 160.dp),
            )
        }

        pendingDeleteIndex?.let { index ->
            AlertDialog(
                onDismissRequest = { pendingDeleteIndex = null },
                title = { Text("Delete tile?") },
                text = { Text("Remove tile ${index + 1} from this panorama?") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            onRemoveFrame(index)
                            pendingDeleteIndex = null
                        },
                    ) {
                        Text("Delete")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pendingDeleteIndex = null }) {
                        Text("Cancel")
                    }
                },
            )
        }
    }
}

@Composable
private fun GuidedCoachBanner(
    coverage: CoverageSnapshot,
    frameCount: Int,
    hintOverride: String? = null,
    reduceMotion: Boolean = false,
) {
    val statusLabel = when {
        !coverage.progressDeterminate -> "Mapping…"
        frameCount == 0 -> "${coverage.coveragePercent}%"
        else -> "${coverage.coveragePercent}% · sharp ${coverage.qualityPercent}%"
    }
    val a11yPct = if (coverage.progressDeterminate) {
        "Coverage ${coverage.coveragePercent} percent"
    } else {
        "Mapping page grid"
    }
    Surface(
        color = CameraChrome.copy(alpha = 0.92f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .semantics {
                contentDescription =
                    "${coverage.title}. $a11yPct. ${hintOverride ?: coverage.nextHint}"
            },
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = coverage.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = Lime400,
                )
                Text(
                    text = statusLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = Lime400.copy(alpha = 0.85f),
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            CoverageBar(
                fraction = if (coverage.progressDeterminate) coverage.coveragePercent / 100f else 0f,
                indeterminate = !coverage.progressDeterminate,
                reduceMotion = reduceMotion,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = hintOverride ?: coverage.nextHint,
                style = MaterialTheme.typography.bodyMedium,
                color = Lime400,
            )
        }
    }
}

@Composable
private fun CoverageBar(
    fraction: Float,
    indeterminate: Boolean = false,
    reduceMotion: Boolean = false,
) {
    val f = fraction.coerceIn(0f, 1f)
    val pulse = rememberInfiniteTransition(label = "coveragePulse")
    val pulseFrac by pulse.animateFloat(
        initialValue = 0.15f,
        targetValue = 0.55f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (reduceMotion) 0 else 900, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulseFrac",
    )
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(4.dp)),
    ) {
        drawRect(Lime400.copy(alpha = 0.25f))
        val widthFrac = when {
            !indeterminate -> f
            reduceMotion -> 0.35f
            else -> pulseFrac
        }
        drawRect(
            color = Lime400,
            size = androidx.compose.ui.geometry.Size(size.width * widthFrac, size.height),
        )
    }
}

@Composable
private fun ModeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = Lime400,
            selectedLabelColor = Ink,
            containerColor = CameraChrome,
            labelColor = Lime400,
        ),
        modifier = Modifier.height(40.dp),
    )
}

@Composable
private fun DocumentGridOverlay(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val color = Lime400.copy(alpha = 0.32f)
        val stroke = 1.dp.toPx()
        val thirdW = size.width / 3f
        val thirdH = size.height / 3f
        for (i in 1..2) {
            drawLine(color, Offset(thirdW * i, 0f), Offset(thirdW * i, size.height), stroke)
            drawLine(color, Offset(0f, thirdH * i), Offset(size.width, thirdH * i), stroke)
        }
        val inset = size.minDimension * 0.06f
        drawLine(color, Offset(inset, inset), Offset(size.width - inset, inset), stroke * 1.5f)
        drawLine(
            color,
            Offset(size.width - inset, inset),
            Offset(size.width - inset, size.height - inset),
            stroke * 1.5f,
        )
        drawLine(
            color,
            Offset(size.width - inset, size.height - inset),
            Offset(inset, size.height - inset),
            stroke * 1.5f,
        )
        drawLine(color, Offset(inset, size.height - inset), Offset(inset, inset), stroke * 1.5f)
    }
}
