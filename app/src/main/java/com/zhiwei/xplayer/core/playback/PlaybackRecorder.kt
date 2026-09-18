package com.zhiwei.xplayer.core.playback

import android.os.SystemClock
import com.zhiwei.xplayer.core.data.LibraryRepository
import com.zhiwei.xplayer.core.mpv.MpvPlayer
import com.zhiwei.xplayer.core.mpv.PlayerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 播放进度记录器。
 *
 * ## 为什么是应用级单例，而不是放在 PlayerViewModel 里
 *
 * 两个原因，都是把逻辑放在 ViewModel 里时踩到的：
 *
 * 1. **ViewModel 活得不够久**。它绑在播放页的 NavBackStackEntry 上，用户一退出
 *    播放页就被销毁，而此时后台播放还在继续（`vo` 切到 null，音频不断）——
 *    进度从此再也不会写库，用户下次回来只能从头看。
 * 2. **`onCleared()` 里写库是空操作**。`ViewModel.clear()` 会先把 `mBagOfTags`
 *    里的 Closeable 关掉（`viewModelScope` 就在其中），之后才调用 `onCleared()`，
 *    所以那里 `viewModelScope.launch` 出去的协程一创建就被取消，body 根本不会跑。
 *
 * 放到应用级单例上，用自己的 scope 写库，上述两个问题都不存在。
 *
 * ## 节流
 *
 * `time-pos` 是每帧都在变的属性，直接跟着写库会每秒写几十次。这里节流到 5 秒一次，
 * 另外在暂停 / 停止 / 离开播放页时由 UI 主动调 [flush] 补一次，保证这些关键时刻
 * 的进度是新的。
 */
@Singleton
class PlaybackRecorder @Inject constructor(
    private val player: MpvPlayer,
    private val libraryRepository: LibraryRepository,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 用 elapsedRealtime 而不是 currentTimeMillis：后者会被系统时间调整影响 */
    @Volatile
    private var lastSavedAt = 0L

    @Volatile
    private var lastSnapshot: PlayerState? = null

    init {
        scope.launch {
            player.state.collect { snapshot ->
                if (snapshot.source == null || snapshot.idle) return@collect
                lastSnapshot = snapshot
                val now = SystemClock.elapsedRealtime()
                if (now - lastSavedAt >= SAVE_INTERVAL_MS) {
                    lastSavedAt = now
                    persist(snapshot)
                }
            }
        }
        scope.launch {
            // 一个文件播完：把最终进度落库
            player.ended.collect { lastSnapshot?.let { persist(it) } }
        }
    }

    /**
     * 立刻把当前进度落库。
     *
     * 由 UI 在「暂停 / 停止 / 退出播放页 / 切后台」这些时刻调用。
     * 不依赖调用方的生命周期，所以 ViewModel 被销毁也照样能写完。
     */
    fun flush() {
        lastSnapshot?.let { persist(it) }
        lastSavedAt = SystemClock.elapsedRealtime()
    }

    /** 开始播新文件时调一下，避免上一轮的节流窗口把这次的首次落库挡掉 */
    fun onPlaybackStarted() {
        lastSnapshot = null
        lastSavedAt = 0L
    }

    private fun persist(snapshot: PlayerState) {
        val source = snapshot.source ?: return
        // 时长还没解出来时写进去的是 0，会把「继续观看」的进度条抹掉，不如不写
        if (snapshot.durationMs <= 0L) return
        val title = source.title.ifBlank { snapshot.mediaTitle }
        scope.launch {
            libraryRepository.recordPlayback(
                uri = source.uri,
                title = title,
                isNetwork = source.isNetwork,
                positionMs = snapshot.positionMs,
                durationMs = snapshot.durationMs,
            )
        }
    }

    companion object {
        private const val SAVE_INTERVAL_MS = 5_000L
    }
}
