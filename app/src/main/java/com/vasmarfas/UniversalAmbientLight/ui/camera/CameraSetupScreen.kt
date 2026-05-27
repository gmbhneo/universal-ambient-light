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
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

// ─────────────────────────────────────────────────────────────────────────────
// Lens presets
// ─────────────────────────────────────────────────────────────────────────────

private data class LensPreset(
    val label: String,
    val zoomRatio: Float,
    val facing: Int
)

/** Ordered list of preset lenses shown in the dropdown.
 *  CameraX clamps the zoom ratio to what the hardware actually supports,
 *  so it is safe to list ratios the device may not reach. */
private val LENS_PRESETS = listOf(
    LensPreset("Haupt",       1.0f, CameraSelector.LENS_FACING_BACK),
    LensPreset("Weitwinkel",  0.6f, CameraSelector.LENS_FACING_BACK),
    LensPreset("Zoom 2×",     2.0f, CameraSelector.LENS_FACING_BACK),
    LensPreset("Zoom 5×",     5.0f, CameraSelector.LENS_FACING_BACK),
    LensPreset("Frontkamera", 1.0f, CameraSelector.LENS_FACING_FRONT),
)

/** Returns the index of the best-matching preset for saved facing + zoom. */
private fun findPresetIndex(facing: Int, zoom: Float): Int {
    // Exact match first
    LENS_PRESETS.forEachIndexed { i, p ->
        if (p.facing == facing && p.zoomRatio == zoom) return i
    }
    // Closest zoom on the same side
    var best = 0
    var bestDist = Float.MAX_VALUE
    LENS_PRESETS.forEachIndexed { i, p ->
        if (p.facing == facing) {
            val d = abs(p.zoomRatio - zoom)
            if (d < bestDist) { bestDist = d; best = i }
        }
    }
    return best
}

// ─────────────────────────────────────────────────────────────────────────────
// CameraSetupScreen
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Full-screen camera setup:
 *  • Edge-to-edge — the canvas fills the SAME area as the main screen's
 *    CameraPreviewBackground so saved corner coordinates match exactly.
 *  • Compact overlay top-bar (back / title / dropdown / reset / save).
 *  • Barrel-distortion slider at the bottom with live visual feedback:
 *    the green quad border curves to reflect the correction amount.
 */
