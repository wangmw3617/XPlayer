package com.zhiwei.xplayer

import android.app.Application
import com.zhiwei.xplayer.core.playback.PlaybackRecorder
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * 应用入口。
 *
 * 这里刻意**不**初始化 libmpv：内核由 [com.zhiwei.xplayer.core.mpv.MpvPlayer] 在
 * 第一次真正要用时惰性创建。冷启动阶段就去建 mpv 实例会拖慢启动，而用户完全可能
 * 只是打开应用看一眼设置。
 *
 * 但 [PlaybackRecorder] 要在这里注入 —— 它是进度记录器，必须与进程同寿：
 * 用户退出播放页后后台播放仍在继续，进度得接着写。
 */
@HiltAndroidApp
class XPlayerApp : Application() {

    @Inject
    lateinit var playbackRecorder: PlaybackRecorder
}
