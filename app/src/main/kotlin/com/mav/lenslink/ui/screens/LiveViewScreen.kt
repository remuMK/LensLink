/*
 * LiveViewScreen.kt — remote viewfinder:
 *   • MJPEG preview frames from gp_camera_capture_preview drawn on a Compose
 *     Canvas with fit-scaling, pinch-to-zoom and pan
 *   • overlays: rule-of-thirds / golden-ratio / crosshair grid, live RGB
 *     histogram, zebra (clipping) warnings
 *   • remote shutter release + control sheet + Bulb toggle
 */
package com.mav.lenslink.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.GridOn
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Whatshot
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mav.lenslink.appContainer
import com.mav.lenslink.camera.Panel
import com.mav.lenslink.models.ConnState
import kotlin.math.min
import kotlin.math.roundToInt

/** Grid overlay style; the toolbar cycles through these. */
enum class GridStyle {
    NONE,
    THIRDS,
    GOLDEN,
    CROSS;

    fun next(): GridStyle = entries[(ordinal + 1) % entries.size]
}

@Composable
fun LiveViewScreen() {
    val repo = appContainer().repository
    val connection by repo.connection.collectAsStateWithLifecycle()
    val frame by repo.preview.collectAsStateWithLifecycle()
    val histogram by repo.histogram.collectAsStateWithLifecycle()
    val zebra by repo.zebra.collectAsStateWithLifecycle()
    val panel by repo.panel.collectAsStateWithLifecycle()
    val fps by repo.fps.collectAsStateWithLifecycle()

    var showControls by rememberSaveable { mutableStateOf(false) }
    var gridStyle by rememberSaveable { mutableStateOf(GridStyle.THIRDS) }

    val connected = connection is ConnState.Connected

    LaunchedEffect(connected) {
        if (connected) repo.startLiveView() else repo.stopLiveView()
    }
    DisposableEffect(Unit) {
        onDispose { repo.stopLiveView() }
    }

    Column(Modifier.fillMaxSize()) {
        // -------- viewport --------------------------------------------
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            Viewport(
                frame = frame,
                histogram = histogram,
                zebraImage = remember(zebra) { zebra?.asImageBitmap() },
                panel = panel,
                gridStyle = gridStyle,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(20.dp)),
            )

            if (frame == null) {
                Column(
                    Modifier
                        .align(Alignment.Center)
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        if (connected) "Starting live view…" else "Camera not connected",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        if (connected)
                            "EOS live view boots on the first preview call — this " +
                                "takes a few seconds and runs at USB2 speed (≈6–10 fps)."
                        else "Connect on the Connect tab first.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // -------- telemetry (top-left) ---------------------------------
            Row(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TelemetryChip("%.1f fps".format(fps))
                (connection as? ConnState.Connected)?.let { state ->
                    TelemetryChip("Batt ${state.info.batteryText}")
                }
            }

            // -------- overlay toolbar (top-right) --------------------------
            Column(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OverlayIconButton(icon = { Icon(Icons.Rounded.GridOn, "Grid style") }) {
                    gridStyle = gridStyle.next()
                }
                OverlayIconButton(
                    icon = {
                        Icon(
                            Icons.Rounded.Equalizer,
                            "Histogram",
                            tint = if (panel.showHistogram) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                        )
                    },
                ) {
                    repo.togglePanel { it.copy(showHistogram = !it.showHistogram) }
                }
                OverlayIconButton(
                    icon = {
                        Icon(
                            Icons.Rounded.Whatshot,
                            "Clipping warnings",
                            tint = if (panel.showZebra) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurface,
                        )
                    },
                ) {
                    repo.togglePanel { it.copy(showZebra = !it.showZebra) }
                }
            }
        }

        // -------- control bar ------------------------------------------
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 14.dp, horizontal = 24.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = {
                if (panel.zoom > Panel.MIN_ZOOM + 0.01f) repo.resetTransform()
            }) {
                Icon(
                    Icons.Rounded.Whatshot,
                    contentDescription = "Reset zoom",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            ShutterButton(
                enabled = connected,
                onClick = { repo.captureAsync() },
            )

            IconButton(onClick = { showControls = true }) {
                Icon(Icons.Rounded.Tune, contentDescription = "Camera controls")
            }
        }

        if (showControls) {
            ControlsSheet(onDismiss = { showControls = false })
        }
    }
}

@Composable
private fun OverlayIconButton(
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.background(
            MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.72f),
            RoundedCornerShape(14.dp),
        ),
    ) { icon() }
}

/** status pill used for fps / battery readouts */
@Composable
private fun TelemetryChip(text: String) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.72f),
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

/** M3 expressive shutter release: big round FAB with a ring. */
@Composable
private fun ShutterButton(enabled: Boolean, onClick: () -> Unit) {
    FloatingActionButton(
        onClick = onClick,
        modifier = Modifier.size(76.dp),
        containerColor = if (enabled) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = if (enabled) MaterialTheme.colorScheme.onPrimary
        else MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Icon(
            Icons.Rounded.CameraAlt,
            contentDescription = "Capture",
            modifier = Modifier.size(34.dp),
        )
    }
}

/* ------------------------------------------------------------------ */
/* viewport canvas                                                     */
/* ------------------------------------------------------------------ */

