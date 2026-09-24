package com.zhiwei.xplayer.ui.player

import android.app.Activity
import android.content.pm.ActivityInfo
import android.os.Build
import android.os.SystemClock
import android.util.Rational
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.outlined.AspectRatio
import androidx.compose.material.icons.outlined.Loop
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PictureInPictureAlt
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zhiwei.xplayer.R
import com.zhiwei.xplayer.core.mpv.MpvPlayer
import com.zhiwei.xplayer.core.mpv.MpvTrack
import com.zhiwei.xplayer.core.mpv.PlayerState
import com.zhiwei.xplayer.core.util.Formatters
import com.zhiwei.xplayer.ui.PendingPlayback
import com.zhiwei.xplayer.ui.components.SUBTITLE_MIME_TYPES
import com.zhiwei.xplayer.ui.components.findActivity
import com.zhiwei.xplayer.ui.components.rememberSubtitlePicker
import com.zhiwei.xplayer.ui.theme.playerGlass
import com.zhiwei.xplayer.ui.vm.PlayerViewModel
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

private const val DRAG_SLOP = 22f
private const val SEEK_THROTTLE_MS = 120L
private const val HINT_DURATION_MS = 900L

/** 手势类型 */
private enum class DragMode { NONE, SEEK, BRIGHTNESS, VOLUME }

/** 画面比例选项 */
private data class AspectOption(val label: String, val value: String)

