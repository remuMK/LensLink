/*
 * LensLinkApplication.kt — app-scoped dependency container.
 */
package com.mav.lenslink

import android.app.Application
import android.util.Log
import com.mav.lenslink.camera.CameraRepository
import com.mav.lenslink.models.SavedCameraStore
import com.mav.lenslink.usb.UsbMonitor
import com.mav.lenslink.wifi.WifiScanner

class LensLinkApplication : Application() {

    companion object {
        private const val TAG = "LensLink"
    }

    lateinit var repository: CameraRepository
        private set

    lateinit var usb: UsbMonitor
        private set

    lateinit var wifi: WifiScanner
        private set

    /** Saved cameras for one-tap connect (Connect tab). */
    lateinit var savedCameras: SavedCameraStore
        private set

    override fun onCreate() {
        super.onCreate()
        repository = CameraRepository(this)
        usb = UsbMonitor(this).also { it.start() }
        wifi = WifiScanner(this)
        savedCameras = SavedCameraStore(this)
        Log.i(TAG, "LensLink container ready")
    }

    override fun onTerminate() {
        repository.teardown()
        usb.stop()
        super.onTerminate()
    }
}
