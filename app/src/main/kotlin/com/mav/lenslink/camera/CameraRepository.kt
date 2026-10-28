/*
 * CameraRepository.kt — single owner of the native camera session and of the
 * observed state the UI renders: connection lifecycle, camera info, live-view
 * frames, configurable properties, capture and download actions.
 *
 * Threading: every native call funnels through [onCamera], which runs on a
 * single-lane dispatcher, so libgphoto2 (not thread-safe) never sees
 * concurrent access and no lock nesting is possible.
 *
 * The foreground [com.mav.lenslink.service.CameraControlService] keeps this
 * process alive while a session is open.
 */
package com.mav.lenslink.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.mav.cameranativebridge.CameraConfig
import com.mav.cameranativebridge.NativeCameraInterface
import com.mav.lenslink.models.CameraFile
import com.mav.lenslink.models.CameraInfo
import com.mav.lenslink.models.ConnState
import com.mav.lenslink.models.Transport
import java.io.File
import java.util.Locale
import java.util.regex.Pattern
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Overlay panel state (toggles + zoom). */
data class Panel(
    val showHistogram: Boolean = false,
    val showGrid: Boolean = true,
    val showZebra: Boolean = false,
    val zoom: Float = 1f,
    val panX: Float = 0f,
    val panY: Float = 0f,
) {
    companion object {
        const val MIN_ZOOM = 1f
        const val MAX_ZOOM = 5f
    }
}

class CameraRepository(context: Context) {

    companion object {
        private const val TAG = "CameraRepository"

        /** Live-view cadence: USB 2.0 realistically yields ~6–10 fps. */
        private const val PREVIEW_INTERVAL_MS = 90L

        /** Battery / SD card refresh cadence while connected. */
        private const val TELEMETRY_INTERVAL_MS = 25_000L

        /** Properties the control panel surfaces, in order. */
        val KEY_PROPERTIES = listOf(
            "iso",
            "aperture",
            "shutterspeed",
            "exposurecompensation",
            "whitebalance",
            "picturestyle",
            "meteringmode",
            "autoexposuremode",
            "drivemode",
            "capturemode",
            "imageformat",
            "eosmoviesize",
            "flashmode",
            "focusmode",
            "bulb",
        )
    }

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** One-lane channel for every native call (libgphoto2 is not thread-safe). */
    private val nativeDispatcher = Dispatchers.IO.limitedParallelism(1)

    @Volatile private var iface: NativeCameraInterface? = null

    /* ------------------------------------------------------------------ */
    /* observed state                                                      */
    /* ------------------------------------------------------------------ */

    private val _connection = MutableStateFlow<ConnState>(ConnState.Idle)
    val connection: StateFlow<ConnState> = _connection.asStateFlow()

    private val _preview = MutableStateFlow<ImageBitmap?>(null)
    val preview: StateFlow<ImageBitmap?> = _preview.asStateFlow()

    /** 256-bin RGB histogram (channel-major), updated per preview frame. */
    private val _histogram = MutableStateFlow<IntArray?>(null)
    val histogram: StateFlow<IntArray?> = _histogram.asStateFlow()

    /** 1/4-scale zebra (clip) mask bitmap for overexposure warnings. */
    private val _zebra = MutableStateFlow<Bitmap?>(null)
    val zebra: StateFlow<Bitmap?> = _zebra.asStateFlow()

    private val _panel = MutableStateFlow(Panel())
    val panel: StateFlow<Panel> = _panel.asStateFlow()

    private val _properties =
        MutableStateFlow<Map<String, CameraConfig>>(emptyMap())
    val properties: StateFlow<Map<String, CameraConfig>> =
        _properties.asStateFlow()

    private val _liveViewActive = MutableStateFlow(false)
    val liveViewActive: StateFlow<Boolean> = _liveViewActive.asStateFlow()

    private val _fps = MutableStateFlow(0f)
    val fps: StateFlow<Float> = _fps.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _lastCapture = MutableStateFlow<CameraFile?>(null)
    val lastCapture: StateFlow<CameraFile?> = _lastCapture.asStateFlow()

