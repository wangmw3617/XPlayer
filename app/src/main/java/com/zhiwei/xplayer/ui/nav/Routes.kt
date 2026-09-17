package com.zhiwei.xplayer.ui.nav

import kotlinx.serialization.Serializable

/**
 * 导航路由。
 *
 * 全部做成无参数的 `@Serializable object`，需要「播哪个媒体」时不走导航参数，
 * 而是先写进 [com.zhiwei.xplayer.core.mpv.MpvPlayer] 再跳转 ——
 * 播放器本来就持有当前播放源，把 uri 塞进路由参数等于同一份状态存两处，
 * 而且会引入 `toRoute<T>()` 的反序列化路径。
 */
@Serializable
object HomeRoute

@Serializable
object LibraryRoute

@Serializable
object FolderRoute

@Serializable
object SettingsRoute

@Serializable
object StreamRoute

@Serializable
object HistoryRoute

@Serializable
object LogRoute

@Serializable
object PlayerRoute
