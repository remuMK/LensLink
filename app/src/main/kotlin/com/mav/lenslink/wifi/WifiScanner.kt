/*
 * WifiScanner.kt — camera-hotspot discovery helpers:
 *   • scan Wi-Fi results and flag likely Canon camera APs
 *   • current-network clue (are we already attached to the camera?)
 *   • /24 subnet probe of TCP/15740 (PTP-IP) addresses
 */
package com.mav.lenslink.wifi

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import android.text.format.Formatter
import androidx.core.content.ContextCompat
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** A Wi-Fi AP that looks like a Canon camera hotspot. */
data class CameraNetwork(
    val ssid: String,
    val bssid: String,
    val rssi: Int,
)

class WifiScanner(private val context: Context) {

    companion object {
        /** gphoto2 PTP/IP default port. */
        const val PTPIP_PORT = 15740
        private val CANON_HINTS =
            listOf("canon", "eos", "powershot", "ae2 ptp", "ptp", "m50", "dx")
    }

    private val wifi = context.getSystemService(Context.WIFI_SERVICE) as WifiManager

    private val _networks = MutableStateFlow<List<CameraNetwork>>(emptyList())
    val networks: StateFlow<List<CameraNetwork>> = _networks.asStateFlow()

    private val _onCameraNetwork = MutableStateFlow<Boolean?>(null)
    val onCameraNetwork: StateFlow<Boolean?> = _onCameraNetwork.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private val _probing = MutableStateFlow(false)
    val probing: StateFlow<Boolean> = _probing.asStateFlow()

    private var scanReceiverRegistered = false

    /** True when a scan result callback is expected (API-dependent race). */
    fun canScan(): Boolean =
        wifi.isWifiEnabled &&
            (hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) ||
                (Build.VERSION.SDK_INT >= 33 &&
                    hasPermission(Manifest.permission.NEARBY_WIFI_DEVICES)))

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(
            context.applicationContext, permission,
        ) == PackageManager.PERMISSION_GRANTED

    /**
     * Kick a Wi-Fi scan; results land in [networks].
     * Polling style (getScanResults right away) also works on most devices —
     * scan throttling is handled gracefully by the UI.
     */
    fun refresh() {
        if (!canScan()) return
        _scanning.value = true
        runCatching { wifi.startScan() }
        // getScanResults right now + poll once after the scan is likely done
        popResults()
    }

    /** Pulls current (possibly cached) scan results into the StateFlow. */
    @SuppressLint("MissingPermission")
    fun popResults() {
        runCatching {
            val results: List<ScanResult> = wifi.scanResults
            _networks.value = results
                .filter { sr -> CANON_HINTS.any { sr.SSID?.lowercase(Locale.ROOT)?.contains(it) == true } }
                .map { CameraNetwork(it.SSID.orEmpty().ifEmpty { "<hidden>" }, it.BSSID, it.level) }
        }
        _onCameraNetwork.value = detectCameraNetworkStatic()
        _scanning.value = false
    }

    /** Are we currently attached to a network that looks like a camera AP? */
    private fun detectCameraNetworkStatic(): Boolean {
        val info = runCatching { wifi.connectionInfo }.getOrNull() ?: return false
        val ssid = info.ssid?.removeSurrounding("\"") ?: return false
        return CANON_HINTS.any { ssid.lowercase(Locale.ROOT).contains(it) }
    }

    /** DHCP-assigned address of this phone on the Wi-Fi network, or null. */
    fun localAddress(): InetAddress? {
        val info = runCatching { wifi.dhcpInfo }.getOrNull() ?: return null
        val ip = Formatter.formatIpAddress(info.ipAddress)
        return runCatching { InetAddress.getByName(ip) }.getOrNull()
    }

    /** Network prefix as "192.168.1" from the DHCP netmask, or null. */
    fun currentPrefix(): String? {
        val info = runCatching { wifi.dhcpInfo }.getOrNull() ?: return null
        val netmask = info.netmask
        val ip = info.ipAddress
        if (ip == 0) return null
        // Android stores int IPs little-endian; reconstruct octets
        val it = ipToBytes(ip)
        val nb = if (netmask != 0) ipToBytes(netmask) else byteArrayOf(255.toByte(), 255.toByte(), 255.toByte(), 0)
        val a = it[0].toInt() and nb[0].toInt()
        val b = it[1].toInt() and nb[1].toInt()
        val c = it[2].toInt() and nb[2].toInt()
        return "$a.$b.$c"
    }

    private fun ipToBytes(leet: Int): ByteArray = byteArrayOf(
        (leet and 0xff).toByte(),
        ((leet ushr 8) and 0xff).toByte(),
        ((leet ushr 16) and 0xff).toByte(),
        ((leet ushr 24) and 0xff).toByte(),
    )

    /**
     * Probes a /24 (or a caller-provided subnet "192.168.1") for an open
     * TCP/15740 socket (PTP-IP listener). Confined to a background thread.
     */
    suspend fun probeSubnet(prefix: String = currentPrefix() ?: "192.168.1"): List<String> {
        _probing.value = true
        return try {
            withContext(Dispatchers.IO) {
                coroutineScope {
                    val hits = ConcurrentLinkedQueue<String>()
                    (1 until 255).map { n ->
                        async {
                            val host = "$prefix.$n"
                            if (hostOpen(host, PTPIP_PORT, timeoutMs = 700)) hits.add(host)
                        }
                    }.awaitAll()
                    hits.sorted()
                }
            }.also { _onCameraNetwork.value = it.isNotEmpty() } // (info only)
        } finally {
            _probing.value = false
        }
    }

    private suspend fun hostOpen(host: String, port: Int, timeoutMs: Int): Boolean {
        return withTimeoutOrNull(timeoutMs.toLong()) {
            suspendCancellableCoroutine { cont ->
                try {
                    val socket = Socket()
                    cont.invokeOnCancellation { runCatching { socket.close() } }
                    socket.connect(InetSocketAddress(host, port), timeoutMs)
                    runCatching { socket.close() }
                    cont.resume(true)
        } catch (e: Exception) {
            cont.resume(false)
        }
            }
        } ?: false
    }

    /** Raw check of an exact host:port (manual entry path). */
    suspend fun checkHost(host: String, port: Int): Boolean =
        withContext(Dispatchers.IO) {
            hostOpen(host, port, timeoutMs = 1200)
        }.also { _onCameraNetwork.value = it }

    fun teardown() {
        // no persistent receiver needed; scan receiver lives on the OS side
    }
}
