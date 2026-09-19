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
import com.zhiwei.xplayer.core.playback.PlaybackRecorder
import com.zhiwei.xplayer.core.playback.PlaybackService
import com.zhiwei.xplayer.core.webdav.WebDavRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.Credentials
import javax.inject.Inject

/**
 * 播放页的 ViewModel。
 *
 * 传输控制（暂停 / 跳转 / 倍速…）不做二次包装，UI 直接调 [MpvPlayer] —— 那些操作
 * 都是「一次 JNI 调用 + 由属性回调解出状态」，再包一层只会多出十几个纯转发方法。
 * ViewModel 只负责两件需要业务判断的事：
 *
 * 1. 播放前查历史决定是否续播；
 * 2. 按设置决定是否拉起前台服务。
 *
 * **进度写回不在这里**，在应用级的 [PlaybackRecorder] 上 —— ViewModel 绑在播放页
 * 的 NavBackStackEntry 上，用户退出播放页就被销毁，而后台播放还在继续，
 * 放在这里会导致退出之后的进度全部丢失。
 */
@HiltViewModel
class PlayerViewModel @Inject constructor(
    val player: MpvPlayer,
    private val libraryRepository: LibraryRepository,
    private val settingsRepository: SettingsRepository,
    private val webDavRepository: WebDavRepository,
    private val recorder: PlaybackRecorder,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    val state: StateFlow<PlayerState> = player.state
    val log: SharedFlow<String> = player.log
    val messages: SharedFlow<String> = player.messages
    val settings: StateFlow<AppSettings> = settingsRepository.settings

    fun play(source: PlaybackSource) {
        viewModelScope.launch {
            // WebDAV 的 uri 不带凭据（避免密码落盘/上屏），所以从历史重播时
            // 这里要把对应账号的 Authorization 头补回去，否则必然 401。
            val effective = if (source.httpHeaders.isEmpty() && source.isNetwork) {
                attachWebDavAuth(source)
            } else {
                source
            }
            val resumeFrom = if (settingsRepository.current.rememberPosition) {
                val saved = libraryRepository.findHistory(effective.uri)?.positionMs ?: 0L
                // 刚开头几秒就不续播了，否则「继续观看」会变成「重看开头」
                if (saved > RESUME_MIN_MS) saved else 0L
            } else {
                0L
            }
            recorder.onPlaybackStarted()
            player.play(effective, resumeFrom)
            // 先落一条「开始播放」的记录，让首页的「继续观看」立刻能看到这个媒体
            libraryRepository.recordPlayback(
                uri = effective.uri,
                title = effective.title,
                isNetwork = effective.isNetwork,
                positionMs = resumeFrom,
                durationMs = 0L,
            )
            if (settingsRepository.current.backgroundPlayback) {
                PlaybackService.start(context)
            }
        }
    }

    /**
     * 给一个网络源补上 WebDAV 的 Basic 认证头。
     *
     * 匹配规则是「哪个账号的 base URL 是这个 uri 的前缀」，命中第一个就够 ——
     * 同一个服务器配置了多个账号是极少见的场景，真发生时用先添加的那个。
     * 找不到匹配账号（普通 http 流、账号已被删）就原样返回。
     */
    private suspend fun attachWebDavAuth(source: PlaybackSource): PlaybackSource {
        val account = webDavRepository.accounts.first().firstOrNull { it.owns(source.uri) }
            ?: return source
        if (account.username.isBlank()) return source
        return source.copy(
            httpHeaders = listOf(
                "Authorization: ${Credentials.basic(account.username, account.password)}",
            ),
        )
    }

    fun togglePlayPause() {
        val wasPlaying = !player.state.value.paused
        player.setPaused(!wasPlaying)
        // 暂停瞬间补写一次，避免「暂停后直接杀进程」丢掉最后几秒
        if (wasPlaying) recorder.flush()
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
        recorder.flush()
        player.stop()
        PlaybackService.stop(context)
    }

    override fun onCleared() {
        // 离开播放页也补写一次。这里能生效是因为 flush 内部用的是 PlaybackRecorder
        // 自己的应用级 scope，而不是已经随 ViewModel 一起被取消的 viewModelScope。
        recorder.flush()
        super.onCleared()
    }

    companion object {
        /** 小于这个进度就不续播了 */
        private const val RESUME_MIN_MS = 5_000L
    }
}