@Composable
fun PlayerScreen(
    onBack: () -> Unit,
    viewModel: PlayerViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    // ---- 播放状态 ----
    // 观察器把 PlayerState 拆成一组「各自独立失效」的字段。
    //
    // 这里刻意**不读**那些高频字段（进度、暂停、倍速…），而是把 getter 以 lambda
    // 的形式交给最里层的小组件，让读取动作发生在那一层的组合作用域里 ——
    // 于是「进度走秒」只会重组进度条那一行，不会连累视频层和整块玻璃面板。
    //
    // ⚠ 别改回 `derivedStateOf { viewModel.state.value.xxx }`：StateFlow.value
    //   不是 Compose 快照状态，这样派生出来的值算一次就永远不再更新，
    //   表现就是「点播放按钮没反应」。见 PlayerStateObserver 的注释与
    //   ComposeStateObservationTest。
    //
    // 唯一在函数体里读的是 sourceUri：换文件时要用它做 key 复位画面参数，低频事件。
    val player = rememberPlayerStateObserver(viewModel.state)
    val sourceUri = player.sourceUri
    val ended = viewModel.player.ended

    var controlsVisible by remember { mutableStateOf(true) }
    var sheet by remember { mutableStateOf<PlayerSheet?>(null) }
    var hint by remember { mutableStateOf<String?>(null) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var aspect by remember { mutableStateOf(ASPECT_DEFAULT) }

    // 长按加速：mpv 上报的 speed 会被长按改掉，所以必须自己记住按下去之前的倍速，
    // 否则松手时会把「加速后的倍速」当成原速存下来，倍速就永久变了。
    var boosted by remember { mutableStateOf(false) }
    var speedBeforeBoost by remember { mutableFloatStateOf(1f) }

    // 手势层里要用到的低频值，先取出来，避免手势回调闭包每次都重建。
    val gestureSource = viewModel.player.state
    val longPressSpeed = settings.longPressSpeed
    val seekStepSeconds = settings.seekStepSeconds

    // ------------------------------------------------------- 待播放请求 ----
    // PendingPlayback 是 StateFlow，collect 会立刻拿到当前值，
    // 所以「进入播放页时播什么」和「播放页已在栈顶时又点了别的文件」是同一条路径。
    LaunchedEffect(Unit) {
        PendingPlayback.request.collect { pending ->
            if (pending != null) {
                PendingPlayback.clear()
                viewModel.play(pending)
            }
        }
    }

    // -------------------------------------------------------- 屏幕常亮 ----
    DisposableEffect(settings.keepScreenOn, activity) {
        val window = activity?.window
        if (settings.keepScreenOn) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // ------------------------------------------------------------ 方向 ----
    // 视频多为横屏，进播放页放开旋转；退出时把控制权交还系统，
    // 否则用户在别的页面也会被迫跟随传感器。
    DisposableEffect(activity) {
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
        onDispose {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    // ---------------------------------------------------------- 沉浸式 ----
    DisposableEffect(controlsVisible, activity) {
        val window = activity?.window
        if (window != null) {
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            if (controlsVisible) {
                controller.show(WindowInsetsCompat.Type.systemBars())
            } else {
                controller.hide(WindowInsetsCompat.Type.systemBars())
            }
        }
        onDispose { }
    }

    // 离开播放页时务必把系统栏放回来，否则其它页面会一直缺状态栏
    DisposableEffect(activity) {
        onDispose {
            val window = activity?.window
            if (window != null) {
                WindowCompat.getInsetsController(window, window.decorView)
                    .show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    // 换文件时把「按文件调的画面参数」复位。
    // mpv 侧的复位在 MpvPlayer.play() 里做，这里同步的是界面自己持有的那份状态，
    // 否则 UI 显示「1.0x / 跟随视频」而实际画面还留着上一个文件的缩放，两边对不上。
    LaunchedEffect(sourceUri) {
        zoom = 1f
        aspect = ASPECT_DEFAULT
    }

    // 播放结束把控制层亮出来，否则用户面对一张静止画面无从下手
    LaunchedEffect(Unit) {
        ended.collect { controlsVisible = true }
    }

    val subtitlePicker = rememberSubtitlePicker { uri ->
        viewModel.player.addSubtitle(uri.toString())
    }

    BackHandler {
        if (!settings.backgroundPlayback) viewModel.stopPlayback()
        onBack()
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        // ① 视频画面。SurfaceView 是独立合成层，会盖在 Compose 内容之下、
        //    窗口之上，所以控制层能正常画在它上面。
        AndroidView(
            factory = { ctx -> MpvSurfaceView(ctx, viewModel.player) },
            modifier = Modifier.fillMaxSize(),
        )

        // ② 手势层。写在控制层之前，控制层里的按钮才会优先拿到点击。
        //
        // 这里只用 onPress / onLongPress / onTap，刻意不用 onDoubleTap。
        // 原因：detectTapGestures 一旦注册了 onDoubleTap，每次单击都必须等
        // 双击判定超时（约 300ms）才会回调 onTap —— 落在空白处的「轻点切换控制层」
        // 会有肉眼可见的延迟，而按钮附近的点击更容易被这段等待窗口吃掉，
        // 表现就是「点播放没反应」。
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(longPressSpeed) {
                    detectTapGestures(
                        onTap = { controlsVisible = !controlsVisible },
                        onLongPress = {
                            viewModel.player.setSpeed(longPressSpeed)
                            boosted = true
                            speedBeforeBoost = gestureSource.value.speed
                            hint = "${Formatters.speed(longPressSpeed)} 快进中"
                        },
                        onPress = {
                            tryAwaitRelease()
                            // 只有真的进入过长按加速才需要恢复，
                            // 普通点击不碰倍速，避免把用户设的倍速冲掉。
                            if (boosted) {
                                viewModel.player.setSpeed(speedBeforeBoost)
                                boosted = false
                            }
                            hint = null
                        },
                    )
                }
                .pointerInput(Unit) {
                    var mode = DragMode.NONE
                    var total = Offset.Zero
                    var startPosition = 0L
                    var lastSeekAt = 0L
                    var brightness = 0.5f
                    var volume = 0f

                    detectTransformGestures(
                        panZoomLock = false,
                    ) { centroid, pan, gestureZoom, _ ->
                        // ---- 双指缩放 ----
                        if (abs(gestureZoom - 1f) > 0.002f) {
                            zoom = (zoom * gestureZoom).coerceIn(1f, 5f)
                            viewModel.player.setVideoZoom(zoom)
                            hint = "缩放 ${String.format(java.util.Locale.US, "%.1f", zoom)}x"
                            return@detectTransformGestures
                        }

                        total += pan
                        if (mode == DragMode.NONE) {
                            if (abs(total.x) < DRAG_SLOP && abs(total.y) < DRAG_SLOP) {
                                return@detectTransformGestures
                            }
                            mode = when {
                                abs(total.x) >= abs(total.y) -> DragMode.SEEK
                                centroid.x < size.width / 2f -> DragMode.BRIGHTNESS
                                else -> DragMode.VOLUME
                            }
                            startPosition = gestureSource.value.positionMs
                            brightness = readBrightness(activity)
                            volume = gestureSource.value.volume.toFloat()
                        }

                        when (mode) {
                            DragMode.SEEK -> {
                                val stepMs = seekStepSeconds * 1000f
                                val deltaMs = (total.x / size.width.toFloat() * stepMs).toLong()
                                val duration = gestureSource.value.durationMs
                                val target = if (duration > 0L) {
                                    (startPosition + deltaMs).coerceIn(0L, duration)
                                } else {
                                    (startPosition + deltaMs).coerceAtLeast(0L)
                                }
                                val sign = if (deltaMs >= 0) "+" else "-"
                                hint = "$sign${abs(deltaMs) / 1000}s  ${Formatters.position(target)}"
                                val now = SystemClock.elapsedRealtime()
                                if (now - lastSeekAt >= SEEK_THROTTLE_MS) {
                                    lastSeekAt = now
                                    viewModel.player.seekTo(target)
                                }
                            }

                            DragMode.BRIGHTNESS -> {
                                brightness = (brightness - pan.y / size.height.toFloat())
                                    .coerceIn(0.02f, 1f)
                                applyBrightness(activity, brightness)
                                hint = "亮度 ${Formatters.percent(brightness)}"
                            }

                            DragMode.VOLUME -> {
                                volume = (volume - pan.y / size.height.toFloat() * MAX_VOLUME)
                                    .coerceIn(0f, MAX_VOLUME)
                                viewModel.player.setVolume(volume.roundToInt())
                                hint = "音量 ${volume.roundToInt()}%"
                            }

                            DragMode.NONE -> Unit
                        }
                    }
                },
        )

        // ③ 缓冲指示。状态读在组件内部，只有它自己重组。
        BufferingIndicator(
            visible = { player.buffering },
            modifier = Modifier.align(Alignment.Center),
        )

        // ④ 手势提示。拖动进度时它每帧都在变 —— 读在组件内部，
        //    否则整屏（含视频层与玻璃面板）会跟着手指一起重组。
        GestureHint(
            hint = { hint },
            onExpired = { hint = null },
            modifier = Modifier.align(Alignment.Center),
        )

        // ⑤ 错误提示
        ErrorBanner(
            message = { player.error },
            modifier = Modifier.align(Alignment.TopCenter),
        )

        // ⑥ 控制层
        if (controlsVisible) {
            PlayerTopBar(
                title = { player.title },
                subtitle = { player.subtitle },
                hwdecActive = { player.hwdecActive },
                onBack = {
                    if (!settings.backgroundPlayback) viewModel.stopPlayback()
                    onBack()
                },
                modifier = Modifier.align(Alignment.TopCenter),
            )

            // 把高频字段包成 lambda：读取动作发生在 ProgressRow / TransportRow 内部，
            // 于是「进度走秒」只会让那一行重组，不会连累整块玻璃面板和视频层。
            PlayerControlPanel(
                hasMedia = { player.hasMedia },
                positionMs = { player.positionMs },
                durationMs = { player.durationMs },
                paused = { player.paused },
                speed = { player.speed },
                loopMode = { player.loopMode },
                onTogglePlay = viewModel::togglePlayPause,
                onPrevious = { viewModel.player.previous() },
                onNext = { viewModel.player.next() },
                onSeekBy = { delta -> viewModel.player.seekBy(delta) },
                onSeekTo = { position -> viewModel.player.seekTo(position) },
                onOpenSheet = { sheet = it },
                onScreenshot = viewModel::screenshot,
                // 画中画的比例要的是**当前**分辨率，用原始快照而不是量化过的显示值
                onEnterPip = { enterPip(activity, viewModel.player.state.value) },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }

    // ------------------------------------------------------------ 面板 ----
    // 面板只在打开时组合，且需要整份快照（轨道列表等），所以这里才读整份 state。
    val activeSheet = sheet
    if (activeSheet != null) {
        val snapshot = viewModel.player.state.value
        OptionSheet(
            title = sheetTitle(context, activeSheet),
            options = sheetOptions(
                kind = activeSheet,
                state = snapshot,
                player = viewModel.player,
                currentAspect = aspect,
                onAspectChange = { aspect = it },
                onLoadSubtitle = { subtitlePicker.launch(SUBTITLE_MIME_TYPES) },
                onDismiss = { sheet = null },
            ),
            onDismiss = { sheet = null },
        )
    }
}

// ========================================================== 独立状态层 ====

/**
 * 缓冲转圈。
 *
 * 单独成组件是为了把「读缓冲状态」这件事关在自己内部：读在别处的话，
 * 每次缓冲开关都会让整个播放页重组一遍。
 */
@Composable
private fun BufferingIndicator(visible: () -> Boolean, modifier: Modifier = Modifier) {
    if (!visible()) return
    CircularProgressIndicator(
        modifier = modifier.size(44.dp),
        color = Color.White,
    )
}

/**
 * 手势提示条，附带自动消失。
 *
 * 自动消失的计时也放在这里：如果把 `LaunchedEffect(hint)` 写在播放页上，
 * 那个 effect 每次提示变化都会重启，等于又把整页拖进重组。
 */
@Composable
private fun GestureHint(
    hint: () -> String?,
    onExpired: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val text = hint() ?: return
    LaunchedEffect(text) {
        delay(HINT_DURATION_MS)
        onExpired()
    }
    Text(
        text = text,
        color = Color.White,
        style = MaterialTheme.typography.titleMedium,
        modifier = modifier
            .playerGlass(RoundedCornerShape(16.dp))
            .padding(horizontal = 18.dp, vertical = 10.dp),
    )
}

/** 错误横幅。同样把状态读关在内部。 */
@Composable
private fun ErrorBanner(message: () -> String?, modifier: Modifier = Modifier) {
    val text = message() ?: return
    Text(
        text = LocalContext.current.getString(R.string.player_error, text),
        color = Color.White,
        style = MaterialTheme.typography.bodySmall,
        modifier = modifier
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .playerGlass(RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
    )
}

// ============================================================== 顶栏 ====

/**
 * 顶栏。
 *
 * 标题与副标题都以 lambda 形式传入：副标题里有播放进度，每秒会变好几次，
 * 读在播放页的函数体里就会把整页拖进重组。读在这里则最多只影响这一行。
 */
@Composable
private fun PlayerTopBar(
    title: () -> String,
    subtitle: () -> String,
    hwdecActive: () -> String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .playerGlass(RoundedCornerShape(20.dp))
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回",
                tint = Color.White,
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = title(),
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val sub = subtitle()
            if (sub.isNotBlank()) {
                Text(
                    text = sub,
                    color = Color.White.copy(alpha = 0.75f),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        val hwdec = hwdecActive()
        if (hwdec != "no" && hwdec.isNotBlank()) {
            Text(
                text = hwdec,
                color = Color.White.copy(alpha = 0.85f),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier
                    .padding(end = 8.dp)
                    .background(Color.White.copy(alpha = 0.16f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
    }
}

// ============================================================ 控制面板 ====

/**
 * 播放控制面板。
 *
 * 这里刻意「只收窄参数、不收整个 [PlayerState]」：
 * `time-pos` 每帧都在变，每变一次都会让 [PlayerState] 整体失效。
 * 面板如果直接吃 `PlayerState`，进度、倍速、缓冲这些字段一变，
 * 整块面板（含玻璃底板）都要重画一遍。
 *
 * 现在改成分工：
 * - 面板外壳只关心「有没有视频」这类低频字段；
 * - 进度区、播放按钮、功能栏各自读自己那一小片状态，
 *   状态变了也只让自己那一行重组。
 *
 * 另外所有参数都是「不可变值 + lambda」：`PlayerStateObserver` 是 `@Stable` 的，
 * 所以这些 lambda 会被 Compose 记忆住，参数不变时整块面板可以被**跳过**，
 * 连函数体都不进。
 */
@Composable
private fun PlayerControlPanel(
    hasMedia: () -> Boolean,
    positionMs: () -> Long,
    durationMs: () -> Long,
    paused: () -> Boolean,
    speed: () -> Float,
    loopMode: () -> Int,
    onTogglePlay: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeekBy: (Long) -> Unit,
    onSeekTo: (Long) -> Unit,
    onOpenSheet: (PlayerSheet) -> Unit,
    onScreenshot: () -> Unit,
    onEnterPip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    Column(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .playerGlass(RoundedCornerShape(24.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        // ---- 进度 ----
        ProgressRow(
            positionMs = positionMs,
            durationMs = durationMs,
            onSeekTo = onSeekTo,
        )

        // ---- 传输控制 ----
        // paused / enabled 都用 lambda 传入，进度更新不会把这一行也一起重组。
        TransportRow(
            paused = paused,
            enabled = hasMedia,
            onTogglePlay = onTogglePlay,
            onPrevious = onPrevious,
            onNext = onNext,
            onSeekBy = onSeekBy,
        )

        // ---- 功能图标 ----
        FeatureRow(
            speed = speed,
            loopMode = loopMode,
            context = context,
            onOpenSheet = onOpenSheet,
            onScreenshot = onScreenshot,
            onEnterPip = onEnterPip,
        )
    }
}

/** 进度条。只有 `position` 变化会重组这一行。 */
@Composable
private fun ProgressRow(
    positionMs: () -> Long,
    durationMs: () -> Long,
    onSeekTo: (Long) -> Unit,
) {
    // 拖动期间不能让 time-pos 把滑块拽回去，所以本地先接管
    var scrubbing by remember { mutableStateOf(false) }
    var scrubValue by remember { mutableFloatStateOf(0f) }

    val duration = durationMs().coerceAtLeast(1L)
    val shownPosition = if (scrubbing) (scrubValue * duration).toLong() else positionMs()
    val sliderValue = if (scrubbing) {
        scrubValue
    } else {
        (positionMs().toFloat() / duration.toFloat()).coerceIn(0f, 1f)
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = Formatters.position(shownPosition),
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
        )
        Slider(
            value = sliderValue,
            onValueChange = {
                scrubbing = true
                scrubValue = it
            },
            onValueChangeFinished = {
                onSeekTo((scrubValue * duration).toLong())
                scrubbing = false
            },
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 10.dp),
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = Color.White,
                inactiveTrackColor = Color.White.copy(alpha = 0.3f),
            ),
        )
        Text(
            text = Formatters.duration(durationMs()),
            color = Color.White.copy(alpha = 0.8f),
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

/** 传输控制行。进度走秒不会碰到这里。 */
@Composable
private fun TransportRow(
    paused: () -> Boolean,
    enabled: () -> Boolean,
    onTogglePlay: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeekBy: (Long) -> Unit,
) {
    val context = LocalContext.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onPrevious, enabled = enabled()) {
            Icon(Icons.Filled.SkipPrevious, contentDescription = context.getString(R.string.player_prev), tint = Color.White)
        }
        IconButton(onClick = { onSeekBy(-DEFAULT_SEEK_STEP_MS) }, enabled = enabled()) {
            Icon(Icons.Filled.Replay10, contentDescription = context.getString(R.string.player_seek_back, 10), tint = Color.White)
        }
        // 播放/暂停是最高频操作，单独包一层：只有 paused 真正翻转时才重组图标。
        PlayPauseButton(paused = paused, onTogglePlay = onTogglePlay)
        IconButton(onClick = { onSeekBy(DEFAULT_SEEK_STEP_MS) }, enabled = enabled()) {
            Icon(Icons.Filled.Forward10, contentDescription = context.getString(R.string.player_seek_forward, 10), tint = Color.White)
        }
        IconButton(onClick = onNext, enabled = enabled()) {
            Icon(Icons.Filled.SkipNext, contentDescription = context.getString(R.string.player_next), tint = Color.White)
        }
    }
}

/**
 * 播放/暂停按钮。
 *
 * 只依赖 `paused` 这一位状态：进度更新不会重建这个按钮。
 *
 * 注意「点了没反应」的真正原因不在这个按钮上 —— 是上层曾经用
 * `derivedStateOf { stateFlow.value.paused }` 取值，而那个值永远不会更新。
 * 现在 `paused()` 读的是 [PlayerStateObserver] 里按字段派生的状态，
 * 读在这里、失效也精确到这里。
 */
@Composable
private fun PlayPauseButton(paused: () -> Boolean, onTogglePlay: () -> Unit) {
    val context = LocalContext.current
    val isPaused = paused()
    FilledIconButton(onClick = onTogglePlay) {
        Icon(
            imageVector = if (isPaused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
            contentDescription = context.getString(
                if (isPaused) R.string.player_play else R.string.player_pause,
            ),
        )
    }
}

/** 倍速 / 音轨 / 字幕 / 比例 / 循环 / 截图 / 画中画 */
@Composable
private fun FeatureRow(
    speed: () -> Float,
    loopMode: () -> Int,
    context: android.content.Context,
    onOpenSheet: (PlayerSheet) -> Unit,
    onScreenshot: () -> Unit,
    onEnterPip: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ControlIcon(Icons.Outlined.Speed, Formatters.speed(speed())) {
            onOpenSheet(PlayerSheet.SPEED)
        }
        ControlIcon(Icons.Outlined.MusicNote, context.getString(R.string.player_audio_track)) {
            onOpenSheet(PlayerSheet.AUDIO_TRACK)
        }
        ControlIcon(Icons.Outlined.Subtitles, context.getString(R.string.player_sub_track)) {
            onOpenSheet(PlayerSheet.SUBTITLE_TRACK)
        }
        ControlIcon(Icons.Outlined.AspectRatio, context.getString(R.string.player_aspect)) {
            onOpenSheet(PlayerSheet.ASPECT)
        }
        ControlIcon(Icons.Outlined.Loop, context.getString(loopLabelRes(loopMode()))) {
            onOpenSheet(PlayerSheet.LOOP)
        }
        ControlIcon(Icons.Outlined.Timer, context.getString(R.string.player_sub_delay)) {
            onOpenSheet(PlayerSheet.SUBTITLE_DELAY)
        }
        ControlIcon(Icons.Outlined.PhotoCamera, context.getString(R.string.player_screenshot), onScreenshot)
        ControlIcon(Icons.Outlined.PictureInPictureAlt, context.getString(R.string.player_pip), onEnterPip)
    }
}

@Composable
private fun ControlIcon(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier.width(64.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        IconButton(onClick = onClick) {
            Icon(icon, contentDescription = label, tint = Color.White)
        }
        Text(
            text = label,
            color = Color.White.copy(alpha = 0.85f),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ============================================================== 工具 ====

private fun loopLabelRes(mode: Int): Int = when (mode) {
    1 -> R.string.loop_all
    2 -> R.string.loop_one
    else -> R.string.loop_off
}

private fun readBrightness(activity: Activity?): Float {
    val value = activity?.window?.attributes?.screenBrightness ?: -1f
    return if (value < 0f) 0.5f else value
}

private fun applyBrightness(activity: Activity?, value: Float) {
    val window = activity?.window ?: return
    val attributes = window.attributes
    attributes.screenBrightness = value
    window.attributes = attributes
}

private fun enterPip(activity: Activity?, state: PlayerState) {
    val target = activity ?: return
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val builder = android.app.PictureInPictureParams.Builder()
    if (state.videoWidth > 0 && state.videoHeight > 0) {
        builder.setAspectRatio(Rational(state.videoWidth, state.videoHeight))
    }
    runCatching { target.enterPictureInPictureMode(builder.build()) }
}

private fun sheetTitle(context: android.content.Context, kind: PlayerSheet): String = when (kind) {
    PlayerSheet.SPEED -> context.getString(R.string.player_speed)
    PlayerSheet.AUDIO_TRACK -> context.getString(R.string.player_audio_track)
    PlayerSheet.VIDEO_TRACK -> context.getString(R.string.player_video_track)
    PlayerSheet.SUBTITLE_TRACK -> context.getString(R.string.player_sub_track)
    PlayerSheet.ASPECT -> context.getString(R.string.player_aspect)
    PlayerSheet.LOOP -> context.getString(R.string.player_loop)
    PlayerSheet.SUBTITLE_DELAY -> context.getString(R.string.player_sub_delay)
    PlayerSheet.AUDIO_DELAY -> context.getString(R.string.player_audio_delay)
}

private fun sheetOptions(
    kind: PlayerSheet,
    state: PlayerState,
    player: MpvPlayer,
    currentAspect: String,
    onAspectChange: (String) -> Unit,
    onLoadSubtitle: () -> Unit,
    onDismiss: () -> Unit,
): List<SheetOption> = when (kind) {
    PlayerSheet.SPEED -> SPEED_VALUES.map { value ->
        SheetOption(
            label = Formatters.speed(value),
            selected = abs(state.speed - value) < 0.01f,
            onClick = {
                player.setSpeed(value)
                onDismiss()
            },
        )
    }

    PlayerSheet.AUDIO_TRACK -> trackOptions(
        tracks = state.audioTracks,
        selectedId = state.audioTrackId,
        offLabel = "关闭",
        onSelect = { id ->
            player.setAudioTrack(id)
            onDismiss()
        },
    )

    PlayerSheet.VIDEO_TRACK -> trackOptions(
        tracks = state.videoTracks,
        selectedId = state.videoTrackId,
        offLabel = "关闭（只听声音）",
        onSelect = { id ->
            player.setVideoTrack(id)
            onDismiss()
        },
    )

    PlayerSheet.SUBTITLE_TRACK -> buildList {
        addAll(
            trackOptions(
                tracks = state.subtitleTracks,
                selectedId = state.subtitleTrackId,
                offLabel = "关闭",
                onSelect = { id ->
                    player.setSubtitleTrack(id)
                    onDismiss()
                },
            ),
        )
        add(
            SheetOption(
                label = "加载外部字幕…",
                onClick = {
                    onDismiss()
                    onLoadSubtitle()
                },
            ),
        )
    }

    PlayerSheet.ASPECT -> ASPECT_OPTIONS.map { option ->
        SheetOption(
            label = option.label,
            selected = currentAspect == option.value,
            onClick = {
                player.setAspectOverride(option.value)
                onAspectChange(option.value)
                onDismiss()
            },
        )
    }

    PlayerSheet.LOOP -> listOf(
        Triple(0, "不循环", R.string.loop_off),
        Triple(1, "列表循环", R.string.loop_all),
        Triple(2, "单曲循环", R.string.loop_one),
    ).map { (mode, label, _) ->
        SheetOption(
            label = label,
            selected = state.loopMode == mode,
            onClick = {
                player.setLoopMode(mode)
                onDismiss()
            },
        )
    }

    PlayerSheet.SUBTITLE_DELAY -> DELAY_VALUES.map { value ->
        SheetOption(
            label = Formatters.delay(value),
            selected = abs(state.subtitleDelay - value) < 0.001,
            onClick = {
                player.setSubtitleDelay(value)
                onDismiss()
            },
        )
    }

    PlayerSheet.AUDIO_DELAY -> DELAY_VALUES.map { value ->
        SheetOption(
            label = Formatters.delay(value),
            selected = abs(state.audioDelay - value) < 0.001,
            onClick = {
                player.setAudioDelay(value)
                onDismiss()
            },
        )
    }
}

private fun trackOptions(
    tracks: List<MpvTrack>,
    selectedId: Int,
    offLabel: String,
    onSelect: (Int) -> Unit,
): List<SheetOption> = buildList {
    add(SheetOption(label = offLabel, selected = selectedId < 0, onClick = { onSelect(-1) }))
    tracks.forEach { track ->
        add(
            SheetOption(
                label = track.displayName,
                detail = if (track.isExternal) "外部" else null,
                selected = selectedId == track.id,
                onClick = { onSelect(track.id) },
            ),
        )
    }
}

private const val ASPECT_DEFAULT = "no"
private const val DEFAULT_SEEK_STEP_MS = 10_000L
private const val MAX_VOLUME = 150f

private val SPEED_VALUES = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f, 4f)

private val DELAY_VALUES = listOf(-2.0, -1.0, -0.5, -0.1, 0.0, 0.1, 0.5, 1.0, 2.0)

private val ASPECT_OPTIONS = listOf(
    AspectOption("跟随视频", "no"),
    AspectOption("拉伸铺满", "-1"),
    AspectOption("16:9", "16:9"),
    AspectOption("4:3", "4:3"),
    AspectOption("21:9", "21:9"),
    AspectOption("1:1", "1:1"),
)
