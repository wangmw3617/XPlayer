package com.zhiwei.xplayer.core.mpv

import dev.jdtech.mpv.MPVLib

/**
 * mpv 属性观察格式。
 *
 * 直接复用 [MPVLib] 的 `@Format` 常量，避免在这里再抄一份数字后与 native 侧脱节。
 */
object MpvFormat {
    const val NONE = MPVLib.MpvFormat.MPV_FORMAT_NONE
    const val STRING = MPVLib.MpvFormat.MPV_FORMAT_STRING
    const val FLAG = MPVLib.MpvFormat.MPV_FORMAT_FLAG
    const val INT64 = MPVLib.MpvFormat.MPV_FORMAT_INT64
    const val DOUBLE = MPVLib.MpvFormat.MPV_FORMAT_DOUBLE
}

/** mpv 事件 id（本应用实际关心的那几个） */
object MpvEvent {
    const val SHUTDOWN = MPVLib.MpvEvent.MPV_EVENT_SHUTDOWN
    const val LOG_MESSAGE = MPVLib.MpvEvent.MPV_EVENT_LOG_MESSAGE
    const val START_FILE = MPVLib.MpvEvent.MPV_EVENT_START_FILE
    const val END_FILE = MPVLib.MpvEvent.MPV_EVENT_END_FILE
    const val FILE_LOADED = MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED
    const val VIDEO_RECONFIG = MPVLib.MpvEvent.MPV_EVENT_VIDEO_RECONFIG
    const val AUDIO_RECONFIG = MPVLib.MpvEvent.MPV_EVENT_AUDIO_RECONFIG
    const val SEEK = MPVLib.MpvEvent.MPV_EVENT_SEEK
    const val PLAYBACK_RESTART = MPVLib.MpvEvent.MPV_EVENT_PLAYBACK_RESTART
}

/**
 * 一条 mpv 轨道。
 *
 * mpv 的 `track-list` 是一个节点数组，没有「结构化读取」的 JNI 通道，
 * 只能按 `track-list/N/<field>` 逐字段读 —— 所以这里把所有需要的字段一次性抓出来，
 * 之后 UI 层就只用这个不可变快照。
 */
data class MpvTrack(
    val id: Int,
    val type: String,
    val title: String?,
    val language: String?,
    val codec: String?,
    val isDefault: Boolean,
    val isSelected: Boolean,
    val isExternal: Boolean,
) {
    val displayName: String
        get() {
            val t = title?.takeIf { it.isNotBlank() }
            val l = language?.takeIf { it.isNotBlank() }
            val head = when {
                t != null && l != null -> "$t ($l)"
                t != null -> t
                l != null -> l
                else -> "轨道 $id"
            }
            val c = codec?.takeIf { it.isNotBlank() }
            return if (c != null) "$head · $c" else head
        }

    val isAudio: Boolean get() = type == "audio"
    val isVideo: Boolean get() = type == "video"
    val isSubtitle: Boolean get() = type == "sub"
}

/**
 * 一次播放请求。
 *
 * [uri] 直接交给 mpv 的 `loadfile`：`content://` 由 FFmpeg 的 Android content
 * 协议处理，`http(s)://` / `rtsp://` / `rtmp://` / `udp://` 由 FFmpeg 的网络协议栈处理。
 */
data class PlaybackSource(
    val uri: String,
    val title: String,
    val isNetwork: Boolean = false,
    val extraSubtitles: List<String> = emptyList(),
    val startPositionMs: Long = 0L,
)

/**
 * 播放器状态快照。
 *
 * 由 [MpvPlayer] 在 native 回调线程上整体替换（[kotlinx.coroutines.flow.MutableStateFlow]
 * 的写入是原子的），UI 层只读。
 */
data class PlayerState(
    val source: PlaybackSource? = null,
    val mediaTitle: String = "",
    val idle: Boolean = true,
    val paused: Boolean = true,
    val buffering: Boolean = false,
    val seeking: Boolean = false,
    val eofReached: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val speed: Float = 1f,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    val videoAspect: Float = 0f,
    val videoRotate: Int = 0,
    val hwdecActive: String = "no",
    val muted: Boolean = false,
    val volume: Int = 100,
    val playlistPosition: Int = -1,
    val playlistCount: Int = 0,
    val loopMode: Int = 0,
    val tracks: List<MpvTrack> = emptyList(),
    val audioTrackId: Int = -1,
    val videoTrackId: Int = -1,
    val subtitleTrackId: Int = -1,
    val subtitleDelay: Double = 0.0,
    val audioDelay: Double = 0.0,
    val error: String? = null,
) {
    val audioTracks: List<MpvTrack> get() = tracks.filter { it.isAudio }
    val videoTracks: List<MpvTrack> get() = tracks.filter { it.isVideo }
    val subtitleTracks: List<MpvTrack> get() = tracks.filter { it.isSubtitle }
    val hasVideo: Boolean get() = videoWidth > 0 && videoHeight > 0
    val hasMedia: Boolean get() = !idle && durationMs > 0L
}