@OptIn(ExperimentalMaterial3Api::class)
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

    // ── Corner state (normalized 0..1) ───────────────────────────────────────
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

    // ── Lens preset ──────────────────────────────────────────────────────────
    var selectedPresetIdx by rememberSaveable {
        val facing = if (
            prefs.getString(R.string.pref_key_camera_lens_facing, "back") == "front"
        ) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
        val zoom = prefs.getString(R.string.pref_key_camera_zoom_ratio, "1.0")
            ?.toFloatOrNull() ?: 1.0f
        mutableIntStateOf(findPresetIndex(facing, zoom))
    }
    val selectedPreset by remember { derivedStateOf { LENS_PRESETS[selectedPresetIdx] } }

    // ── Barrel distortion ────────────────────────────────────────────────────
    var barrelK by rememberSaveable {
        mutableStateOf(
            prefs.getString(R.string.pref_key_camera_barrel_distortion, "0.0")
                ?.toFloatOrNull() ?: 0.0f
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

    // ── Dropdown state ───────────────────────────────────────────────────────
    var lensDropdownExpanded by remember { mutableStateOf(false) }

    // ── Helpers ──────────────────────────────────────────────────────────────
    fun saveAndExit() {
        prefs.putString(
            R.string.pref_key_camera_corners,
            CameraEncoder.cornersToString(floatArrayOf(
                topLeft.x, topLeft.y, topRight.x, topRight.y,
                bottomRight.x, bottomRight.y, bottomLeft.x, bottomLeft.y
            ))
        )
        prefs.putString(
            R.string.pref_key_camera_lens_facing,
            if (selectedPreset.facing == CameraSelector.LENS_FACING_FRONT) "front" else "back"
        )
        prefs.putString(R.string.pref_key_camera_zoom_ratio,
            "%.2f".format(selectedPreset.zoomRatio))
        prefs.putString(R.string.pref_key_camera_barrel_distortion,
            "%.3f".format(barrelK))
        onBackClick()
    }

    // ── Full-screen layout ───────────────────────────────────────────────────
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
            // ── Camera preview ───────────────────────────────────────────────
            val cameraSelector = if (selectedPreset.facing == CameraSelector.LENS_FACING_FRONT)
                CameraSelector.DEFAULT_FRONT_CAMERA
            else
                CameraSelector.DEFAULT_BACK_CAMERA

            CameraPreviewView(
                lifecycleOwner  = lifecycleOwner,
                cameraSelector  = cameraSelector,
                targetZoomRatio = selectedPreset.zoomRatio
            )

            // ── Corner + barrel-distortion overlay ───────────────────────────
            // barrelK is captured by the lambda so the canvas redraws reactively.
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
                                        minDist = dist; minIdx = idx
                                    }
                                }
                                dragCorner = minIdx
                            },
                            onDrag = { change, _ ->
                                if (dragCorner < 0) return@detectDragGestures
                                val w  = size.width.toFloat()
                                val h  = size.height.toFloat()
                                val pos = change.position
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
                drawCornersOverlay(topLeft, topRight, bottomRight, bottomLeft, dragCorner, barrelK)
            }

            // ── Overlay top-bar ──────────────────────────────────────────────
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
                            tint = Color(0xFF00E676))
                    }
                }

                // Row 2: lens dropdown
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    ExposedDropdownMenuBox(
                        expanded = lensDropdownExpanded,
                        onExpandedChange = { lensDropdownExpanded = it },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedTextField(
                            value = selectedPreset.label,
                            onValueChange = {},
                            readOnly = true,
                            label = {
                                Text(
                                    text = "Kamera",
                                    color = Color.White.copy(alpha = 0.6f),
                                    fontSize = 11.sp
                                )
                            },
                            trailingIcon = {
                                ExposedDropdownMenuDefaults.TrailingIcon(
                                    expanded = lensDropdownExpanded
                                )
                            },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor        = Color(0xFF00E676),
                                unfocusedTextColor      = Color(0xFF00E676),
                                focusedBorderColor      = Color(0xFF00E676),
                                unfocusedBorderColor    = Color.White.copy(alpha = 0.3f),
                                focusedTrailingIconColor   = Color(0xFF00E676),
                                unfocusedTrailingIconColor = Color.White.copy(alpha = 0.6f),
                            ),
                            textStyle = androidx.compose.ui.text.TextStyle(
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor()
                                .height(52.dp)
                        )

                        ExposedDropdownMenu(
                            expanded = lensDropdownExpanded,
                            onDismissRequest = { lensDropdownExpanded = false },
                            modifier = Modifier.background(Color(0xFF1E1E1E))
                        ) {
                            LENS_PRESETS.forEachIndexed { idx, preset ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = preset.label,
                                            color = if (idx == selectedPresetIdx)
                                                Color(0xFF00E676) else Color.White,
                                            fontSize = 14.sp
                                        )
                                    },
                                    onClick = {
                                        selectedPresetIdx = idx
                                        lensDropdownExpanded = false
                                    },
                                    modifier = Modifier.background(
                                        if (idx == selectedPresetIdx)
                                            Color.White.copy(alpha = 0.06f)
                                        else Color.Transparent
                                    )
                                )
                            }
                        }
                    }
                }
            }

            // ── Bottom controls ──────────────────────────────────────────────
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .background(Color.Black.copy(alpha = 0.72f))
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(horizontal = 16.dp, vertical = 10.dp)
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
                        modifier = Modifier.width(112.dp)
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
                // Instruction
                Text(
                    text = stringResource(R.string.camera_setup_instruction),
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 2.dp, bottom = 2.dp)
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
 *
 * When [barrelK] != 0 the quad edges are rendered as quadratic Bézier curves
 * so the user can see the barrel / pincushion correction visually:
 *  - barrelK < 0  → edges bow **outward** (correcting barrel distortion)
 *  - barrelK > 0  → edges bow **inward**  (correcting pincushion distortion)
 *  - barrelK = 0  → straight lines (no correction)
 */
fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCornersOverlay(
    topLeft: Offset,
    topRight: Offset,
    bottomRight: Offset,
    bottomLeft: Offset,
    dragCorner: Int = -1,
    barrelK: Float = 0f
) {
    val w = size.width
    val h = size.height

    val tl = Offset(topLeft.x * w,     topLeft.y * h)
    val tr = Offset(topRight.x * w,    topRight.y * h)
    val br = Offset(bottomRight.x * w, bottomRight.y * h)
    val bl = Offset(bottomLeft.x * w,  bottomLeft.y * h)

    // Semi-transparent dark overlay
    drawRect(Color.Black.copy(alpha = 0.4f))

    val accent = Color(0xFF00E676)

    // Build quad path — straight or curved depending on barrelK
    val quadPath = buildQuadPath(tl, tr, br, bl, barrelK)

    drawPath(quadPath, Color.White.copy(alpha = 0.25f))
    drawPath(quadPath, accent, style = Stroke(width = 3.5f))

    // Corner markers
    val corners = listOf(tl, tr, br, bl)
    val labels  = listOf("TL", "TR", "BR", "BL")
    corners.forEachIndexed { idx, pos ->
        val isActive = dragCorner == idx
        val radius   = if (isActive) 27f else 18f
        drawCircle(color = accent, radius = radius, center = pos, style = Stroke(width = 3f))
        drawCircle(
            color  = if (isActive) accent.copy(alpha = 0.8f) else accent.copy(alpha = 0.35f),
            radius = radius - 3f,
            center = pos
        )
        drawContext.canvas.nativeCanvas.drawText(
            labels[idx], pos.x, pos.y + 8f,
            android.graphics.Paint().apply {
                color          = android.graphics.Color.WHITE
                textSize       = 26f
                isAntiAlias    = true
                isFakeBoldText = true
                textAlign      = android.graphics.Paint.Align.CENTER
            }
        )
    }
}

