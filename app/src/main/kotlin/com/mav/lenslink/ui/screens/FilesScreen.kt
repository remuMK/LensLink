/*
 * FilesScreen.kt — camera SD card browser:
 *   • depth-limited listing from gp_camera_folder_list_files
 *   • streaming downloads into app external storage (bounded memory)
 */
package com.mav.lenslink.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mav.lenslink.appContainer
import com.mav.lenslink.models.CameraFile
import com.mav.lenslink.models.ConnState
import kotlinx.coroutines.launch

@Composable
fun FilesScreen() {
    val repo = appContainer().repository
    val connection by repo.connection.collectAsStateWithLifecycle()
    val progress by repo.downloadProgress.collectAsStateWithLifecycle()

    val scope = rememberCoroutineScope()

    val state = remember { FilesUiState() }

    fun reload() {
        state.loading = true
        state.error = null
        scope.launch {
            try {
                state.files = repo.listFiles()
                    .filter { it.extension in listOf("JPG", "JPEG", "CR2", "CR3", "MOV", "MP4", "PNG") }
                    .sortedWith(
                        compareByDescending<CameraFile> { it.name }
                            .thenByDescending { it.folder },
                    )
                state.loading = false
            } catch (t: Throwable) {
                state.error = t.message
                state.loading = false
            }
        }
    }

    LaunchedEffect(connection) {
        if (connection is ConnState.Connected) reload()
        else { state.files = emptyList(); state.loading = false }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text("On camera", style = MaterialTheme.typography.titleLarge)
                Text(
                    buildString {
                        append("${state.files.size} files")
                        state.savedPath?.let { append("  ·  saved ").append(it) }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FilledTonalButton(onClick = { reload() }) {
                Icon(Icons.Rounded.Refresh, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Refresh")
            }
        }

        if (progress > 0f && progress < 1f) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            )
        }

        when {
            connection !is ConnState.Connected ->
                CenterHint("Connect a camera first — the list mirrors the SD card.", loading = false)
            state.loading && state.files.isEmpty() -> CenterHint("Reading card…", loading = true)
            state.error != null -> CenterHint("Listing failed: ${state.error}", loading = false)
            state.files.isEmpty() -> CenterHint("No compatible files on the card.", loading = false)
            else -> FileList(
                files = state.files,
                downloading = {
                    scope.launch {
                        try {
                            val file = repo.downloadTo(it.folder, it.name)
                            state.savedPath =
                                file.absolutePath.substringAfterLast('/')
                        } catch (t: Throwable) {
                            state.error = t.message
                        }
                    }
                },
            )
        }
    }
}

/** Simple observable UI state holder for this screen. */
private class FilesUiState {
    var files by mutableStateOf<List<CameraFile>>(emptyList())
    var loading by mutableStateOf(true)
    var error by mutableStateOf<String?>(null)
    var savedPath by mutableStateOf<String?>(null)
}

@Composable
private fun FileList(files: List<CameraFile>, downloading: (CameraFile) -> Unit) {
    LazyColumn(Modifier.fillMaxSize()) {
        items(files, key = { it.fullPath }) { file ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { downloading(file) }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    file.icon(),
                    contentDescription = file.extension,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Column(Modifier.weight(1f)) {
                    Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        file.folder,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    file.extension,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                IconButton(onClick = { downloading(file) }) {
                    Icon(Icons.Rounded.Download, contentDescription = "Download")
                }
            }
        }
    }
}

@Composable
private fun CameraFile.icon() =
    when {
        isMovie -> Icons.Rounded.Movie
        isRaw -> Icons.Rounded.PhotoCamera
        else -> Icons.Rounded.InsertDriveFile
    }

@Composable
private fun CenterHint(text: String, loading: Boolean) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (loading) CircularProgressIndicator(Modifier.size(24.dp))
        Spacer(Modifier.height(12.dp))
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

