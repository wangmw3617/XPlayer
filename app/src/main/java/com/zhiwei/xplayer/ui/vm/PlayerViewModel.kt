package com.zhiwei.xplayer.ui.vm

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zhiwei.xplayer.R
import com.zhiwei.xplayer.core.data.AppSettings
import com.zhiwei.xplayer.core.data.LibraryRepository
import com.zhiwei.xplayer.core.data.SettingsRepository
import com.zhiwei.xplayer.core.mpv.MpvPlayer
import com.zhiwei.xplayer.core.mpv.PlaybackSource
import com.zhiwei.xplayer.core.mpv.PlayerState
import com.zhiwei.xplayer.core.playback.PlaybackService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 播放页的 ViewModel。
 *
 * 传输控制（暂停/跳转/倍速…）不做二次包装，UI 直接调 [MpvPlayer] —— 那些操作
 * 都是「一次 JNI 调用 + 由属性回调解出状态」，再包一层只会多出十几个纯转发方法。
 * ViewModel 只负责三件需要业务判断的事：
 *
 * 1. 播放前查历史决定是否续播；
 * 2. 播放中定期把进度写回历史；
 * 3. 按设置决定是否拉起前台服务。
 */
@HiltViewModel
class PlayerViewModel @Inject constructor(
    val player: MpvPlayer,
    private val libraryRepository: LibraryRepository,
    private val settingsRepository: SettingsRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    val state: StateFlow<PlayerState> = player.state
    val log: SharedFlow<String> = player.log
    val messages: SharedFlow<String> = player.messages
    val settings: StateFlow<AppSettings> = settingsRepository.settings

    private var lastSavedAt = 0L

    /** 最近一次有意义的播放状态，用于 onCleared 时补写进度 */
    private var lastSnapshot: PlayerState? = null

    init {
        // 进度写回不能跟着 time-pos 每一帧写库，这里按时间节流到 5 秒一次；
        // 暂停、播放结束、ViewModel 销毁时再各补一次，保证退出时进度是新的。
        viewModelScope.launch {
            player.state.collect { snapshot ->
                if (snapshot.source == null || snapshot.idle) return@collect
                lastSnapshot = snapshot
                val now = System.currentTimeMillis()
                if (now - lastSavedAt >= SAVE_INTERVAL_MS) {
                    lastSavedAt = now
                    persist(snapshot)
                }
            }
        }
        viewModelScope.launch {
            player.ended.collect { lastSnapshot?.let { persist(it) } }
        }
    }

    fun play(source: PlaybackSource) {
        viewModelScope.launch {
            val resumeFrom = if (settingsRepository.current.rememberPosition) {
                val saved = libraryRepository.findHistory(source.uri)?.positionMs ?: 0L
                // 距离结尾 15 秒内就别续播了，直接从头开始更像「重新看一遍」
                if (saved > RESUME_MIN_MS) saved else 0L
            } else {
                0L
            }
            lastSavedAt = System.currentTimeMillis()
            player.play(source, resumeFrom)
            libraryRepository.recordPlayback(
                uri = source.uri,
                title = source.title,
                isNetwork = source.isNetwork,
                positionMs = resumeFrom,
                durationMs = 0L,
            )
            if (settingsRepository.current.backgroundPlayback) {
                PlaybackService.start(context)
            }
        }
    }

    fun togglePlayPause() {
        val snapshot = player.state.value
        player.setPaused(!snapshot.paused)
        // 暂停瞬间补写一次，避免「暂停后直接杀进程」丢掉最后几秒
        if (!snapshot.paused) {
            lastSnapshot?.let { persist(it) }
            lastSavedAt = System.currentTimeMillis()
        }
    }

    fun screenshot() {
        val path = player.screenshot()
        player.notify(
            if (path != null) {
                context.getString(R.string.player_screenshot_saved, path)
            } else {
                context.getString(R.string.player_screenshot_failed)
            },
        )
    }

    fun stopPlayback() {
        lastSnapshot?.let { persist(it) }
        player.stop()
        PlaybackService.stop(context)
    }

    override fun onCleared() {
        lastSnapshot?.let { persist(it) }
        super.onCleared()
    }

    private fun persist(snapshot: PlayerState) {
        val source = snapshot.source ?: return
        if (snapshot.durationMs <= 0L) return
        viewModelScope.launch {
            libraryRepository.recordPlayback(
                uri = source.uri,
                title = source.title.ifBlank { snapshot.mediaTitle },
                isNetwork = source.isNetwork,
                positionMs = snapshot.positionMs,
                durationMs = snapshot.durationMs,
            )
        }
    }

    companion object {
        private const val SAVE_INTERVAL_MS = 5_000L

        /** 小于这个进度就不续播了（刚开头没必要提示「继续播放」） */
        private const val RESUME_MIN_MS = 5_000L
    }
}
