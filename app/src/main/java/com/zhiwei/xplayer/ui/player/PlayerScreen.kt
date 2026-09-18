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
import androidx.compose.foundation.layout.Spacer
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
import com.zhiwei.xplayer.ui.theme.rememberAppBackdrop
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
    val state by viewModel.state.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val backdrop = rememberAppBackdrop()

    var controlsVisible by remember { mutableStateOf(true) }
    var sheet by remember { mutableStateOf<PlayerSheet?>(null) }
    var hint by remember { mutableStateOf<String?>(null) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var aspect by remember { mutableStateOf(ASPECT_DEFAULT) }

    // 长按加速：mpv 上报的 speed 会被长按改掉，所以必须自己记住按下去之前的倍速，
    // 否则松手时会把「加速后的倍速」当成原速存下来，倍速就永久变了。
    var boosted by remember { mutableStateOf(false) }
    var speedBeforeBoost by remember { mutableFloatStateOf(1f) }

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

    // 手势提示自动消失
    LaunchedEffect(hint) {
        if (hint != null) {
            delay(HINT_DURATION_MS)
            hint = null
        }
    }

    // 播放结束把控制层亮出来，否则用户面对一张静止画面无从下手
    LaunchedEffect(Unit) {
        viewModel.player.ended.collect { controlsVisible = true }
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
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { controlsVisible = !controlsVisible },
                        onDoubleTap = { viewModel.togglePlayPause() },
                        onLongPress = {
                            viewModel.player.setSpeed(settings.longPressSpeed)
                            hint = "${Formatters.speed(settings.longPressSpeed)} 快进中"
                        },
                        onPress = {
                            tryAwaitRelease()
                            // 长按结束恢复原速；普通点击走到这里时倍速本来就是原值，无副作用
                            viewModel.player.setSpeed(state.speed)
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
                            startPosition = state.positionMs
                            brightness = readBrightness(activity)
                            volume = state.volume.toFloat()
                        }

                        when (mode) {
                            DragMode.SEEK -> {
                                val stepMs = settings.seekStepSeconds * 1000f
                                val deltaMs = (total.x / size.width.toFloat() * stepMs).toLong()
                                val duration = state.durationMs
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

        // ③ 缓冲指示
        if (state.buffering || (!state.idle && state.durationMs == 0L)) {
            CircularProgressIndicator(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(44.dp),
                color = Color.White,
            )
        }

        // ④ 手势提示
        hint?.let { text ->
            Text(
                text = text,
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .align(Alignment.Center)
                    .playerGlass(backdrop, RoundedCornerShape(16.dp), blurRadius = 18.dp, lensAmount = 8.dp)
                    .padding(horizontal = 18.dp, vertical = 10.dp),
            )
        }

        // ⑤ 错误提示
        state.error?.let { message ->
            Text(
                text = context.getString(R.string.player_error, message),
                color = Color.White,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .playerGlass(backdrop, RoundedCornerShape(14.dp), blurRadius = 16.dp, lensAmount = 6.dp)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }

        // ⑥ 控制层
        if (controlsVisible) {
            PlayerTopBar(
                title = state.mediaTitle.ifBlank { state.source?.title.orEmpty() },
                subtitle = buildSubtitle(state),
                hwdecActive = state.hwdecActive,
                backdrop = backdrop,
                onBack = {
                    if (!settings.backgroundPlayback) viewModel.stopPlayback()
                    onBack()
                },
                modifier = Modifier.align(Alignment.TopCenter),
            )

            PlayerControlPanel(
                state = state,
                backdrop = backdrop,
                onTogglePlay = { viewModel.togglePlayPause() },
                onPrevious = { viewModel.player.previous() },
                onNext = { viewModel.player.next() },
                onSeekBy = { delta -> viewModel.player.seekBy(delta) },
                onSeekTo = { position -> viewModel.player.seekTo(position) },
                onOpenSheet = { sheet = it },
                onScreenshot = { viewModel.screenshot() },
                onEnterPip = { enterPip(activity, state) },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }

    // ------------------------------------------------------------ 面板 ----
    val activeSheet = sheet
    if (activeSheet != null) {
        OptionSheet(
            title = sheetTitle(context, activeSheet),
            options = sheetOptions(
                kind = activeSheet,
                state = state,
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

// ============================================================== 顶栏 ====

@Composable
private fun PlayerTopBar(
    title: String,
    subtitle: String,
    hwdecActive: String,
    backdrop: com.kyant.backdrop.Backdrop,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .playerGlass(backdrop, RoundedCornerShape(20.dp), blurRadius = 20.dp, lensAmount = 10.dp)
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
                text = title,
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    color = Color.White.copy(alpha = 0.75f),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (hwdecActive != "no" && hwdecActive.isNotBlank()) {
            Text(
                text = hwdecActive,
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

@Composable
private fun PlayerControlPanel(
    state: PlayerState,
    backdrop: com.kyant.backdrop.Backdrop,
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
    val duration = state.durationMs.coerceAtLeast(1L)

    // 拖动进度条期间不能让 time-pos 把滑块拽回去，所以本地先接管
    var scrubbing by remember { mutableStateOf(false) }
    var scrubValue by remember { mutableFloatStateOf(0f) }
    val sliderValue = if (scrubbing) {
        scrubValue
    } else {
        (state.positionMs.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .playerGlass(backdrop, RoundedCornerShape(24.dp), blurRadius = 26.dp, lensAmount = 12.dp)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        // ---- 进度 ----
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = Formatters.position(if (scrubbing) (scrubValue * duration).toLong() else state.positionMs),
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
                text = Formatters.duration(state.durationMs),
                color = Color.White.copy(alpha = 0.8f),
                style = MaterialTheme.typography.labelSmall,
            )
        }

        // ---- 传输控制 ----
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onPrevious) {
                Icon(Icons.Filled.SkipPrevious, contentDescription = context.getString(R.string.player_prev), tint = Color.White)
            }
            IconButton(onClick = { onSeekBy(-DEFAULT_SEEK_STEP_MS) }) {
                Icon(Icons.Filled.Replay10, contentDescription = context.getString(R.string.player_seek_back, 10), tint = Color.White)
            }
            FilledIconButton(onClick = onTogglePlay) {
                Icon(
                    imageVector = if (state.paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                    contentDescription = context.getString(
                        if (state.paused) R.string.player_play else R.string.player_pause,
                    ),
                )
            }
            IconButton(onClick = { onSeekBy(DEFAULT_SEEK_STEP_MS) }) {
                Icon(Icons.Filled.Forward10, contentDescription = context.getString(R.string.player_seek_forward, 10), tint = Color.White)
            }
            IconButton(onClick = onNext) {
                Icon(Icons.Filled.SkipNext, contentDescription = context.getString(R.string.player_next), tint = Color.White)
            }
        }

        // ---- 功能图标 ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ControlIcon(Icons.Outlined.Speed, Formatters.speed(state.speed)) {
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
            ControlIcon(Icons.Outlined.Loop, loopLabelRes(state.loopMode).let { context.getString(it) }) {
                onOpenSheet(PlayerSheet.LOOP)
            }
            ControlIcon(Icons.Outlined.Timer, context.getString(R.string.player_sub_delay)) {
                onOpenSheet(PlayerSheet.SUBTITLE_DELAY)
            }
            ControlIcon(Icons.Outlined.PhotoCamera, context.getString(R.string.player_screenshot), onScreenshot)
            ControlIcon(Icons.Outlined.PictureInPictureAlt, context.getString(R.string.player_pip), onEnterPip)
        }
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