    private val _downloadProgress = MutableStateFlow(0f)
    val downloadProgress: StateFlow<Float> = _downloadProgress.asStateFlow()

    /* ------------------------------------------------------------------ */
    /* native access primitives                                            */
    /* ------------------------------------------------------------------ */

    /** Runs block on the single native lane; throws if there is no session. */
    private suspend fun <T> onCamera(
        block: suspend (NativeCameraInterface) -> T,
    ): T = withContext(nativeDispatcher) {
        val i = iface ?: error("no camera session open")
        block(i)
    }

    private suspend fun <T> onCameraOr(block: suspend (NativeCameraInterface) -> T): T? =
        if (iface == null) null else runCatching { onCamera(block) }
            .onFailure { Log.w(TAG, "camera op failed", it) }
            .getOrNull()

    fun isConnected(): Boolean = iface != null

    fun currentTransport(): Transport? =
        (_connection.value as? ConnState.Connected)?.transport

    /* ------------------------------------------------------------------ */
    /* connect / disconnect                                                */
    /* ------------------------------------------------------------------ */

    fun connectUsb(
        usbManager: android.hardware.usb.UsbManager,
        device: android.hardware.usb.UsbDevice,
    ) = connect(Transport.USB) {
        NativeCameraInterface(appContext).init(usbManager, device)
    }

    fun connectWifi(host: String, port: Int) = connect(Transport.WIFI) {
        NativeCameraInterface(appContext).initWifi(host, port)
    }

    private inline fun connect(
        kind: Transport,
        crossinline open: () -> NativeCameraInterface,
    ) {
        _connection.value = ConnState.Connecting(kind)
        _error.value = null
        scope.launch {
            try {
                stopLiveView()
                disconnectLocked()      // stays on the native lane
                val opened = onCamera { open() }
                iface = opened
                onConnected(kind)
            } catch (t: Throwable) {
                Log.e(TAG, "connect failed", t)
                _error.value = t.message
                _connection.value = ConnState.Failed(
                    kind,
                    t.message?.lineSequence()?.firstOrNull() ?: "connect failed",
                )
                disconnectLocked()
            }
        }
    }

    private suspend fun onConnected(kind: Transport) {
        val info = readInfo(kind)
        Log.i(TAG, "connected: ${info.model} battery=${info.batteryText}")
        _connection.value = ConnState.Connected(kind, info)
        refreshControls()
        startTelemetry()
    }

    fun disconnect() {
        scope.launch { disconnectLocked() }
    }

    /** Idempotent shutdown; must be started while idle (preview stopped). */
    private suspend fun disconnectLocked() {
        stopLiveView()
        onFinishTelemetry()
        onCameraOr { i ->
            i.close()
            null
        }
        iface = null
        _preview.value = null
        _histogram.value = null
        _zebra.value = null
        _properties.value = emptyMap()
        _lastCapture.value = null
        _fps.value = 0f
        // Only the live lifecycle states reset to Idle here; a Failed state
        // is the connect() outcome and must survive the cleanup.
        val current = _connection.value
        if (current is ConnState.Connected || current is ConnState.Connecting) {
            _connection.value = ConnState.Idle
        }
    }

    /* ------------------------------------------------------------------ */
    /* camera info                                                         */
    /* ------------------------------------------------------------------ */

    private suspend fun readInfo(kind: Transport): CameraInfo {
        val summary = onCameraOr { i -> i.getSummary() }.orEmpty()
        val abilitiesModel = onCameraOr { i -> i.getCameraModel() }.orEmpty()
        val bridgeVersion = onCameraOr { i -> i.libraryVersion() }.orEmpty()
        val batteryText = onCameraOr { i -> i.readConfigText("batterylevel") }
            .orEmpty()
        val storages = onCameraOr { i -> i.getStorageInfo().toList() }
            .orEmpty()
        val mainSd = storages.firstOrNull { it.capacityKb > 0 }

        return CameraInfo(
            model = parseModel(summary) ?: abilitiesModel.ifEmpty { "Canon EOS" },
            summary = summary,
            batteryPercent = parseBattery(batteryText),
            batteryText = batteryText.ifEmpty { "unknown" },
            storageTotalGb = mainSd?.capacityKb?.div(1024.0 * 1024.0),
            storageFreeGb = mainSd?.freeKb?.div(1024.0 * 1024.0),
            storageFreeImages = mainSd?.freeImages?.takeIf { it > 0 },
            bridgeVersion = bridgeVersion,
        )
    }

