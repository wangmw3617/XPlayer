package com.zhiwei.xplayer.ui.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zhiwei.xplayer.core.data.AppSettings
import com.zhiwei.xplayer.core.data.SettingsRepository
import com.zhiwei.xplayer.core.data.ThemeMode
import com.zhiwei.xplayer.core.mpv.MpvPlayer
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val player: MpvPlayer,
) : ViewModel() {

    val settings: StateFlow<AppSettings> = settingsRepository.settings

    fun setThemeMode(mode: ThemeMode) = mutate { it.copy(themeMode = mode) }

    fun setDynamicColor(enabled: Boolean) = mutate { it.copy(dynamicColor = enabled) }

    fun setHardwareDecoding(enabled: Boolean) = mutate { it.copy(hardwareDecoding = enabled) }

    fun setGpuNext(enabled: Boolean) = mutate { it.copy(gpuNext = enabled) }

    fun setBackgroundPlayback(enabled: Boolean) = mutate { it.copy(backgroundPlayback = enabled) }

    fun setKeepScreenOn(enabled: Boolean) = mutate { it.copy(keepScreenOn = enabled) }

    fun setRememberPosition(enabled: Boolean) = mutate { it.copy(rememberPosition = enabled) }

    fun setAutoLoadSubtitles(enabled: Boolean) = mutate { it.copy(autoLoadSubtitles = enabled) }

    fun setAudioLanguage(value: String) = mutate { it.copy(defaultAudioLanguage = value.trim()) }

    fun setSubtitleLanguage(value: String) = mutate { it.copy(defaultSubtitleLanguage = value.trim()) }

    fun setSeekStep(seconds: Int) = mutate { it.copy(seekStepSeconds = seconds.coerceIn(10, 600)) }

    fun setLongPressSpeed(speed: Float) = mutate { it.copy(longPressSpeed = speed.coerceIn(1f, 8f)) }

    fun resetAll() {
        viewModelScope.launch {
            settingsRepository.reset()
            player.applyRuntimeSettings()
        }
    }

    /**
     * 所有设置改动都走这里。
     *
     * 写完立刻把「能运行时生效」的项同步给 mpv，用户不必重启应用才能看到硬解开关
     * 的变化 —— 这一点在排查花屏时特别重要：用户要能边看边切换。
     */
    private fun mutate(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch {
            settingsRepository.update(transform)
            player.applyRuntimeSettings()
        }
    }
}
