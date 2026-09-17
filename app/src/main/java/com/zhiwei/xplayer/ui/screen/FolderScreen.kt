package com.zhiwei.xplayer.ui.screen

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zhiwei.xplayer.R
import com.zhiwei.xplayer.core.media.SafBrowser
import com.zhiwei.xplayer.core.media.SafNode
import com.zhiwei.xplayer.core.mpv.PlaybackSource
import com.zhiwei.xplayer.core.util.Formatters
import com.zhiwei.xplayer.ui.components.EmptyState
import com.zhiwei.xplayer.ui.vm.LibraryViewModel

/**
 * 文件夹页：浏览用户通过 SAF 授权的目录。
 *
 * 路径用 `\n` 连接成一个字符串保存 —— 单个 `List<String>` 在
 * `rememberSaveable` 里要自定义 Saver，而目录名里几乎不可能出现换行符，
 * 用分隔符拼接既简单又能随屏幕旋转保留。
 */
@Composable
fun FolderScreen(
    onPlay: (PlaybackSource) -> Unit,
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val folders by viewModel.folders.collectAsStateWithLifecycle()

    var selectedTree by rememberSaveable { mutableStateOf<String?>(null) }
    var pathText by rememberSaveable { mutableStateOf("") }
    var nodes by remember { mutableStateOf<List<SafNode>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }

    val path = remember(pathText) {
        if (pathText.isBlank()) emptyList() else pathText.split(PATH_SEPARATOR)
    }

    val treePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            viewModel.addFolder(uri.toString())
            selectedTree = uri.toString()
            pathText = ""
        }
    }

    // 授权列表变化时保证选中的那一棵仍然存在
    LaunchedEffect(folders) {
        val current = selectedTree
        if (current == null || folders.none { SafBrowser.sameTree(it.treeUri, current) }) {
            selectedTree = folders.firstOrNull()?.treeUri
            pathText = ""
        }
    }

    LaunchedEffect(selectedTree, pathText) {
        val tree = selectedTree ?: run {
            nodes = emptyList()
            return@LaunchedEffect
        }
        loading = true
        nodes = SafBrowser.list(context, tree, path)
        loading = false
    }

    if (folders.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            EmptyState(
                icon = Icons.Outlined.FolderOpen,
                title = context.getString(R.string.folder_empty),
                description = context.getString(R.string.folder_add_desc),
                actionLabel = context.getString(R.string.folder_add),
                onAction = { treePicker.launch(null) },
            )
        }
        return
    }

    Column(Modifier.fillMaxSize()) {
        // ------------------------------------------------------ 已授权目录 ----
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(items = folders, key = { it.treeUri }) { folder ->
                val selected = selectedTree != null && SafBrowser.sameTree(folder.treeUri, selectedTree!!)
                FilterChip(
                    selected = selected,
                    onClick = {
                        selectedTree = folder.treeUri
                        pathText = ""
                    },
                    label = { Text(folder.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingIcon = {
                        Icon(Icons.Outlined.Folder, contentDescription = null, modifier = Modifier.size(18.dp))
                    },
                )
            }
            item {
                AssistChip(
                    onClick = { treePicker.launch(null) },
                    label = { Text(context.getString(R.string.folder_add)) },
                    leadingIcon = {
                        Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    },
                    colors = AssistChipDefaults.assistChipColors(
                        labelColor = MaterialTheme.colorScheme.primary,
                        leadingIconContentColor = MaterialTheme.colorScheme.primary,
                    ),
                )
            }
        }

        // ---------------------------------------------------------- 面包屑 ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = {
                    if (path.isNotEmpty()) {
                        pathText = path.dropLast(1).joinToString(PATH_SEPARATOR)
                    }
                },
                enabled = path.isNotEmpty(),
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = context.getString(R.string.folder_up))
            }
            Text(
                text = buildString {
                    append(folders.firstOrNull { it.treeUri == selectedTree }?.displayName ?: "")
                    if (path.isNotEmpty()) append(" / ").append(path.joinToString(" / "))
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = { selectedTree?.let { viewModel.removeFolder(it) } },
            ) {
                Icon(Icons.Outlined.Delete, contentDescription = context.getString(R.string.folder_remove))
            }
        }

        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }

            nodes.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState(
                    icon = Icons.Outlined.FolderOpen,
                    title = context.getString(R.string.folder_no_media),
                )
            }

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = BOTTOM_BAR_RESERVE),
            ) {
                items(items = nodes, key = { it.uri }) { node ->
                    SafNodeRow(
                        node = node,
                        onClick = {
                            if (node.isDirectory) {
                                pathText = (path + node.name).joinToString(PATH_SEPARATOR)
                            } else {
                                onPlay(PlaybackSource(uri = node.uri, title = node.name))
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SafNodeRow(node: SafNode, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (node.isDirectory) Icons.Outlined.Folder else Icons.Outlined.InsertDriveFile,
                contentDescription = null,
                tint = if (node.isDirectory) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = node.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!node.isDirectory && node.sizeBytes > 0L) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = Formatters.size(node.sizeBytes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private const val PATH_SEPARATOR = "\n"
