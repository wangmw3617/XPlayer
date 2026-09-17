package com.zhiwei.xplayer.ui

import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 来自系统「分享 / 打开方式」的媒体入口。
 *
 * 单独放一个信箱而不是直接跳转：`onNewIntent` 触发时 Compose 的 NavController
 * 可能还没建立（冷启动场景），此时导航会静默失败。让界面侧订阅这个流，
 * 谁先准备好谁处理。
 */
object SharedInput {

    private val _uris = MutableSharedFlow<Uri>(extraBufferCapacity = 4)
    val uris: SharedFlow<Uri> = _uris.asSharedFlow()

    fun post(uri: Uri) {
        _uris.tryEmit(uri)
    }

    /** 从 Intent 里取出媒体 uri；不是分享/查看意图时返回 null */
    fun extract(intent: Intent?): Uri? {
        if (intent == null) return null
        return when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND ->
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            else -> null
        }
    }
}
