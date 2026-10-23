package com.zhiwei.xplayer.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.outlined.Brightness6
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zhiwei.xplayer.R
import com.zhiwei.xplayer.core.data.AppSettings
import com.zhiwei.xplayer.ui.components.GlassCard
import com.zhiwei.xplayer.ui.components.InfoRow
import com.zhiwei.xplayer.ui.components.SectionHeader
import com.zhiwei.xplayer.ui.components.rememberResources
import com.zhiwei.xplayer.ui.vm.SettingsViewModel

private enum class GestureNumberDialog {
    SeekStep,
    LongPressSpeed,
    ;

    fun initial(settings: AppSettings): String = when (this) {
        SeekStep -> settings.seekStepSeconds.toString()
        LongPressSpeed -> settings.longPressSpeed.toString()
    }

    fun apply(viewModel: SettingsViewModel, raw: String) {
        when (this) {
            SeekStep -> raw.toIntOrNull()?.let(viewModel::setSeekStep)
            LongPressSpeed -> raw.toFloatOrNull()?.let(viewModel::setLongPressSpeed)
        }
    }

    fun titleRes(): Int = when (this) {
        SeekStep -> R.string.settings_seek_step
        LongPressSpeed -> R.string.settings_long_press_speed
    }
}

@Composable
fun GestureSettingsScreen(viewModel: SettingsViewModel = hiltViewModel()) {
    val resources = rememberResources()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    var numberDialog by remember { mutableStateOf<GestureNumberDialog?>(null) }
    val seekStepSummary = settings.seekStepSeconds.toString() + "s · " +
        resources.getString(R.string.settings_seek_step_desc)

    val dialog = numberDialog
    if (dialog != null) {
        TextInputDialog(
            title = resources.getString(dialog.titleRes()),
            initial = dialog.initial(settings),
            hint = null,
            numeric = true,
            onDismiss = { numberDialog = null },
            onConfirm = { value ->
                dialog.apply(viewModel, value)
                numberDialog = null
            },
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item { SectionHeader(resources.getString(R.string.gesture_touch_controls)) }
        item {
            GlassCard {
                SwitchRow(
                    icon = Icons.Filled.Forward10,
                    title = resources.getString(R.string.gesture_double_tap_seek),
                    subtitle = resources.getString(R.string.gesture_double_tap_seek_desc),
                    checked = settings.doubleTapSeekEnabled,
                    onCheckedChange = viewModel::setDoubleTapSeek,
                )
                SwitchRow(
                    icon = Icons.Outlined.SwapHoriz,
                    title = resources.getString(R.string.gesture_horizontal_seek),
                    subtitle = resources.getString(R.string.gesture_horizontal_seek_desc),
                    checked = settings.seekGestureEnabled,
                    onCheckedChange = viewModel::setSeekGesture,
                )
                SwitchRow(
                    icon = Icons.Outlined.Brightness6,
                    title = resources.getString(R.string.gesture_brightness),
                    subtitle = resources.getString(R.string.gesture_brightness_desc),
                    checked = settings.brightnessGestureEnabled,
                    onCheckedChange = viewModel::setBrightnessGesture,
                )
                SwitchRow(
                    icon = Icons.Outlined.VolumeUp,
                    title = resources.getString(R.string.gesture_volume),
                    subtitle = resources.getString(R.string.gesture_volume_desc),
                    checked = settings.volumeGestureEnabled,
                    onCheckedChange = viewModel::setVolumeGesture,
                )
            }
        }

        item { SectionHeader(resources.getString(R.string.gesture_parameters)) }
        item {
            GlassCard {
                InfoRow(
                    icon = Icons.Outlined.SwapHoriz,
                    title = resources.getString(R.string.settings_seek_step),
                    subtitle = seekStepSummary,
                    onClick = { numberDialog = GestureNumberDialog.SeekStep },
                )
                InfoRow(
                    icon = Icons.Outlined.Speed,
                    title = resources.getString(R.string.settings_long_press_speed),
                    subtitle = "${settings.longPressSpeed}x",
                    onClick = { numberDialog = GestureNumberDialog.LongPressSpeed },
                )
            }
        }
    }
}
