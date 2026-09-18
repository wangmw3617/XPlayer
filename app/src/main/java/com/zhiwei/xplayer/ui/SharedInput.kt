package com.zhiwei.xplayer.ui

import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 来自系统「分享 / 打开方式」的媒体入口。
 *
 * 单独放一个信箱而不是直接跳转：`onNewIntent` 触发时 Compose 的 NavController
 * 可能还没建立（冷启动场景），此时导航会静默失败。让界面侧订阅这个信箱，
 * 谁先准备好谁处理。
 */
object SharedInput {

    // 用 StateFlow 而不是 SharedFlow：MainActivity.onCreate 里 post 的时候
    // AppRoot 还没开始订阅，SharedFlow 的 tryEmit 会把事件直接丢掉 ——
    // 表现就是「冷启动时从系统分享进来没反应」。StateFlow 会保留最新值，
    // 订阅者一上来就能拿到。
    private val _pending = MutableStateFlow<Uri?>(null)
    val pending: StateFlow<Uri?> = _pending.asStateFlow()

    fun post(uri: Uri) {
        _pending.value = uri
    }

    /** 取走并清空，避免界面重组时重复跳转 */
    fun consume(): Uri? {
        val current = _pending.value
        _pending.value = null
        return current
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
