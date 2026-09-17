package com.zhiwei.xplayer.ui

import com.zhiwei.xplayer.core.mpv.PlaybackSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 「待播放请求」信箱。
 *
 * 播放源不通过导航参数传递：导航参数会被序列化进 back stack，进程恢复时可能反序列化
 * 出一个过期的 uri；而且播放源本来就要落到 [com.zhiwei.xplayer.core.mpv.MpvPlayer]
 * 上，存两份必然不同步。这里放一个信箱，页面负责投递、播放页负责取走。
 */
object PendingPlayback {

    private val _request = MutableStateFlow<PlaybackSource?>(null)
    val request: StateFlow<PlaybackSource?> = _request.asStateFlow()

    fun request(source: PlaybackSource) {
        _request.value = source
    }

    fun consume(): PlaybackSource? {
        val current = _request.value
        _request.value = null
        return current
    }

    fun clear() {
        _request.value = null
    }
}
