/*
 * UsbMonitor.kt — UsbManager glue for OTG cameras:
 *   • hot-plug notifications (device attached / detached)
 *   • permission request plumbing (custom action broadcast)
 *   • a StateFlow for "the Canon camera currently on the bus"
 */
package com.mav.lenslink.usb

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import com.mav.cameranativebridge.NativeCameraInterface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class UsbMonitor(private val context: Context) {

    companion object {
        private const val TAG = "UsbMonitor"
        const val ACTION_USB_PERMISSION =
            "com.mav.lenslink.USB_PERMISSION"

        /** Canon, Inc. vendor id 0x04a9 — EOS M50 Mark II is 0x04a9:3e5d. */
        const val CANON_VENDOR_ID = NativeCameraInterface.CANON_VENDOR_ID
    }

    private val usbManager =
        context.getSystemService(Context.USB_SERVICE) as UsbManager

    /** The (Canon) camera currently attached, or null. */
    private val _attached = MutableStateFlow<UsbDevice?>(null)
    val attached: StateFlow<UsbDevice?> = _attached.asStateFlow()

    /** Devices that already have OTG permission granted. */
    private val _permitted = MutableStateFlow<Set<String>>(
        mutableSetOf<String>().apply { addAll(currentDeviceNames()) },
    )
    val permitted: StateFlow<Set<String>> = _permitted.asStateFlow()

    private var permissionRequestedFor: String? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    scan()
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    @Suppress("DEPRECATION")
                    val detached: UsbDevice? =
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    if (detached != null) {
                        _permitted.value = _permitted.value - detached.deviceId.toString()
                    }
                    scan()
                }
                ACTION_USB_PERMISSION -> {
                    @Suppress("DEPRECATION")
                    val device: UsbDevice? =
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    if (intent.getBooleanExtra(
                            UsbManager.EXTRA_PERMISSION_GRANTED,
                            false,
                        ) && device != null
                    ) {
                        Log.i(TAG, "USB permission granted: $device")
                        _permitted.value = _permitted.value + device.deviceId.toString()
                    } else {
                        Log.w(TAG, "USB permission DENIED for $device")
                    }
                    permissionRequestedFor = null
                }
            }
        }
    }

    fun start() {
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(ACTION_USB_PERMISSION)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        scan()
    }

    fun stop() {
        context.unregisterReceiver(receiver)
    }

    /** Refreshes the "currently attached Canon" StateFlow. */
    @SuppressLint("DefaultLocale")
    fun scan() {
        val deviceList = usbManager.deviceList
        val canon = deviceList.values.firstOrNull {
            it.vendorId == UsbMonitor.CANON_VENDOR_ID
        }
        Log.d(TAG, "usb scan: ${deviceList.size} devices, canon=$canon")
        _attached.value = canon
    }

    private fun currentDeviceNames(): Set<String> =
        usbManager.deviceList.values
            .filter { usbManager.hasPermission(it) }
            .map { it.deviceId.toString() }
            .toSet()

    fun hasPermission(device: UsbDevice): Boolean = usbManager.hasPermission(device)

    /** Asks the user once (system dialog) for raw USB access to the device. */
    fun requestPermission(device: UsbDevice) {
        val key = device.deviceId.toString()
        if (permissionRequestedFor == key) return   // dialog already up
        permissionRequestedFor = key
        val intent = PendingIntent.getBroadcast(
            context,
            0,
            Intent(ACTION_USB_PERMISSION).setPackage(context.packageName),
            if (Build.VERSION.SDK_INT >= 31)
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            else PendingIntent.FLAG_UPDATE_CURRENT,
        )
        usbManager.requestPermission(device, intent)
    }
}
