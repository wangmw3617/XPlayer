package com.zhiwei.xplayer.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.spring
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.fastRoundToInt
import androidx.compose.ui.util.lerp
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.zhiwei.xplayer.ui.theme.glass.CapsuleShape
import com.zhiwei.xplayer.core.mpv.PlaybackSource
import com.zhiwei.xplayer.ui.components.queryDisplayName
import com.zhiwei.xplayer.ui.nav.FolderRoute
import com.zhiwei.xplayer.ui.nav.HistoryRoute
import com.zhiwei.xplayer.ui.nav.HomeRoute
import com.zhiwei.xplayer.ui.nav.LibraryRoute
import com.zhiwei.xplayer.ui.nav.LogRoute
import com.zhiwei.xplayer.ui.nav.PlayerRoute
import com.zhiwei.xplayer.ui.nav.SettingsRoute
import com.zhiwei.xplayer.ui.nav.StreamRoute
import com.zhiwei.xplayer.ui.nav.WebDavRoute
import com.zhiwei.xplayer.ui.player.PlayerScreen
import com.zhiwei.xplayer.ui.screen.FolderScreen
import com.zhiwei.xplayer.ui.screen.HistoryScreen
import com.zhiwei.xplayer.ui.screen.HomeScreen
import com.zhiwei.xplayer.ui.screen.LibraryScreen
import com.zhiwei.xplayer.ui.screen.LogScreen
import com.zhiwei.xplayer.ui.screen.SettingsScreen
import com.zhiwei.xplayer.ui.screen.StreamScreen
import com.zhiwei.xplayer.ui.screen.WebDavBrowserScreen
import com.zhiwei.xplayer.ui.theme.AppBackground
import com.zhiwei.xplayer.ui.theme.GlassDefaults
import com.zhiwei.xplayer.ui.theme.glass.DampedDragAnimation
import com.zhiwei.xplayer.ui.theme.glass.InteractiveHighlight
import com.zhiwei.xplayer.ui.theme.glass.LiquidBottomTab
import com.zhiwei.xplayer.ui.theme.glass.LocalLiquidBottomTabScale
import com.zhiwei.xplayer.ui.theme.rememberAppBackdrop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.sign

private data class TopLevelTab(
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val route: Any,
)

private val TOP_LEVEL_TABS = listOf(
    TopLevelTab("首页", Icons.Filled.Home, Icons.Outlined.Home, HomeRoute),
    TopLevelTab("媒体库", Icons.Filled.VideoLibrary, Icons.Outlined.VideoLibrary, LibraryRoute),
    TopLevelTab("文件夹", Icons.Filled.Folder, Icons.Outlined.Folder, FolderRoute),
    TopLevelTab("设置", Icons.Filled.Settings, Icons.Outlined.Settings, SettingsRoute),
)

/** 悬浮底栏的高度（不含外留白与手势条内边距） */
private val BOTTOM_BAR_HEIGHT = 64.dp
private val BOTTOM_BAR_MARGIN = 10.dp
private val BOTTOM_BAR_SIDE_MARGIN = 14.dp

