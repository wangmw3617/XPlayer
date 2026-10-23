package com.zhiwei.xplayer.ui.screen

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zhiwei.xplayer.R
import com.zhiwei.xplayer.core.media.MediaEntry
import com.zhiwei.xplayer.core.media.MediaSort
import com.zhiwei.xplayer.core.mpv.PlaybackSource
import com.zhiwei.xplayer.core.media.MediaAccess
import com.zhiwei.xplayer.core.util.Formatters
import com.zhiwei.xplayer.ui.components.rememberResources
import com.zhiwei.xplayer.ui.components.EmptyState
import com.zhiwei.xplayer.ui.components.MediaListRow
import com.zhiwei.xplayer.ui.components.MediaThumbnail
import com.zhiwei.xplayer.ui.vm.LibraryViewModel

@Composable
fun LibraryScreen(
    onPlay: (PlaybackSource) -> Unit,
    onPlayQueue: (List<PlaybackSource>, Int) -> Unit = { queue, index ->
        queue.getOrNull(index)?.let(onPlay)
    },
    onOpen: (Any) -> Unit,
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val resources = rememberResources()
    var tabIndex by rememberSaveable { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var sortName by rememberSaveable { mutableStateOf(MediaSort.ADDED.name) }
    var gridMode by rememberSaveable { mutableStateOf(true) }

    val catalog by viewModel.catalog.collectAsStateWithLifecycle()
    val videos = catalog.videos
    val audio = catalog.audio
    val scanned = catalog.scanned

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { viewModel.refresh() }

    LaunchedEffect(Unit) {
        viewModel.ensureLoaded()
    }

    val sort = MediaSort.fromName(sortName)
    val source = if (tabIndex == 0) videos else audio
    val currentAccess = if (tabIndex == 0) catalog.videoAccess else catalog.audioAccess
    val entries = remember(source, query, sort) {
        source
            .filter { query.isBlank() || it.title.contains(query, ignoreCase = true) }
            .let { list -> sortEntries(list, sort) }
    }

    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tabIndex, containerColor = androidx.compose.ui.graphics.Color.Transparent) {
            Tab(
                selected = tabIndex == 0,
                onClick = { tabIndex = 0 },
                text = { Text("${resources.getString(R.string.library_video)} (${videos.size})") },
            )
            Tab(
                selected = tabIndex == 1,
                onClick = { tabIndex = 1 },
                text = { Text("${resources.getString(R.string.library_audio)} (${audio.size})") },
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text(resources.getString(R.string.library_search)) },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                shape = RoundedCornerShape(16.dp),
            )
            Spacer(Modifier.size(4.dp))
            IconButton(
                onClick = { onPlayQueue(entries.map { it.toSource() }, 0) },
                enabled = entries.isNotEmpty(),
            ) {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = resources.getString(R.string.player_play_all),
                )
            }
            SortMenu(
                current = sort,
                onSelected = { sortName = it.name },
            )
            IconButton(onClick = { gridMode = !gridMode }) {
                Icon(
                    imageVector = if (gridMode) Icons.AutoMirrored.Filled.ViewList else Icons.Filled.GridView,
                    contentDescription = resources.getString(
                        if (gridMode) R.string.library_list else R.string.library_grid,
                    ),
                )
            }
        }

        if (currentAccess == MediaAccess.PARTIAL) {
            Text(
                text = resources.getString(R.string.permission_partial_video),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        catalog.error?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        when {
            currentAccess == MediaAccess.DENIED -> Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                EmptyState(
                    icon = Icons.Outlined.Movie,
                    title = resources.getString(R.string.home_empty),
                    description = resources.getString(R.string.home_empty_desc),
                    actionLabel = resources.getString(R.string.home_grant),
                    onAction = {
                        permissionLauncher.launch(
                            if (tabIndex == 0) {
                                viewModel.requiredVideoPermissions()
                            } else {
                                viewModel.requiredPermissions()
                            },
                        )
                    },
                )
            }

            catalog.loading && entries.isEmpty() -> Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }

            entries.isEmpty() && scanned -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState(
                    icon = Icons.Outlined.Movie,
                    title = resources.getString(R.string.library_empty),
                )
            }

            gridMode -> LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 148.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = BOTTOM_BAR_RESERVE),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(
                    count = entries.size,
                    key = { entries[it].uri },
                    contentType = { if (entries[it].isVideo) "video" else "audio" },
                ) { index ->
                    val entry = entries[index]
                    MediaGridCard(
                        entry = entry,
                        onClick = { onPlayQueue(entries.map { it.toSource() }, index) },
                    )
                }
            }

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = BOTTOM_BAR_RESERVE),
            ) {
                items(
                    count = entries.size,
                    key = { entries[it].uri },
                    contentType = { if (entries[it].isVideo) "video" else "audio" },
                ) { index ->
                    val entry = entries[index]
                    MediaListRow(
                        title = entry.title,
                        subtitle = entrySubtitle(entry),
                        uri = entry.uri,
                        isVideo = entry.isVideo,
                        onClick = { onPlayQueue(entries.map { it.toSource() }, index) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SortMenu(current: MediaSort, onSelected: (MediaSort) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val resources = rememberResources()
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.AutoMirrored.Outlined.Sort, contentDescription = resources.getString(R.string.library_sort))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            MediaSort.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(resources.getString(sortLabel(option))) },
                    onClick = {
                        expanded = false
                        onSelected(option)
                    },
                )
            }
        }
    }
}

private fun sortLabel(sort: MediaSort): Int = when (sort) {
    MediaSort.ADDED -> R.string.library_sort_added
    MediaSort.NAME -> R.string.library_sort_name
    MediaSort.DURATION -> R.string.library_sort_duration
    MediaSort.SIZE -> R.string.library_sort_size
}

private fun sortEntries(list: List<MediaEntry>, sort: MediaSort): List<MediaEntry> = when (sort) {
    MediaSort.ADDED -> list.sortedByDescending { it.addedAt }
    MediaSort.NAME -> list.sortedBy { it.title.lowercase() }
    MediaSort.DURATION -> list.sortedByDescending { it.durationMs }
    MediaSort.SIZE -> list.sortedByDescending { it.sizeBytes }
}

private fun entrySubtitle(entry: MediaEntry): String {
    val parts = mutableListOf<String>()
    if (entry.durationMs > 0L) parts += Formatters.duration(entry.durationMs)
    entry.resolution?.let { parts += it }
    if (entry.sizeBytes > 0L) parts += Formatters.size(entry.sizeBytes)
    entry.bucket?.takeIf { it.isNotBlank() }?.let { parts += it }
    return parts.joinToString(" · ")
}

@Composable
private fun MediaGridCard(entry: MediaEntry, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
    ) {
        MediaThumbnail(
            uri = entry.uri,
            isVideo = entry.isVideo,
            modifier = Modifier
                .fillMaxWidth()
                .height(104.dp)
                .clip(RoundedCornerShape(16.dp)),
            sizePx = 384,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = entry.title,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = entrySubtitle(entry),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