@Composable
private fun Viewport(
    frame: ImageBitmap?,
    histogram: IntArray?,
    zebraImage: ImageBitmap?,
    panel: Panel,
    gridStyle: GridStyle,
    modifier: Modifier = Modifier,
) {
    val repo = appContainer().repository
    var viewW by remember { mutableStateOf(0f) }
    var viewH by remember { mutableStateOf(0f) }

    val transformState = rememberTransformableState { zoom, pan, _ ->
        repo.applyTransform(zoom, pan.x, pan.y, viewW, viewH)
    }

    Canvas(
        modifier
            .onSizeChanged { size ->
                viewW = size.width.toFloat()
                viewH = size.height.toFloat()
            }
            .transformable(transformState),
    ) {
        drawRect(Color(0xFF06090A))

        val image = frame ?: return@Canvas
        if (image.width <= 0 || image.height <= 0) return@Canvas
        val rect = fittedRect(image.width, image.height, size.width, size.height, panel)

        drawScaled(image, rect, FilterQuality.Low)

        if (panel.showZebra && zebraImage != null) {
            drawScaled(zebraImage, rect, FilterQuality.Nearest, alpha = 0.85f)
        }

        when (gridStyle) {
            GridStyle.THIRDS -> drawGrid(rect, listOf(1.0 / 3, 2.0 / 3))
            GridStyle.GOLDEN -> drawGrid(rect, listOf(0.382, 0.618))
            GridStyle.CROSS -> drawCross(rect)
            GridStyle.NONE -> Unit
        }

        if (panel.showHistogram && histogram != null) {
            drawHistogram(
                histogram,
                size.width - 12.dp.toPx() - HISTOGRAM_W.dp.toPx(),
                12.dp.toPx(),
                HISTOGRAM_W.dp.toPx(),
                HISTOGRAM_H.dp.toPx(),
            )
        }
    }
}

private const val HISTOGRAM_W = 128
private const val HISTOGRAM_H = 48

private fun DrawScope.drawScaled(
    image: ImageBitmap,
    rect: Rect,
    quality: FilterQuality,
    alpha: Float = 1f,
) {
    drawImage(
        image,
        dstOffset = IntOffset(rect.left.roundToInt(), rect.top.roundToInt()),
        dstSize = IntSize(
            rect.width.roundToInt().coerceAtLeast(1),
            rect.height.roundToInt().coerceAtLeast(1),
        ),
        filterQuality = quality,
        alpha = alpha,
    )
}

/**
 * Fit-scales a frame into the viewport, centered, applying the zoom/pan of
 * [panel] (zoom ≥ 1 always covers at least the fitted rectangle).
 */
private fun fittedRect(
    imageW: Int,
    imageH: Int,
    viewW: Float,
    viewH: Float,
    panel: Panel,
): Rect {
    val base = min(viewW / imageW, viewH / imageH)
    val scale = base * panel.zoom
    val w = imageW * scale
    val h = imageH * scale
    val left = (viewW - w) / 2f + panel.panX
    val top = (viewH - h) / 2f + panel.panY
    return Rect(left, top, left + w, top + h)
}

private fun DrawScope.drawGrid(rect: Rect, positions: List<Double>) {
    positions.forEach { f ->
        val x = rect.left + (rect.width * f).toFloat()
        drawLine(
            Color.White.copy(alpha = 0.65f),
            Offset(x, rect.top),
            Offset(x, rect.bottom),
            strokeWidth = 1.5.dp.toPx(),
            cap = StrokeCap.Round,
        )
        val y = rect.top + (rect.height * f).toFloat()
        drawLine(
            Color.White.copy(alpha = 0.65f),
            Offset(rect.left, y),
            Offset(rect.right, y),
            strokeWidth = 1.5.dp.toPx(),
            cap = StrokeCap.Round,
        )
    }
}

private fun DrawScope.drawCross(rect: Rect) {
    val cx = rect.center.x
    val cy = rect.center.y
    val len = 18.dp.toPx()
    drawLine(
        Color.White.copy(alpha = 0.85f),
        Offset(cx - len, cy),
        Offset(cx + len, cy),
        strokeWidth = 1.5.dp.toPx(),
        cap = StrokeCap.Round,
    )
    drawLine(
        Color.White.copy(alpha = 0.85f),
        Offset(cx, cy - len),
        Offset(cx, cy + len),
        strokeWidth = 1.5.dp.toPx(),
        cap = StrokeCap.Round,
    )
}

/** Live RGB histogram mini-panel (drawn top-right of the frame). */
private fun DrawScope.drawHistogram(
    histogram: IntArray,
    x: Float,
    y: Float,
    w: Float,
    h: Float,
) {
    var max = 1
    for (bin in 0 until 768) {
        if (histogram[bin] > max) max = histogram[bin]
    }
    drawRoundRect(
        Color(0xB3101214),
        topLeft = Offset(x, y),
        size = Size(w, h),
        cornerRadius = CornerRadius(10f, 10f),
    )

    val channels = listOf(
        Color(0xAAFF5252), // R
        Color(0xAA8BC34A), // G
        Color(0xAA40C4FF), // B
    )
    channels.forEachIndexed { c, color ->
        val path = Path()
        path.moveTo(x, y + h)
        for (bin in 0 until 256) {
            val px = x + w * bin / 255f
            val py = y + h - histogram[c * 256 + bin] * h / max
            path.lineTo(px, py)
        }
        path.lineTo(x + w, y + h)
        path.close()
        drawPath(path, color)
    }
}
