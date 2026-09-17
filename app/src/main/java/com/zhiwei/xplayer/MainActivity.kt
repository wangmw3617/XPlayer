package com.zhiwei.xplayer

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.zhiwei.xplayer.core.data.AppSettings
import com.zhiwei.xplayer.core.data.SettingsRepository
import com.zhiwei.xplayer.ui.AppRoot
import com.zhiwei.xplayer.ui.SharedInput
import com.zhiwei.xplayer.ui.theme.XPlayerTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var settingsRepository: SettingsRepository

    /** 主题相关设置要在 setContent 之前拿到，用一个轻量 StateFlow 桥接 */
    private val settingsState = MutableStateFlow(AppSettings())

    override fun onCreate(savedInstanceState: Bundle?) {
        // installSplashScreen 必须在 super.onCreate 之前调用
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        handleIntent(intent)

        lifecycleScope.launch {
            settingsRepository.settings.collect { settingsState.value = it }
        }

        setContent {
            val settings by settingsState.collectAsStateWithLifecycle()
            XPlayerTheme(
                themeMode = settings.themeMode,
                dynamicColor = settings.dynamicColor,
            ) {
                AppRoot()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    /**
     * 处理「用 XPlayer 打开」的意图。
     *
     * 必须显式 grantUriPermission：分享过来的 uri 权限默认只在原 Activity 上，
     * 而实际读取它的是 libmpv 的 native 线程（通过 FFmpeg 的 content 协议），
     * 不补一次授权就会在打开时得到 EACCES。
     */
    private fun handleIntent(intent: Intent?) {
        val uri = SharedInput.extract(intent) ?: return
        runCatching {
            grantUriPermission(packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        SharedInput.post(uri)
    }
}