    /** Re-reads model/battery/SD and replaces the Connected info. */
    fun refreshCameraInfo() {
        scope.launch {
            val current = _connection.value as? ConnState.Connected
                ?: return@launch
            runCatching { readInfo(current.transport) }
                .onSuccess { info -> _connection.value = current.copy(info = info) }
                .onFailure { Log.w(TAG, "info refresh failed", it) }
        }
    }

    /* ------------------------------------------------------------------ */
    /* telemetry job: battery + SD, every ~25 s                            */
    /* ------------------------------------------------------------------ */

    private var telemetryJob: Job? = null

    private fun startTelemetry() {
        stopTelemetry()
        telemetryJob = scope.launch {
            while (isActive) {
                delay(TELEMETRY_INTERVAL_MS)
                refreshCameraInfo()
            }
        }
    }

    private fun stopTelemetry() {
        telemetryJob?.cancel()
        telemetryJob = null
    }

    private fun onFinishTelemetry() = stopTelemetry()

    /* ------------------------------------------------------------------ */
    /* live view                                                           */
    /* ------------------------------------------------------------------ */

    private var previewJob: Job? = null

    /**
     * Starts the decode loop: one `gp_camera_capture_preview` per tick
     * (the ptp2 camlib boots the EOS live view itself on the first call).
     */
    fun startLiveView() {
        if (iface == null) return
        if (previewJob?.isActive == true) return
        _liveViewActive.value = true
        previewJob = scope.launch {
            val sb = StringBuilder()
            var lastNs = 0L
            var frames = 0
            var histogramScale = 1L
            var first = true
            try {
                while (isActive && _liveViewActive.value && iface != null) {
                    val bytes = onCamera {
                        runCatching { it.capturePreviewOnce() }.getOrElse {
                            Log.d(TAG, "preview tick skipped: ${it.message}")
                            null
                        }
                    }
                    if (bytes == null) {
                        sb.setLength(0)
                        delay(PREVIEW_INTERVAL_MS)
                        continue
                    }
                    val decoded = withContext(Dispatchers.Default) { decodeFrame(bytes) }
                    decoded.imageBitmap?.let { bmp -> _preview.value = bmp }
                    decoded.histogram?.let { hist -> _histogram.value = hist }
                    decoded.zebra?.let { mask -> _zebra.value = mask }

                    val now = System.nanoTime()
                    if (lastNs != 0L) {
                        val dt = (now - lastNs) / 1_000_000L   // ms
                        if (dt > 0) {
                            val inst = 1000f / dt
                            _fps.value = if (first) inst else _fps.value * 0.6f + inst * 0.4f
                            first = false
                        }
                        lastNs = now
                    } else {
                        lastNs = now
                    }
                    frames++
                    histogramScale = frames.toLong()
                    delay(PREVIEW_INTERVAL_MS)
                }
            } catch (t: Throwable) {
                // session torn down mid-loop: expected on disconnect
                Log.d(TAG, "preview loop ended", t)
            } finally {
                if (_liveViewActive.value) _liveViewActive.value = false
            }
        }
    }

    fun stopLiveView() {
        _liveViewActive.value = false
        previewJob?.cancel()
        previewJob = null
    }

    fun setPanel(p: Panel) {
        _panel.value = p
    }

