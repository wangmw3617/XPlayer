package com.zhiwei.xplayer

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * 应用入口。
 *
 * 这里刻意不初始化 libmpv：内核由 [com.zhiwei.xplayer.core.mpv.MpvPlayer] 在第一次
 * 真正要用时惰性创建。冷启动阶段就去建 mpv 实例会拖慢启动，而用户完全可能只是
 * 打开应用看一眼设置。
 */
@HiltAndroidApp
class XPlayerApp : Application()
