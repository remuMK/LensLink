/*
 * ConnectScreen.kt — auto-discovery dashboard:
 *   • connection status card (model / battery / SD card / library version)
 *   • USB OTG card with permission flow
 *   • Wi-Fi card: camera-AP scan + manual IP entry (PTP-IP, port 15740 is
 *     the fixed protocol port so no field for it)
 */
package com.mav.lenslink.ui.screens

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mav.lenslink.appContainer
import com.mav.lenslink.models.CameraInfo
import com.mav.lenslink.models.ConnState
import com.mav.lenslink.models.Transport
import kotlinx.coroutines.delay

private const val DEFAULT_CAMERA_IP = "192.168.1.1"
private const val DEFAULT_CAMERA_PORT = 15740
private const val PREFS = "lenslink"
private const val PREF_LAST_HOST = "last_wifi_host"
private const val PREF_LAST_PORT = "last_wifi_port"

@Composable
fun ConnectScreen() {
    val container = appContainer()
    val repository = container.repository
    val usbDevice by container.usb.attached.collectAsStateWithLifecycle()
    val permitted by container.usb.permitted.collectAsStateWithLifecycle()
    val connection by repository.connection.collectAsStateWithLifecycle()

    Column(
        Modifier
            .fillMaxSize()
            .imePadding()            // lift the fields above the soft keyboard
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        when (connection) {
            is ConnState.Connected -> StatusCard((connection as ConnState.Connected).info)
            is ConnState.Connecting ->
                ConnectingCard(
                    (connection as ConnState.Connecting).transport,
                    (connection as ConnState.Connecting).attempt,
                )
            is ConnState.Failed -> FailedCard(
                transport = (connection as ConnState.Failed).transport,
                message = (connection as ConnState.Failed).message,
            )
            is ConnState.Lost -> LostCard(
                transport = (connection as ConnState.Lost).transport,
                message = (connection as ConnState.Lost).message,
            )
            ConnState.Idle -> Text(
                "Not connected yet. Plug the EOS M50 Mark II in over USB, or join " +
                    "the camera's Wi-Fi hotspot and use the PTP/IP card below.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        UsbCard(
            device = usbDevice,
            hasPermission = usbDevice != null &&
                permitted.contains(usbDevice!!.deviceId.toString()),
            busy = connection is ConnState.Connecting,
        )

        WifiCard()
    }
}

/* ---------------------------------------------------------------- */
/* connection status card                                           */
/* ---------------------------------------------------------------- */

@Composable
private fun StatusCard(info: CameraInfo) {
    val repository = appContainer().repository
    /*
     * MaterialShapes hero shapes (Cookie9Sided etc.) always clip: text placed
     * near the shape's notches gets cut off, which cut the camera info off.
     * The dashboard therefore uses a plain rounded container; expressive
     * shapes stay on non-text decorations elsewhere.
     */
    Card(
        shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp), Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.Memory,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    info.model,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Text(
                "Battery: ${info.batteryText}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            info.storageFreeGb.let { free ->
                val total = info.storageTotalGb
                Text(
                    buildString {
                        if (free != null) {
                            append("SD: %.1f GiB free".format(free))
                            if (total != null) append(" of %.1f GiB".format(total))
                        } else {
                            append("SD card info unavailable")
                        }
                        info.storageFreeImages?.let {
                            append(" (~$it shots)")
                        }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Text(
                info.bridgeVersion,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { repository.refreshCameraInfo() }) {
                    Icon(Icons.Rounded.Refresh, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Refresh")
                }
                FilledTonalButton(onClick = { repository.disconnect() }) {
                    Icon(Icons.Rounded.LinkOff, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Disconnect")
                }
            }
        }
    }
}

@Composable
private fun ConnectingCard(transport: Transport, attempt: Int) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CircularProgressIndicator(Modifier.size(22.dp))
            Text(
                "Connecting " + over(transport) +
                    (if (attempt > 1) "  ·  attempt $attempt" else "") +
                    " — accept the consent prompt on the camera if it shows one",
            )
        }
    }
}

private fun over(t: Transport): String =
    if (t == Transport.USB) "over USB" else "over Wi-Fi"

/**
 * The liveness probe decided the camera stopped answering (Wi-Fi asleep or
 * hotspot dropped). Renders as "not connected" — deliberately NOT an error
 * red — with a one-tap reconnect that reuses the last endpoint.
 */
