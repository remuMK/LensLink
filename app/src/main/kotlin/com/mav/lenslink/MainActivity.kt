/*
 * MainActivity.kt — the single-activity Compose host:
 *   • theme-aware window (M3 dynamic color, dark/light)
 *   • edge-to-edge, USB detach disconnects the session immediately
 */
package com.mav.lenslink

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.mav.lenslink.models.ConnState
import com.mav.lenslink.service.CameraControlService
import com.mav.lenslink.ui.LensLinkApp
import com.mav.lenslink.ui.theme.LensLinkTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val container: LensLinkApplication
        get() = application as LensLinkApplication

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LensLinkTheme {
                LensLinkApp()
            }
        }
        observeSession()
    }

    /** Foreground-service bookkeeping follows the live session. */
    private fun observeSession() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                container.repository.connection.collect { state ->
                    when (state) {
                        // Session up: keep the process alive in the background.
                        is ConnState.Connected ->
                            CameraControlService.start(this@MainActivity)
                        // Idle / Connecting / Failed: no live session to serve.
                        else -> CameraControlService.stop(this@MainActivity)
                    }
                }
            }
        }
    }

    /** USB detach while returning via the system intent disconnects softly. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        container.usb.scan()
    }

    override fun onRestart() {
        super.onRestart()
        // The camera might have been unplugged while in the background.
        container.usb.scan()
    }
}
