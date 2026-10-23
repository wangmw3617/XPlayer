package com.zhiwei.xplayer.core.playback

import android.os.SystemClock
import com.zhiwei.xplayer.core.data.SettingsRepository
import com.zhiwei.xplayer.core.mpv.MpvPlayer
import com.zhiwei.xplayer.core.mpv.PlaybackSource
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

data class PlaybackQueueState(
    val items: List<PlaybackSource> = emptyList(),
    val index: Int = -1,
    val loopMode: Int = 0,
) {
    val current: PlaybackSource? get() = items.getOrNull(index)
    val hasPrevious: Boolean
        get() = items.isNotEmpty() && (index > 0 || loopMode == 1)
    val hasNext: Boolean
        get() = items.isNotEmpty() && (index in 0 until items.lastIndex || loopMode == 1)
}

internal object PlaybackQueueNavigator {
    fun next(state: PlaybackQueueState): Int? = when {
        state.items.isEmpty() -> null
        state.index < state.items.lastIndex -> state.index + 1
        state.loopMode == 1 -> 0
        else -> null
    }

    fun previous(state: PlaybackQueueState): Int? = when {
        state.items.isEmpty() -> null
        state.index > 0 -> state.index - 1
        state.loopMode == 1 -> state.items.lastIndex
        state.index == 0 -> 0
        else -> null
    }

    fun afterNaturalEnd(state: PlaybackQueueState): Int? =
        if (state.loopMode == 2) null else next(state)
}

/**
 * 应用级播放队列。
 *
 * 不依赖 mpv 内部 playlist：每个文件重新走 [MpvPlayer.play]，这样 WebDAV 的认证头、
 * 按文件属性复位和播放历史都会按同一套规则处理，不会把上一个源的请求头带给下一个源。
 */
@Singleton
class PlaybackQueueController @Inject constructor(
    private val player: MpvPlayer,
    private val recorder: PlaybackRecorder,
    private val settingsRepository: SettingsRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(
        PlaybackQueueState(loopMode = settingsRepository.current.loopMode),
    )
    val state: StateFlow<PlaybackQueueState> = _state.asStateFlow()

    @Volatile
    private var fileLoaded = false

    @Volatile
    private var lastStartAt = 0L

    /**
     * 用户主动停止时 mpv 同样会发 END_FILE。
     * 这个标记只吃掉紧随其后的那一次结束事件，避免 stop 被误判成自然播完并自动下一首。
     */
    @Volatile
    private var suppressNextEnd = false

    init {
        scope.launch {
            player.fileLoaded.collect { fileLoaded = true }
        }
        scope.launch {
            player.ended.collect { advanceAfterEnd() }
        }
    }

    @Synchronized
    fun play(
        source: PlaybackSource,
        queue: List<PlaybackSource> = listOf(source),
        index: Int = queue.indexOfFirst { it.uri == source.uri && it.title == source.title },
        startMs: Long = source.startPositionMs,
    ) {
        val items = queue.ifEmpty { listOf(source) }.toMutableList()
        val safeIndex = when {
            index in items.indices -> index
            else -> items.indexOfFirst { it.uri == source.uri }.takeIf { it >= 0 } ?: 0
        }
        items[safeIndex] = source
        _state.value = PlaybackQueueState(
            items = items.toList(),
            index = safeIndex,
            loopMode = settingsRepository.current.loopMode,
        )
        player.setLoopMode(settingsRepository.current.loopMode)
        startAt(safeIndex, startMs)
    }

    @Synchronized
    fun next(): Boolean {
        val queue = _state.value
        val nextIndex = PlaybackQueueNavigator.next(queue) ?: return false
        startAt(nextIndex, 0L)
        return true
    }

    @Synchronized
    fun previous(): Boolean {
        val queue = _state.value
        val previousIndex = PlaybackQueueNavigator.previous(queue) ?: return false
        startAt(previousIndex, 0L)
        return true
    }

    @Synchronized
    fun stop() {
        suppressNextEnd = true
        fileLoaded = false
        recorder.flush()
        player.stop()
    }

    fun setLoopMode(mode: Int) {
        val normalized = mode.coerceIn(0, 2)
        player.setLoopMode(normalized)
        _state.value = _state.value.copy(loopMode = normalized)
        scope.launch {
            settingsRepository.update { it.copy(loopMode = normalized) }
        }
    }

    @Synchronized
    private fun advanceAfterEnd() {
        if (suppressNextEnd) {
            suppressNextEnd = false
            return
        }
        if (!fileLoaded) return
        // 手动切文件时 mpv 也会发 END_FILE。刚发起 loadfile 的短窗口内忽略它，
        // 防止队列被旧文件的结束事件多推进一格。
        if (SystemClock.elapsedRealtime() - lastStartAt < END_GUARD_MS) return
        fileLoaded = false
        val queue = _state.value
        val nextIndex = PlaybackQueueNavigator.afterNaturalEnd(queue) ?: return
        startAt(nextIndex, 0L)
    }

    private fun startAt(index: Int, startMs: Long) {
        val queue = _state.value
        val source = queue.items.getOrNull(index) ?: return
        _state.value = queue.copy(index = index)
        suppressNextEnd = false
        fileLoaded = false
        lastStartAt = SystemClock.elapsedRealtime()
        recorder.beginPlayback(source, startMs)
        player.play(source, startMs)
        // play() 会确保 mpv 初始化；再设置一次，保证首次播放也能应用列表循环状态。
        player.setLoopMode(queue.loopMode)
    }

    private companion object {
        const val END_GUARD_MS = 750L
    }
}