/**
 * Constructs the quad Path with optionally curved edges.
 *
 * Each edge's Bézier control point is displaced along the outward normal from
 * the quad centre, scaled by [barrelK] and the half-length of the edge.
 * This gives an intuitive "bulge" that matches what the barrel correction does
 * to the actual camera image.
 */
private fun buildQuadPath(
    tl: Offset, tr: Offset, br: Offset, bl: Offset,
    barrelK: Float
): Path {
    val path = Path()
    if (abs(barrelK) < 0.005f) {
        // Straight lines — no visual clutter when correction is off
        path.moveTo(tl.x, tl.y)
        path.lineTo(tr.x, tr.y)
        path.lineTo(br.x, br.y)
        path.lineTo(bl.x, bl.y)
        path.close()
        return path
    }

    // Quad centroid
    val cx = (tl.x + tr.x + br.x + bl.x) / 4f
    val cy = (tl.y + tr.y + br.y + bl.y) / 4f

    val edges = listOf(tl to tr, tr to br, br to bl, bl to tl)
    path.moveTo(tl.x, tl.y)

    for ((start, end) in edges) {
        // Midpoint of this edge
        val mx = (start.x + end.x) / 2f
        val my = (start.y + end.y) / 2f

        // Vector from centroid to midpoint (outward direction)
        val dx = mx - cx
        val dy = my - cy
        val len = sqrt(dx * dx + dy * dy)

        if (len < 1f) {
            path.lineTo(end.x, end.y)
            continue
        }

        // Displacement: negative barrelK → outward bulge (barrel), positive → inward
        // We negate so the visual matches the physical effect of the correction.
        val push  = -barrelK * len * 0.55f
        val cpx   = mx + (dx / len) * push
        val cpy   = my + (dy / len) * push

        path.quadraticBezierTo(cpx, cpy, end.x, end.y)
    }
    path.close()
    return path
}

// ─────────────────────────────────────────────────────────────────────────────
// Camera preview composable
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Camera preview that fills the available space.
 * Only binds the Preview use case — does NOT call unbindAll() so the
 * CameraEncoder's ImageAnalysis in the service stays active.
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
 * mode is selected. Renders the saved barrel distortion curve as well so the
 * overlay matches the setup screen exactly.
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

    val corners = remember {
        val saved = prefs.getString(R.string.pref_key_camera_corners, null)
        CameraEncoder.parseCornersString(saved)
    }
    val topLeft     = Offset(corners[0], corners[1])
    val topRight    = Offset(corners[2], corners[3])
    val bottomRight = Offset(corners[4], corners[5])
    val bottomLeft  = Offset(corners[6], corners[7])

    val savedFacing = remember {
        if (prefs.getString(R.string.pref_key_camera_lens_facing, "back") == "front")
            CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
    }
    val savedZoom = remember {
        prefs.getString(R.string.pref_key_camera_zoom_ratio, "1.0")?.toFloatOrNull() ?: 1.0f
    }
    val cameraSelector = remember {
        if (savedFacing == CameraSelector.LENS_FACING_FRONT)
            CameraSelector.DEFAULT_FRONT_CAMERA
        else
            CameraSelector.DEFAULT_BACK_CAMERA
    }
    val barrelK = remember {
        prefs.getString(R.string.pref_key_camera_barrel_distortion, "0.0")
            ?.toFloatOrNull() ?: 0.0f
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (!isCapturing && hasCameraPermission) {
            CameraPreviewView(cameraSelector = cameraSelector, targetZoomRatio = savedZoom)
        } else {
            Spacer(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
            )
        }

        // Read-only corner overlay — includes barrel curvature
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawCornersOverlay(topLeft, topRight, bottomRight, bottomLeft, barrelK = barrelK)
        }

        if (isCapturing) {
            val infiniteTransition = rememberInfiniteTransition(label = "capturePulse")
            val alpha by infiniteTransition.animateFloat(
                initialValue = 0.4f, targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation  = tween(1000, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse
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