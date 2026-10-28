/*
 * FilesScreen.kt — camera SD card browser:
 *   • depth-limited listing from gp_camera_folder_list_files; the listing
 *     paints instantly from the repository's persisted cache (metadata +
 *     thumbnails on disk), and only NEW files hit the SD card on refresh
 *   • list/grid view toggle
 *   • tap = metadata/details, long-press = enter selection mode (hold-one)
 *   • PTP thumbnail chips (GetObjectThumb, disk cached)
 *   • fullscreen in-app viewer: streams the original from the camera into a
 *     PRIVATE cache (no public download), decodes downsampled, and shows
 *     full EXIF metadata (aperture / shutter / ISO / focal length / …)
 *   • single + bulk downloads go to the phone's Downloads folder
 *     (Download/LensLink) with success notifications
 */
package com.mav.lenslink.ui.screens

import android.graphics.BitmapFactory
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.Sort
import androidx.compose.material.icons.rounded.ViewList
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mav.cameranativebridge.CameraFileInfo
import com.mav.lenslink.appContainer
import com.mav.lenslink.models.CameraFile
import com.mav.lenslink.models.ConnState
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    val cachedSeed by repo.cachedFiles.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    val state = remember { FilesUiState() }

    // Paint the persisted listing immediately (cache never leaves the SD
    // card in between sessions), then verify against the camera in background.
    LaunchedEffect(cachedSeed) {
        if (cachedSeed.isNotEmpty() && state.files.isEmpty()) {
            state.files = cachedSeed
            state.loading = false
        }
    }

    fun reload() {
        state.loading = true
        state.error = null
        scope.launch {
            try {
                state.files = repo.listFiles()
                    .filter { it.extension in COMPATIBLE_EXT }
                // drop metadata/thumbs of files that left the card
                repo.pruneMetaCache(state.files.map { it.fullPath }.toSet())
                state.loading = false
            } catch (t: Throwable) {
                state.error = t.message
                state.loading = false
            }
        }
    }

    LaunchedEffect(connection) {
        when {
            connection is ConnState.Connected && state.files.isEmpty() -> reload()
            connection !is ConnState.Connected -> state.loading = false
        }
    }

    // Fetch size/date metadata sequentially once per listing; each arrival
    // re-sorts the view automatically (sorting reads withMetadata).
    // Cached entries cost nothing (read-through, persisted on disk).
    LaunchedEffect(state.files, connection) {
        if (state.files.isEmpty()) return@LaunchedEffect
        for (file in state.files.take(400)) {
            if (file.fullPath in state.withMetadata) continue
            state.withMetadata[file.fullPath] =
                try { repo.fileInfoFor(file) } catch (_: Throwable) { CameraFileInfo.UNKNOWN }
        }
    }

    state.viewer?.let { target ->
        FullscreenViewer(file = target, onDismiss = { state.viewer = null })
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

            // grid / list view toggle
            FilledTonalIconButton(
                onClick = { state.grid = !state.grid },
            ) {
                Icon(
                    if (state.grid) Icons.Rounded.ViewList else Icons.Rounded.GridView,
                    contentDescription = if (state.grid) "List view" else "Grid view",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }

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
                                val result = repo.bulkDownload(picked.map { it.folder to it.name })
                                state.savedPath = if (result.savedCount > 0) {
                                    "→ ${result.savedToLabel} (${result.savedCount} files)"
                                } else null
                                if (result.failedCount > 0) {
                                    state.error = "${result.failedCount} download(s) failed"
                                }
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
            state.error != null && state.files.isEmpty() ->
                CenterHint("Listing failed: ${state.error}", loading = false)
            state.files.isEmpty() -> CenterHint("No compatible files on the card.", loading = false)
            else -> {
                // read the map size so newly-arrived metadata re-sorts the view
                val metaVersion = state.withMetadata.size
                val sorted = sortFiles(state.files, state.withMetadata, state.sort, metaVersion)
                fun downloading(file: CameraFile) {
                    scope.launch {
                        try {
                            val result = repo.downloadToPublic(file.folder, file.name)
                            state.savedPath = if (result.savedCount > 0)
                                "→ ${result.savedToLabel}" else null
                            if (result.failedCount > 0) state.error = "Download failed"
                        } catch (t: Throwable) {
                            state.error = t.message
                        }
                    }
                }
                if (state.grid) {
                    FileGrid(files = sorted, state = state, downloading = ::downloading)
                } else {
                    FileList(files = sorted, state = state, downloading = ::downloading)
                }
            }
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
    var grid by mutableStateOf(false)
    var bulkDownloading by mutableStateOf(false)

    /** fullPath → marked in select-mode bulk download. */
    val selected = mutableStateMapOf<String, Boolean>()

    /** fullPath → object info, filled lazily in listing order. */
    val withMetadata = mutableStateMapOf<String, CameraFileInfo>()

    /** Fullscreen viewer target (opened by tap on a row/cell). */
    var viewer by mutableStateOf<CameraFile?>(null)
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
/* list / grid — shared interaction:                                    */
/*   tap → fullscreen viewer, long-press → (de)select & selection mode   */
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
private fun FileGrid(
    files: List<CameraFile>,
    state: FilesUiState,
    downloading: (CameraFile) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(108.dp),
        contentPadding = PaddingValues(
            horizontal = 8.dp,
            vertical = 4.dp,
        ),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        gridItems(files, key = { it.fullPath }) { file ->
            FileCell(file, state)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileCell(
    file: CameraFile,
    state: FilesUiState,
) {
    var thumb by remember(file.fullPath) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(file.fullPath) { thumb = appContainer().repository.thumbnailFor(file) }

    val isSelected = state.selected.containsKey(file.fullPath)
    val selecting = state.selecting || state.selected.isNotEmpty()

    Box(
        Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(
                onClick = {
                    if (selecting) {
                        if (isSelected) state.selected.remove(file.fullPath)
                        else state.selected[file.fullPath] = true
                    } else {
                        state.viewer = file
                    }
                },
                onLongClick = {
                    state.selecting = true
                    if (isSelected) state.selected.remove(file.fullPath)
                    else state.selected[file.fullPath] = true
                },
            ),
    ) {
        val t = thumb
        if (t != null) {
            Image(
                bitmap = t,
                contentDescription = file.extension,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Surface(
                shape = RoundedCornerShape(12.dp),
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
        // selection chrome
        if (isSelected) {
            Surface(
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                modifier = Modifier.fillMaxSize(),
            ) {}
            Checkbox(
                checked = true,
                onCheckedChange = { state.selected.remove(file.fullPath) },
                modifier = Modifier.align(Alignment.TopEnd),
            )
        } else if (selecting) {
            Checkbox(
                checked = false,
                onCheckedChange = { state.selected[file.fullPath] = true },
                modifier = Modifier.align(Alignment.TopEnd),
            )
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

/* ------------------------------------------------------------------ */
/* list rows                                                           */
/* ------------------------------------------------------------------ */

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRow(
    file: CameraFile,
    state: FilesUiState,
    downloading: (CameraFile) -> Unit,
) {
    // thumbnail (PTP GetObjectThumb through the disk+memory caches)
    var thumb by remember(file.fullPath) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(file.fullPath) { thumb = appContainer().repository.thumbnailFor(file) }

    val isSelected = state.selected.containsKey(file.fullPath)
    val selecting = state.selecting || state.selected.isNotEmpty()
    val info = state.withMetadata[file.fullPath]

    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = {
                    if (selecting) {
                        if (isSelected) state.selected.remove(file.fullPath)
                        else state.selected[file.fullPath] = true
                    } else {
                        state.viewer = file
                    }
                },
                onLongClick = {
                    state.selecting = true
                    if (isSelected) state.selected.remove(file.fullPath)
                    else state.selected[file.fullPath] = true
                },
            )
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

        if (selecting) {
            Checkbox(
                checked = isSelected,
                onCheckedChange = {
                    if (it) state.selected[file.fullPath] = true
                    else state.selected.remove(file.fullPath)
                },
            )
        } else {
            IconButton(onClick = { state.viewer = file }) {
                Icon(Icons.Rounded.GridView, contentDescription = "Preview")
            }
            IconButton(onClick = { downloading(file) }) {
                Icon(Icons.Rounded.Download, contentDescription = "Download")
            }
        }
    }
}

/* ------------------------------------------------------------------ */
/* metadata sheet                                                      */
/* ------------------------------------------------------------------ */

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

/* ------------------------------------------------------------------ */
/* fullscreen in-app viewer (no public download) + full EXIF details    */
/* ------------------------------------------------------------------ */

/**
 * Streams the original file from the camera into the app-private viewer
 * cache, decodes it downsampled to the screen, and shows the full metadata
 * panel (size/date/MIME from PTP object info + EXIF from the JPEG header:
 * aperture, shutter, ISO, focal length, metering…).
 */
@Composable
private fun FullscreenViewer(file: CameraFile, onDismiss: () -> Unit) {
    val repo = appContainer().repository
    var bitmap by remember(file.fullPath) { mutableStateOf<ImageBitmap?>(null) }
    var busy by remember(file.fullPath) { mutableStateOf(true) }
    var exifLines by remember(file.fullPath) { mutableStateOf<List<Pair<String, String>>>(emptyList()) }

    LaunchedEffect(file.fullPath) {
        val original = runCatching { repo.fetchForViewer(file) }.getOrNull()
        if (original != null) {
            exifLines = withContext(Dispatchers.IO) { parseExif(original) }
            bitmap = withContext(Dispatchers.Default) { decodeForScreen(original) }
        }
        busy = false
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            color = MaterialTheme.colorScheme.scrim,
            modifier = Modifier.fillMaxSize(),
        ) {
            Column(Modifier.fillMaxSize()) {
                // header
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            Icons.Rounded.Close,
                            contentDescription = "Close",
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            file.name,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            file.folder,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                Box(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    val bmp = bitmap
                    when {
                        bmp != null -> Image(
                            bitmap = bmp,
                            contentDescription = file.name,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                        busy -> Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            CircularProgressIndicator(Modifier.size(28.dp))
                            Spacer(Modifier.height(10.dp))
                            Text(
                                "Streaming from camera…",
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        else -> Text(
                            "Could not load this file for preview.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                if (exifLines.isNotEmpty()) {
                    Surface(
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp)
                            .clip(RoundedCornerShape(14.dp)),
                    ) {
                        Column(
                            Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            exifLines.forEach { (label, value) ->
                                MetaLine(label, value)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** EXIF summary lines for the viewer footer. */
private fun parseExif(file: File): List<Pair<String, String>> {
    val out = mutableListOf<Pair<String, String>>()
    runCatching {
        val exif = ExifInterface(file.absolutePath)
        /*
         * Rational tags (F number, exposure time, focal length) come back as
         * "100/1"-style strings — getAttributeDouble() resolves them; plain
         * toFloatOrNull() would silently drop every line.
         */
        exif.getAttributeDouble(ExifInterface.TAG_F_NUMBER, 0.0)
            .takeIf { it > 0.0 }?.let { out += "Aperture" to "f/%.1f".format(it) }
        exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, 0.0)
            .takeIf { it > 0.0 }?.let { sec ->
                val label = if (sec >= 1.0) "%.1f s".format(sec)
                else "1/%d s".format((1.0 / sec).roundToInt())
                out += "Shutter" to label
            }
        exif.getAttributeInt(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, 0)
            .takeIf { it > 0 }?.let { out += "ISO" to it.toString() }
        exif.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, 0.0)
            .takeIf { it > 0.0 }?.let { out += "Focal length" to "%.1f mm".format(it) }
        exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_BIAS_VALUE, 0.0)
            .let { if (it != 0.0) out += "Exposure bias" to "%+.1f EV".format(it) }
        exif.getAttribute(ExifInterface.TAG_MODEL)
            ?.takeIf { it.isNotBlank() }?.let { out += "Body" to it }
        exif.getAttributeInt(ExifInterface.TAG_METERING_MODE, 0)
            .let { out += "Metering" to meteringModeName(it) }
        exif.getAttributeInt(ExifInterface.TAG_WHITE_BALANCE, 0)
            .let { out += "White balance" to whiteBalanceName(it) }
        exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
            ?.takeIf { it.isNotBlank() }?.let { out += "Taken" to formatExifDate(it) }
        exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 0).let { rot ->
            val deg = when (rot) {
                3, 4 -> 180
                5, 6 -> 90
                7, 8 -> 270
                else -> 0
            }
            if (deg != 0) out += "Orientation" to "$deg°"
        }
    }
    return out
}

private fun meteringModeName(raw: Int): String = when (raw) {
    0 -> "Unknown"
    1 -> "Average"
    2 -> "Center weighted"
    3 -> "Spot"
    4 -> "Multi spot"
    5 -> "Pattern"
    6 -> "Partial"
    else -> raw.toString()
}

private fun whiteBalanceName(raw: Int): String = when (raw) {
    0 -> "Auto"
    1 -> "Manual"
    else -> raw.toString()
}

private val EXIF_DATE_FMT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

private fun formatExifDate(raw: String): String = try {
    EXIF_DATE_FMT.format(
        java.text.SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).parse(raw) ?: raw,
    )
} catch (_: Exception) { raw }

/** Downsamples so the largest dimension fits ~2048 px — cheap memory. */
private fun decodeForScreen(file: File): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0) return null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2048) sample *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    return BitmapFactory.decodeFile(file.absolutePath, opts)?.asImageBitmap()
}
