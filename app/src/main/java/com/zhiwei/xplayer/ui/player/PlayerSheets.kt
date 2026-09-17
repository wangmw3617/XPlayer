package com.zhiwei.xplayer.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** 播放器页里可弹出的面板种类 */
enum class PlayerSheet {
    SPEED,
    AUDIO_TRACK,
    VIDEO_TRACK,
    SUBTITLE_TRACK,
    ASPECT,
    LOOP,
    SUBTITLE_DELAY,
    AUDIO_DELAY,
}

/** 面板里的一个选项 */
data class SheetOption(
    val label: String,
    val detail: String? = null,
    val selected: Boolean = false,
    val onClick: () -> Unit,
)

/**
 * 通用选项面板。
 *
 * 用 `ModalBottomSheet` 而不是 `AlertDialog`：这些选项都是「边看边调」的
 * （切音轨、调延迟），底部面板不会把画面挡住，而且手指离得近。
 */
@Composable
fun OptionSheet(
    title: String,
    options: List<SheetOption>,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
        )
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 420.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            items(items = options, key = { it.label + (it.detail ?: "") }) { option ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = option.onClick)
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = option.label,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        option.detail?.let { detail ->
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = detail,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (option.selected) {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
    }
}