@Composable
private fun LostCard(transport: Transport, message: String) {
    val repository = appContainer().repository
    val prefs = LocalContext.current.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
    )) {
        Column(Modifier.padding(20.dp), Arrangement.spacedBy(10.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    Icons.Rounded.WifiOff,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Column {
                    Text(
                        "Not connected",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (transport == Transport.WIFI) {
                Button(
                    onClick = {
                        val lastHost = prefs.getString(PREF_LAST_HOST, null)
                            ?: DEFAULT_CAMERA_IP
                        repository.connectWifi(lastHost, DEFAULT_CAMERA_PORT)
                    },
                ) {
                    Icon(Icons.Rounded.Link, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Reconnect")
                }
            }
        }
    }
}

@Composable
private fun FailedCard(transport: Transport, message: String) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                Icons.Rounded.Close,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
            Text(
                message,
                color = MaterialTheme.colorScheme.error,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/* ---------------------------------------------------------------- */
/* USB card                                                         */
/* ---------------------------------------------------------------- */

@Composable
private fun UsbCard(device: UsbDevice?, hasPermission: Boolean, busy: Boolean) {
    val container = appContainer()
    val context = LocalContext.current
    val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.Usb,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(10.dp))
                Text("USB (OTG)", style = MaterialTheme.typography.titleMedium)
                Text(
                    "  — Canon EOS M50 Mark II compatible",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (device == null) {
                Text(
                    "Plug the camera in with a cable. Auto-detect is on — the " +
                        "body shows up as soon as Android enumerates it.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    "Detected: ${device.productName ?: "Canon device"} " +
                        "(VID %04x PID %04x)".format(device.vendorId, device.productId),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    device.deviceName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Button(
                enabled = device != null && !busy,
                onClick = {
                    val d = device ?: return@Button
                    if (container.usb.hasPermission(d)) {
                        container.repository.connectUsb(usbManager, d)
                    } else {
                        container.usb.requestPermission(d)
                    }
                },
            ) {
                Icon(Icons.Rounded.Link, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(
                    when {
                        device == null -> "Waiting for device"
                        !hasPermission -> "Grant USB permission"
                        else -> "Connect"
                    },
                )
            }
        }
    }
}

/* ---------------------------------------------------------------- */
/* Wi-Fi card                                                       */
/* ---------------------------------------------------------------- */

@Composable
private fun WifiCard() {
    val container = appContainer()
    val wifi = container.wifi
    val repository = container.repository
    val prefs = LocalContext.current.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    val networks by wifi.networks.collectAsStateWithLifecycle()
    val scanning by wifi.scanning.collectAsStateWithLifecycle()
    val onCamera by wifi.onCameraNetwork.collectAsStateWithLifecycle()
    val connection by repository.connection.collectAsStateWithLifecycle()

    // seed fields with the last successfully-connected endpoint, if any
    var host by remember {
        mutableStateOf(
            prefs.getString(PREF_LAST_HOST, null) ?: DEFAULT_CAMERA_IP,
        )
    }

    // Scan results land a few seconds later on throttled platforms.
    LaunchedEffect(Unit) {
        while (true) {
            delay(4000)
            wifi.popResults()
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (onCamera == true) Icons.Rounded.Wifi else Icons.Rounded.WifiOff,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(10.dp))
                Text("Wi-Fi (PTP/IP)", style = MaterialTheme.typography.titleMedium)
            }

            FilledTonalButton(
                enabled = !scanning,
                onClick = { wifi.refresh(); wifi.popResults() },
            ) {
                Icon(Icons.Rounded.Refresh, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(if (scanning) "Scanning…" else "Scan")
            }

            if (networks.isNotEmpty()) {
                Text(
                    "Camera hotspots nearby:",
                    style = MaterialTheme.typography.labelLarge,
                )
                networks.take(4).forEach { network ->
                    AssistChip(
                        onClick = { host = network.ssid.split(' ').first() },
                        label = { Text("${network.ssid} (rssi ${network.rssi})") },
                    )
                }
            }

            OutlinedTextField(
                value = host,
                onValueChange = { host = it },
                label = { Text("Camera IP (from the hotspot, e.g. 192.168.1.1)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Button(
                onClick = {
                    val trimmedHost = host.trim()
                    // remember the endpoint so a reconnect / Lost-retry pre-fills it
                    prefs.edit()
                        .putString(PREF_LAST_HOST, trimmedHost)
                        .putInt(PREF_LAST_PORT, DEFAULT_CAMERA_PORT)
                        .apply()
                    repository.connectWifi(trimmedHost, DEFAULT_CAMERA_PORT)
                },
                enabled = host.isNotBlank() && connection !is ConnState.Connecting,
            ) {
                Icon(Icons.Rounded.Wifi, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Connect")
            }
        }
    }
}
