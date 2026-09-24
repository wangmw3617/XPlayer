package com.zhiwei.xplayer.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zhiwei.xplayer.core.mpv.PlayerState
import com.zhiwei.xplayer.core.util.Formatters
import kotlinx.coroutines.flow.StateFlow

/**
 * 把 [PlayerState] 拆成一组「各自独立失效」的可观察字段。
 *
 * ## 为什么必须经过 `collectAsState`
 *
 * 曾经这里是这么写的：
 *
 * ```kotlin
 * val state = viewModel.state                       // StateFlow<PlayerState>
 * val paused by remember { derivedStateOf { state.value.paused } }
 * ```
 *
 * 能编译、能显示，但**永远不更新**：`StateFlow.value` 只是一次普通字段读取，
 * 不是 Compose 的快照状态，`derivedStateOf` 记录不到任何依赖，
 * 于是算过一次就把结果缓存住，再也不会失效。表现就是「点播放按钮没反应」
 * 「进度条不走」——整个播放页看起来像卡住了。
 *
 * 正确做法是先 `collectAsState`（这里是 [collectAsStateWithLifecycle]）拿到
 * Compose 的 [State]，再在它上面派生。见 `ComposeStateObservationTest`，
 * 那里有一对测试把这两种写法的差别钉死了。
 *
 * ## 为什么按字段拆开，而不是暴露整份快照
 *
 * mpv 的 `time-pos` 是**每帧都在变**的属性，每秒会推几十次新快照。
 * 如果只暴露一个 `PlayerState`，那么进度一变，所有读它的组件都要重组 ——
 * 包括整块玻璃控制面板。按字段派生之后，每个字段各自失效：
 * `paused` 翻转才轮到播放按钮重组，进度变化只影响进度条那一行。
 *
 * 每个字段都是一个 `derivedStateOf`，因此**结果没变时不会通知读者**，
 * 这也是「进度在走、播放按钮不动」能成立的原因。
 *
 * ## 用法
 *
 * 直接当普通属性读即可 —— getter 内部读的是派生 [State]，
 * 读取动作发生在**调用方**的组合作用域里，所以重组范围会被正确限制住：
 *
 * ```kotlin
 * val player = rememberPlayerStateObserver(viewModel.state)
 * val paused = player.paused                       // 只有 paused 变才重组
 * PlayPauseButton(paused = { player.paused })      // 读在子组件里，更省
 * ```
 */
@Stable
class PlayerStateObserver(private val snapshot: State<PlayerState>) {

    /** 当前文件 uri。换文件时用它做 key 复位画面参数。 */
    val sourceUri: String? get() = sourceUriState.value

    /** 有媒体且时长已解出。用来决定传输按钮是否可用。 */
    val hasMedia: Boolean get() = hasMediaState.value

    val error: String? get() = errorState.value

    /** 缓冲中：mpv 明确在缓冲，或者文件已加载但时长还没解出来。 */
    val buffering: Boolean get() = bufferingState.value

    val paused: Boolean get() = pausedState.value

    val speed: Float get() = speedState.value

    val loopMode: Int get() = loopModeState.value

    val hwdecActive: String get() = hwdecState.value

    /**
     * 显示用进度，已按 [POSITION_STEP_MS] 量化。
     *
     * `time-pos` 每帧都变，直接拿来驱动界面等于每秒重组几十次进度条。
     * 量化到 200ms 后最多每秒变 5 次，肉眼完全看不出台阶
     * （2 小时的片子走一格只有万分之三的行程），但重组次数降了一个数量级。
     *
     * 需要**精确**进度的地方（手势相对跳转的起点、写库）不走这里，
     * 直接用 `MpvPlayer.state.value.positionMs` 的原始值。
     */
    val positionMs: Long get() = positionState.value

    val durationMs: Long get() = durationState.value

    /** 顶栏副标题：`位置 / 时长 · 分辨率`。 */
    val subtitle: String get() = subtitleState.value

    /** 顶栏主标题：mpv 上报的媒体名，没有就退回播放源标题。 */
    val title: String get() = titleState.value

    // ------------------------------------------------------------ 派生状态 ----
    // 全部在构造时建好，之后只是被读；derivedStateOf 本身不需要组合环境。

    private val sourceUriState = derivedStateOf { snapshot.value.source?.uri }
    private val hasMediaState = derivedStateOf { snapshot.value.hasMedia }
    private val errorState = derivedStateOf { snapshot.value.error }
    private val pausedState = derivedStateOf { snapshot.value.paused }
    private val speedState = derivedStateOf { snapshot.value.speed }
    private val loopModeState = derivedStateOf { snapshot.value.loopMode }
    private val hwdecState = derivedStateOf { snapshot.value.hwdecActive }
    private val durationState = derivedStateOf { snapshot.value.durationMs }

    private val positionState = derivedStateOf {
        // 整除再乘回，等价于向下取整到 200ms 的整数倍
        snapshot.value.positionMs / POSITION_STEP_MS * POSITION_STEP_MS
    }

    private val bufferingState = derivedStateOf {
        val s = snapshot.value
        s.buffering || (!s.idle && s.durationMs == 0L)
    }

    private val titleState = derivedStateOf {
        val s = snapshot.value
        s.mediaTitle.ifBlank { s.source?.title.orEmpty() }
    }

    private val subtitleState = derivedStateOf { buildSubtitle(snapshot.value) }

    companion object {
        /**
         * 进度量化步长。
         *
         * 200ms 是折中：再小就失去节流意义，再大进度条的移动会开始显得一顿一顿。
         */
        const val POSITION_STEP_MS = 200L
    }
}

/**
 * 订阅播放状态并拆成可观察字段。跟着生命周期走，后台时不收。
 */
@Composable
fun rememberPlayerStateObserver(state: StateFlow<PlayerState>): PlayerStateObserver {
    // collectAsStateWithLifecycle 返回的 State 实例是 remember 住的、跨重组稳定，
    // 所以这里可以用无 key 的 remember —— 换成 remember(snapshot) 反而会
    // 每来一个新快照就重建观察器（而快照每秒来几十个）。
    val snapshot = state.collectAsStateWithLifecycle()
    return remember { PlayerStateObserver(snapshot) }
}

private fun buildSubtitle(state: PlayerState): String {
    if (state.durationMs <= 0L) return ""
    val parts = mutableListOf(
        "${Formatters.position(state.positionMs)} / ${Formatters.duration(state.durationMs)}",
    )
    if (state.videoWidth > 0 && state.videoHeight > 0) {
        parts += "${state.videoWidth}×${state.videoHeight}"
    }
    return parts.joinToString(" · ")
}
