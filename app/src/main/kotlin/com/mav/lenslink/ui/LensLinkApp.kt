/*
 * LensLinkApp.kt — app shell: M3 Scaffold, bottom navigation, snackbars.
 */
package com.mav.lenslink.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.mav.lenslink.appContainer
import com.mav.lenslink.models.ConnState
import com.mav.lenslink.models.Transport
import com.mav.lenslink.ui.screens.ConnectScreen
import com.mav.lenslink.ui.screens.FilesScreen
import com.mav.lenslink.ui.screens.LiveViewScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Tab destinations. */
enum class Tab(val label: String) {
    CONNECT("Connect"),
    VIEWFINDER("Viewfinder"),
    FILES("Files");

    @Composable
    fun icon(): ImageVector = when (this) {
        CONNECT -> Icons.Rounded.Link
        VIEWFINDER -> Icons.Rounded.CameraAlt
        FILES -> Icons.Rounded.PhotoLibrary
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LensLinkApp() {
    val container = appContainer()
    val repository = container.repository

    var selectedTab by rememberSaveable { mutableStateOf(Tab.CONNECT) }
    val snackbar = remember { SnackbarHostState() }

    // Keeps each tab's saveable state (the Files list/grid scroll positions,
    // text fields, …) while another tab is shown — without this every tab
    // switch recomposed from scratch and the files list restarted at the top.
    val tabStateHolder = rememberSaveableStateHolder()

    LaunchedEffect(Unit) {
        container.usb.scan()
    }

    LaunchedEffect(Unit) {
        repository.error.collect { message ->
            message?.let {
                snackbar.showSnackbar(it)
                repository.clearError()
            }
        }
    }

    val conState by repository.connection.collectAsStateWithLifecycle()
    LaunchedEffect(conState) {
        when (val s = conState) {
            is ConnState.Connected -> {
                /*
                 * Files first, live view opt-in: jumping straight to the
                 * viewfinder blanked through the EOS's camera-side screen
                 * (capture_preview interrupts it) before the user asked for
                 * remote viewfinder at all. The Files tab also paints
                 * instantly from the persisted thumbnail/metadata caches.
                 */
                // remember the Wi-Fi endpoint under the camera's model name
                // so the Connect tab can offer a one-tap connect afterwards
                if (s.transport == Transport.WIFI) {
                    repository.lastWifiEndpoint?.let { (host, port) ->
                        container.savedCameras.recordSuccess(host, port, s.info.model)
                    }
                }
                selectedTab = Tab.FILES
            }
            is ConnState.Failed -> selectedTab = Tab.CONNECT
            is ConnState.Lost -> {
                // probe-detected drop: back to Connect so the Lost card with
                // its one-tap reconnect is visible
                selectedTab = Tab.CONNECT
            }
            else -> Unit
        }
    }

    Surface(color = MaterialTheme.colorScheme.background) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("LensLink")
                            when (val s = conState) {
                                is ConnState.Connected -> Text(
                                    s.info.model,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                is ConnState.Lost -> Text(
                                    "Not connected",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                else -> Unit
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                )
            },
            bottomBar = {
                NavigationBar {
                    Tab.entries.forEach { tab ->
                        val selected = selectedTab == tab
                        NavigationBarItem(
                            selected = selected,
                            onClick = { selectedTab = tab },
                            icon = { Icon(tab.icon(), contentDescription = tab.label) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                tabStateHolder.SaveableStateProvider(selectedTab.name) {
                    when (selectedTab) {
                        Tab.CONNECT -> ConnectScreen()
                        Tab.VIEWFINDER -> LiveViewScreen()
                        Tab.FILES -> FilesScreen()
                    }
                }
            }
        }
    }
}
