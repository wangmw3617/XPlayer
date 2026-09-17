package com.zhiwei.xplayer.core.mpv

import android.content.Context
import android.os.Environment
import android.util.Log
import android.view.Surface
import com.zhiwei.xplayer.core.data.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.jdtech.mpv.MPVLib
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.log2
import kotlin.math.roundToInt

/**
 * libmpv 播放内核封装。
 *
 * ## 为什么是「单例 + 自己管生命周期」
 *
 * 播放必须能跨页面、跨 Activity 存活（后台播放、画中画、旋转），所以内核不能绑在
 * 某个 Composable 或 View 上。这里做成应用级单例，由 [ensureInitialized] 惰性创建、
 * [release] 显式销毁：
 *
 * ```
 *   MPVLib.create(ctx) -> 设选项 -> MPVLib.init() -> 设硬编码选项 -> 注册观察者
 * ```
 *
 * 顺序不能乱：`setOptionString` 只在 `create` 与 `init` 之间生效（`init` 之后设置
 * 会写进属性而不是初始选项），观察者也必须在 `init` 之后注册才收得到事件。
 *
 * ## 线程模型
 *
 * `MPVLib` 的所有回调都来自 mpv 自己的线程（事件线程 / 日志线程），而
 * [MutableStateFlow] 的写入是原子的、[MutableStateFlow.update] 是 CAS，
 * 因此这里不做线程切换，UI 侧通过 `collectAsStateWithLifecycle` 拿值即可。
 *
 * ## 视频输出与 surface
 *
 * `vo` 在「有 surface」和「没 surface」之间切换（`gpu-next` <-> `null`），而不是
 * 在没 surface 时停掉播放 —— 这样锁屏/切后台时音频继续，回到前台画面立刻回来，
 * 就是 mpv-android 的做法。
 */
