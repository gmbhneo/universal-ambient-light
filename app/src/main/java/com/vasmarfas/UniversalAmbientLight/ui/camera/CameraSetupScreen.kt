package com.vasmarfas.UniversalAmbientLight.ui.camera

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.vasmarfas.UniversalAmbientLight.R
import com.vasmarfas.UniversalAmbientLight.common.CameraEncoder
import com.vasmarfas.UniversalAmbientLight.common.util.Preferences
import kotlin.math.roundToInt
import kotlin.math.sqrt

// ─────────────────────────────────────────────────────────────────────────────
// CameraSetupScreen
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Full-screen camera setup:
 *  • Edge-to-edge — the canvas fills the SAME area as the main screen's
 *    CameraPreviewBackground so saved corner coordinates match exactly.
 *  • Overlay top-bar (back / reset / save + lens / zoom buttons) — it sits
 *    on top of the preview without consuming vertical space.
 *  • Barrel-distortion slider at the bottom.
 *  • Lens toggle (back ↔ front) and zoom control.
 */
@Composable
fun CameraSetupScreen(onBackClick: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { Preferences(context) }
    val lifecycleOwner = LocalLifecycleOwner.current

    // ── Camera permission ────────────────────────────────────────────────────
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasCameraPermission = granted }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    // ── Corner state (normalized 0..1, same coordinate space as main screen) ─
    val offsetSaver = remember {
        listSaver<Offset, Float>(
            save    = { listOf(it.x, it.y) },
            restore = { Offset(it[0], it[1]) }
        )
    }
    var topLeft     by rememberSaveable(stateSaver = offsetSaver) { mutableStateOf(Offset(0.1f, 0.1f)) }
    var topRight    by rememberSaveable(stateSaver = offsetSaver) { mutableStateOf(Offset(0.9f, 0.1f)) }
    var bottomRight by rememberSaveable(stateSaver = offsetSaver) { mutableStateOf(Offset(0.9f, 0.9f)) }
    var bottomLeft  by rememberSaveable(stateSaver = offsetSaver) { mutableStateOf(Offset(0.1f, 0.9f)) }

    // ── Camera options ───────────────────────────────────────────────────────
    var useFrontCamera by rememberSaveable {
        mutableStateOf(
            prefs.getString(R.string.pref_key_camera_lens_facing, "back") == "front"
        )
    }
    var zoomRatio by rememberSaveable {
        mutableStateOf(
            prefs.getString(R.string.pref_key_camera_zoom_ratio, "1.0")?.toFloatOrNull() ?: 1.0f
        )
    }
    var barrelK by rememberSaveable {
        mutableStateOf(
            prefs.getString(R.string.pref_key_camera_barrel_distortion, "0.0")?.toFloatOrNull() ?: 0.0f
        )
    }

    // ── Load saved corners ───────────────────────────────────────────────────
    LaunchedEffect(Unit) {
        val saved = prefs.getString(R.string.pref_key_camera_corners, null)
        val c = CameraEncoder.parseCornersString(saved)
        topLeft     = Offset(c[0], c[1])
        topRight    = Offset(c[2], c[3])
        bottomRight = Offset(c[4], c[5])
        bottomLeft  = Offset(c[6], c[7])
    }

    // ── Drag state ───────────────────────────────────────────────────────────
    var dragCorner by remember { mutableIntStateOf(-1) }

    // ── Helpers ──────────────────────────────────────────────────────────────
    fun saveAndExit() {
        prefs.putString(
            R.string.pref_key_camera_corners,
            CameraEncoder.cornersToString(floatArrayOf(
                topLeft.x, topLeft.y, topRight.x, topRight.y,
                bottomRight.x, bottomRight.y, bottomLeft.x, bottomLeft.y
            ))
        )
        prefs.putString(R.string.pref_key_camera_lens_facing,
            if (useFrontCamera) "front" else "back")
        prefs.putString(R.string.pref_key_camera_zoom_ratio,
            "%.2f".format(zoomRatio))
        prefs.putString(R.string.pref_key_camera_barrel_distortion,
            "%.3f".format(barrelK))
        onBackClick()
    }

    // ── Full-screen layout (no Scaffold padding — matches main screen) ───────
    Box(modifier = Modifier.fillMaxSize()) {

        if (!hasCameraPermission) {
            // ── Permission denied UI ─────────────────────────────────────────
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(R.string.camera_permission_required),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 32.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                    Text(stringResource(R.string.camera_grant_permission))
                }
            }
        } else {
            // ── Camera preview (fills entire screen, same as main screen) ────
            val cameraSelector = if (useFrontCamera)
                CameraSelector.DEFAULT_FRONT_CAMERA
            else
                CameraSelector.DEFAULT_BACK_CAMERA

            CameraPreviewView(
                lifecycleOwner  = lifecycleOwner,
                cameraSelector  = cameraSelector,
                targetZoomRatio = zoomRatio
            )

            // ── Corner overlay (full-screen, unconstrained dragging) ─────────
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { startOffset ->
                                val w = size.width.toFloat()
                                val h = size.height.toFloat()
                                val corners = listOf(
                                    Offset(topLeft.x * w,     topLeft.y * h),
                                    Offset(topRight.x * w,    topRight.y * h),
                                    Offset(bottomRight.x * w, bottomRight.y * h),
                                    Offset(bottomLeft.x * w,  bottomLeft.y * h)
                                )
                                val threshold = 80f
                                var minDist = Float.MAX_VALUE
                                var minIdx  = -1
                                corners.forEachIndexed { idx, pos ->
                                    val dx = startOffset.x - pos.x
                                    val dy = startOffset.y - pos.y
                                    val dist = sqrt(dx * dx + dy * dy)
                                    if (dist < threshold && dist < minDist) {
                                        minDist = dist
                                        minIdx  = idx
                                    }
                                }
                                dragCorner = minIdx
                            },
                            onDrag = { change, _ ->
                                if (dragCorner < 0) return@detectDragGestures
                                val w  = size.width.toFloat()
                                val h  = size.height.toFloat()
                                val pos = change.position
                                // Allow full 0..1 range so corners can reach the screen edges
                                val nx = (pos.x / w).coerceIn(0f, 1f)
                                val ny = (pos.y / h).coerceIn(0f, 1f)
                                val newOffset = Offset(nx, ny)
                                when (dragCorner) {
                                    0 -> topLeft     = newOffset
                                    1 -> topRight    = newOffset
                                    2 -> bottomRight = newOffset
                                    3 -> bottomLeft  = newOffset
                                }
                            },
                            onDragEnd    = { dragCorner = -1 },
                            onDragCancel = { dragCorner = -1 }
                        )
                    }
            ) {
                drawCornersOverlay(topLeft, topRight, bottomRight, bottomLeft, dragCorner)
            }

            // ── Overlay top-bar ──────────────────────────────────────────────
            // Uses WindowInsets so it aligns below the status bar on all devices.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopStart)
                    .background(Color.Black.copy(alpha = 0.72f))
                    .windowInsetsPadding(WindowInsets.statusBars)
            ) {
                // Row 1: navigation + title + reset + save
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back",
                            tint = Color.White)
                    }
                    Text(
                        text = stringResource(R.string.camera_setup_title),
                        color = Color.White,
                        fontWeight = FontWeight.Medium,
                        fontSize = 18.sp,
                        modifier = Modifier.weight(1f)
                    )
                    // Lens toggle: Back / Front
                    TextButton(onClick = { useFrontCamera = !useFrontCamera }) {
                        Text(
                            text = if (useFrontCamera)
                                stringResource(R.string.camera_lens_front)
                            else
                                stringResource(R.string.camera_lens_back),
                            color = Color(0xFF00E676),
                            fontSize = 13.sp
                        )
                    }
                    IconButton(onClick = {
                        topLeft     = Offset(0.1f, 0.1f)
                        topRight    = Offset(0.9f, 0.1f)
                        bottomRight = Offset(0.9f, 0.9f)
                        bottomLeft  = Offset(0.1f, 0.9f)
                    }) {
                        Icon(Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.camera_setup_reset),
                            tint = Color.White)
                    }
                    IconButton(onClick = { saveAndExit() }) {
                        Icon(Icons.Default.Check,
                            contentDescription = stringResource(R.string.camera_setup_save),
                            tint = Color.White)
                    }
                }

                // Row 2: zoom control
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.camera_zoom_label),
                        color = Color.White.copy(alpha = 0.8f),
                        fontSize = 12.sp,
                        modifier = Modifier.width(46.dp)
                    )
                    IconButton(
                        onClick = { zoomRatio = (zoomRatio - 0.1f).coerceAtLeast(0.5f) },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Text("−", color = Color.White, fontSize = 20.sp)
                    }
                    Text(
                        text = "%.1fx".format(zoomRatio),
                        color = Color(0xFF00E676),
                        fontSize = 13.sp,
                        modifier = Modifier.width(38.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    IconButton(
                        onClick = { zoomRatio = (zoomRatio + 0.1f).coerceAtMost(6.0f) },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Text("+", color = Color.White, fontSize = 20.sp)
                    }
                    Slider(
                        value = zoomRatio,
                        onValueChange = { zoomRatio = it },
                        valueRange = 0.5f..6.0f,
                        modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                        colors = SliderDefaults.colors(
                            thumbColor       = Color(0xFF00E676),
                            activeTrackColor = Color(0xFF00E676)
                        )
                    )
                }
            }

            // ── Bottom controls: barrel distortion + instruction ─────────────
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .background(Color.Black.copy(alpha = 0.72f))
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                // Barrel distortion slider
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.camera_barrel_label),
                        color = Color.White.copy(alpha = 0.8f),
                        fontSize = 12.sp,
                        modifier = Modifier.width(96.dp)
                    )
                    Slider(
                        value = barrelK,
                        onValueChange = { barrelK = (it * 1000).roundToInt() / 1000f },
                        valueRange = -1.0f..1.0f,
                        modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                        colors = SliderDefaults.colors(
                            thumbColor       = Color(0xFF00E676),
                            activeTrackColor = Color(0xFF00E676)
                        )
                    )
                    Text(
                        text = "%.2f".format(barrelK),
                        color = Color(0xFF00E676),
                        fontSize = 12.sp,
                        modifier = Modifier.width(36.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.End
                    )
                }
                Text(
                    text = stringResource(R.string.camera_barrel_hint),
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 10.sp,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                // Instruction
                Text(
                    text = stringResource(R.string.camera_setup_instruction),
                    color = Color.White,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Corner overlay drawing
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Draws the four-corner overlay quad + draggable markers.
 * Pure drawing function used inside a Canvas DrawScope.
 */
fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCornersOverlay(
    topLeft: Offset,
    topRight: Offset,
    bottomRight: Offset,
    bottomLeft: Offset,
    dragCorner: Int = -1
) {
    val w = size.width
    val h = size.height

    val tl = Offset(topLeft.x * w,     topLeft.y * h)
    val tr = Offset(topRight.x * w,    topRight.y * h)
    val br = Offset(bottomRight.x * w, bottomRight.y * h)
    val bl = Offset(bottomLeft.x * w,  bottomLeft.y * h)

    // Semi-transparent dark overlay
    drawRect(Color.Black.copy(alpha = 0.4f))

    // Quad fill + border
    val quadPath = Path().apply {
        moveTo(tl.x, tl.y); lineTo(tr.x, tr.y)
        lineTo(br.x, br.y); lineTo(bl.x, bl.y)
        close()
    }
    drawPath(quadPath, Color.White.copy(alpha = 0.3f))
    val accent = Color(0xFF00E676)
    drawPath(quadPath, accent, style = Stroke(width = 3f))

    // Corner markers
    val corners = listOf(tl, tr, br, bl)
    val labels  = listOf("TL", "TR", "BR", "BL")
    corners.forEachIndexed { idx, pos ->
        val isActive = dragCorner == idx
        val radius   = if (isActive) 27f else 18f
        drawCircle(color = accent, radius = radius, center = pos, style = Stroke(width = 3f))
        drawCircle(
            color  = if (isActive) accent.copy(alpha = 0.8f) else accent.copy(alpha = 0.4f),
            radius = radius - 3f,
            center = pos
        )
        drawContext.canvas.nativeCanvas.drawText(
            labels[idx], pos.x, pos.y + 8f,
            android.graphics.Paint().apply {
                color       = android.graphics.Color.WHITE
                textSize    = 26f
                isAntiAlias = true
                isFakeBoldText = true
                textAlign   = android.graphics.Paint.Align.CENTER
            }
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Camera preview composable
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Camera preview that fills the available space.
 * Only binds the Preview use case — does NOT call unbindAll() so the
 * CameraEncoder's ImageAnalysis in the service stays active.
 * Supports [cameraSelector] and optional [targetZoomRatio].
 */
@Composable
fun CameraPreviewView(
    lifecycleOwner  : androidx.lifecycle.LifecycleOwner = LocalLifecycleOwner.current,
    cameraSelector  : CameraSelector = CameraSelector.DEFAULT_BACK_CAMERA,
    targetZoomRatio : Float = 1.0f
) {
    val context = LocalContext.current

    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType          = PreviewView.ScaleType.FILL_CENTER
        }
    }

    // Re-bind whenever selector or zoom changes
    DisposableEffect(lifecycleOwner, cameraSelector, targetZoomRatio) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        var previewUseCase: Preview? = null
        var boundProvider: ProcessCameraProvider? = null

        cameraProviderFuture.addListener({
            try {
                val provider = cameraProviderFuture.get()
                boundProvider = provider

                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }
                previewUseCase = preview

                val camera = provider.bindToLifecycle(lifecycleOwner, cameraSelector, preview)

                // Apply zoom ratio — clamp to the supported range reported by the camera
                val zoomState = camera.cameraInfo.zoomState.value
                val minZoom   = zoomState?.minZoomRatio ?: 0.5f
                val maxZoom   = zoomState?.maxZoomRatio ?: 6.0f
                val clamped   = targetZoomRatio.coerceIn(minZoom, maxZoom)
                camera.cameraControl.setZoomRatio(clamped)
            } catch (e: Exception) {
                Log.e("CameraPreview", "Camera bind failed", e)
            }
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            previewUseCase?.let { uc ->
                try { boundProvider?.unbind(uc) } catch (_: Exception) {}
            }
        }
    }

    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
}

// ─────────────────────────────────────────────────────────────────────────────
// Main-screen camera preview background (read-only corners overlay)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Full-screen camera preview background shown on the main screen when camera
 * mode is selected. Uses the same coordinate space as CameraSetupScreen so
 * corners align perfectly.
 *
 * @param isCapturing When true the service is using the camera, so we show a
 *   dark background + pulsing indicator instead of a live preview.
 */
@Composable
fun CameraPreviewBackground(isCapturing: Boolean = false) {
    val context = LocalContext.current
    val prefs   = remember { Preferences(context) }

    val hasCameraPermission = remember {
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
    }

    // Load saved corners
    val corners = remember {
        val saved = prefs.getString(R.string.pref_key_camera_corners, null)
        CameraEncoder.parseCornersString(saved)
    }
    val topLeft     = Offset(corners[0], corners[1])
    val topRight    = Offset(corners[2], corners[3])
    val bottomRight = Offset(corners[4], corners[5])
    val bottomLeft  = Offset(corners[6], corners[7])

    // Load saved camera options for the preview
    val cameraSelector = remember {
        if (prefs.getString(R.string.pref_key_camera_lens_facing, "back") == "front")
            CameraSelector.DEFAULT_FRONT_CAMERA
        else
            CameraSelector.DEFAULT_BACK_CAMERA
    }
    val zoomRatio = remember {
        prefs.getString(R.string.pref_key_camera_zoom_ratio, "1.0")?.toFloatOrNull() ?: 1.0f
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (!isCapturing && hasCameraPermission) {
            // Live preview for calibration (uses same selector + zoom as setup)
            CameraPreviewView(cameraSelector = cameraSelector, targetZoomRatio = zoomRatio)
        } else {
            Spacer(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
            )
        }

        // Read-only corner overlay
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawCornersOverlay(topLeft, topRight, bottomRight, bottomLeft)
        }

        // Capturing indicator
        if (isCapturing) {
            val infiniteTransition = rememberInfiniteTransition(label = "capturePulse")
            val alpha by infiniteTransition.animateFloat(
                initialValue = 0.4f, targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation    = tween(1000, easing = LinearEasing),
                    repeatMode   = RepeatMode.Reverse
                ),
                label = "pulseAlpha"
            )
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 32.dp)
                    .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(16.dp))
                    .padding(horizontal = 20.dp, vertical = 10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Canvas(modifier = Modifier.size(12.dp)) {
                        drawCircle(Color.Red.copy(alpha = alpha))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text  = stringResource(R.string.camera_capturing_status),
                        color = Color.White,
                        fontSize = 14.sp
                    )
                }
            }
        }
    }
}
