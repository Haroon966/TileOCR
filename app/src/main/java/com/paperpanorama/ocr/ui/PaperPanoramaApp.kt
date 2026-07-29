package com.paperpanorama.ocr.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.paperpanorama.ocr.camera.CaptureStore
import com.paperpanorama.ocr.session.ScanNavEvent
import com.paperpanorama.ocr.session.ScanSessionViewModel
import com.paperpanorama.ocr.ui.navigation.Destinations
import com.paperpanorama.ocr.ui.screens.CameraPermissionDialog
import com.paperpanorama.ocr.ui.screens.CameraScreen
import com.paperpanorama.ocr.ui.screens.HomeScreen
import com.paperpanorama.ocr.ui.screens.OcrReadyScreen
import com.paperpanorama.ocr.ui.screens.PrepareScreen
import com.paperpanorama.ocr.ui.screens.StitchFailureSheet
import com.paperpanorama.ocr.ui.screens.StitchingScreen
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@Composable
fun PaperPanoramaApp(
    viewModel: ScanSessionViewModel = viewModel(),
) {
    val navController = rememberNavController()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = context as? Activity
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var showPermissionRationale by remember { mutableStateOf(false) }
    /** True after user taps Allow — ignore dialog dismiss-as-deny while system prompt is up. */
    var awaitingSystemPermission by remember { mutableStateOf(false) }
    var launchSystemPermission by remember { mutableStateOf(false) }

    fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    fun shouldShowRationale(): Boolean =
        activity != null &&
            ActivityCompat.shouldShowRequestPermissionRationale(
                activity,
                Manifest.permission.CAMERA,
            )

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        awaitingSystemPermission = false
        launchSystemPermission = false
        if (granted) {
            viewModel.onPermissionGranted()
        } else {
            viewModel.onPermissionDenied()
            // Permanently denied → offer Settings
            if (!shouldShowRationale()) {
                scope.launch {
                    val result = snackbarHostState.showSnackbar(
                        message = "Camera blocked. Enable it in Settings.",
                        actionLabel = "Settings",
                        duration = SnackbarDuration.Long,
                    )
                    if (result == SnackbarResult.ActionPerformed) {
                        val intent = Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", context.packageName, null),
                        )
                        context.startActivity(intent)
                    }
                }
            }
        }
    }

    // Launch system dialog only after rationale UI is gone (avoids OEM focus bugs).
    LaunchedEffect(launchSystemPermission) {
        if (launchSystemPermission) {
            awaitingSystemPermission = true
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    fun requestCameraAccess() {
        when {
            hasCameraPermission() -> viewModel.onNewScanClicked(true)
            shouldShowRationale() -> showPermissionRationale = true
            else -> {
                // First ask (or return from Settings): go straight to system prompt
                launchSystemPermission = true
            }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.navEvents.collectLatest { event ->
            when (event) {
                ScanNavEvent.ToHome -> {
                    navController.navigate(Destinations.Home.route) {
                        popUpTo(Destinations.Home.route) { inclusive = true }
                    }
                }
                ScanNavEvent.ToCamera -> {
                    navController.navigate(Destinations.Camera.route) {
                        launchSingleTop = true
                    }
                }
                ScanNavEvent.ToStitching -> {
                    navController.navigate(Destinations.Stitching.route) {
                        launchSingleTop = true
                    }
                }
                ScanNavEvent.ToPrepare -> {
                    navController.navigate(Destinations.Prepare.route) {
                        popUpTo(Destinations.Camera.route) { inclusive = false }
                        launchSingleTop = true
                    }
                }
                ScanNavEvent.ToOcrReady -> {
                    navController.navigate(Destinations.OcrReady.route) {
                        popUpTo(Destinations.Camera.route) { inclusive = false }
                        launchSingleTop = true
                    }
                }
                ScanNavEvent.RequestPermission -> {
                    if (shouldShowRationale()) {
                        showPermissionRationale = true
                    } else {
                        launchSystemPermission = true
                    }
                }
                is ScanNavEvent.Snackbar -> {
                    snackbarHostState.showSnackbar(event.message)
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        NavHost(
            navController = navController,
            startDestination = Destinations.Home.route,
        ) {
            composable(Destinations.Home.route) {
                HomeScreen(
                    scans = state.library,
                    onNewScan = { requestCameraAccess() },
                    onOpenScan = viewModel::openScan,
                    onDeleteScan = viewModel::deleteScan,
                )
            }
            composable(Destinations.Camera.route) {
                val store = remember { CaptureStore(context) }
                CameraScreen(
                    mode = state.mode,
                    frames = state.frames,
                    featureWarn = state.featureWarn,
                    sessionDir = store.sessionDir(state.sessionId),
                    onModeChange = viewModel::setMode,
                    onCaptured = { file, rotation ->
                        viewModel.addCapturedFile(file, rotation)
                    },
                    onRemoveFrame = viewModel::removeFrameAt,
                    onMoveFrame = viewModel::moveFrame,
                    onDonePanorama = viewModel::onPanoramaDone,
                    onBack = {
                        viewModel.goHome()
                        navController.popBackStack(Destinations.Home.route, false)
                    },
                    onClearWarn = viewModel::clearFeatureWarn,
                )
            }
            composable(Destinations.Stitching.route) {
                StitchingScreen(
                    progress = state.stitchProgress,
                    message = state.stitchMessage,
                    onCancel = viewModel::cancelStitch,
                )
                if (state.showFailureSheet) {
                    StitchFailureSheet(
                        reason = state.stitchFailedReason.orEmpty(),
                        hasBestFrame = state.bestFrameUri != null,
                        onUseBest = viewModel::useBestFrame,
                        onRetake = viewModel::dismissFailureRetake,
                        onCancel = viewModel::dismissFailureRetake,
                    )
                }
            }
            composable(Destinations.Prepare.route) {
                PrepareScreen(
                    mosaicUri = state.mosaicUri,
                    usedFallback = state.usedFallback,
                    quad = state.docQuad,
                    enhancePreset = state.enhancePreset,
                    isDetectingQuad = state.isDetectingQuad,
                    isPreparingPage = state.isPreparingPage,
                    prepareError = state.prepareError,
                    onCornerMove = viewModel::updateCorner,
                    onResetQuad = viewModel::resetQuad,
                    onEnhanceChange = viewModel::setEnhancePreset,
                    onRotate90 = viewModel::rotatePrepare90,
                    onRetake = viewModel::retake,
                    onConfirm = viewModel::confirmPrepare,
                    onBack = {
                        if (state.pageUri != null) {
                            navController.navigate(Destinations.OcrReady.route) {
                                launchSingleTop = true
                            }
                        } else {
                            navController.popBackStack(Destinations.Camera.route, false)
                        }
                    },
                )
            }
            composable(Destinations.OcrReady.route) {
                OcrReadyScreen(
                    pageUri = state.pageUri,
                    pageWidth = state.pageWidth,
                    pageHeight = state.pageHeight,
                    onCrop = viewModel::beginCrop,
                    onRotate = viewModel::rotateOcrPage,
                    onRetake = viewModel::retake,
                    onDone = {
                        viewModel.goHome()
                        navController.navigate(Destinations.Home.route) {
                            popUpTo(Destinations.Home.route) { inclusive = true }
                        }
                    },
                )
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }

    if (showPermissionRationale) {
        CameraPermissionDialog(
            onAllow = {
                showPermissionRationale = false
                launchSystemPermission = true
            },
            onDismiss = {
                if (!awaitingSystemPermission) {
                    showPermissionRationale = false
                    viewModel.onPermissionDenied()
                }
            },
        )
    }
}