@Singleton
class MpvPlayer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
) : MPVLib.EventObserver, MPVLib.LogObserver {

    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    /** libmpv 的实时日志（环形缓冲在 UI 侧再截断） */
    private val _log = MutableSharedFlow<String>(extraBufferCapacity = 512)
    val log: SharedFlow<String> = _log.asSharedFlow()

    /** 一次性提示（截图完成、加载失败等） */
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    /** 一个文件播放结束（自然结束或被切走） */
    private val _ended = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    val ended: SharedFlow<Unit> = _ended.asSharedFlow()

    @Volatile
    private var initialized = false

    @Volatile
    private var surfaceAttached = false

    /** 日志里最近一条 ERROR，用于在加载失败时给出可读原因 */
    private val errorLines = ArrayDeque<String>(8)

    @Volatile
    private var pendingSubtitles: List<String> = emptyList()

    private val voName: String
        get() = if (settingsRepository.current.gpuNext) VO_GPU_NEXT else VO_GPU

    // ============================================================ 生命周期 ====

    /**
     * 幂等初始化。第一次调用时创建并启动 mpv 实例；之后是空操作。
     */
    @Synchronized
    fun ensureInitialized() {
        if (initialized) return
        runCatching {
            MPVLib.create(context)
            applyInitialOptions()
            MPVLib.init()
            // init 之后设置的选项用户配置无法覆盖，这里只放「必须这样」的几条
            MPVLib.setOptionString("force-window", "no")
            MPVLib.setOptionString("idle", "yes")
            MPVLib.setOptionString("save-position-on-quit", "no")
            MPVLib.addObserver(this)
            MPVLib.addLogObserver(this)
            observeProperties()
            initialized = true
            Log.i(TAG, "libmpv 已初始化")
        }.onFailure {
            Log.e(TAG, "libmpv 初始化失败", it)
            _state.update { s -> s.copy(error = it.message ?: "libmpv 初始化失败") }
        }
    }

    /**
     * 销毁 mpv 实例。
     *
     * 只在「确实没有界面在用」时调用（前台服务被显式停止）。之后再播放会重新
     * 走一遍 [ensureInitialized] —— mpv-android 的 Activity 每次重建都这么做，
     * create/destroy 循环是受支持的。
     */
    @Synchronized
    fun release() {
        if (!initialized) return
        if (surfaceAttached) detachSurface()
        runCatching { MPVLib.removeObserver(this) }
        runCatching { MPVLib.removeLogObserver(this) }
        runCatching { MPVLib.destroy() }
        initialized = false
        _state.value = PlayerState()
        Log.i(TAG, "libmpv 已销毁")
    }

    private fun applyInitialOptions() {
        val settings = settingsRepository.current

        // 不读用户配置文件：本应用的设置项才是唯一来源，否则 mpv.conf 会悄悄
        // 覆盖用户在界面上的选择，排查起来非常费劲。
        MPVLib.setOptionString("config", "no")
        MPVLib.setOptionString("terminal", "no")
        MPVLib.setOptionString("input-default-bindings", "no")
        MPVLib.setOptionString("input-vo-keyboard", "no")
        MPVLib.setOptionString("osc", "no")
        MPVLib.setOptionString("osd-level", "0")

        // 视频输出：Android 上必须显式指定 gpu-context=android 并用 GLES，
        // 否则 mpv 会去找 X11/Wayland 的上下文。
        MPVLib.setOptionString("vo", voName)
        MPVLib.setOptionString("gpu-context", "android")
        MPVLib.setOptionString("opengl-es", "yes")

        // 硬解：mediacodec 优先，mediacodec-copy 兜底（部分格式只能拷贝回内存）
        MPVLib.setOptionString("hwdec", if (settings.hardwareDecoding) HWDEC else "no")
        MPVLib.setOptionString("hwdec-codecs", HWDEC_CODECS)

        // 音频：audiotrack 是 Android 原生输出，opensles 在老设备上更稳
        MPVLib.setOptionString("ao", "audiotrack,opensles")
        MPVLib.setOptionString("audio-set-media-role", "yes")

        // 字幕：fuzzy 会自动加载同名字幕（含 .zh.srt 之类的语言后缀）
        MPVLib.setOptionString("sub-auto", if (settings.autoLoadSubtitles) "fuzzy" else "no")
        MPVLib.setOptionString("audio-file-auto", if (settings.autoLoadSubtitles) "fuzzy" else "no")
        MPVLib.setOptionString("sub-file-paths", "subs:subtitles:sub:Subs:Subtitles")

        // 网络
        MPVLib.setOptionString("tls-verify", "yes")

        // 移动端默认的 demuxer 缓存过大（动辄几百 MB），按内存档位收一收
        MPVLib.setOptionString("demuxer-max-bytes", DEMUXER_CACHE_BYTES)
        MPVLib.setOptionString("demuxer-max-back-bytes", DEMUXER_CACHE_BYTES)
        MPVLib.setOptionString("cache", "yes")

        // 播放到结尾就结束，不要停在最后一帧（结束行为由应用自己决定）
        MPVLib.setOptionString("keep-open", "no")

        MPVLib.setOptionString("screenshot-format", "jpg")
        MPVLib.setOptionString("screenshot-directory", screenshotDir().absolutePath)

        if (settings.defaultAudioLanguage.isNotBlank()) {
            MPVLib.setOptionString("alang", settings.defaultAudioLanguage)
        }
        if (settings.defaultSubtitleLanguage.isNotBlank()) {
            MPVLib.setOptionString("slang", settings.defaultSubtitleLanguage)
        }
    }

    /**
     * 把「可以运行时改」的设置重新写进 mpv。
     *
     * 设置页改完立刻调用，用户不必重启应用。只处理 mpv 允许运行时修改的属性
     * （hwdec / vo / sub-auto / alang / slang），其余（缓存大小等）要下次启动才生效。
     */
    fun applyRuntimeSettings() {
        if (!initialized) return
        val settings = settingsRepository.current
        runCatching {
            MPVLib.setPropertyString("hwdec", if (settings.hardwareDecoding) HWDEC else "no")
            MPVLib.setPropertyString("vo", if (surfaceAttached) voName else "null")
            MPVLib.setPropertyString("sub-auto", if (settings.autoLoadSubtitles) "fuzzy" else "no")
            MPVLib.setPropertyString("audio-file-auto", if (settings.autoLoadSubtitles) "fuzzy" else "no")
            MPVLib.setPropertyString("alang", settings.defaultAudioLanguage)
            MPVLib.setPropertyString("slang", settings.defaultSubtitleLanguage)
        }.onFailure { Log.w(TAG, "运行时设置应用失败", it) }
    }

    // ============================================================== surface ====

    fun attachSurface(surface: Surface) {
        ensureInitialized()
        if (!initialized) return
        runCatching {
            MPVLib.attachSurface(surface)
            // 先挂 surface 再开 vo：mpv 打开 vo 时会去取当前已注册的 Android surface
            MPVLib.setPropertyString("vo", voName)
            // force-window=yes 让 mpv 在没有视频的纯音频场景也把窗口（surface）建起来，
            // 这样封面图与 OSD 才有地方画
            MPVLib.setOptionString("force-window", "yes")
            surfaceAttached = true
            refreshVideoParams()
        }.onFailure { Log.e(TAG, "挂载 surface 失败", it) }
    }

    fun detachSurface() {
        if (!initialized) return
        runCatching {
            MPVLib.setPropertyString("vo", "null")
            MPVLib.setOptionString("force-window", "no")
            MPVLib.detachSurface()
        }.onFailure { Log.w(TAG, "卸载 surface 失败", it) }
        surfaceAttached = false
    }

    /** surface 尺寸变化时同步给 mpv，影响 OSD 缩放与 `--video-aspect-override` 的计算 */
    fun setSurfaceSize(width: Int, height: Int) {
        if (!initialized || width <= 0 || height <= 0) return
        runCatching { MPVLib.setPropertyString("android-surface-size", "${width}x$height") }
    }

    // =============================================================== 播放 ====

    /**
     * 播放一个媒体。
     *
     * [startMs] 通过 `loadfile` 的第四个参数（一次性选项）传进去，而不是先
     * `setOptionString("start", ...)` —— 后者会残留在实例上，下一个文件也会被
     * 莫名其妙地 seek。
     */
    fun play(source: PlaybackSource, startMs: Long = source.startPositionMs) {
        ensureInitialized()
        if (!initialized) return
        errorLines.clear()
        pendingSubtitles = source.extraSubtitles
        _state.update {
            it.copy(
                source = source,
                error = null,
                eofReached = false,
                positionMs = 0L,
                durationMs = 0L,
                tracks = emptyList(),
                mediaTitle = source.title,
            )
        }
        val args = ArrayList<String>(5)
        args += "loadfile"
        args += source.uri
        args += "replace"
        if (startMs > 0L) {
            // loadfile <url> <flags> <index> <options>
            args += "-1"
            args += "start=${startMs / 1000.0}"
        }
        runCatching { MPVLib.command(args.toTypedArray()) }
            .onFailure { Log.e(TAG, "loadfile 失败", it) }
    }

    /** 追加到 mpv 内部播放列表 */
    fun enqueue(sources: List<PlaybackSource>) {
        ensureInitialized()
        if (!initialized) return
        sources.forEach { source ->
            runCatching { MPVLib.command(arrayOf("loadfile", source.uri, "append-play")) }
        }
    }

    fun stop() {
        if (!initialized) return
        runCatching { MPVLib.command(arrayOf("stop")) }
        _state.update { it.copy(idle = true, paused = true, positionMs = 0L) }
    }

    fun togglePause() {
        if (!initialized) return
        runCatching { MPVLib.command(arrayOf("cycle", "pause")) }
    }

    fun setPaused(paused: Boolean) {
        if (!initialized) return
        runCatching { MPVLib.setPropertyBoolean("pause", paused) }
    }

    /** 绝对跳转（毫秒） */
    fun seekTo(positionMs: Long) {
        if (!initialized) return
        val seconds = (positionMs.coerceAtLeast(0L) / 1000.0)
        runCatching { MPVLib.command(arrayOf("seek", seconds.toString(), "absolute")) }
    }

    /** 相对跳转（毫秒，可负） */
    fun seekBy(deltaMs: Long) {
        if (!initialized) return
        runCatching { MPVLib.command(arrayOf("seek", (deltaMs / 1000.0).toString(), "relative")) }
    }

    fun next() {
        if (!initialized) return
        runCatching { MPVLib.command(arrayOf("playlist-next", "weak")) }
    }

    fun previous() {
        if (!initialized) return
        runCatching { MPVLib.command(arrayOf("playlist-prev", "weak")) }
    }

    // ============================================================ 播放参数 ====

    fun setSpeed(speed: Float) {
        if (!initialized) return
        runCatching { MPVLib.setPropertyDouble("speed", speed.toDouble()) }
    }

    fun setVolume(volume: Int) {
        if (!initialized) return
        val clamped = volume.coerceIn(0, MAX_VOLUME)
        runCatching { MPVLib.setPropertyDouble("volume", clamped.toDouble()) }
    }

    fun toggleMute() {
        if (!initialized) return
        runCatching { MPVLib.setPropertyBoolean("mute", !state.value.muted) }
    }

    fun setAudioTrack(id: Int) = setTrackProperty("aid", id)

    fun setVideoTrack(id: Int) = setTrackProperty("vid", id)

    fun setSubtitleTrack(id: Int) = setTrackProperty("sid", id)

    private fun setTrackProperty(property: String, id: Int) {
        if (!initialized) return
        runCatching {
            // mpv 用字符串 "no" 表示「关闭该轨」，不能用 -1
            if (id < 0) MPVLib.setPropertyString(property, "no")
            else MPVLib.setPropertyInt(property, id)
        }
    }

    fun addSubtitle(uri: String) {
        if (!initialized) return
        runCatching { MPVLib.command(arrayOf("sub-add", uri, "select")) }
    }

    fun setSubtitleDelay(seconds: Double) {
        if (!initialized) return
        runCatching { MPVLib.setPropertyDouble("sub-delay", seconds) }
    }

    fun setAudioDelay(seconds: Double) {
        if (!initialized) return
        runCatching { MPVLib.setPropertyDouble("audio-delay", seconds) }
    }

    /** [value] 传 `no` 表示恢复「跟随视频」 */
    fun setAspectOverride(value: String) {
        if (!initialized) return
        runCatching { MPVLib.setPropertyString("video-aspect-override", value) }
    }

    fun setRotation(degrees: Int) {
        if (!initialized) return
        runCatching { MPVLib.setPropertyInt("video-rotate", ((degrees % 360) + 360) % 360) }
    }

    /**
     * 双指缩放。
     *
     * mpv 的 `video-zoom` 是 **log2 尺度**：0 表示原始大小，1 表示放大两倍，
     * -1 表示缩小一半。所以这里要把用户直观的倍数取 log2 再写进去。
     */
    fun setVideoZoom(zoom: Float) {
        if (!initialized) return
        val value = if (zoom <= 1f) 0.0 else log2(zoom.toDouble())
        runCatching { MPVLib.setPropertyDouble("video-zoom", value) }
    }

    /** 0 不循环 / 1 列表循环 / 2 单曲循环 */
    fun setLoopMode(mode: Int) {
        if (!initialized) return
        runCatching {
            when (mode) {
                1 -> {
                    MPVLib.setPropertyString("loop-playlist", "inf")
                    MPVLib.setPropertyString("loop-file", "no")
                }
                2 -> {
                    MPVLib.setPropertyString("loop-playlist", "no")
                    MPVLib.setPropertyString("loop-file", "inf")
                }
                else -> {
                    MPVLib.setPropertyString("loop-playlist", "no")
                    MPVLib.setPropertyString("loop-file", "no")
                }
            }
        }
    }

    /**
     * 截图。
     *
     * 用 `screenshot-to-file` 显式指定路径，而不是 `screenshot` + `screenshot-directory`：
     * 后者无法得知最终文件名，也就没法把路径回显给用户。
     * 目录取应用专属外部目录，不需要任何存储权限。
     */
    fun screenshot(): String? {
        if (!initialized) return null
        val dir = screenshotDir()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(dir, "XPlayer_$stamp.jpg")
        return runCatching {
            MPVLib.command(arrayOf("screenshot-to-file", file.absolutePath))
            file.absolutePath
        }.getOrNull()
    }

    private fun screenshotDir(): File {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
            ?: File(context.filesDir, "screenshots")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    // ======================================================== 属性观察回调 ====

    override fun eventProperty(property: String) {
        when (property) {
            PROP_TRACK_LIST -> refreshTracks()
            PROP_AID -> _state.update { it.copy(audioTrackId = readTrackId(PROP_AID)) }
            PROP_VID -> _state.update { it.copy(videoTrackId = readTrackId(PROP_VID)) }
            PROP_SID -> _state.update { it.copy(subtitleTrackId = readTrackId(PROP_SID)) }
        }
    }

    override fun eventProperty(property: String, value: Long) {
        when (property) {
            PROP_VIDEO_W -> _state.update { it.copy(videoWidth = value.toInt()) }
            PROP_VIDEO_H -> _state.update { it.copy(videoHeight = value.toInt()) }
            PROP_VIDEO_ROTATE -> _state.update { it.copy(videoRotate = value.toInt()) }
            PROP_PLAYLIST_POS -> _state.update { it.copy(playlistPosition = value.toInt()) }
            PROP_PLAYLIST_COUNT -> _state.update { it.copy(playlistCount = value.toInt()) }
        }
    }

    override fun eventProperty(property: String, value: Double) {
        when (property) {
            PROP_TIME_POS -> _state.update { it.copy(positionMs = (value * 1000.0).toLong()) }
            PROP_DURATION -> _state.update { it.copy(durationMs = (value * 1000.0).toLong()) }
            PROP_SPEED -> _state.update { it.copy(speed = value.toFloat()) }
            PROP_VOLUME -> _state.update { it.copy(volume = value.roundToInt()) }
            PROP_ASPECT -> _state.update { it.copy(videoAspect = value.toFloat()) }
            PROP_SUB_DELAY -> _state.update { it.copy(subtitleDelay = value) }
            PROP_AUDIO_DELAY -> _state.update { it.copy(audioDelay = value) }
        }
    }

    override fun eventProperty(property: String, value: Boolean) {
        when (property) {
            PROP_PAUSE -> _state.update { it.copy(paused = value) }
            PROP_BUFFERING -> _state.update { it.copy(buffering = value) }
            PROP_SEEKING -> _state.update { it.copy(seeking = value) }
            PROP_EOF -> _state.update { it.copy(eofReached = value) }
            PROP_IDLE -> _state.update { it.copy(idle = value) }
            PROP_MUTE -> _state.update { it.copy(muted = value) }
        }
    }

    override fun eventProperty(property: String, value: String) {
        when (property) {
            PROP_MEDIA_TITLE -> _state.update { it.copy(mediaTitle = value) }
            PROP_HWDEC_CURRENT -> _state.update { it.copy(hwdecActive = value) }
            PROP_LOOP_FILE, PROP_LOOP_PLAYLIST -> _state.update { it.copy(loopMode = readLoopMode()) }
        }
    }

    override fun event(eventId: Int) {
        when (eventId) {
            MpvEvent.START_FILE -> {
                errorLines.clear()
                _state.update {
                    it.copy(error = null, eofReached = false, positionMs = 0L, durationMs = 0L)
                }
            }

            MpvEvent.FILE_LOADED -> {
                refreshTracks()
                refreshVideoParams()
                _state.update { it.copy(idle = false, error = null, eofReached = false) }
                // 外部字幕要等文件就绪后再追加：否则会与自动加载的同名字幕互相顶掉
                val pending = pendingSubtitles
                pendingSubtitles = emptyList()
                pending.forEach { path ->
                    runCatching { MPVLib.command(arrayOf("sub-add", path, "select")) }
                }
            }

            MpvEvent.END_FILE -> {
                _state.update { it.copy(paused = true) }
                val lastError = synchronized(errorLines) { errorLines.peekLast() }
                if (lastError != null && !_state.value.eofReached) {
                    _state.update { it.copy(error = lastError) }
                }
                _ended.tryEmit(Unit)
            }

            MpvEvent.SEEK -> _state.update { it.copy(seeking = true) }

            MpvEvent.PLAYBACK_RESTART -> _state.update { it.copy(seeking = false) }

            MpvEvent.VIDEO_RECONFIG, MpvEvent.AUDIO_RECONFIG -> {
                refreshVideoParams()
                refreshTracks()
            }

            MpvEvent.SHUTDOWN -> Log.i(TAG, "mpv 已关闭")
        }
    }

    // ================================================================ 日志 ====

    override fun logMessage(prefix: String, level: Int, text: String) {
        val line = "[${levelTag(level)}] ${prefix.trim()} ${text.trim()}".trim()
        _log.tryEmit(line)
        if (level <= MPVLib.MPV_LOG_LEVEL_ERROR) {
            synchronized(errorLines) {
                if (errorLines.size >= ERROR_LINE_LIMIT) errorLines.pollFirst()
                errorLines.addLast(text.trim())
            }
        }
    }

    private fun levelTag(level: Int): String = when {
        level <= MPVLib.MPV_LOG_LEVEL_FATAL -> "FATAL"
        level <= MPVLib.MPV_LOG_LEVEL_ERROR -> "ERROR"
        level <= MPVLib.MPV_LOG_LEVEL_WARN -> "WARN"
        level <= MPVLib.MPV_LOG_LEVEL_INFO -> "INFO"
        level <= MPVLib.MPV_LOG_LEVEL_V -> "VERBOSE"
        level <= MPVLib.MPV_LOG_LEVEL_DEBUG -> "DEBUG"
        else -> "TRACE"
    }

    // ============================================================== 内部 ====

    private fun observeProperties() {
        val doubles = arrayOf(
            PROP_TIME_POS, PROP_DURATION, PROP_SPEED, PROP_VOLUME,
            PROP_ASPECT, PROP_SUB_DELAY, PROP_AUDIO_DELAY,
        )
        val flags = arrayOf(
            PROP_PAUSE, PROP_BUFFERING, PROP_SEEKING, PROP_EOF, PROP_IDLE, PROP_MUTE,
        )
        val strings = arrayOf(PROP_MEDIA_TITLE, PROP_HWDEC_CURRENT, PROP_LOOP_FILE, PROP_LOOP_PLAYLIST)
        val ints = arrayOf(
            PROP_VIDEO_W, PROP_VIDEO_H, PROP_VIDEO_ROTATE, PROP_PLAYLIST_POS, PROP_PLAYLIST_COUNT,
        )
        val nodes = arrayOf(PROP_TRACK_LIST, PROP_AID, PROP_VID, PROP_SID)

        doubles.forEach { runCatching { MPVLib.observeProperty(it, MpvFormat.DOUBLE) } }
        flags.forEach { runCatching { MPVLib.observeProperty(it, MpvFormat.FLAG) } }
        strings.forEach { runCatching { MPVLib.observeProperty(it, MpvFormat.STRING) } }
        ints.forEach { runCatching { MPVLib.observeProperty(it, MpvFormat.INT64) } }
        nodes.forEach { runCatching { MPVLib.observeProperty(it, MpvFormat.NONE) } }
    }

    private fun refreshTracks() {
        if (!initialized) return
        val count = runCatching { MPVLib.getPropertyInt("track-list/count") }.getOrNull() ?: 0
        if (count <= 0) {
            _state.update { it.copy(tracks = emptyList()) }
            return
        }
        val list = ArrayList<MpvTrack>(count)
        for (index in 0 until count) {
            // 事件是异步的，属性随时可能消失，所以每个字段都要能容忍 null
            val type = runCatching { MPVLib.getPropertyString("track-list/$index/type") }.getOrNull()
                ?: continue
            val id = runCatching { MPVLib.getPropertyInt("track-list/$index/id") }.getOrNull()
                ?: continue
            list.add(
                MpvTrack(
                    id = id,
                    type = type,
                    title = runCatching { MPVLib.getPropertyString("track-list/$index/title") }.getOrNull(),
                    language = runCatching { MPVLib.getPropertyString("track-list/$index/lang") }.getOrNull(),
                    codec = runCatching { MPVLib.getPropertyString("track-list/$index/codec") }.getOrNull(),
                    isDefault = runCatching { MPVLib.getPropertyBoolean("track-list/$index/default") }
                        .getOrNull() == true,
                    isSelected = runCatching { MPVLib.getPropertyBoolean("track-list/$index/selected") }
                        .getOrNull() == true,
                    isExternal = runCatching { MPVLib.getPropertyBoolean("track-list/$index/external") }
                        .getOrNull() == true,
                ),
            )
        }
        _state.update { it.copy(tracks = list) }
    }

    private fun refreshVideoParams() {
        if (!initialized) return
        val width = runCatching { MPVLib.getPropertyInt("video-params/w") }.getOrNull() ?: 0
        val height = runCatching { MPVLib.getPropertyInt("video-params/h") }.getOrNull() ?: 0
        val rotate = runCatching { MPVLib.getPropertyInt("video-params/rotate") }.getOrNull() ?: 0
        _state.update {
            it.copy(
                videoWidth = width,
                videoHeight = height,
                videoRotate = rotate,
            )
        }
    }

    private fun readTrackId(property: String): Int {
        val raw = runCatching { MPVLib.getPropertyString(property) }.getOrNull() ?: return -1
        return raw.toIntOrNull() ?: -1
    }

    private fun readLoopMode(): Int {
        val file = runCatching { MPVLib.getPropertyString("loop-file") }.getOrNull()
        val list = runCatching { MPVLib.getPropertyString("loop-playlist") }.getOrNull()
        return when {
            file == "inf" -> 2
            list == "inf" -> 1
            else -> 0
        }
    }

    /** 给 UI 的即时提示通道 */
    fun notify(message: String) {
        _messages.tryEmit(message)
    }

    companion object {
        private const val TAG = "XPlayer/mpv"

        const val VO_GPU_NEXT = "gpu-next"
        const val VO_GPU = "gpu"

        /** mediacodec 优先，mediacodec-copy 兜底 */
        const val HWDEC = "mediacodec,mediacodec-copy"
        const val HWDEC_CODECS = "h264,hevc,mpeg4,mpeg2video,vp8,vp9,av1"

        const val MAX_VOLUME = 150
        private const val DEMUXER_CACHE_BYTES = "${64 * 1024 * 1024}"
        private const val ERROR_LINE_LIMIT = 8

        private const val PROP_TIME_POS = "time-pos"
        private const val PROP_DURATION = "duration"
        private const val PROP_PAUSE = "pause"
        private const val PROP_BUFFERING = "paused-for-cache"
        private const val PROP_SEEKING = "seeking"
        private const val PROP_EOF = "eof-reached"
        private const val PROP_IDLE = "idle-active"
        private const val PROP_SPEED = "speed"
        private const val PROP_VOLUME = "volume"
        private const val PROP_MUTE = "mute"
        private const val PROP_MEDIA_TITLE = "media-title"
        private const val PROP_ASPECT = "video-params/aspect"
        private const val PROP_VIDEO_ROTATE = "video-params/rotate"
        private const val PROP_VIDEO_W = "video-params/w"
        private const val PROP_VIDEO_H = "video-params/h"
        private const val PROP_HWDEC_CURRENT = "hwdec-current"
        private const val PROP_TRACK_LIST = "track-list"
        private const val PROP_AID = "aid"
        private const val PROP_VID = "vid"
        private const val PROP_SID = "sid"
        private const val PROP_PLAYLIST_POS = "playlist-pos"
        private const val PROP_PLAYLIST_COUNT = "playlist-count"
        private const val PROP_LOOP_FILE = "loop-file"
        private const val PROP_LOOP_PLAYLIST = "loop-playlist"
        private const val PROP_SUB_DELAY = "sub-delay"
        private const val PROP_AUDIO_DELAY = "audio-delay"
    }
}