    /**
     * Frame-space transform from the pinch gesture: clamps zoom to
     * [MIN_ZOOM, MAX_ZOOM] and the pan so the frame never slides fully out
     * of the viewport.
     */
    fun applyTransform(zoomDelta: Float, panX: Float, panY: Float, vw: Float, vh: Float) {
        val p = _panel.value
        val zoom = (p.zoom * zoomDelta).coerceIn(Panel.MIN_ZOOM, Panel.MAX_ZOOM)
        val maxX = (zoom - 1f) * vw / 2f
        val maxY = (zoom - 1f) * vh / 2f
        setPanel(
            p.copy(
                zoom = zoom,
                panX = (p.panX + panX).coerceIn(-maxX, maxX),
                panY = (p.panY + panY).coerceIn(-maxY, maxY),
            ),
        )
    }

    fun resetTransform() = setPanel(
        _panel.value.copy(
            zoom = Panel.MIN_ZOOM,
            panX = 0f,
            panY = 0f,
        ),
    )

    fun zoomIn() = setPanel(
        _panel.value.copy(
            zoom = (_panel.value.zoom * 1.25f).coerceIn(Panel.MIN_ZOOM, Panel.MAX_ZOOM),
        ),
    )

    fun togglePanel(flag: (Panel) -> Panel) = setPanel(flag(_panel.value))

    /* ------------------------------------------------------------------ */
    /* controls (config)                                                   */
    /* ------------------------------------------------------------------ */

    suspend fun refreshControlProperties() {
        val available = onCameraOr { i -> i.listConfigNames().toSet() }
            .orEmpty()
        if (available.isEmpty()) return
        val wanted = KEY_PROPERTIES.filter { it in available }
        val map = LinkedHashMap<String, CameraConfig>()
        for (name in wanted) {
            onCameraOr { i -> i.getConfig(name) }?.let { map[name] = it }
        }
        _properties.value = map
    }

    fun refreshControls() {
        scope.launch { refreshControlProperties() }
    }

    /**
     * Optimistically updates the UI, then applies on camera; on rejection
     * properties are re-synced and the failure surfaces in [error].
     */
    fun setProperty(name: String, value: String) {
        _properties.update { map ->
            val updated = map.toMutableMap()
            updated[name]?.let { updated[name] = it.copy(current = value) }
            updated
        }
        scope.launch {
            try {
                onCamera { i ->
                    val ok = i.setConfig(name, value)
                    if (!ok) error("camera rejected $name=$value")
                }
                refreshControlProperties()
            } catch (t: Throwable) {
                Log.w(TAG, "setConfig($name=$value) failed", t)
                _error.value = t.message
                runCatching { refreshControlProperties() }
            }
        }
    }

    /** Canon EOS bulb: flips the "bulb" config widget (BulbStart/BulbEnd). */
    fun setBulb(start: Boolean) = setProperty("bulb", if (start) "1" else "0")

    /* ------------------------------------------------------------------ */
    /* capture                                                             */
    /* ------------------------------------------------------------------ */

    suspend fun capture(): CameraFile? = try {
        val result = onCamera { i -> i.captureImage() } ?: error("capture returned nothing")
        CameraFile(result.folder.ifEmpty { "/" }, result.name)
    } finally {
        scope.launch { runCatching { refreshCameraInfo() } }
    }.also { _lastCapture.value = it }

    fun captureAsync() {
        scope.launch {
            try {
                val file = capture()
                if (file == null) _error.value = "Capture returned no file"
            } catch (t: Throwable) {
                Log.w(TAG, "capture failed", t)
                _error.value = t.message
            }
        }
    }

    /* ------------------------------------------------------------------ */
    /* files                                                               */
    /* ------------------------------------------------------------------ */

    suspend fun listFiles(): List<CameraFile> = onCamera { i ->
        i.listFiles().mapNotNull { raw ->
            val slash = raw.lastIndexOf('/')
            if (slash <= 0) null
            else CameraFile(
                folder = raw.substring(0, slash + 1),
                name = raw.substring(slash + 1),
            )
        }
    }

    /**
     * Streams a file from the SD card to app-private external storage; the
     * bridge writes through a raw fd in 256 KiB chunks (OOM-safe for RAWs).
     */
    suspend fun downloadTo(folder: String, fileName: String): File =
        onCamera { i ->
            val dir = File(appContext.getExternalFilesDir(null), "camera").apply { mkdirs() }
            val out = File(dir, fileName)
            _downloadProgress.value = 0f
            i.downloadFileTo(folder, fileName, out)
            _downloadProgress.value = 1f
            out
        }