@Composable
fun AppRoot() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val destination = backStackEntry?.destination

    val isPlayer = destination?.hasRoute(PlayerRoute::class) == true
    val isTopLevel = TOP_LEVEL_TABS.any { destination?.hasRoute(it.route::class) == true }
    val title = titleFor(destination)

    val backdrop = rememberAppBackdrop()

    // Android 13+ 需要显式申请通知权限，否则后台播放的通知不会显示
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* 拒绝也不影响播放，只是看不到通知 */ }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // 从系统「分享 / 打开方式」进来的媒体：查一次显示名再跳播放页。
    // 查询要读 ContentResolver，放到 IO 线程，避免分享大文件时卡住首帧。
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        SharedInput.pending.collect { uri ->
            if (uri == null) return@collect
            SharedInput.consume()
            val title = withContext(Dispatchers.IO) { queryDisplayName(context, uri) }
            PendingPlayback.request(PlaybackSource(uri = uri.toString(), title = title))
            navController.navigate(PlayerRoute) { launchSingleTop = true }
        }
    }

    Box(Modifier.fillMaxSize()) {
        // ① 采样层：背景色斑 + 全部页面内容。
        //
        // 内容铺满整屏，不给底栏预留位置 —— 这样它能滚到悬浮底栏下面，玻璃才有
        // 东西可以模糊。各一级页面自己在内容末尾留出底部空档，避免最后一项被挡住。
        //
        // 播放页例外（sampling = false）：底栏在播放页不显示，没人消费这份录制，
        // 而录制本身是全屏离屏渲染、每帧都要重来一次。详见 AppBackground 的注释。
        AppBackground(backdrop, sampling = !isPlayer) {
            Column(Modifier.fillMaxSize()) {
                // 播放页不要顶栏：视频要能顶到状态栏下面
                if (!isPlayer) {
                    TopAppBar(
                        title = {
                            Text(text = title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                        navigationIcon = {
                            if (!isTopLevel) {
                                IconButton(onClick = { navController.popBackStack() }) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = "返回",
                                    )
                                }
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                    )
                }

                NavHost(
                    navController = navController,
                    startDestination = HomeRoute,
                    modifier = Modifier.weight(1f),
                ) {
                    composable<HomeRoute> {
                        HomeScreen(
                            onOpen = { route -> navController.navigate(route) },
                            onPlay = { source -> playFrom(navController, source) },
                        )
                    }
                    composable<LibraryRoute> {
                        LibraryScreen(
                            onPlay = { source -> playFrom(navController, source) },
                            onOpen = { route -> navController.navigate(route) },
                        )
                    }
                    composable<FolderRoute> { FolderScreen(onPlay = { source -> playFrom(navController, source) }) }
                    composable<SettingsRoute> { SettingsScreen() }
                    composable<StreamRoute> { StreamScreen(onPlay = { source -> playFrom(navController, source) }) }
                    composable<WebDavRoute> { WebDavBrowserScreen(onPlay = { source -> playFrom(navController, source) }) }
                    composable<HistoryRoute> {
                        HistoryScreen(onPlay = { source -> playFrom(navController, source) })
                    }
                    composable<LogRoute> { LogScreen() }
                    composable<PlayerRoute> { PlayerScreen(onBack = { navController.popBackStack() }) }
                }
            }
        }

        // ② 玻璃层。必须是采样层的兄弟且排在后面：被包在采样层里的话，
        //    玻璃采到的是自己，会递归成一团糊。
        if (isTopLevel) {
            GlassBottomBar(
                navController = navController,
                destination = destination,
                backdrop = backdrop,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

/** 统一的「播放并跳转」入口：所有页面点条目都走这里，避免各页各写一遍 */
private fun playFrom(
    navController: NavHostController,
    source: com.zhiwei.xplayer.core.mpv.PlaybackSource,
) {
    PendingPlayback.request(source)
    // launchSingleTop：播放页已经在栈顶时就复用它，否则连点几个条目会堆一串播放页
    navController.navigate(PlayerRoute) { launchSingleTop = true }
}

/**
 * 悬浮玻璃底栏。
 *
 * 做成悬浮（左右与底部都留白、四角圆角）而不是通栏贴边：四周留白能让背景与内容
 * 从边上透出来，玻璃「浮在内容之上」的观感才成立；通栏贴边时玻璃只和屏幕边缘
 * 相接，看起来就像一块实心色板。
 *
 * ## 结构照抄库自带 catalog 的 LiquidBottomTabs
 *
 * 关键是**三层同构的兄弟节点**，顺序不能乱：
 *
 * 1. **玻璃底板**：真正画模糊+折射的那一层，内容也在这一层（可见的那份）。
 * 2. **着色内容层**：和第一层完全一样的内容，但 `alpha=0`、`clearAndSetSemantics`
 *    （不参与无障碍、不响应点击），只用来「被采样」：它把自己画进 [tabsBackdrop]，
 *    而它的 `drawBackdrop` 里用了 `ColorFilter.tint(accentColor)` ——
 *    于是得到的是一份**被主色染过、但形状与底栏完全吻合**的图层。
 * 3. **移动指示器**：采样 `rememberCombinedBackdrop(backdrop, tabsBackdrop)`，
 *    也就是「背景 + 上面那份染色层」的合成。指示器滑到哪个 tab，就在哪个 tab
 *    的图标文字上叠一层玻璃 —— 因为染色层里有那个 tab 的图标，折射出来的
 *    正好是「放大的彩色图标」，这就是原版那种「图标被液态玻璃放大扭曲」的效果。
 *
 * 之前那版是「玻璃条 + 在选中项内部再画一层胶囊玻璃」，问题在于：
 * 内层采样的是整块 backdrop，得到的是背景的一部分，
 * 所以高亮块永远只是一片模糊色，永远不会像 catalog 那样把图标「放大透过去」。
 */
@Composable
private fun GlassBottomBar(
    navController: NavHostController,
    destination: NavDestination?,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
) {
    val isLightTheme = !isSystemInDarkTheme()
    val accentColor = if (isLightTheme) Color(0xFF0088FF) else Color(0xFF0091FF)
    val containerColor = if (isLightTheme) {
        Color(0xFFFAFAFA).copy(alpha = 0.4f)
    } else {
        Color(0xFF121212).copy(alpha = 0.4f)
    }

    val tabs = TOP_LEVEL_TABS
    val tabsCount = tabs.size
    // 当前 destination 对应的 tab 下标；不在任何 tab 上（比如播放页）时算作第 0 个
    val selectedIndex = tabs.indexOfFirst { destination?.hasRoute(it.route::class) == true }
        .coerceAtLeast(0)

    // 第 2 层用来「被采样」的图层
    val tabsBackdrop = rememberLayerBackdrop()

    val animationScope = rememberCoroutineScope()
    var currentIndex by remember { mutableIntStateOf(selectedIndex) }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = BOTTOM_BAR_SIDE_MARGIN, vertical = BOTTOM_BAR_MARGIN),
        contentAlignment = Alignment.CenterStart,
    ) {
        val density = LocalDensity.current
        val tabWidth = with(density) {
            (constraints.maxWidth.toFloat() - 8f.dp.toPx()) / tabsCount
        }

        // 拖动整条底栏时，底板做一点点反向位移，制造「橡皮筋」手感
        val offsetAnimation = remember { Animatable(0f) }
        val panelOffset by remember(density) {
            derivedStateOf {
                val fraction = (offsetAnimation.value / constraints.maxWidth).fastCoerceIn(-1f, 1f)
                with(density) { 4f.dp.toPx() * fraction.sign * EaseOut.transform(abs(fraction)) }
            }
        }
        val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr

        val dampedDragAnimation = remember(animationScope) {
            DampedDragAnimation(
                animationScope = animationScope,
                initialValue = selectedIndex.toFloat(),
                valueRange = 0f..(tabsCount - 1).toFloat(),
                visibilityThreshold = 0.001f,
                initialScale = 1f,
                pressedScale = 78f / 56f,
                onDragStarted = {},
                onDragStopped = {
                    val target = targetValue.fastRoundToInt().fastCoerceIn(0, tabsCount - 1)
                    currentIndex = target
                    animateToValue(target.toFloat())
                    animationScope.launch {
                        offsetAnimation.animateTo(0f, spring(1f, 300f, 0.5f))
                    }
                },
                onDrag = { _, dragAmount ->
                    updateValue(
                        (targetValue + dragAmount.x / tabWidth * if (isLtr) 1f else -1f)
                            .fastCoerceIn(0f, (tabsCount - 1).toFloat()),
                    )
                    animationScope.launch {
                        offsetAnimation.snapTo(offsetAnimation.value + dragAmount.x)
                    }
                },
            )
        }

        // 外部（点 tab / 返回键）导致的选中变化要同步给动画
        LaunchedEffect(selectedIndex) {
            currentIndex = selectedIndex
        }
        LaunchedEffect(dampedDragAnimation) {
            snapshotFlow { currentIndex }
                .drop(1)
                .collectLatest { index ->
                    dampedDragAnimation.animateToValue(index.toFloat())
                }
        }

        val interactiveHighlight = remember(animationScope) {
            InteractiveHighlight(
                animationScope = animationScope,
                position = { size, _ ->
                    Offset(
                        if (isLtr) {
                            (dampedDragAnimation.value + 0.5f) * tabWidth + panelOffset
                        } else {
                            size.width - (dampedDragAnimation.value + 0.5f) * tabWidth + panelOffset
                        },
                        size.height / 2f,
                    )
                },
            )
        }

        val tabContent: @Composable RowScope.() -> Unit = {
            tabs.forEach { tab ->
                LiquidBottomTab(
                    onClick = {
                        val index = tabs.indexOf(tab)
                        if (index != selectedIndex) {
                            currentIndex = index
                            navController.navigate(tab.route) {
                                popUpTo(HomeRoute) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    },
                ) {
                    TopLevelTabLabel(
                        tab = tab,
                        selected = destination?.hasRoute(tab.route::class) == true,
                    )
                }
            }
        }

        // ---- 第 1 层：玻璃底板（可见的那份内容） ----
        Row(
            Modifier
                .graphicsLayer { translationX = panelOffset }
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { CapsuleShape },
                    effects = {
                        vibrancy()
                        blur(8f.dp.toPx())
                        lens(24f.dp.toPx(), 24f.dp.toPx())
                    },
                    layerBlock = {
                        val progress = dampedDragAnimation.pressProgress
                        val scale = lerp(1f, 1f + 16f.dp.toPx() / size.width, progress)
                        scaleX = scale
                        scaleY = scale
                    },
                    highlight = { GlassDefaults.highlight },
                    shadow = { GlassDefaults.shadow },
                    onDrawSurface = { drawRect(containerColor) },
                )
                .then(interactiveHighlight.modifier)
                .height(BOTTOM_BAR_HEIGHT)
                .fillMaxWidth()
                .padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = tabContent,
        )

        // ---- 第 2 层：同构的「被采样」层。alpha=0 但会画进 tabsBackdrop ----
        CompositionLocalProvider(
            LocalLiquidBottomTabScale provides {
                // 按压时内容放大一点，让「图标透过去被放大」更明显
                lerp(1f, 1.2f, dampedDragAnimation.pressProgress)
            },
        ) {
            Row(
                Modifier
                    .clearAndSetSemantics {}
                    .alpha(0f)
                    .layerBackdrop(tabsBackdrop)
                    .graphicsLayer { translationX = panelOffset }
                    .drawBackdrop(
                        backdrop = backdrop,
                        shape = { CapsuleShape },
                        effects = {
                            val progress = dampedDragAnimation.pressProgress
                            vibrancy()
                            blur(8f.dp.toPx())
                            lens(24f.dp.toPx() * progress, 24f.dp.toPx() * progress)
                        },
                        highlight = {
                            Highlight.Default.copy(alpha = dampedDragAnimation.pressProgress)
                        },
                        onDrawSurface = { drawRect(containerColor) },
                    )
                    .then(interactiveHighlight.modifier)
                    .height(56.dp)
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp)
                    // 染成主色：指示器折射出来的是「彩色的图标」
                    .graphicsLayer(colorFilter = ColorFilter.tint(accentColor)),
                verticalAlignment = Alignment.CenterVertically,
                content = tabContent,
            )
        }

        // ---- 第 3 层：跟随位置的指示器 ----
        Box(
            Modifier
                .padding(horizontal = 4.dp)
                .graphicsLayer {
                    translationX = if (isLtr) {
                        dampedDragAnimation.value * tabWidth + panelOffset
                    } else {
                        size.width - (dampedDragAnimation.value + 1f) * tabWidth + panelOffset
                    }
                }
                .then(interactiveHighlight.gestureModifier)
                .then(dampedDragAnimation.modifier)
                .drawBackdrop(
                    backdrop = rememberCombinedBackdrop(backdrop, tabsBackdrop),
                    shape = { CapsuleShape },
                    effects = {
                        val progress = dampedDragAnimation.pressProgress
                        lens(
                            10f.dp.toPx() * progress,
                            14f.dp.toPx() * progress,
                            chromaticAberration = true,
                        )
                    },
                    highlight = {
                        Highlight.Default.copy(alpha = dampedDragAnimation.pressProgress)
                    },
                    shadow = { Shadow(alpha = dampedDragAnimation.pressProgress) },
                    innerShadow = {
                        InnerShadow(
                            radius = 8.dp * dampedDragAnimation.pressProgress,
                            alpha = dampedDragAnimation.pressProgress,
                        )
                    },
                    layerBlock = {
                        scaleX = dampedDragAnimation.scaleX
                        scaleY = dampedDragAnimation.scaleY
                        // 快速拖动时按速度做拉伸/挤压，像一颗被甩动的液滴
                        val velocity = dampedDragAnimation.velocity / 10f
                        scaleX /= 1f - (velocity * 0.75f).fastCoerceIn(-0.2f, 0.2f)
                        scaleY *= 1f - (velocity * 0.25f).fastCoerceIn(-0.2f, 0.2f)
                    },
                    onDrawSurface = {
                        val progress = dampedDragAnimation.pressProgress
                        // 未按压时铺一层极淡的对比，让指示器在没有交互时也能看出来
                        drawRect(
                            if (isLightTheme) Color.Black.copy(0.1f) else Color.White.copy(0.1f),
                            alpha = 1f - progress,
                        )
                        drawRect(Color.Black.copy(alpha = 0.03f * progress))
                    },
                )
                .height(56.dp)
                .fillMaxWidth(1f / tabsCount),
        )
    }
}

/** 底栏单项的图标 + 文字。第 1、2 层都要画同一份，所以抽出来。 */
@Composable
private fun TopLevelTabLabel(tab: TopLevelTab, selected: Boolean) {
    val tint = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Icon(
        imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
        contentDescription = tab.label,
        tint = tint,
    )
    Spacer(Modifier.height(2.dp))
    Text(
        text = tab.label,
        style = MaterialTheme.typography.labelMedium,
        color = tint,
    )
}

private fun titleFor(destination: NavDestination?): String = when {
    destination == null -> "XPlayer"
    destination.hasRoute(HomeRoute::class) -> "XPlayer"
    destination.hasRoute(LibraryRoute::class) -> "媒体库"
    destination.hasRoute(FolderRoute::class) -> "文件夹"
    destination.hasRoute(SettingsRoute::class) -> "设置"
    destination.hasRoute(StreamRoute::class) -> "网络串流"
    destination.hasRoute(WebDavRoute::class) -> "WebDAV"
    destination.hasRoute(HistoryRoute::class) -> "播放历史"
    destination.hasRoute(LogRoute::class) -> "运行日志"
    destination.hasRoute(PlayerRoute::class) -> "正在播放"
    else -> "XPlayer"
}
