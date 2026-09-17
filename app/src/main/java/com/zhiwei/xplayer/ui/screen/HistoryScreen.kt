package com.zhiwei.xplayer.ui.screen

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Podcasts
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zhiwei.xplayer.R
import com.zhiwei.xplayer.core.mpv.PlaybackSource
import com.zhiwei.xplayer.core.util.Formatters
import com.zhiwei.xplayer.ui.components.EmptyState
import com.zhiwei.xplayer.ui.components.InfoRow
import com.zhiwei.xplayer.ui.vm.LibraryViewModel

/** 播放历史 */
@Composable
fun HistoryScreen(
    onPlay: (PlaybackSource) -> Unit,
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val history by viewModel.history.collectAsStateWithLifecycle()
    var confirmClear by remember { mutableStateOf(false) }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(context.getString(R.string.history_clear)) },
            text = { Text(context.getString(R.string.history_clear_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmClear = false
                        viewModel.clearHistory()
                    },
                ) { Text(context.getString(R.string.action_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) {
                    Text(context.getString(R.string.action_cancel))
                }
            },
        )
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = context.getString(R.string.library_count, history.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (history.isNotEmpty()) {
                TextButton(onClick = { confirmClear = true }) {
                    Text(context.getString(R.string.history_clear))
                }
            }
        }

        if (history.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState(
                    icon = Icons.Outlined.History,
                    title = context.getString(R.string.history_empty),
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                items(items = history, key = { it.uri }) { item ->
                    InfoRow(
                        icon = if (item.isNetwork) Icons.Outlined.Podcasts else Icons.Outlined.Videocam,
                        title = item.title.ifBlank { item.uri },
                        subtitle = buildString {
                            append(Formatters.relativeTime(item.updatedAt, System.currentTimeMillis()))
                            if (item.durationMs > 0L) {
                                append(" · ")
                                append(Formatters.position(item.positionMs))
                                append(" / ")
                                append(Formatters.duration(item.durationMs))
                            }
                        },
                        onClick = {
                            onPlay(
                                PlaybackSource(
                                    uri = item.uri,
                                    title = item.title.ifBlank { item.uri },
                                    isNetwork = item.isNetwork,
                                    startPositionMs = item.positionMs,
                                ),
                            )
                        },
                        trailing = {
                            IconButton(onClick = { viewModel.removeHistory(item.uri) }) {
                                Icon(
                                    imageVector = Icons.Outlined.Delete,
                                    contentDescription = context.getString(R.string.history_remove),
                                )
                            }
                        },
                    )
                }
            }
        }
    }
}
