package com.zhiwei.xplayer.ui.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zhiwei.xplayer.core.data.SettingsRepository
import com.zhiwei.xplayer.core.device.SystemHardwareProbe
import com.zhiwei.xplayer.core.device.SystemHardwareSnapshot
import com.zhiwei.xplayer.core.mpv.MpvPlayer
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class SystemInfoUiState(
    val loading: Boolean = true,
    val snapshot: SystemHardwareSnapshot? = null,
    val error: String? = null,
)

@HiltViewModel
class SystemInfoViewModel @Inject constructor(
    private val hardwareProbe: SystemHardwareProbe,
    settingsRepository: SettingsRepository,
    player: MpvPlayer,
) : ViewModel() {

    private val _hardware = MutableStateFlow(SystemInfoUiState())
    val hardware: StateFlow<SystemInfoUiState> = _hardware.asStateFlow()

    val settings = settingsRepository.settings
    val playerState = player.state

    private var scanJob: Job? = null

    init {
        refresh()
    }

    fun refresh() {
        if (scanJob?.isActive == true) return
        _hardware.value = _hardware.value.copy(loading = true, error = null)
        scanJob = viewModelScope.launch {
            try {
                val snapshot = withContext(Dispatchers.Default) {
                    hardwareProbe.probe()
                }
                _hardware.value = SystemInfoUiState(
                    loading = false,
                    snapshot = snapshot,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _hardware.value = _hardware.value.copy(
                    loading = false,
                    error = error.message ?: error::class.java.simpleName,
                )
            } finally {
                scanJob = null
            }
        }
    }
}
