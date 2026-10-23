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
 *   MPVLib.create(ctx) -> 设选项 -> mpv?.init() -> 设硬编码选项 -> 注册观察者
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

    /** 新文件已经完成加载，队列据此忽略切换文件时旧文件的 END_FILE。 */
    private val _fileLoaded = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    val fileLoaded: SharedFlow<Unit> = _fileLoaded.asSharedFlow()

    @Volatile
    private var configuredLoopMode = 0

    /**
     * 当前 mpv 实例。
     *
     * libmpv 1.0.0 起 `MPVLib` 不再是「静态方法集合」，而是实例对象：
     * `MPVLib.create(ctx)` 返回一个实例，其余方法都在实例上调用。
     * 未初始化时为 null，所以所有调用点都写成 `mpv?.xxx(...)`。
     */
    @Volatile
    private var lib: MPVLib? = null

    private val mpv: MPVLib? get() = lib

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
            // 1.0.0 起 create() 是**返回实例的静态工厂**，之后所有调用都走这个实例
            // （支持多实例是这一版的主要变化；本应用只用单实例，但调用方式必须改）
            lib = MPVLib.create(context)
            applyInitialOptions()
            mpv?.init()
            // init 之后设置的选项用户配置无法覆盖，这里只放「必须这样」的几条
            mpv?.setOptionString("force-window", "no")
            mpv?.setOptionString("idle", "yes")
            mpv?.setOptionString("save-position-on-quit", "no")
            mpv?.addObserver(this)
            mpv?.addLogObserver(this)
            observeProperties()
            initialized = true
            Log.i(TAG, "libmpv 已初始化")
        }.onFailure {
            Log.e(TAG, "libmpv 初始化失败", it)
            lib = null
            initialized = false
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
        runCatching { mpv?.removeObserver(this) }
        runCatching { mpv?.removeLogObserver(this) }
        runCatching { mpv?.destroy() }
        lib = null
        initialized = false
        _state.value = PlayerState()
        Log.i(TAG, "libmpv 已销毁")
    }

    private fun applyInitialOptions() {
        val settings = settingsRepository.current

        // 不读用户配置文件：本应用的设置项才是唯一来源，否则 mpv.conf 会悄悄
        // 覆盖用户在界面上的选择，排查起来非常费劲。
        mpv?.setOptionString("config", "no")
        mpv?.setOptionString("terminal", "no")
        mpv?.setOptionString("input-default-bindings", "no")
        mpv?.setOptionString("input-vo-keyboard", "no")
        mpv?.setOptionString("osc", "no")
        mpv?.setOptionString("osd-level", "0")

        // 视频输出：Android 上必须显式指定 gpu-context=android 并用 GLES，
        // 否则 mpv 会去找 X11/Wayland 的上下文。
        mpv?.setOptionString("vo", voName)
        mpv?.setOptionString("gpu-context", "android")
        mpv?.setOptionString("opengl-es", "yes")

        // 硬解：mediacodec 优先，mediacodec-copy 兜底（部分格式只能拷贝回内存）
        mpv?.setOptionString("hwdec", if (settings.hardwareDecoding) HWDEC else "no")
        mpv?.setOptionString("hwdec-codecs", HWDEC_CODECS)

        // 音频：audiotrack 是 Android 原生输出，opensles 在老设备上更稳
        mpv?.setOptionString("ao", "audiotrack,opensles")
        mpv?.setOptionString("audio-set-media-role", "yes")

        // 字幕：fuzzy 会自动加载同名字幕（含 .zh.srt 之类的语言后缀）
        mpv?.setOptionString("sub-auto", if (settings.autoLoadSubtitles) "fuzzy" else "no")
        mpv?.setOptionString("audio-file-auto", if (settings.autoLoadSubtitles) "fuzzy" else "no")
        mpv?.setOptionString("sub-file-paths", "subs:subtitles:sub:Subs:Subtitles")

        // 网络
        mpv?.setOptionString("tls-verify", "yes")

        // 移动端默认的 demuxer 缓存过大（动辄几百 MB），按内存档位收一收
        mpv?.setOptionString("demuxer-max-bytes", DEMUXER_CACHE_BYTES)
        mpv?.setOptionString("demuxer-max-back-bytes", DEMUXER_CACHE_BYTES)
        mpv?.setOptionString("cache", "yes")

        // 播放到结尾就结束，不要停在最后一帧（结束行为由应用自己决定）
        mpv?.setOptionString("keep-open", "no")

        mpv?.setOptionString("screenshot-format", "jpg")
        mpv?.setOptionString("screenshot-directory", screenshotDir().absolutePath)

        if (settings.defaultAudioLanguage.isNotBlank()) {
            mpv?.setOptionString("alang", settings.defaultAudioLanguage)
        }
        if (settings.defaultSubtitleLanguage.isNotBlank()) {
            mpv?.setOptionString("slang", settings.defaultSubtitleLanguage)
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
            mpv?.setPropertyString("hwdec", if (settings.hardwareDecoding) HWDEC else "no")
            mpv?.setPropertyString("vo", if (surfaceAttached) voName else "null")
            mpv?.setPropertyString("sub-auto", if (settings.autoLoadSubtitles) "fuzzy" else "no")
            mpv?.setPropertyString("audio-file-auto", if (settings.autoLoadSubtitles) "fuzzy" else "no")
            mpv?.setPropertyString("alang", settings.defaultAudioLanguage)
            mpv?.setPropertyString("slang", settings.defaultSubtitleLanguage)
        }.onFailure { Log.w(TAG, "运行时设置应用失败", it) }
    }

    // ============================================================== surface ====

    fun attachSurface(surface: Surface) {
        ensureInitialized()
        if (!initialized) return
        runCatching {
            mpv?.attachSurface(surface)
            // 先挂 surface 再开 vo：mpv 打开 vo 时会去取当前已注册的 Android surface
            mpv?.setPropertyString("vo", voName)
            // force-window=yes 让 mpv 在没有视频的纯音频场景也把窗口（surface）建起来，
            // 这样封面图与 OSD 才有地方画
            mpv?.setOptionString("force-window", "yes")
            surfaceAttached = true
            refreshVideoParams()
        }.onFailure { Log.e(TAG, "挂载 surface 失败", it) }
    }

    fun detachSurface() {
        if (!initialized) return
        runCatching {
            mpv?.setPropertyString("vo", "null")
            mpv?.setOptionString("force-window", "no")
            mpv?.detachSurface()
        }.onFailure { Log.w(TAG, "卸载 surface 失败", it) }
        surfaceAttached = false
    }

    /** surface 尺寸变化时同步给 mpv，影响 OSD 缩放与 `--video-aspect-override` 的计算 */
    fun setSurfaceSize(width: Int, height: Int) {
        if (!initialized || width <= 0 || height <= 0) return
        runCatching { mpv?.setPropertyString("android-surface-size", "${width}x$height") }
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
        synchronized(errorLines) { errorLines.clear() }
        pendingSubtitles = source.extraSubtitles
        resetPerFileProperties()
        _state.update {
            it.copy(
                source = source,
                error = null,
                eofReached = false,
                positionMs = 0L,
                durationMs = 0L,
                tracks = emptyList(),
                chapters = emptyList(),
                currentChapter = -1,
                editions = emptyList(),
                currentEdition = -1,
                playlistItems = emptyList(),
                abLoopA = null,
                abLoopB = null,
                mediaTitle = source.title,
                // 乐观置 false：loadfile 到 FILE_LOADED 之间 mpv 仍报 idle-active=true，
                // 前台服务会据此判定「播放已结束」并把刚发出去的 loadfile 停掉。
                // 播完 A 再播 B 时必现，所以这里必须抢先把 idle 翻过来。
                idle = false,
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
        // 认证头要在 loadfile 之前设好，否则第一次请求就发出去了。
        // 空列表时 resetPerFileProperties 已经把属性清成 ""，这里跳过即可。
        if (source.httpHeaders.isNotEmpty()) {
            runCatching {
                mpv?.setPropertyString("http-header-fields", source.httpHeaders.joinToString(","))
            }.onFailure { Log.w(TAG, "设置 http-header-fields 失败", it) }
        }
        runCatching { mpv?.command(args.toTypedArray()) }
            .onFailure { Log.e(TAG, "loadfile 失败", it) }
    }

    /** 追加到 mpv 内部播放列表 */
    fun enqueue(sources: List<PlaybackSource>) {
        ensureInitialized()
        if (!initialized) return
        sources.forEach { source ->
            runCatching { mpv?.command(arrayOf("loadfile", source.uri, "append-play")) }
        }
    }

    fun stop() {
        if (!initialized) return
        runCatching { mpv?.command(arrayOf("stop")) }
        _state.update { it.copy(idle = true, paused = true, positionMs = 0L) }
    }

    fun togglePause() {
        if (!initialized) return
        runCatching { mpv?.command(arrayOf("cycle", "pause")) }
    }

    fun setPaused(paused: Boolean) {
        if (!initialized) return
        runCatching { mpv?.setPropertyBoolean("pause", paused) }
    }

    /** 绝对跳转（毫秒） */
    fun seekTo(positionMs: Long) {
        if (!initialized) return
        val seconds = (positionMs.coerceAtLeast(0L) / 1000.0)
        runCatching { mpv?.command(arrayOf("seek", seconds.toString(), "absolute")) }
    }

    /** 相对跳转（毫秒，可负） */
    fun seekBy(deltaMs: Long) {
        if (!initialized) return
        runCatching { mpv?.command(arrayOf("seek", (deltaMs / 1000.0).toString(), "relative")) }
    }

    fun next() {
        if (!initialized) return
        runCatching { mpv?.command(arrayOf("playlist-next", "weak")) }
    }

    fun previous() {
        if (!initialized) return
        runCatching { mpv?.command(arrayOf("playlist-prev", "weak")) }
    }

    // ============================================================ 播放参数 ====

    fun setSpeed(speed: Float) {
        if (!initialized) return
        runCatching { mpv?.setPropertyDouble("speed", speed.toDouble()) }
    }

    fun setVolume(volume: Int) {
        if (!initialized) return
        val clamped = volume.coerceIn(0, MAX_VOLUME)
        runCatching { mpv?.setPropertyDouble("volume", clamped.toDouble()) }
    }

    fun toggleMute() {
        if (!initialized) return
        runCatching { mpv?.setPropertyBoolean("mute", !state.value.muted) }
    }

    fun setAudioTrack(id: Int) = setTrackProperty("aid", id)

    fun setVideoTrack(id: Int) = setTrackProperty("vid", id)

    fun setSubtitleTrack(id: Int) = setTrackProperty("sid", id)

    private fun setTrackProperty(property: String, id: Int) {
        if (!initialized) return
        runCatching {
            // mpv 用字符串 "no" 表示「关闭该轨」，不能用 -1
            if (id < 0) mpv?.setPropertyString(property, "no")
            else mpv?.setPropertyInt(property, id)
        }
    }

    fun addSubtitle(uri: String) {
        if (!initialized) return
        runCatching { mpv?.command(arrayOf("sub-add", uri, "select")) }
    }

    fun addAudio(uri: String) {
        if (!initialized) return
        runCatching { mpv?.command(arrayOf("audio-add", uri, "select")) }
    }

    fun setSubtitleDelay(seconds: Double) {
        if (!initialized) return
        runCatching { mpv?.setPropertyDouble("sub-delay", seconds) }
    }

    fun setAudioDelay(seconds: Double) {
        if (!initialized) return
        runCatching { mpv?.setPropertyDouble("audio-delay", seconds) }
    }

    /** [value] 传 `no` 表示恢复「跟随视频」 */
    fun setAspectOverride(value: String) {
        if (!initialized) return
        runCatching { mpv?.setPropertyString("video-aspect-override", value) }
    }

    fun setRotation(degrees: Int) {
        if (!initialized) return
        runCatching { mpv?.setPropertyInt("video-rotate", ((degrees % 360) + 360) % 360) }
    }

    fun setChapter(index: Int) {
        if (!initialized || index < 0) return
        runCatching { mpv?.setPropertyInt("chapter", index) }
    }

    fun setEdition(index: Int) {
        if (!initialized || index < 0) return
        runCatching { mpv?.setPropertyInt("edition", index) }
    }

    fun playPlaylistIndex(index: Int) {
        if (!initialized || index < 0) return
        runCatching { mpv?.command(arrayOf("playlist-play-index", index.toString())) }
    }

    fun setAbLoopA(positionMs: Long) {
        if (!initialized) return
        runCatching {
            mpv?.setPropertyString("ab-loop-a", (positionMs.coerceAtLeast(0L) / 1000.0).toString())
        }
    }

    fun setAbLoopB(positionMs: Long) {
        if (!initialized) return
        runCatching {
            mpv?.setPropertyString("ab-loop-b", (positionMs.coerceAtLeast(0L) / 1000.0).toString())
        }
    }

    fun clearAbLoop() {
        if (!initialized) return
        runCatching {
            mpv?.setPropertyString("ab-loop-a", "no")
            mpv?.setPropertyString("ab-loop-b", "no")
        }
    }

    /** 单步播放。mpv 会在执行后保持暂停。 */
    fun stepFrame(forward: Boolean) {
        if (!initialized) return
        runCatching {
            mpv?.command(arrayOf(if (forward) "frame-step" else "frame-back-step"))
        }
    }

    fun setSubtitleScale(scale: Float) {
        if (!initialized) return
        runCatching {
            mpv?.setPropertyDouble("sub-scale", scale.coerceIn(0.5f, 2.5f).toDouble())
        }
    }

    fun setSubtitlePosition(position: Int) {
        if (!initialized) return
        runCatching {
            mpv?.setPropertyInt("sub-pos", position.coerceIn(0, 150))
        }
    }

    /** [value] 使用 mpv 的 auto / yes / no。 */
    fun setDeinterlace(value: String) {
        if (!initialized) return
        runCatching { mpv?.setPropertyString("deinterlace", value) }
    }

    fun setToneMapping(value: String) {
        if (!initialized) return
        runCatching { mpv?.setPropertyString("tone-mapping", value) }
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
        runCatching { mpv?.setPropertyDouble("video-zoom", value) }
    }

    /**
     * 复位「按文件调整过」的运行时属性。
     *
     * mpv 里 `video-zoom` / `video-rotate` / `video-aspect-override` / `sub-delay` /
     * `audio-delay` 都是**全局运行时属性**，切换文件不会自动回到默认值。
     * 不显式复位的话，给上一个文件设的缩放、旋转、画面比例、字幕/音频延迟会跟着
     * 进下一个文件 —— 用户会看到「新片子怎么是歪的 / 放大的 / 字幕对不上」。
     */
    private fun resetPerFileProperties() {
        if (!initialized) return
        runCatching {
            mpv?.setPropertyDouble("video-zoom", 0.0)
            mpv?.setPropertyInt("video-rotate", 0)
            mpv?.setPropertyString("video-aspect-override", "no")
            mpv?.setPropertyDouble("sub-delay", 0.0)
            mpv?.setPropertyDouble("audio-delay", 0.0)
            mpv?.setPropertyString("ab-loop-a", "no")
            mpv?.setPropertyString("ab-loop-b", "no")
            // http-header-fields 也是全局运行时属性，不清掉的话上一个源
            // （比如带 Basic 认证的 WebDAV）的 Authorization 头会跟着发给
            // 下一个源 —— 既是凭据外泄，也会让无关的请求带上不该有的头。
            mpv?.setPropertyString("http-header-fields", "")
        }.onFailure { Log.w(TAG, "复位按文件属性失败", it) }
    }

    /** 0 不循环 / 1 列表循环 / 2 单曲循环 */
    fun setLoopMode(mode: Int) {
        if (!initialized) return
        configuredLoopMode = mode.coerceIn(0, 2)
        runCatching {
            when (configuredLoopMode) {
                1 -> {
                    // 列表循环由应用级队列负责，mpv 内部只保留当前文件。
                    mpv?.setPropertyString("loop-playlist", "no")
                    mpv?.setPropertyString("loop-file", "no")
                }
                2 -> {
                    mpv?.setPropertyString("loop-playlist", "no")
                    mpv?.setPropertyString("loop-file", "inf")
                }
                else -> {
                    mpv?.setPropertyString("loop-playlist", "no")
                    mpv?.setPropertyString("loop-file", "no")
                }
            }
            _state.update { it.copy(loopMode = configuredLoopMode) }
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
            mpv?.command(arrayOf("screenshot-to-file", file.absolutePath))
            file.absolutePath
        }.getOrNull()
    }

    /**
     * 读取 PotPlayer 风格播放信息所需的原始属性快照。
     *
     * 这个接口只应在信息面板打开时调用，并按较低频率刷新，避免常驻读取几十个
     * mpv 属性。所有属性都允许当前文件不支持，因此不支持的项统一显示“不可用”。
     */
    fun playbackInfoSnapshot(): List<PlaybackInfoSection> {
        if (!initialized) return emptyList()
        fun section(title: String, properties: List<Pair<String, String>>) =
            PlaybackInfoSection(
                title = title,
                rows = properties.map { (key, label) ->
                    PlaybackInfoRow(
                        key = key,
                        label = label,
                        value = readPropertyDisplayValue(key),
                    )
                },
            )

        return listOf(
            section(
                "媒体",
                listOf(
                    "filename" to "文件",
                    "media-title" to "标题",
                    "file-format" to "容器",
                    "file-size" to "大小",
                    "duration" to "时长",
                    "seekable" to "可跳转",
                    "percent-pos" to "进度",
                ),
            ),
            section(
                "视频",
                listOf(
                    "video-codec" to "编码",
                    "video-params/w" to "宽度",
                    "video-params/h" to "高度",
                    "video-params/pixelformat" to "像素格式",
                    "video-params/colormatrix" to "色彩矩阵",
                    "video-params/primaries" to "色彩原色",
                    "video-params/gamma" to "传递函数",
                    "video-params/sig-peak" to "峰值亮度",
                    "container-fps" to "容器帧率",
                    "estimated-vf-fps" to "输出帧率",
                    "hwdec-current" to "当前硬解",
                    "hwdec-interop" to "硬解互操作",
                    "video-bitrate" to "码率",
                    "video-zoom" to "缩放",
                    "video-rotate" to "旋转",
                    "deinterlace" to "反交错",
                    "tone-mapping" to "HDR 映射",
                ),
            ),
            section(
                "音频",
                listOf(
                    "audio-codec-name" to "编码",
                    "audio-params/channel-count" to "声道数",
                    "audio-params/samplerate" to "采样率",
                    "audio-params/format" to "采样格式",
                    "audio-bitrate" to "码率",
                    "audio-device" to "输出设备",
                    "audio-delay" to "延迟",
                    "volume" to "音量",
                    "mute" to "静音",
                ),
            ),
            section(
                "播放与缓存",
                listOf(
                    "time-pos" to "当前位置",
                    "speed" to "倍速",
                    "pause" to "暂停",
                    "seeking" to "跳转中",
                    "paused-for-cache" to "缓存暂停",
                    "cache-buffering-state" to "缓存进度",
                    "cache-speed" to "缓存速度",
                    "demuxer-cache-time" to "已缓存时长",
                    "demuxer-cache-idle" to "解复用空闲",
                    "avsync" to "音画同步",
                    "display-fps" to "显示刷新率",
                    "mistimed-frame-count" to "错时帧",
                    "vo-drop-frame-count" to "渲染丢帧",
                    "decoder-frame-drop-count" to "解码丢帧",
                ),
            ),
            section(
                "轨道与文件结构",
                listOf(
                    "aid" to "音轨 ID",
                    "vid" to "视频轨 ID",
                    "sid" to "字幕轨 ID",
                    "track-list/count" to "轨道数",
                    "chapter" to "当前章节",
                    "chapter-list/count" to "章节数",
                    "edition" to "当前版本",
                    "edition-list/count" to "版本数",
                    "playlist-pos" to "列表位置",
                    "playlist-count" to "列表长度",
                ),
            ),
        )
    }

    private fun readPropertyDisplayValue(property: String): String {
        val value = runCatching { mpv?.getPropertyString(property) }.getOrNull()
        return value?.takeIf { it.isNotBlank() } ?: "不可用"
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
            PROP_CHAPTER_LIST -> refreshChapters()
            PROP_EDITION_LIST -> refreshEditions()
            PROP_PLAYLIST -> refreshPlaylist()
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
            PROP_PLAYLIST_POS -> {
                val position = value.toInt()
                _state.update { state ->
                    state.copy(
                        playlistPosition = position,
                        playlistItems = state.playlistItems.map { item ->
                            item.copy(isPlaying = item.index == position)
                        },
                    )
                }
            }
            PROP_PLAYLIST_COUNT -> {
                _state.update { it.copy(playlistCount = value.toInt()) }
                refreshPlaylist()
            }
            PROP_CHAPTER -> _state.update { it.copy(currentChapter = value.toInt()) }
            PROP_EDITION -> _state.update { it.copy(currentEdition = value.toInt()) }
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
            PROP_SUB_SCALE -> _state.update { it.copy(subtitleScale = value.toFloat()) }
            PROP_SUB_POS -> _state.update { it.copy(subtitlePosition = value.toInt()) }
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
            PROP_SEEKABLE -> _state.update { it.copy(seekable = value) }
        }
    }

    override fun eventProperty(property: String, value: String) {
        when (property) {
            PROP_MEDIA_TITLE -> _state.update { it.copy(mediaTitle = value) }
            PROP_HWDEC_CURRENT -> _state.update { it.copy(hwdecActive = value) }
            PROP_LOOP_FILE, PROP_LOOP_PLAYLIST -> _state.update { it.copy(loopMode = readLoopMode()) }
            PROP_AB_LOOP_A -> _state.update { it.copy(abLoopA = readOptionalSeconds(value)) }
            PROP_AB_LOOP_B -> _state.update { it.copy(abLoopB = readOptionalSeconds(value)) }
            PROP_DEINTERLACE -> _state.update { it.copy(deinterlace = value) }
            PROP_TONE_MAPPING -> _state.update { it.copy(toneMapping = value) }
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
                refreshChapters()
                refreshEditions()
                refreshPlaylist()
                _state.update { it.copy(idle = false, error = null, eofReached = false) }
                _fileLoaded.tryEmit(Unit)
                // 外部字幕要等文件就绪后再追加：否则会与自动加载的同名字幕互相顶掉
                val pending = pendingSubtitles
                pendingSubtitles = emptyList()
                pending.forEach { path ->
                    runCatching { mpv?.command(arrayOf("sub-add", path, "select")) }
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
        if (level <= MPVLib.MpvLogLevel.MPV_LOG_LEVEL_ERROR) {
            synchronized(errorLines) {
                if (errorLines.size >= ERROR_LINE_LIMIT) errorLines.pollFirst()
                errorLines.addLast(text.trim())
            }
        }
    }

    private fun levelTag(level: Int): String = when {
        level <= MPVLib.MpvLogLevel.MPV_LOG_LEVEL_FATAL -> "FATAL"
        level <= MPVLib.MpvLogLevel.MPV_LOG_LEVEL_ERROR -> "ERROR"
        level <= MPVLib.MpvLogLevel.MPV_LOG_LEVEL_WARN -> "WARN"
        level <= MPVLib.MpvLogLevel.MPV_LOG_LEVEL_INFO -> "INFO"
        level <= MPVLib.MpvLogLevel.MPV_LOG_LEVEL_V -> "VERBOSE"
        level <= MPVLib.MpvLogLevel.MPV_LOG_LEVEL_DEBUG -> "DEBUG"
        else -> "TRACE"
    }

    // ============================================================== 内部 ====

    private fun observeProperties() {
        val doubles = arrayOf(
            PROP_TIME_POS, PROP_DURATION, PROP_SPEED, PROP_VOLUME,
            PROP_ASPECT, PROP_SUB_DELAY, PROP_AUDIO_DELAY, PROP_SUB_SCALE, PROP_SUB_POS,
        )
        val flags = arrayOf(
            PROP_PAUSE, PROP_BUFFERING, PROP_SEEKING, PROP_EOF, PROP_IDLE, PROP_MUTE,
            PROP_SEEKABLE,
        )
        val strings = arrayOf(
            PROP_MEDIA_TITLE,
            PROP_HWDEC_CURRENT,
            PROP_LOOP_FILE,
            PROP_LOOP_PLAYLIST,
            PROP_AB_LOOP_A,
            PROP_AB_LOOP_B,
            PROP_DEINTERLACE,
            PROP_TONE_MAPPING,
        )
        val ints = arrayOf(
            PROP_VIDEO_W,
            PROP_VIDEO_H,
            PROP_VIDEO_ROTATE,
            PROP_PLAYLIST_POS,
            PROP_PLAYLIST_COUNT,
            PROP_CHAPTER,
            PROP_EDITION,
        )
        val nodes = arrayOf(
            PROP_TRACK_LIST,
            PROP_AID,
            PROP_VID,
            PROP_SID,
            PROP_CHAPTER_LIST,
            PROP_EDITION_LIST,
            PROP_PLAYLIST,
        )

        doubles.forEach { runCatching { mpv?.observeProperty(it, MpvFormat.DOUBLE) } }
        flags.forEach { runCatching { mpv?.observeProperty(it, MpvFormat.FLAG) } }
        strings.forEach { runCatching { mpv?.observeProperty(it, MpvFormat.STRING) } }
        ints.forEach { runCatching { mpv?.observeProperty(it, MpvFormat.INT64) } }
        nodes.forEach { runCatching { mpv?.observeProperty(it, MpvFormat.NONE) } }
    }

    private fun refreshTracks() {
        if (!initialized) return
        val count = runCatching { mpv?.getPropertyInt("track-list/count") }.getOrNull() ?: 0
        if (count <= 0) {
            _state.update { it.copy(tracks = emptyList()) }
            return
        }
        val list = ArrayList<MpvTrack>(count)
        for (index in 0 until count) {
            // 事件是异步的，属性随时可能消失，所以每个字段都要能容忍 null
            val type = runCatching { mpv?.getPropertyString("track-list/$index/type") }.getOrNull()
                ?: continue
            val id = runCatching { mpv?.getPropertyInt("track-list/$index/id") }.getOrNull()
                ?: continue
            list.add(
                MpvTrack(
                    id = id,
                    type = type,
                    title = runCatching { mpv?.getPropertyString("track-list/$index/title") }.getOrNull(),
                    language = runCatching { mpv?.getPropertyString("track-list/$index/lang") }.getOrNull(),
                    codec = runCatching { mpv?.getPropertyString("track-list/$index/codec") }.getOrNull(),
                    isDefault = runCatching { mpv?.getPropertyBoolean("track-list/$index/default") }
                        .getOrNull() == true,
                    isSelected = runCatching { mpv?.getPropertyBoolean("track-list/$index/selected") }
                        .getOrNull() == true,
                    isExternal = runCatching { mpv?.getPropertyBoolean("track-list/$index/external") }
                        .getOrNull() == true,
                ),
            )
        }
        _state.update { it.copy(tracks = list) }
    }

    private fun refreshChapters() {
        if (!initialized) return
        val count = runCatching { mpv?.getPropertyInt("chapter-list/count") }.getOrNull() ?: 0
        val chapters = if (count <= 0) {
            emptyList()
        } else {
            buildList {
                for (index in 0 until count) {
                    val time = runCatching {
                        mpv?.getPropertyDouble("chapter-list/$index/time")
                    }.getOrNull() ?: continue
                    add(
                        MpvChapter(
                            index = index,
                            title = readOptionalProperty("chapter-list/$index/title"),
                            timeMs = (time * 1000.0).toLong(),
                        ),
                    )
                }
            }
        }
        val current = runCatching { mpv?.getPropertyInt("chapter") }.getOrNull() ?: -1
        _state.update { it.copy(chapters = chapters, currentChapter = current) }
    }

    private fun refreshEditions() {
        if (!initialized) return
        val count = runCatching { mpv?.getPropertyInt("edition-list/count") }.getOrNull() ?: 0
        val current = runCatching { mpv?.getPropertyInt("edition") }.getOrNull() ?: -1
        val editions = if (count <= 0) {
            emptyList()
        } else {
            buildList {
                for (index in 0 until count) {
                    add(
                        MpvEdition(
                            index = index,
                            title = readOptionalProperty("edition-list/$index/title"),
                            isDefault = runCatching {
                                mpv?.getPropertyBoolean("edition-list/$index/default")
                            }.getOrNull() == true,
                            isSelected = current == index,
                        ),
                    )
                }
            }
        }
        _state.update { it.copy(editions = editions, currentEdition = current) }
    }

    private fun refreshPlaylist() {
        if (!initialized) return
        val count = runCatching { mpv?.getPropertyInt("playlist/count") }.getOrNull() ?: 0
        val current = runCatching { mpv?.getPropertyInt("playlist-pos") }.getOrNull() ?: -1
        val currentTitle = readOptionalProperty(PROP_MEDIA_TITLE)
        val items = if (count <= 0) {
            emptyList()
        } else {
            buildList {
                for (index in 0 until count) {
                    // mpv 的 playlist 节点只稳定提供 filename/current/playing；
                    // 条目不存在独立的 title 字段，当前条目标题从 media-title 补。
                    val playing = runCatching {
                        mpv?.getPropertyBoolean("playlist/$index/playing")
                    }.getOrNull() ?: (index == current)
                    add(
                        MpvPlaylistItem(
                            index = index,
                            title = currentTitle.takeIf { index == current },
                            filename = readOptionalProperty("playlist/$index/filename"),
                            isPlaying = playing,
                        ),
                    )
                }
            }
        }
        _state.update {
            it.copy(
                playlistItems = items,
                playlistPosition = current,
                playlistCount = count,
            )
        }
    }

    private fun readOptionalProperty(property: String): String? =
        runCatching { mpv?.getPropertyString(property) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() && !it.equals("no", ignoreCase = true) }

    private fun readOptionalSeconds(value: String): Double? {
        if (value.isBlank() || value.equals("no", ignoreCase = true)) return null
        return value.toDoubleOrNull()
    }

    private fun refreshVideoParams() {
        if (!initialized) return
        val width = runCatching { mpv?.getPropertyInt("video-params/w") }.getOrNull() ?: 0
        val height = runCatching { mpv?.getPropertyInt("video-params/h") }.getOrNull() ?: 0
        val rotate = runCatching { mpv?.getPropertyInt("video-params/rotate") }.getOrNull() ?: 0
        _state.update {
            it.copy(
                videoWidth = width,
                videoHeight = height,
                videoRotate = rotate,
            )
        }
    }

    private fun readTrackId(property: String): Int {
        val raw = runCatching { mpv?.getPropertyString(property) }.getOrNull() ?: return -1
        return raw.toIntOrNull() ?: -1
    }

    private fun readLoopMode(): Int {
        val file = runCatching { mpv?.getPropertyString("loop-file") }.getOrNull()
        return if (file == "inf") 2 else configuredLoopMode
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
        private const val PROP_SEEKABLE = "seekable"
        private const val PROP_CHAPTER_LIST = "chapter-list"
        private const val PROP_CHAPTER = "chapter"
        private const val PROP_EDITION_LIST = "edition-list"
        private const val PROP_EDITION = "edition"
        private const val PROP_PLAYLIST = "playlist"
        private const val PROP_AB_LOOP_A = "ab-loop-a"
        private const val PROP_AB_LOOP_B = "ab-loop-b"
        private const val PROP_SUB_SCALE = "sub-scale"
        private const val PROP_SUB_POS = "sub-pos"
        private const val PROP_DEINTERLACE = "deinterlace"
        private const val PROP_TONE_MAPPING = "tone-mapping"
    }
}
