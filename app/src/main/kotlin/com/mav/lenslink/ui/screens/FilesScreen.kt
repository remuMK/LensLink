/*
 * FilesScreen.kt — camera SD card browser:
 *   • depth-limited listing from gp_camera_folder_list_files
 *   • PTP thumbnail chips (GetObjectThumb, cached in the repository)
 *   • sorting: name / date / size / type
 *   • metadata sheet (size, date, resolution, MIME) per file
 *   • single + bulk (multi-select) streaming downloads with progress
 */
package com.mav.lenslink.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.Sort
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mav.cameranativebridge.CameraFileInfo
import com.mav.lenslink.appContainer
import com.mav.lenslink.models.CameraFile
import com.mav.lenslink.models.ConnState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

/** Sort orders surfaced as a drop-down on the header. */
private enum class SortMode(val label: String) {
    DATE_DESC("Newest first"),
    DATE_ASC("Oldest first"),
    NAME_ASC("Name A→Z"),
    NAME_DESC("Name Z→A"),
    SIZE_DESC("Size, largest first"),
    SIZE_ASC("Size, smallest first"),
    TYPE("Group by type, newest first"),
}

private val COMPATIBLE_EXT =
    setOf("JPG", "JPEG", "CR2", "CR3", "MOV", "MP4", "PNG", "AVI", "HEIF", "HEIC")

