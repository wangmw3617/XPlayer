package com.zhiwei.xplayer.ui.screen

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Podcasts
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zhiwei.xplayer.R
import com.zhiwei.xplayer.core.mpv.PlaybackSource
import com.zhiwei.xplayer.core.util.Formatters
import com.zhiwei.xplayer.ui.components.EmptyState
import com.zhiwei.xplayer.ui.components.InfoRow
import com.zhiwei.xplayer.ui.vm.LibraryViewModel

/** 网络串流：手输地址 + 最近的串流历史 */
@Composable
fun StreamScreen(
    onPlay: (PlaybackSource) -> Unit,
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    var url by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }

    val history by viewModel.history.collectAsStateWithLifecycle()
    val streams = remember(history) { history.filter { it.isNetwork } }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            OutlinedTextField(
                value = url,
                onValueChange = {
                    url = it
                    error = false
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                isError = error,
                label = { Text(context.getString(R.string.stream_url)) },
                placeholder = { Text(context.getString(R.string.stream_url_hint)) },
                supportingText = if (error) {
                    { Text(context.getString(R.string.stream_invalid)) }
                } else {
                    null
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Go,
                ),
                shape = RoundedCornerShape(16.dp),
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    val trimmed = url.trim()
                    if (trimmed.isBlank()) {
                        error = true
                    } else {
                        onPlay(
                            PlaybackSource(
                                uri = trimmed,
                                title = trimmed.substringAfterLast('/').ifBlank { trimmed },
                                isNetwork = true,
                            ),
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null)
                Text(
                    text = context.getString(R.string.stream_play),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
        }

        if (streams.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState(
                    icon = Icons.Outlined.Podcasts,
                    title = context.getString(R.string.stream_empty),
                    description = context.getString(R.string.home_stream_desc),
                )
            }
        } else {
            Text(
                text = context.getString(R.string.stream_recent),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                items(items = streams, key = { it.uri }) { item ->
                    InfoRow(
                        icon = Icons.Outlined.Podcasts,
                        title = item.title.ifBlank { item.uri },
                        subtitle = item.uri,
                        onClick = {
                            onPlay(
                                PlaybackSource(
                                    uri = item.uri,
                                    title = item.title.ifBlank { item.uri },
                                    isNetwork = true,
                                    startPositionMs = item.positionMs,
                                ),
                            )
                        },
                        trailing = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (item.durationMs > 0L) {
                                    Text(
                                        text = Formatters.duration(item.durationMs),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                IconButton(onClick = { viewModel.removeHistory(item.uri) }) {
                                    Icon(
                                        imageVector = Icons.Outlined.Delete,
                                        contentDescription = context.getString(R.string.history_remove),
                                    )
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}
