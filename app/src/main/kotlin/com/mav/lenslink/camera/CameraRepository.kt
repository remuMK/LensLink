/*
 * CameraRepository.kt — single owner of the native camera session and of the
 * observed state the UI renders: connection lifecycle, camera info, live-view
 * frames, configurable properties, capture and download actions.
 *
 * Threading: every native call funnels through [onCamera], which holds
 * [nativeLock] (an explicit kotlinx Mutex) for the whole call. An earlier
 * `limitedParallelism(1)` lane leapt out of its slot whenever the underlying
 * functions re-dispatched on the generic IO pool, letting two coroutines run
 * libgphoto2 ops at once — the PTP/IP stream desynced and every read of the
 * session degraded into `ptp_ptpip_generic_read: len < 0, -N` socket churn.
 * A Mutex keeps its permit across nested withContext switches.
 *
 * Persistence: thumbnails and per-file metadata are cached on disk (filesDir)
 * and keyed by path+size+mtime, so browsing doesn't re-read the SD card every
 * time; only new files trigger camera round-trips.
 *
 * The foreground [com.mav.lenslink.service.CameraControlService] keeps this
 * process alive while a session is open.
 */
package com.mav.lenslink.camera

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.ContextCompat
import com.mav.cameranativebridge.CameraConfig
import com.mav.cameranativebridge.CameraFileInfo
import com.mav.cameranativebridge.NativeCameraInterface
import com.mav.lenslink.R
import com.mav.lenslink.models.CameraFile
import com.mav.lenslink.models.CameraInfo
import com.mav.lenslink.models.ConnState
import com.mav.lenslink.models.Transport
import java.util.concurrent.atomic.AtomicInteger
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.io.File
import java.security.MessageDigest
import java.util.Collections
import java.util.Locale
import java.util.regex.Pattern
import android.util.LruCache

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

        /** Full battery/SD telemetry refresh, in liveness-probe periods. */
        private const val TELEMETRY_PROBES = 4  // 4 × PROBE_INTERVAL_MS ≈ 24 s

        /** Liveness probe cadence — detect a vanished camera quickly. */
        private const val PROBE_INTERVAL_MS = 6_000L

        /** Consecutive failed probes before "not connected" is declared. */
        private const val LIVENESS_FAIL_THRESHOLD = 2

        /** gp_camera_init is retried before surfacing "gp I/O 7 (-10)". */
        private const val CONNECT_ATTEMPTS = 3
        private const val CONNECT_RETRY_DELAY_MS = 2_500L

        /** Notification channel for download success messages. */
        private const val DOWNLOAD_CHANNEL_ID = "lenslink_downloads"

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

    /* ------------------------------------------------------------ */
    /* callback identity (surface for the last explicit action)      */
    /* ------------------------------------------------------------ */

    /** Result of a public-Folder download batch. */
    data class DownloadResult(
        val savedCount: Int,
        val failedCount: Int,
        /** Human label of where files were saved, e.g. "Download/LensLink". */
        val savedToLabel: String,
    )

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Explicit mutex held for every native call (libgphoto2 not thread-safe). */
    private val nativeLock = Mutex()

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

    /** Status of the last remote capture, for shutter feedback on the
     *  Viewfinder tab: busy → captured file → saved-to label / error. */
    data class CaptureState(
        val busy: Boolean,
        val file: CameraFile? = null,
        /** e.g. "Download/LensLink" once the auto-save finished. */
        val savedToLabel: String? = null,
        val error: String? = null,
    )

    private val _captureState = MutableStateFlow<CaptureState?>(null)
    val captureState: StateFlow<CaptureState?> = _captureState.asStateFlow()

    /** Consecutive failed live-view ticks (0 while streaming); drives the
     *  "live view couldn't start" card with Retry. */
    private val _previewFails = MutableStateFlow(0)
    val previewFails: StateFlow<Int> = _previewFails.asStateFlow()

    /** Bumped when the card content changed (e.g. after a capture) so the
     *  Files screen can reload without the user pressing Refresh. */
    private val _filesVersion = MutableStateFlow(0)
    val filesVersion: StateFlow<Int> = _filesVersion.asStateFlow()

    private val _downloadProgress = MutableStateFlow(0f)
    val downloadProgress: StateFlow<Float> = _downloadProgress.asStateFlow()

    /** Last successful listing (persisted to disk, shown before a refresh). */
    private val _cachedFiles = MutableStateFlow<List<CameraFile>>(emptyList())
    val cachedFiles: StateFlow<List<CameraFile>> = _cachedFiles.asStateFlow()

    /* ------------------------------------------------------------------ */
    /* native access primitives                                            */
    /* ------------------------------------------------------------------ */

    /** Runs block under the session mutex; throws if there is no session. */
    private suspend fun <T> onCamera(
        block: suspend (NativeCameraInterface) -> T,
    ): T = nativeLock.withLock {
        val i = iface ?: error("no camera session open")
        withContext(Dispatchers.IO) { block(i) }
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

    private var lastWifiHost: String? = null
    private var lastWifiPort = 15740

    fun connectWifi(host: String, port: Int) {
        lastWifiHost = host
        lastWifiPort = port
        connect(Transport.WIFI) {
            NativeCameraInterface(appContext).initWifi(host, port)
        }
    }

    private inline fun connect(
        kind: Transport,
        crossinline open: () -> NativeCameraInterface,
    ) {
        _connection.value = ConnState.Connecting(kind)
        _error.value = null
        scope.launch {
            stopLiveView()
            var lastError: Throwable? = null
            for (attempt in 1..CONNECT_ATTEMPTS) {
                /*
                 * Open the session directly under the native mutex. Do NOT
                 * go through [onCamera] here: its `iface != null` guard
                 * fires before the block and would abort the open with
                 * "no camera session open" on every fresh connection.
                 */
                try {
                    _connection.value = ConnState.Connecting(kind, attempt)
                    closeSession()                 // close any stale session
                    val opened = nativeLock.withLock { open() }
                    iface = opened
                    onConnected(kind)
                    return@launch
                } catch (t: Throwable) {
                    lastError = t
                    Log.w(TAG, "connect attempt $attempt/$CONNECT_ATTEMPTS failed", t)
                    /*
                     * Retry before surfacing "gp I/O 7 (-10) Timeout": the
                     * camera's consent dialog (and its PTP/IP bootstrap after
                     * "Wireless remote control" is armed) can take well over
                     * the initial handshake budget, and the first attempts
                     * while the camera finishes booting its network stack
                     * routinely bounce.
                     */
                    if (attempt < CONNECT_ATTEMPTS) delay(CONNECT_RETRY_DELAY_MS)
                }
            }
            Log.e(TAG, "connect failed", lastError)
            _error.value = lastError?.message
            _connection.value = ConnState.Failed(
                kind,
                lastError?.message?.lineSequence()?.firstOrNull() ?: "connect failed",
            )
            closeSession()
        }
    }

    private suspend fun onConnected(kind: Transport) {
        val info = readInfo(kind)
        Log.i(TAG, "connected: ${info.model} battery=${info.batteryText}")
        metaMutex.withLock { noInfoSet.clear() }
        thumbNoInfo.clear()
        _connection.value = ConnState.Connected(kind, info)
        refreshControls()
        startLiveness(kind)
    }

    fun disconnect() {
        scope.launch { disconnectLocked() }
    }

    /** Idempotent shutdown; must be started while idle (preview stopped). */
    private suspend fun disconnectLocked(): Unit = nativeLock.withLock {
        stopLiveView()
        stopLiveness()
        closeSessionInner()
        val current = _connection.value
        if (current is ConnState.Connected || current is ConnState.Connecting) {
            _connection.value = ConnState.Idle
        }
    }

    /**
     * Resets camera artifacts but keeps whatever lifecycle state the caller
     * set (Lost / Failed survive the cleanup on purpose).
     *
     * PRECONDITION: the caller holds [nativeLock] (it is not reentrant).
     */
    private suspend fun closeSessionInner() {
        val i = iface
        if (i != null) {
            withContext(Dispatchers.IO) {
                runCatching { i.close() }.onFailure { Log.w(TAG, "camera close failed", it) }
            }
        }
        warmupJob?.cancel()
        iface = null
        _preview.value = null
        _histogram.value = null
        _zebra.value = null
        _properties.value = emptyMap()
        _captureState.value = null
        _fps.value = 0f
    }

    /** Lock-acquiring wrapper so non-lock-holding callers can use it too. */
    private suspend fun closeSession() = nativeLock.withLock { closeSessionInner() }

    /* ------------------------------------------------------------------ */
    /* camera info                                                         */
    /* ------------------------------------------------------------------ */

    private suspend fun readInfo(kind: Transport): CameraInfo {
        val summary = onCameraOr { i -> i.getSummary() }.orEmpty()
        val batteryText = onCameraOr { i -> i.readConfigText("batterylevel") }
            .orEmpty()
        val storages = onCameraOr { i -> i.getStorageInfo().toList() }
            .orEmpty()
        val mainSd = storages.firstOrNull { it.capacityKb > 0 }
        /*
         * Guard against dead-on-arrival sessions: if the camera answered
         * nothing at all (summary, battery, storage), the PTP handshake may
         * have "succeeded" against a camera that is still deciding — surfacing
         * a Connected state here is what made the dashboard show the
         * "A&K AK120II" abilities placeholder instead of a real camera. Let
         * connect()'s retry loop handle it as a failure instead.
         */
        if (summary.isBlank() && batteryText.isBlank() && storages.isEmpty()) {
            throw IllegalStateException(
                "camera did not report any info — connection not usable yet",
            )
        }
        val model = parseModel(summary) ?: "Canon EOS"

        return CameraInfo(
            model = model,
            summary = summary,
            batteryPercent = parseBattery(batteryText),
            batteryText = batteryText.ifEmpty { "unknown" },
            storageTotalGb = mainSd?.capacityKb?.div(1024.0 * 1024.0),
            storageFreeGb = mainSd?.freeKb?.div(1024.0 * 1024.0),
            storageFreeImages = mainSd?.freeImages?.takeIf { it > 0 },
            bridgeVersion = onCameraOr { i -> i.libraryVersion() }.orEmpty(),
        )
    }
    /** Re-reads model/battery/SD and replaces the Connected info. */
    fun refreshCameraInfo() {
        scope.launch {
            val current = _connection.value as? ConnState.Connected
                ?: return@launch
            runCatching { readInfo(current.transport) }
                .onSuccess { info ->
                    if (_connection.value is ConnState.Connected) {
                        _connection.value = current.copy(info = info)
                    }
                }
                .onFailure { Log.w(TAG, "info refresh failed", it) }
        }
    }

    /* ------------------------------------------------------------------ */
    /* liveness probe (fast offline detection) + slow battery/SD telemetry  */
    /* ------------------------------------------------------------------ */

    private var livenessJob: Job? = null

    private fun startLiveness(kind: Transport) {
        stopLiveness()
        livenessJob = scope.launch {
            var misses = 0
            var telemetryCountdown = TELEMETRY_PROBES
            while (isActive && iface != null) {
                delay(PROBE_INTERVAL_MS)
                if (iface == null) break
                // cheap single-property read; both fast on PTP and honest
                // about whether the device still answers
                val battery = runCatching {
                    onCamera { i -> i.readConfigText("batterylevel") }
                }.getOrNull()
                if (battery == null) {
                    misses++
                    Log.w(TAG, "liveness probe missed ($misses)")
                    if (misses >= LIVENESS_FAIL_THRESHOLD) {
                        Log.w(TAG, "camera stopped answering → NotConnected")
                        closeSession()
                        _connection.value = ConnState.Lost(
                            kind,
                            "Camera connection lost — tap Connect to retry.",
                        )
                        break
                    }
                    continue
                }
                misses = 0
                if (telemetryCountdown-- > 0) continue
                telemetryCountdown = TELEMETRY_PROBES
                val current = _connection.value as? ConnState.Connected ?: continue
                runCatching { readInfo(current.transport) }
                    .onSuccess { info ->
                        if (_connection.value is ConnState.Connected) {
                            _connection.value = current.copy(info = info)
                        }
                    }
                    .onFailure { Log.w(TAG, "telemetry refresh failed", it) }
            }
        }
    }

    private fun stopLiveness() {
        livenessJob?.cancel()
        livenessJob = null
    }

    /* ------------------------------------------------------------------ */
    /* live view                                                           */
    /* ------------------------------------------------------------------ */

    private var previewJob: Job? = null

    /** > 0 while a capture holds the native mutex: the preview loop idles
     *  instead of fighting for the lock (and kicking the camera) mid-release. */
    private val previewPaused = AtomicInteger(0)

    /**
     * Starts the decode loop: one `gp_camera_capture_preview` per tick
     * (the ptp2 camlib boots the EOS live view itself on the first call).
     *
     * Only the Viewfinder tab calls this — connecting goes to Files first so
     * the camera's own screen blanks only when the user opts into remote
     * live view (EOS M50 II interrupts camera-side display otherwise).
     *
     * Failing ticks back off progressively: each `capture_preview` burns
     * several seconds on the session mutex while the camera reports
     * "Camera Not Ready" (0xa102), and re-querying every 90 ms would starve
     * every other camera op (capture, config, files, battery).
     */
    fun startLiveView() {
        if (iface == null) return
        if (previewJob?.isActive == true) return
        _liveViewActive.value = true
        _previewFails.value = 0
        previewJob = scope.launch {
            var lastNs = 0L
            var frames = 0
            var fails = 0
            var first = true
            try {
                while (isActive && _liveViewActive.value && iface != null) {
                    if (previewPaused.get() > 0) {
                        // capture in progress — keep the native lock free
                        delay(60)
                        continue
                    }
                    val bytes = onCameraOr { i ->
                        i.capturePreviewOnce()
                    }
                    if (bytes == null) {
                        fails++
                        _previewFails.value = fails
                        // 90 → 360 ms backoff between attempts; the native
                        // call itself is the slow part when arming fails
                        delay(PREVIEW_INTERVAL_MS * (1L shl fails.coerceAtMost(2)))
                        continue
                    }
                    fails = 0
                    _previewFails.value = 0
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

    suspend fun capture(): CameraFile? {
        if (iface == null) error("no camera session open")
        /*
         * The EOS release must not fight the preview loop for the native
         * mutex (a failing arming attempt holds it for seconds) and the
         * camera must not receive EVF kicks mid-release: pause the ticks
         * until the capture returns.
         */
        previewPaused.incrementAndGet()
        try {
            _captureState.value = CaptureState(busy = true)
            val result = onCamera { i -> i.captureImage() } ?: error("capture returned nothing")
            val file = CameraFile(result.folder.ifEmpty { "/" }, result.name)
            _captureState.value = CaptureState(busy = false, file = file)
            Log.i(TAG, "capture: ${file.folder}${file.name}")

            /*
             * Follow-ups off the critical path: metadata first so the new
             * file sorts by its real capture date, then the auto-save into
             * the public gallery (Downloads/LensLink, with notification),
             * then the Files-screen listing refresh.
             */
            scope.launch {
                runCatching { fileInfoFor(file) }
                    .onFailure { Log.w(TAG, "capture: metadata fetch failed", it) }
                val dl = runCatching { downloadToPublic(file.folder, file.name) }
                    .onFailure { Log.w(TAG, "capture: auto-download failed", it) }
                    .getOrNull()
                val savedOk = dl != null && dl.savedCount > 0
                _captureState.value = CaptureState(
                    busy = false,
                    file = file,
                    savedToLabel = if (savedOk) dl?.savedToLabel else null,
                    error = if (!savedOk) "auto-download failed — photo is on the card" else null,
                )
                _filesVersion.value += 1
                refreshCameraInfo()
            }
            return file
        } finally {
            previewPaused.decrementAndGet()
        }
    }

    fun captureAsync() {
        scope.launch {
            try {
                if (capture() == null) _error.value = "Capture returned no file"
            } catch (t: Throwable) {
                Log.w(TAG, "capture failed", t)
                _captureState.value = CaptureState(busy = false, error = t.message ?: "capture failed")
                _error.value = t.message
            }
        }
    }

    /**
     * Manual live-view recovery (Retry card): rebuild the Wi-Fi session so
     * libgphoto2's EOS arming state starts from scratch, then re-enter live
     * view. On USB (where arming rarely fails) just restarts the loop.
     */
    fun retryLiveView() {
        val host = lastWifiHost
        val port = lastWifiPort
        scope.launch {
            if (currentTransport() == Transport.WIFI && host != null) {
                stopLiveView()
                disconnectLocked()
                connectWifi(host, port)
            } else {
                stopLiveView()
                startLiveView()
            }
        }
    }

    /* ------------------------------------------------------------------ */
    /* files                                                                */
    /* ------------------------------------------------------------------ */

    /**
     * Full SD listing. The per-file metadata/thumbnail caches below make the
     * *expensive* parts incremental; the listing sweep itself is a couple of
     * fast PTP ops. The just-used result also seeds [cachedFiles], so the
     * Files screen can paint instantly from the last-known list.
     */
    suspend fun listFiles(): List<CameraFile> = onCamera { i ->
        val seeds = LinkedHashMap<String, CameraFileInfo>()
        val list = i.listFiles().mapNotNull { raw ->
            // native entry: "<folder><name>" or "<folder><name>\t<size> <mtime>"
            // (the tab part is seeded from the camera's object-info cache,
            // gathered during the listing itself — no extra PTP ops)
            val tab = raw.indexOf('\t')
            val path = if (tab >= 0) raw.substring(0, tab) else raw
            val slash = path.lastIndexOf('/')
            if (slash <= 0) return@mapNotNull null
            val file = CameraFile(
                folder = path.substring(0, slash + 1),
                name = path.substring(slash + 1),
            )
            if (tab >= 0) {
                val f = raw.substring(tab + 1).split(' ')
                val size = f.getOrNull(0)?.toLongOrNull() ?: 0L
                val mtime = f.getOrNull(1)?.toLongOrNull() ?: 0L
                // seed only REAL values: hollow object info (both 0) is
                // common over PTP/IP and would just shadow real fetches
                if (size > 0 || mtime > 0) {
                    seeds[file.fullPath] = CameraFileInfo(size, mtime, 0, 0, "unknown")
                }
            }
            file
        }
        listingMeta = seeds
        Log.i(TAG, "listing: ${list.size} files (${seeds.size} with size/date from object info)")
        if (list.isNotEmpty()) _cachedFiles.value = list
        list
    }

    /* ------------------------------------------------------------------ */
    /* thumbnails: memory LRU ↔ disk store ↔ camera                         */
    /* ------------------------------------------------------------------ */

    /**
     * Disk-backed thumbnail cache so browsing never re-fetches the SD card:
     * a `LruCache` holds the decoded bitmaps active, `filesDir/thumbcache/`
     * persists the raw PTP `GetObjectThumb` JPEG bytes across sessions keyed
     * by a SHA-1 of the full camera path.
     */
    private val thumbDir = File(appContext.filesDir, "thumbcache").apply { mkdirs() }
    private val thumbDirQ = Mutex()
    /** first-N failure logging for thumbnails (keeps the log readable). */
    private var thumbFailLogged = 0

    /** Per-session "camera has no thumbnail" negative cache (thread-safe). */
    private val thumbNoInfo: MutableSet<String> = Collections.synchronizedSet(HashSet())

    private var warmupJob: Job? = null

    /**
     * Background thumbnail warm-up: walks the listing once, pulling every
     * not-yet-cached thumbnail through the normal memory→disk→camera path.
     * Visible rows still fetch on demand; this makes scrolling — and the
     * *next* session, via the disk store — instant. Sequential on the
     * camera session; visible fetches interleave at the native mutex.
     */
    fun startThumbnailWarmup(files: List<CameraFile>) {
        warmupJob?.cancel()
        if (files.isEmpty()) return
        warmupJob = scope.launch {
            for (file in files) {
                if (!isActive) break
                if (thumbCache.get(file.fullPath) != null) continue
                if (file.fullPath in thumbNoInfo) continue
                runCatching { thumbnailFor(file) }
                yield()
            }
            Log.i(TAG, "thumbnail warm-up done (${files.size} files)")
        }
    }

    /** Up to 256 decoded thumbs (~10 MB) resident while browsing. */
    private val thumbCache =
        object : LruCache<String, ImageBitmap>(256) {
            override fun sizeOf(key: String, value: ImageBitmap): Int = 1
        }

    /** Decodes to ≤ 256 px (fits a list row / grid cell, stays cheap). */
    private fun decodeThumb(bytes: ByteArray): ImageBitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 256) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            ?.asImageBitmap()
    }

    private fun thumbDiskFile(fullPath: String): File = File(
        thumbDir,
        MessageDigest.getInstance("SHA-1").digest(fullPath.toByteArray())
            .joinToString("") { "%02x".format(it) } + ".jpg",
    )

    /**
     * Thumbnail for a camera file; null when the camera has no thumbnail or
     * the session went away. Reads memory → disk → camera, so a session
     * restart paints instantly from disk and only *new* files hit the SD card.
     */
    suspend fun thumbnailFor(file: CameraFile): ImageBitmap? {
        thumbCache.get(file.fullPath)?.let { return it }
        if (file.fullPath in thumbNoInfo) return null
        // 1) disk store
        withContext(Dispatchers.IO) {
            runCatching { thumbDiskFile(file.fullPath).takeIf { it.isFile }?.readBytes() }
        }.getOrNull()?.let { bytes ->
            decodeThumb(bytes)?.let {
                thumbCache.put(file.fullPath, it)
                return it
            }
        }
        // 2) camera (+ persist)
        val bytes = onCameraOr { i -> i.thumbnailTo(file.folder, file.name) }
        if (bytes == null) {
            thumbNoInfo.add(file.fullPath)
            thumbFailLogged++
            if (thumbFailLogged <= 3) {
                Log.w(TAG, "thumbnail: camera returned none for ${file.fullPath}")
            }
            return null
        }
        withContext(Dispatchers.IO) {
            thumbDirQ.withLock {
                runCatching {
                    val disk = thumbDiskFile(file.fullPath)
                    val tmp = File(thumbDir, disk.name + ".part")
                    tmp.writeBytes(bytes)
                    tmp.renameTo(disk) || tmp.delete()
                }
            }
        }
        val thumb = withContext(Dispatchers.Default) { decodeThumb(bytes) }
        if (thumb != null) thumbCache.put(file.fullPath, thumb)
        return thumb
    }

    /* ------------------------------------------------------------------ */
    /* per-file metadata: read-through persistent cache                     */
    /* ------------------------------------------------------------------ */

    /**
     * fullPath → size/mtime/width/height/mime, persisted to
     * `filesDir/filemeta/listing.tsv` so refreshing doesn't "read SD card"
     * into zero-state each session: metadata queries only run for files the
     * cache has never seen (or that changed).
     */
    private val metaDir = File(appContext.filesDir, "filemeta").apply { mkdirs() }
    private val metaFile = File(metaDir, "listing.tsv")
    private val metaMutex = Mutex()
    private var metaMap = LinkedHashMap<String, CameraFileInfo>()
    private var metaPersistPending = false
    private var mtimeZeroLogged = false
    private var noInfoLogged = 0

    /** Per-listing size/date seeds (session-only, not persisted, refreshed
     * on every [listFiles]); consulted before any per-file PTP fetch. */
    @Volatile
    private var listingMeta: Map<String, CameraFileInfo> = emptyMap()

    /** Per-session negative cache for "camera returned no info" paths
     * (metaMutex-guarded): over PTP/IP most per-file info fetches fail —
     * re-asking on every reload stole the session from thumbnails/preview. */
    private val noInfoSet = HashSet<String>()

    init {
        scope.launch {
            val loaded = withContext(Dispatchers.IO) {
                runCatching {
                    metaFile.takeIf { it.isFile }?.readLines()?.mapNotNull { line ->
                        val f = line.split('\t')
                        if (f.size < 5) return@mapNotNull null
                        f[0] to CameraFileInfo(
                            sizeBytes = f[1].toLongOrNull() ?: -1L,
                            mtimeSec = f[2].toLongOrNull() ?: 0L,
                            widthPx = f[3].toIntOrNull() ?: 0,
                            heightPx = f[4].toIntOrNull() ?: 0,
                            mime = f.getOrElse(5) { "" }.ifEmpty { "unknown" },
                        )
                    }?.toMap(LinkedHashMap())
                }.getOrNull()
            }
            if (loaded != null) {
                metaMutex.withLock {
                    metaMap.putAll(loaded)
                    Log.i(TAG, "file-metadata cache: ${metaMap.size} entries restored")
                }
            }
        }
    }

    private fun persistMetaSoon() {
        if (metaPersistPending) return
        metaPersistPending = true
        scope.launch {
            val snapshot = metaMutex.withLock { metaMap.toList() }
            withContext(Dispatchers.IO) {
                runCatching {
                    val tmp = File(metaDir, "listing.tsv.part")
                    tmp.writeText(
                        snapshot.joinToString("\n") { (path, info) ->
                            "$path\t${info.sizeBytes}\t${info.mtimeSec}" +
                                "\t${info.widthPx}\t${info.heightPx}\t${info.mime}"
                        },
                    )
                    tmp.renameTo(metaFile) || tmp.delete()
                }.onFailure { Log.w(TAG, "meta cache persist failed", it) }
            }
            metaPersistPending = false
        }
    }

    /** Discards entries whose paths are no longer on the card. */
    fun pruneMetaCache(alive: Set<String>) {
        scope.launch {
            val removed = metaMutex.withLock {
                val before = metaMap.size
                metaMap.keys.asSequence()
                    .filter { it !in alive }
                    .toList()
                    .forEach { metaMap.remove(it) }
                before - metaMap.size
            }
            if (removed > 0) {
                Log.i(TAG, "file-metadata cache: $removed stale entries pruned")
                persistMetaSoon()
            }
        }
    }

    /**
     * One-shot object info. Read-through: cache hits never touch the camera;
     * misses fetch once and are persisted, so a session restart still paints
     * sizes/dates immediately.
     */
    suspend fun fileInfoFor(file: CameraFile): CameraFileInfo {
        metaMutex.withLock {
            when {
                metaMap.containsKey(file.fullPath) -> metaMap[file.fullPath]
                listingMeta.containsKey(file.fullPath) -> listingMeta[file.fullPath]
                noInfoSet.contains(file.fullPath) -> CameraFileInfo.UNKNOWN
                else -> null
            }
        }?.let { return it }
        val fresh = onCameraOr { i -> i.fileInfo(file.folder, file.name) }
            ?: CameraFileInfo.UNKNOWN
        if (!fresh.hasSize) {
            metaMutex.withLock { noInfoSet.add(file.fullPath) }
            noInfoLogged++
            if (noInfoLogged <= 3) {
                Log.w(TAG, "file metadata: camera returned no info for ${file.name}")
            }
            return fresh   // unknown info is not cacheable
        }
        if (fresh.mtimeSec <= 0 && !mtimeZeroLogged) {
            mtimeZeroLogged = true
            Log.i(
                TAG,
                "metadata: camera reports no timestamp (mtime=0) for ${file.name} " +
                    "— date sorts fall back to file-name order",
            )
        }
        metaMutex.withLock { metaMap[file.fullPath] = fresh }
        persistMetaSoon()
        return fresh
    }

    /* ------------------------------------------------------------------ */
    /* in-app fullscreen viewer pipeline                                    */
    /* ------------------------------------------------------------------ */

    private val viewDir = File(appContext.filesDir, "viewcache").apply { mkdirs() }

    /** Total bytes allowed for the fullscreen-viewer cache. */
    private val viewCacheCapBytes = 256L * 1024 * 1024

    private fun viewCacheKey(file: CameraFile): String =
        MessageDigest.getInstance("SHA-1").digest(file.fullPath.toByteArray())
            .joinToString("") { "%02x".format(it) } + "." +
            file.name.substringAfterLast('.', "dat").lowercase()

    /**
     * Streams a file into the *private* viewer cache (NOT the phone
     * Downloads folder) so the fullscreen preview can decode locally without
     * an "actual" download the user never asked for. Bounded: the oldest
     * cached originals are evicted once the cache exceeds the cap.
     */
    suspend fun fetchForViewer(file: CameraFile): File {
        val dst = File(viewDir, viewCacheKey(file))
        if (dst.isFile && dst.length() > 0) return dst
        val tmp = File(viewDir, dst.name + ".part")
        onCamera { i -> i.downloadFileTo(file.folder, file.name, tmp) }
        tmp.renameTo(dst) || dst.delete()
        withContext(Dispatchers.IO) { trimViewCache() }
        return dst
    }

    private suspend fun trimViewCache() {
        val files = viewDir.listFiles()?.filter { it.isFile } ?: return
        var total = files.sumOf { it.length() }
        if (total <= viewCacheCapBytes) return
        for (f in files.sortedBy { it.lastModified() }) {
            if (total <= viewCacheCapBytes) break
            val size = f.length()
            if (f.delete()) total -= size
        }
    }

    /* ------------------------------------------------------------------ */
    /* downloads → phone Downloads folder + success notification            */
    /* ------------------------------------------------------------------ */

    /**
     * Streams a file from the SD card into the **public Downloads folder**
     * (`Download/LensLink/…` on API 29+ through MediaStore; pre-29 falls
     * back to the app-scoped external storage) and posts a success
     * notification.
     *
     * The bridge writes the PTP stream to a private staging file in 256 KiB
     * chunks (OOM-safe for RAWs); the staging file is then copied into
     * MediaStore. Returns the batch summary for UI feedback.
     */
    suspend fun downloadToPublic(folder: String, fileName: String): DownloadResult =
        bulkDownload(listOf(folder to fileName), notifyEach = true)

    /**
     * Downloads a selection sequentially into the shared Downloads folder,
     * reporting overall progress (0..1).
     */
    suspend fun bulkDownload(
        items: List<Pair<String, String>>,
        notifyEach: Boolean = false,
    ): DownloadResult {
        _downloadProgress.value = 0f
        var saved = 0
        var failed = 0
        for ((index, item) in items.withIndex()) {
            val staging = stagingFile(item.second)
            try {
                onCamera { i ->
                    i.downloadFileTo(item.first, item.second, staging)
                }
                saveStaging(staging, item.second)?.let { finalName ->
                    saved++
                    if (notifyEach) notifyDownloadSaved(finalName)
                } ?: run { failed++ }
            } catch (t: Throwable) {
                Log.w(TAG, "bulk download failed for ${item.first}${item.second}", t)
                failed++
            } finally {
                staging.delete()
            }
            _downloadProgress.value = (index + 1).toFloat() / items.size
        }
        _downloadProgress.value = 1f
        if (saved > 0 && !notifyEach) notifyDownloadSavedBatch(saved)
        return DownloadResult(saved, failed, "Download/LensLink")
    }

    private fun stagingDir(): File = File(appContext.cacheDir, "staging").apply { mkdirs() }

    private fun stagingFile(name: String): File =
        File(stagingDir(), name.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".part")

    private fun mimeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "cr2" -> "image/x-canon-cr2"
        "cr3" -> "image/x-canon-cr3"
        "heif", "heic" -> "image/heif"
        "png" -> "image/png"
        "mov" -> "video/quicktime"
        "mp4" -> "video/mp4"
        else -> "application/octet-stream"
    }

    /** Pushes the staging file into the phone Downloads folder. */
    private suspend fun saveStaging(src: File, name: String): String? =
        withContext(Dispatchers.IO) {
            if (!src.isFile) return@withContext null
            runCatching {
                if (Build.VERSION.SDK_INT >= 29) {
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                        put(MediaStore.MediaColumns.MIME_TYPE, mimeFor(name))
                        put(
                            MediaStore.MediaColumns.RELATIVE_PATH,
                            Environment.DIRECTORY_DOWNLOADS + "/LensLink",
                        )
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                    val resolver = appContext.contentResolver
                    val uri = resolver.insert(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        values,
                    ) ?: error("MediaStore insert returned null")
                    resolver.openOutputStream(uri)?.use { out ->
                        src.inputStream().use { it.copyTo(out) }
                    } ?: error("cannot open output stream for $uri")
                    values.clear()
                    values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                    name
                } else {
                    // pre-29: public writes need WRITE_EXTERNAL_STORAGE, which the
                    // manifest deliberately omits — stay in app-scoped storage.
                    val dir = File(
                        appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
                        "LensLink",
                    ).apply { mkdirs() }
                    src.copyTo(File(dir, name), overwrite = true)
                    "App storage/LensLink/$name"
                }
            }.onFailure { Log.w(TAG, "save to Downloads failed for $name", it) }
                .getOrNull()
        }

    private fun downloadsManager(): NotificationManager =
        appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun ensureDownloadChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            downloadsManager().createNotificationChannel(
                NotificationChannel(
                    DOWNLOAD_CHANNEL_ID,
                    "Downloads",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ),
            )
        }
    }

    private fun notifyAllowed(): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(
                appContext,
                android.Manifest.permission.POST_NOTIFICATIONS,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun postNotification(title: String, text: String) {
        if (!notifyAllowed()) return
        ensureDownloadChannel()
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(appContext, DOWNLOAD_CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(appContext)
        }
        builder
            .setSmallIcon(R.drawable.ic_notif_camera)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
        runCatching { downloadsManager().notify(0x42, builder.build()) }
    }

    private fun notifyDownloadSaved(finalName: String) {
        postNotification(
            "Download complete",
            "$finalName saved to Downloads/LensLink",
        )
    }

    private fun notifyDownloadSavedBatch(count: Int) {
        postNotification(
            "Downloads complete",
            "$count file${if (count == 1) "" else "s"} saved to Downloads/LensLink",
        )
    }

    fun clearError() {
        _error.value = null
    }

    /** Shuts everything down (Application.onTerminate / final UI exit). */
    fun teardown() {
        stopLiveness()
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