    fun clearError() {
        _error.value = null
    }

    /** Shuts everything down (Application.onTerminate / final UI exit). */
    fun teardown() {
        stopTelemetry()
        stopLiveView()
        scope.cancel()
        iface?.let { runCatching { it.close() } }
        iface = null
    }
}

/* ---------------------------------------------------------------------- */
/* decoding / frame analysis (runs on Dispatchers.Default)                 */
/* ---------------------------------------------------------------------- */

private data class DecodedFrame(
    val imageBitmap: ImageBitmap?,
    /** 256-bin RGB histogram (channel-major): 0..255 R, 256..511 G, 512..767 B. */
    val histogram: IntArray?,
    /** 1/4-scale ARGB mask; clipped pixels get a light red, else transparent. */
    val zebra: Bitmap?,
)

private fun decodeFrame(bytes: ByteArray): DecodedFrame {
    if (bytes.isEmpty()) return DecodedFrame(null, null, null)
    val bitmap = runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
        .getOrNull() ?: return DecodedFrame(null, null, null)
    val histogram = computeHistogram(bitmap)
    val zebra = computeZebra(bitmap)
    return DecodedFrame(bitmap.asImageBitmap(), histogram, zebra)
}

/** 256-bin histogram computed over a small scaled proxy (stride-5 sample). */
private fun computeHistogram(src: Bitmap): IntArray {
    val w = src.width.coerceAtLeast(1)
    val h = src.height.coerceAtLeast(1)
    val target = 160
    val scale = maxOf(1, w / target)
    val sw = w / scale
    val sh = h / scale
    val proxy = if (scale > 1) {
        Bitmap.createScaledBitmap(src, sw, sh, false)
    } else src
    val out = IntArray(768)
    val pixels = IntArray(sw * sh)
    proxy.getPixels(pixels, 0, sw, 0, 0, sw, sh)
    for (p in pixels) {
        out[(p ushr 16) and 0xFF]++                       // R
        out[256 + ((p ushr 8) and 0xFF)]++                // G
        out[512 + (p and 0xFF)]++                         // B
    }
    if (proxy !== src) proxy.recycle()
    return out
}

/** 1/4-scale mask of near-white pixels (zebra / clipping warning). */
private fun computeZebra(src: Bitmap): Bitmap {
    val w = maxOf(1, src.width / 4)
    val h = maxOf(1, src.height / 4)
    val mask = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val proxy = Bitmap.createScaledBitmap(src, w, h, false)
    val pixels = IntArray(w * h)
    proxy.getPixels(pixels, 0, w, 0, 0, w, h)
    for (i in pixels.indices) {
        val p = pixels[i]
        val r = (p ushr 16) and 0xFF
        val g = (p ushr 8) and 0xFF
        val b = p and 0xFF
        pixels[i] = if (r > 250 && g > 250 && b > 250) 0xCCEB5757.toInt() else 0x00000000
        // 0xCCEB5757 = ~80% alpha of M3 error red
    }
    mask.setPixels(pixels, 0, w, 0, 0, w, h)
    proxy.recycle()
    return mask
}

private val MODEL_LINE = Pattern.compile("(?im)^Model\\s*:\\s*(.+?)\\s*$")

private fun parseModel(summary: String): String? {
    val m = MODEL_LINE.matcher(summary)
    return if (m.find()) m.group(1)?.take(64) else null
}

private val BATTERY_NUM = Pattern.compile("(\\d{1,3})\\s*%")

/** Maps "100%"/"75%"/… and "Low" onto 0–100. */
private fun parseBattery(batteryText: String): Int? {
    val m = BATTERY_NUM.matcher(batteryText)
    if (m.find()) return m.group(1)?.toIntOrNull()?.coerceIn(0, 100)
    return when (batteryText.trim().lowercase(Locale.ROOT)) {
        "low" -> 10
        else -> null
    }
}
