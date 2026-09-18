package com.zhiwei.xplayer.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.kyant.backdrop.Backdrop
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
import com.zhiwei.xplayer.ui.player.PlayerScreen
import com.zhiwei.xplayer.ui.screen.FolderScreen
import com.zhiwei.xplayer.ui.screen.HistoryScreen
import com.zhiwei.xplayer.ui.screen.HomeScreen
import com.zhiwei.xplayer.ui.screen.LibraryScreen
import com.zhiwei.xplayer.ui.screen.LogScreen
import com.zhiwei.xplayer.ui.screen.SettingsScreen
import com.zhiwei.xplayer.ui.screen.StreamScreen
import com.zhiwei.xplayer.ui.theme.AppBackground
import com.zhiwei.xplayer.ui.theme.liquidGlass
import com.zhiwei.xplayer.ui.theme.rememberAppBackdrop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
        AppBackground(backdrop) {
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
 */
@Composable
private fun GlassBottomBar(
    navController: NavHostController,
    destination: NavDestination?,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            // 修饰符顺序有讲究：先避开手势条，再留出悬浮的空白，最后才是 liquidGlass ——
            // 它写在内层，玻璃只覆盖底栏本身，不会把外面的留白也涂上。
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = BOTTOM_BAR_SIDE_MARGIN, vertical = BOTTOM_BAR_MARGIN)
            .height(BOTTOM_BAR_HEIGHT)
            .liquidGlass(
                backdrop = backdrop,
                shape = RoundedCornerShape(26.dp),
                blurRadius = 22.dp,
                lensAmount = 12.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TOP_LEVEL_TABS.forEach { tab ->
            val selected = destination?.hasRoute(tab.route::class) == true
            GlassTab(
                tab = tab,
                selected = selected,
                backdrop = backdrop,
                modifier = Modifier.weight(1f).fillMaxHeight(),
                onClick = {
                    if (!selected) {
                        navController.navigate(tab.route) {
                            popUpTo(HomeRoute) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                },
            )
        }
    }
}

/**
 * 单个底栏项。
 *
 * 选中时在图标与文字后面垫一层胶囊玻璃。它引用的是同一个 backdrop，于是会再采一次
 * 底栏背后的内容 —— 看上去就是叠在玻璃上的一小片玻璃，而不是一块纯色高亮。
 */
@Composable
private fun GlassTab(
    tab: TopLevelTab,
    selected: Boolean,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier.clickable(role = Role.Tab, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .padding(horizontal = 6.dp, vertical = 7.dp)
                    .liquidGlass(
                        backdrop = backdrop,
                        shape = RoundedCornerShape(percent = 50),
                        blurRadius = 10.dp,
                        lensAmount = 8.dp,
                    ),
            )
        }

        val tint = if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
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
    }
}

private fun titleFor(destination: NavDestination?): String = when {
    destination == null -> "XPlayer"
    destination.hasRoute(HomeRoute::class) -> "XPlayer"
    destination.hasRoute(LibraryRoute::class) -> "媒体库"
    destination.hasRoute(FolderRoute::class) -> "文件夹"
    destination.hasRoute(SettingsRoute::class) -> "设置"
    destination.hasRoute(StreamRoute::class) -> "网络串流"
    destination.hasRoute(HistoryRoute::class) -> "播放历史"
    destination.hasRoute(LogRoute::class) -> "运行日志"
    destination.hasRoute(PlayerRoute::class) -> "正在播放"
    else -> "XPlayer"
}
