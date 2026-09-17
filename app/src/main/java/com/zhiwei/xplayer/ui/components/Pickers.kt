package com.zhiwei.xplayer.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/** 从 content uri 里读出显示名，失败时退回 uri 的最后一段 */
fun queryDisplayName(context: Context, uri: Uri): String = runCatching {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
        }
}.getOrNull()?.takeIf { it.isNotBlank() }
    ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
    ?: "未知媒体"

/**
 * 「打开媒体文件」的选择器。
 *
 * 用 `OpenDocument` 而不是 `GetContent`：只有前者会带上
 * `FLAG_GRANT_PERSISTABLE_URI_PERMISSION`，才能把权限持久化下来 ——
 * 否则重启应用后历史记录里的条目就点不开了。
 */
@Composable
fun rememberMediaPicker(onPicked: (Uri) -> Unit): ManagedActivityResultLauncher<Array<String>, Uri?> {
    val context = LocalContext.current
    return rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            onPicked(uri)
        }
    }
}

/** 「打开字幕文件」的选择器 */
@Composable
fun rememberSubtitlePicker(onPicked: (Uri) -> Unit): ManagedActivityResultLauncher<Array<String>, Uri?> {
    val context = LocalContext.current
    return rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            onPicked(uri)
        }
    }
}

/** 媒体类型的 MIME 过滤（给上面两个选择器用） */
val MEDIA_MIME_TYPES: Array<String> = arrayOf("video/*", "audio/*")

/** 字幕文件的 MIME 过滤。部分 DocumentsProvider 不认这些类型，所以还留了 `*/*` 兜底 */
val SUBTITLE_MIME_TYPES: Array<String> = arrayOf(
    "application/x-subrip",
    "text/plain",
    "text/*",
    "*/*",
)
