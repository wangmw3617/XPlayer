package com.zhiwei.xplayer.ui.screen

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Podcasts
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zhiwei.xplayer.R
import com.zhiwei.xplayer.core.media.MediaEntry
import com.zhiwei.xplayer.core.mpv.PlaybackSource
import com.zhiwei.xplayer.core.util.Formatters
import com.zhiwei.xplayer.ui.components.EmptyState
import com.zhiwei.xplayer.ui.components.GlassCard
import com.zhiwei.xplayer.ui.components.InfoRow
import com.zhiwei.xplayer.ui.components.MEDIA_MIME_TYPES
import com.zhiwei.xplayer.ui.components.MediaThumbnail
import com.zhiwei.xplayer.ui.components.SectionHeader
import com.zhiwei.xplayer.ui.components.queryDisplayName
import com.zhiwei.xplayer.ui.components.rememberMediaPicker
import com.zhiwei.xplayer.ui.nav.HistoryRoute
import com.zhiwei.xplayer.ui.nav.LogRoute
import com.zhiwei.xplayer.ui.nav.StreamRoute
import com.zhiwei.xplayer.ui.vm.LibraryViewModel
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts

/** 玻璃底栏占位高度：内容末尾要留出这么多，否则最后一项被底栏挡住 */
val BOTTOM_BAR_RESERVE = 108.dp

@Composable
fun HomeScreen(
    onOpen: (Any) -> Unit,
    onPlay: (PlaybackSource) -> Unit,
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val videos by viewModel.videos.collectAsStateWithLifecycle()
    val audio by viewModel.audio.collectAsStateWithLifecycle()
    val recent by viewModel.recentHistory.collectAsStateWithLifecycle()
    val permissionGranted by viewModel.permissionGranted.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { viewModel.refresh() }

    val filePicker = rememberMediaPicker { uri ->
        onPlay(
            PlaybackSource(
                uri = uri.toString(),
                title = queryDisplayName(context, uri),
            ),
        )
    }

    // 首次进入且已有权限就直接扫一遍；没权限等用户点授权
    LaunchedEffect(permissionGranted) {
        if (permissionGranted) viewModel.refresh()
    }

    val media = remember(videos, audio) { videos + audio }
    val continueItem = recent.firstOrNull()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = BOTTOM_BAR_RESERVE),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (!permissionGranted) {
            item {
                GlassCard {
                    Text(
                        text = context.getString(R.string.home_empty),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = context.getString(R.string.home_empty_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    androidx.compose.material3.Button(
                        onClick = { permissionLauncher.launch(viewModel.requiredPermissions()) },
                    ) {
                        Text(context.getString(R.string.home_grant))
                    }
                }
            }
        }

        // ---------------------------------------------------------- 继续观看 ----
        if (continueItem != null) {
            item {
                SectionHeader(title = context.getString(R.string.home_continue))
            }
            item {
                ContinueCard(
                    title = continueItem.title,
                    uri = continueItem.uri,
                    positionMs = continueItem.positionMs,
                    durationMs = continueItem.durationMs,
                    onClick = {
                        onPlay(
                            PlaybackSource(
                                title = continueItem.title,
                                uri = continueItem.uri,
                                isNetwork = continueItem.isNetwork,
                                startPositionMs = continueItem.positionMs,
                            ),
                        )
                    },
                )
            }
        }

        // ---------------------------------------------------------- 快捷入口 ----
        item {
            SectionHeader(title = context.getString(R.string.home_quick))
        }
        item {
            GlassCard(contentPadding = PaddingValues(vertical = 4.dp)) {
                InfoRow(
                    icon = Icons.Outlined.FolderOpen,
                    title = context.getString(R.string.home_open_file),
                    subtitle = context.getString(R.string.home_open_file_desc),
                    onClick = { filePicker.launch(MEDIA_MIME_TYPES) },
                )
                InfoRow(
                    icon = Icons.Outlined.Podcasts,
                    title = context.getString(R.string.home_stream),
                    subtitle = context.getString(R.string.home_stream_desc),
                    onClick = { onOpen(StreamRoute) },
                )
                InfoRow(
                    icon = Icons.Outlined.History,
                    title = context.getString(R.string.home_history),
                    subtitle = context.getString(R.string.home_history_desc),
                    onClick = { onOpen(HistoryRoute) },
                )
                InfoRow(
                    icon = Icons.Outlined.Terminal,
                    title = context.getString(R.string.home_log),
                    subtitle = context.getString(R.string.home_log_desc),
                    onClick = { onOpen(LogRoute) },
                )
            }
        }

        // ---------------------------------------------------------- 最近添加 ----
        if (media.isNotEmpty()) {
            item {
                SectionHeader(
                    title = context.getString(R.string.home_recent_added),
                    trailing = {
                        Text(
                            text = context.getString(R.string.library_count, media.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                )
            }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(items = media.take(12), key = { it.uri }) { entry ->
                        RecentTile(entry = entry, onClick = { onPlay(entry.toSource()) })
                    }
                }
            }
        } else if (permissionGranted && !loading) {
            item {
                EmptyState(
                    icon = Icons.Outlined.Movie,
                    title = context.getString(R.string.library_empty),
                    description = context.getString(R.string.home_empty_desc),
                )
            }
        }
    }
}

@Composable
private fun ContinueCard(
    title: String,
    uri: String,
    positionMs: Long,
    durationMs: Long,
    onClick: () -> Unit,
) {
    GlassCard(onClick = onClick, contentPadding = PaddingValues(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MediaThumbnail(
                uri = uri,
                isVideo = true,
                modifier = Modifier
                    .size(width = 116.dp, height = 66.dp)
                    .clip(RoundedCornerShape(12.dp)),
                sizePx = 320,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "${Formatters.position(positionMs)} / ${Formatters.duration(durationMs)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { Formatters.progress(positionMs, durationMs) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp)),
                )
            }
        }
    }
}

@Composable
private fun RecentTile(entry: MediaEntry, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(132.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
    ) {
        MediaThumbnail(
            uri = entry.uri,
            isVideo = entry.isVideo,
            modifier = Modifier
                .fillMaxWidth()
                .height(78.dp)
                .clip(RoundedCornerShape(14.dp)),
            sizePx = 320,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = entry.title,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = Formatters.duration(entry.durationMs),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 媒体条目 -> 播放请求 */
fun MediaEntry.toSource(): PlaybackSource = PlaybackSource(
    title = title,
    uri = uri,
    isNetwork = false,
)
