package com.zhiwei.xplayer.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zhiwei.xplayer.R
import com.zhiwei.xplayer.core.mpv.PlaybackSource
import com.zhiwei.xplayer.core.util.Formatters
import com.zhiwei.xplayer.core.webdav.DavAccount
import com.zhiwei.xplayer.core.webdav.DavEntry
import com.zhiwei.xplayer.ui.components.EmptyState
import com.zhiwei.xplayer.ui.components.InfoRow
import com.zhiwei.xplayer.ui.vm.WebDavViewModel

/**
 * WebDAV 浏览页。
 *
 * 结构与 [FolderScreen] 对齐：顶部是「服务器」横向选择条 + 添加按钮，
 * 下面是面包屑，再下面是内容区。这样两个「浏览远端文件」的页面操作方式一致，
 * 用户从 SAF 换到 WebDAV 不用重新学。
 *
 * 播放不需要先下载：WebDAV 上的文件给出一个直链，交给 mpv/FFmpeg 的
 * http 协议去拉流；Basic 凭据通过 [PlaybackSource.httpHeaders] 以请求头
 * 下发，**不放进 uri**（uri 会进播放历史并显示在界面上）。
 */
@Composable
fun WebDavBrowserScreen(
    onPlay: (PlaybackSource) -> Unit,
    viewModel: WebDavViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val account by viewModel.account.collectAsStateWithLifecycle()

    var showAddDialog by remember { mutableStateOf(false) }

    // 首次进入或者账号列表就绪后自动选第一个，省去一次点击
    LaunchedEffect(accounts) {
        if (account == null && accounts.isNotEmpty()) {
            viewModel.openAccount(accounts.first())
        }
    }

    if (showAddDialog) {
        AddServerDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { newAccount ->
                viewModel.addAccount(newAccount)
                viewModel.openAccount(newAccount)
                showAddDialog = false
            },
        )
    }

    if (accounts.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            EmptyState(
                icon = Icons.Outlined.Cloud,
                title = context.getString(R.string.webdav_empty),
                description = context.getString(R.string.webdav_add_desc),
                actionLabel = context.getString(R.string.webdav_add),
                onAction = { showAddDialog = true },
            )
        }
        return
    }

    Column(Modifier.fillMaxSize()) {
        // ------------------------------------------------------ 服务器列表 ----
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(items = accounts, key = { it.url + it.username }) { item ->
                FilterChip(
                    selected = account == item,
                    onClick = { viewModel.openAccount(item) },
                    label = {
                        Text(item.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Outlined.Cloud,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                )
            }
            item {
                AssistChip(
                    onClick = { showAddDialog = true },
                    label = { Text(context.getString(R.string.webdav_add)) },
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
                onClick = { viewModel.goUp() },
                enabled = state.path != "/",
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = context.getString(R.string.webdav_up),
                )
            }
            Text(
                text = buildString {
                    append(account?.label.orEmpty())
                    if (state.path != "/") append(" · ").append(state.path)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { viewModel.refresh() }) {
                Icon(
                    Icons.Outlined.Refresh,
                    contentDescription = context.getString(R.string.webdav_refresh),
                )
            }
            IconButton(onClick = { account?.let { viewModel.removeAccount(it) } }) {
                Icon(
                    Icons.Outlined.Delete,
                    contentDescription = context.getString(R.string.webdav_remove),
                )
            }
        }

        // ---------------------------------------------------------- 内容 ----
        when {
            state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }

            state.error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState(
                    icon = Icons.Outlined.Cloud,
                    title = state.error.orEmpty(),
                    actionLabel = context.getString(R.string.webdav_refresh),
                    onAction = { viewModel.refresh() },
                )
            }

            state.entries.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState(
                    icon = Icons.Outlined.Folder,
                    title = context.getString(R.string.webdav_no_media),
                )
            }

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = BOTTOM_BAR_RESERVE),
            ) {
                items(items = state.entries, key = { it.path }) { entry ->
                    DavEntryRow(
                        entry = entry,
                        onClick = {
                            if (entry.isDirectory) {
                                viewModel.openDirectory(entry)
                            } else if (entry.isPlayable) {
                                val url = viewModel.playableUrl(entry) ?: return@DavEntryRow
                                onPlay(
                                    PlaybackSource(
                                        uri = url,
                                        title = entry.name,
                                        isNetwork = true,
                                        // Basic 凭据走请求头，而不是塞进 uri：
                                        // uri 会进播放历史并显示在界面上。
                                        httpHeaders = viewModel.playbackHeaders(),
                                    ),
                                )
                            }
                        },
                    )
                }
            }
        }
    }
}

/** 一条 WebDAV 条目。目录显示为「文件夹 / 可进入」，文件显示大小。 */
@Composable
private fun DavEntryRow(entry: DavEntry, onClick: () -> Unit) {
    InfoRow(
        icon = if (entry.isDirectory) Icons.Outlined.Folder else Icons.Outlined.InsertDriveFile,
        title = entry.name,
        subtitle = if (entry.isDirectory) null else Formatters.size(entry.sizeBytes),
        onClick = onClick,
    )
}

/**
 * 添加服务器的对话框。
 *
 * WebDAV 的配置项就是四个字段，用一个对话框收完最简单；
 * 拆成多页向导反而让「填完就能用」这件事变得啰嗦。
 */
@Composable
private fun AddServerDialog(
    onDismiss: () -> Unit,
    onConfirm: (DavAccount) -> Unit,
) {
    val context = LocalContext.current
    var url by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(context.getString(R.string.webdav_add)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = url,
                    onValueChange = {
                        url = it
                        error = null
                    },
                    label = { Text(context.getString(R.string.webdav_url)) },
                    placeholder = {
                        Text(context.getString(R.string.webdav_url_hint))
                    },
                    singleLine = true,
                    isError = error != null,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Next,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text(context.getString(R.string.webdav_username)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(context.getString(R.string.webdav_password)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    label = { Text(context.getString(R.string.webdav_display_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let {
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(
                    text = context.getString(R.string.webdav_password_note),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val trimmed = url.trim()
                    if (trimmed.isBlank()) {
                        error = context.getString(R.string.webdav_invalid)
                        return@TextButton
                    }
                    onConfirm(
                        DavAccount(
                            url = normalizeUrl(trimmed),
                            username = username.trim(),
                            password = password,
                            displayName = displayName.trim(),
                        ),
                    )
                },
            ) {
                Text(context.getString(R.string.action_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(context.getString(R.string.action_cancel))
            }
        },
    )
}

/**
 * 补全协议头。
 *
 * 用户填 `dav.example.com/dav` 是很自然的写法，但 OkHttp 要求 scheme 必须存在。
 * 这里统一补 `https://` —— 明文 http 属于少数情况，真要用的用户会自己写全。
 */
private fun normalizeUrl(raw: String): String = when {
    raw.startsWith("http://", ignoreCase = true) -> raw
    raw.startsWith("https://", ignoreCase = true) -> raw
    else -> "https://$raw"
}