private val DATE_FMT = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

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
                state.withMetadata.clear()
                state.selected.clear()
                state.files = repo.listFiles()
                    .filter { it.extension in COMPATIBLE_EXT }
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

    // Fetch size/date metadata sequentially once per listing; each arrival
    // re-sorts the view automatically (sorting reads withMetadata).
    LaunchedEffect(state.files, connection) {
        if (state.files.isEmpty()) return@LaunchedEffect
        for (file in state.files) {
            if (file.fullPath in state.withMetadata) continue
            state.withMetadata[file.fullPath] =
                try { repo.fileInfoFor(file) } catch (_: Throwable) { CameraFileInfo.UNKNOWN }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
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

            SortMenu(mode = state.sort, onPick = { state.sort = it })

            // multi-select toggle — rows switch to checkbox mode
            FilledTonalIconButton(
                onClick = {
                    if (state.selecting) {
                        state.selected.clear()
                        state.selecting = false
                    } else {
                        state.selecting = true
                    }
                },
            ) {
                Icon(
                    Icons.Rounded.SelectAll,
                    contentDescription = "Multi-select mode",
                    tint = if (state.selecting) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.width(6.dp))
            FilledTonalButton(onClick = { reload() }) {
                Icon(Icons.Rounded.Refresh, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Refresh")
            }
        }

        if (state.selecting && state.files.isNotEmpty()) {
            val selectedCount = state.selected.size
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(onClick = {
                    if (selectedCount == state.files.size) {
                        state.selected.clear()
                    } else {
                        state.files.forEach { state.selected[it.fullPath] = true }
                    }
                }) {
                    Text(
                        if (selectedCount == state.files.size)
                            "Clear all" else "Select all",
                    )
                }
                Spacer(Modifier.weight(1f))
                FilledTonalButton(
                    enabled = selectedCount > 0 && !state.bulkDownloading,
                    onClick = {
                        state.bulkDownloading = true
                        val picked = state.files.filter { it.fullPath in state.selected }
                        scope.launch {
                            try {
                                val dir = repo.bulkDownload(picked.map { it.folder to it.name })
                                state.savedPath = "→ ${dir.name} (${picked.size} files)"
                            } catch (t: Throwable) {
                                state.error = t.message
                            }
                            state.bulkDownloading = false
                        }
                    },
                ) {
                    if (state.bulkDownloading) {
                        CircularProgressIndicator(Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("Download $selectedCount")
                }
            }
        }

        if (progress in 0.01f..0.99f || state.bulkDownloading) {
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
            else -> {
                // read the map size so newly-arrived metadata re-sorts the view
                val metaVersion = state.withMetadata.size
                val sorted = sortFiles(state.files, state.withMetadata, state.sort, metaVersion)
                FileList(
                    files = sorted,
                    state = state,
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

        state.details?.let { detailsTarget ->
            MetadataSheet(file = detailsTarget, onDismiss = { state.details = null })
        }
    }
}

/** Simple observable UI state holder for this screen. */
private class FilesUiState {
    var files by mutableStateOf<List<CameraFile>>(emptyList())
    var loading by mutableStateOf(true)
    var error by mutableStateOf<String?>(null)
    var savedPath by mutableStateOf<String?>(null)
    var sort by mutableStateOf(SortMode.DATE_DESC)
    var selecting by mutableStateOf(false)
    var bulkDownloading by mutableStateOf(false)

    /** fullPath → marked in select-mode bulk download. */
    val selected = mutableStateMapOf<String, Boolean>()

    /** fullPath → object info, filled lazily in listing order. */
    val withMetadata = mutableStateMapOf<String, CameraFileInfo>()

    /** Metadata-sheet target. */
    var details by mutableStateOf<CameraFile?>(null)
}

/* ------------------------------------------------------------------ */
/* sorting                                                             */
/* ------------------------------------------------------------------ */

private fun sortFiles(
    files: List<CameraFile>,
    meta: Map<String, CameraFileInfo>,
    mode: SortMode,
    @Suppress("UNUSED_PARAMETER") metaVersion: Int,   // re-sorts as metadata lands
): List<CameraFile> = when (mode) {
    SortMode.NAME_ASC -> files.sortedWith(compareBy { it.name })
    SortMode.NAME_DESC -> files.sortedWith(compareByDescending { it.name })
    SortMode.DATE_DESC -> files.sortedWith(compareByDescending { meta[it.fullPath]?.mtimeSec ?: 0L })
    SortMode.DATE_ASC -> files.sortedWith(compareBy { meta[it.fullPath]?.mtimeSec ?: 0L })
    SortMode.SIZE_DESC ->
        files.sortedWith(compareByDescending { meta[it.fullPath]?.sizeBytes ?: -1L })
    SortMode.SIZE_ASC ->
        files.sortedWith(compareBy { meta[it.fullPath]?.sizeBytes ?: Long.MAX_VALUE })
    SortMode.TYPE -> files.sortedWith(
        compareBy<CameraFile> { it.extension }.thenByDescending { it.name },
    )
}

@Composable
private fun SortMenu(mode: SortMode, onPick: (SortMode) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilledTonalIconButton(onClick = { open = true }) {
            Icon(
                Icons.Rounded.Sort,
                contentDescription = "Sort",
                tint = if (mode != SortMode.DATE_DESC) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            SortMode.entries.forEach { m ->
                DropdownMenuItem(
                    text = { Text(m.label) },
                    onClick = { onPick(m); open = false },
                )
            }
        }
    }
}

/* ------------------------------------------------------------------ */
/* list                                                                */
/* ------------------------------------------------------------------ */

@Composable
private fun FileList(
    files: List<CameraFile>,
    state: FilesUiState,
    downloading: (CameraFile) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize()) {
        items(files, key = { it.fullPath }) { file ->
            FileRow(
                file = file,
                state = state,
                downloading = downloading,
            )
        }
    }
}

@Composable
private fun FileRow(
    file: CameraFile,
    state: FilesUiState,
    downloading: (CameraFile) -> Unit,
) {
    val repo = appContainer().repository

    // thumbnail (PTP GetObjectThumb through the repository cache)
    var thumb by remember(file.fullPath) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(file.fullPath) { thumb = repo.thumbnailFor(file) }

    val isSelected = state.selected.containsKey(file.fullPath)
    val info = state.withMetadata[file.fullPath]

    Row(
        Modifier
            .fillMaxWidth()
            .clickable {
                when (state.selecting) {
                    true -> if (isSelected) state.selected.remove(file.fullPath)
                            else state.selected[file.fullPath] = true
                    false -> state.details = file
                }
            }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // thumbnail chip (falls back to the type icon)
        Box(Modifier.size(56.dp)) {
            val t = thumb
            if (t != null) {
                Image(
                    bitmap = t,
                    contentDescription = file.extension,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(10.dp)),
                )
            } else {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            file.icon(),
                            contentDescription = file.extension,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }

        Column(Modifier.weight(1f)) {
            Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(
                    info?.humanSize(),
                    (info?.takeIf { it.mtimeSec > 0 })
                        ?.let { DATE_FMT.format(Date(it.mtimeSec * 1000)) },
                ).joinToString("  ·  ").ifEmpty { file.folder },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (state.selecting) {
            Checkbox(
                checked = isSelected,
                onCheckedChange = {
                    if (it) state.selected[file.fullPath] = true
                    else state.selected.remove(file.fullPath)
                },
            )
        } else {
            IconButton(onClick = { downloading(file) }) {
                Icon(Icons.Rounded.Download, contentDescription = "Download")
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

private fun CameraFileInfo.humanSize(): String? {
    if (sizeBytes < 0) return null
    val kb = sizeBytes / 1024.0
    return when {
        kb >= 1024 * 1024 -> "%.1f GiB".format(kb / 1024f / 1024f)
        kb >= 1024 -> "%.1f MiB".format(kb / 1024f)
        else -> "%.0f KiB".format(kb)
    }
}

/* ------------------------------------------------------------------ */
/* metadata sheet                                                      */
/* ------------------------------------------------------------------ */

@Composable
private fun MetadataSheet(file: CameraFile, onDismiss: () -> Unit) {
    val repo = appContainer().repository
    var info by remember(file.fullPath) { mutableStateOf<CameraFileInfo?>(null) }

    LaunchedEffect(file.fullPath) {
        info = try { repo.fileInfoFor(file) } catch (_: Throwable) { null }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
        title = { Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val i = info
                if (i == null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        CircularProgressIndicator(Modifier.size(18.dp))
                        Text("Reading object info…")
                    }
                } else {
                    MetaLine("Folder", file.folder)
                    MetaLine("Size", i.humanSize() ?: "unknown")
                    if (i.mtimeSec > 0) {
                        MetaLine("Date", DATE_FMT.format(Date(i.mtimeSec * 1000)))
                    }
                    if (i.widthPx > 0 && i.heightPx > 0) {
                        MetaLine("Resolution", "${i.widthPx} × ${i.heightPx}")
                    }
                    MetaLine("Type", i.mime)
                }
            }
        },
    )
}

@Composable
private fun MetaLine(label: String, value: String) {
    Row {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier
                .weight(0.28f)
                .padding(top = 2.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(0.72f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
