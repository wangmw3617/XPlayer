package com.zhiwei.xplayer.core.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** 主题模式 */
enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
    ;

    companion object {
        fun fromName(name: String?): ThemeMode =
            entries.firstOrNull { it.name == name } ?: SYSTEM
    }
}

/**
 * 应用设置。
 *
 * 全是不可变值 + 默认值，所以「恢复默认」= 丢掉整个 DataStore 文件即可。
 */
data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val hardwareDecoding: Boolean = true,
    val gpuNext: Boolean = true,
    val backgroundPlayback: Boolean = true,
    val keepScreenOn: Boolean = false,
    val rememberPosition: Boolean = true,
    val autoLoadSubtitles: Boolean = true,
    val defaultAudioLanguage: String = "",
    val defaultSubtitleLanguage: String = "",
    /** 横向滑动一屏宽度对应的快进秒数 */
    val seekStepSeconds: Int = 90,
    /** 长按时的临时倍速 */
    val longPressSpeed: Float = 3f,
    /** 0 不循环 / 1 列表循环 / 2 单曲循环 */
    val loopMode: Int = 0,
    /**
     * WebDAV 服务器列表（JSON 数组）。
     *
     * 存 JSON 而不是 DataStore 的 stringSet：一个账号有 4 个字段，
     * 塞进 Set<String> 就得自己拼分隔符，而 URL 与密码里都可能出现分隔符，
     * 一不小心就解析错位。序列化成 JSON 是唯一稳的做法。
     */
    val webDavAccountsJson: String = "",
)

private val Context.settingsDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "xplayer_settings")

private object Keys {
    val THEME_MODE = stringPreferencesKey("theme_mode")
    val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
    val HARDWARE_DECODING = booleanPreferencesKey("hardware_decoding")
    val GPU_NEXT = booleanPreferencesKey("gpu_next")
    val BACKGROUND_PLAYBACK = booleanPreferencesKey("background_playback")
    val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
    val REMEMBER_POSITION = booleanPreferencesKey("remember_position")
    val AUTO_LOAD_SUBTITLES = booleanPreferencesKey("auto_load_subtitles")
    val ALANG = stringPreferencesKey("alang")
    val SLANG = stringPreferencesKey("slang")
    val SEEK_STEP = intPreferencesKey("seek_step_seconds")
    val LONG_PRESS_SPEED = floatPreferencesKey("long_press_speed")
    val LOOP_MODE = intPreferencesKey("loop_mode")
    val WEBDAV_ACCOUNTS = stringPreferencesKey("webdav_accounts")
}

/**
 * 设置仓库。
 *
 * 除了对 UI 暴露 [settings] 这个 Flow，还额外维护一个 [current] 快照：
 * libmpv 的选项必须在 `init()` 之前同步设好，那时没法 `await` 一个 Flow，
 * 只能读快照。
 */
@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    /** 同步快照，供 libmpv 初始化时读取 */
    @Volatile
    var current: AppSettings = AppSettings()
        private set

    init {
        scope.launch {
            context.settingsDataStore.data
                .map { it.toAppSettings() }
                .collect { loaded ->
                    current = loaded
                    _settings.value = loaded
                }
        }
    }

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.settingsDataStore.edit { prefs ->
            val next = transform(prefs.toAppSettings())
            prefs[Keys.THEME_MODE] = next.themeMode.name
            prefs[Keys.DYNAMIC_COLOR] = next.dynamicColor
            prefs[Keys.HARDWARE_DECODING] = next.hardwareDecoding
            prefs[Keys.GPU_NEXT] = next.gpuNext
            prefs[Keys.BACKGROUND_PLAYBACK] = next.backgroundPlayback
            prefs[Keys.KEEP_SCREEN_ON] = next.keepScreenOn
            prefs[Keys.REMEMBER_POSITION] = next.rememberPosition
            prefs[Keys.AUTO_LOAD_SUBTITLES] = next.autoLoadSubtitles
            prefs[Keys.ALANG] = next.defaultAudioLanguage
            prefs[Keys.SLANG] = next.defaultSubtitleLanguage
            prefs[Keys.SEEK_STEP] = next.seekStepSeconds
            prefs[Keys.LONG_PRESS_SPEED] = next.longPressSpeed
            prefs[Keys.LOOP_MODE] = next.loopMode
            prefs[Keys.WEBDAV_ACCOUNTS] = next.webDavAccountsJson
        }
    }

    suspend fun reset() {
        context.settingsDataStore.edit { it.clear() }
    }
}

private fun Preferences.toAppSettings(): AppSettings {
    val defaults = AppSettings()
    return AppSettings(
        themeMode = ThemeMode.fromName(this[Keys.THEME_MODE]),
        dynamicColor = this[Keys.DYNAMIC_COLOR] ?: defaults.dynamicColor,
        hardwareDecoding = this[Keys.HARDWARE_DECODING] ?: defaults.hardwareDecoding,
        gpuNext = this[Keys.GPU_NEXT] ?: defaults.gpuNext,
        backgroundPlayback = this[Keys.BACKGROUND_PLAYBACK] ?: defaults.backgroundPlayback,
        keepScreenOn = this[Keys.KEEP_SCREEN_ON] ?: defaults.keepScreenOn,
        rememberPosition = this[Keys.REMEMBER_POSITION] ?: defaults.rememberPosition,
        autoLoadSubtitles = this[Keys.AUTO_LOAD_SUBTITLES] ?: defaults.autoLoadSubtitles,
        defaultAudioLanguage = this[Keys.ALANG] ?: defaults.defaultAudioLanguage,
        defaultSubtitleLanguage = this[Keys.SLANG] ?: defaults.defaultSubtitleLanguage,
        seekStepSeconds = this[Keys.SEEK_STEP] ?: defaults.seekStepSeconds,
        longPressSpeed = this[Keys.LONG_PRESS_SPEED] ?: defaults.longPressSpeed,
        loopMode = this[Keys.LOOP_MODE] ?: defaults.loopMode,
        webDavAccountsJson = this[Keys.WEBDAV_ACCOUNTS] ?: defaults.webDavAccountsJson,
    )
}
