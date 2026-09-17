package com.zhiwei.xplayer.ui.screen

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.zhiwei.xplayer.R
import com.zhiwei.xplayer.ui.components.EmptyState
import com.zhiwei.xplayer.ui.theme.MonospaceStyle
import com.zhiwei.xplayer.ui.vm.PlayerViewModel

/**
 * libmpv 实时日志。
 *
 * 日志是排查「某个文件放不出来」最直接的证据，所以这里给一个能看、能复制的地方，
 * 而不是只让它烂在 logcat 里。
 */
@Composable
fun LogScreen(viewModel: PlayerViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val lines = remember { mutableStateListOf<String>() }
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) {
        viewModel.log.collect { line ->
            lines.add(line)
            // 日志是无限流，只保留最近一段，否则内存会一直涨
            if (lines.size > MAX_LINES) {
                lines.removeRange(0, lines.size - MAX_LINES)
            }
        }
    }

    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) {
            listState.scrollToItem(lines.size - 1)
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${lines.size} 行",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { lines.clear() }) {
                Icon(Icons.Outlined.Delete, contentDescription = context.getString(R.string.log_clear))
            }
            IconButton(
                onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    clipboard?.setPrimaryClip(
                        ClipData.newPlainText("XPlayer log", lines.joinToString("\n")),
                    )
                    Toast.makeText(
                        context,
                        context.getString(R.string.log_copied),
                        Toast.LENGTH_SHORT,
                    ).show()
                },
            ) {
                Icon(Icons.Outlined.ContentCopy, contentDescription = context.getString(R.string.log_copy))
            }
        }

        if (lines.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState(
                    icon = Icons.Outlined.Terminal,
                    title = context.getString(R.string.log_empty),
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            ) {
                itemsIndexed(items = lines) { _, line ->
                    Text(
                        text = line,
                        style = MonospaceStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 1.dp),
                    )
                }
            }
        }
    }
}

private const val MAX_LINES = 800
