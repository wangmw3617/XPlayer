package com.zhiwei.xplayer.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zhiwei.xplayer.BuildConfig
import com.zhiwei.xplayer.R
import com.zhiwei.xplayer.core.data.AppSettings
import com.zhiwei.xplayer.core.data.ThemeMode
import com.zhiwei.xplayer.ui.components.rememberResources
import com.zhiwei.xplayer.ui.components.GlassCard
import com.zhiwei.xplayer.ui.components.InfoRow
import com.zhiwei.xplayer.ui.components.SectionHeader
import com.zhiwei.xplayer.ui.vm.SettingsViewModel

@Composable
fun SettingsScreen(
    onOpenGestures: () -> Unit,
    onOpenSystemInfo: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val resources = rememberResources()
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    var textDialog by remember { mutableStateOf<TextDialogKind?>(null) }
    var confirmReset by remember { mutableStateOf(false) }

    // ------------------------------------------------------------- 文本输入 ----
    val dialog = textDialog
    if (dialog != null) {
        TextInputDialog(
            title = resources.getString(dialog.titleRes),
            initial = dialog.initial(settings),
            hint = dialog.hintRes?.let { resources.getString(it) },
            numeric = dialog.numeric,
            onDismiss = { textDialog = null },
            onConfirm = { value ->
                dialog.apply(viewModel, value)
                textDialog = null
            },
        )
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text(resources.getString(R.string.settings_reset)) },
            text = { Text(resources.getString(R.string.settings_reset_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmReset = false
                        viewModel.resetAll()
                    },
                ) { Text(resources.getString(R.string.action_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) {
                    Text(resources.getString(R.string.action_cancel))
                }
            },
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = BOTTOM_BAR_RESERVE),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // ---------------------------------------------------------- 外观 ----
        item { SectionHeader(resources.getString(R.string.settings_appearance)) }
        item {
            GlassCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ThemeMode.entries.forEach { mode ->
                        FilterChip(
                            selected = settings.themeMode == mode,
                            onClick = { viewModel.setThemeMode(mode) },
                            label = { Text(resources.getString(themeLabel(mode))) },
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                SwitchRow(
                    icon = Icons.Outlined.DarkMode,
                    title = resources.getString(R.string.settings_dynamic_color),
                    subtitle = resources.getString(R.string.settings_dynamic_color_desc),
                    checked = settings.dynamicColor,
                    onCheckedChange = viewModel::setDynamicColor,
                )
            }
        }

        // ---------------------------------------------------------- 播放 ----
        item { SectionHeader(resources.getString(R.string.settings_playback)) }
        item {
            GlassCard {
                SwitchRow(
                    icon = Icons.Outlined.Memory,
                    title = resources.getString(R.string.settings_hwdec),
                    subtitle = resources.getString(R.string.settings_hwdec_desc),
                    checked = settings.hardwareDecoding,
                    onCheckedChange = viewModel::setHardwareDecoding,
                )
                SwitchRow(
                    icon = Icons.Outlined.Videocam,
                    title = resources.getString(R.string.settings_gpu_next),
                    subtitle = resources.getString(R.string.settings_gpu_next_desc),
                    checked = settings.gpuNext,
                    onCheckedChange = viewModel::setGpuNext,
                )
                SwitchRow(
                    icon = Icons.Outlined.MusicNote,
                    title = resources.getString(R.string.settings_background),
                    subtitle = resources.getString(R.string.settings_background_desc),
                    checked = settings.backgroundPlayback,
                    onCheckedChange = viewModel::setBackgroundPlayback,
                )
                SwitchRow(
                    icon = Icons.Outlined.Palette,
                    title = resources.getString(R.string.settings_keep_screen_on),
                    checked = settings.keepScreenOn,
                    onCheckedChange = viewModel::setKeepScreenOn,
                )
                SwitchRow(
                    icon = Icons.Outlined.Speed,
                    title = resources.getString(R.string.settings_remember_position),
                    subtitle = resources.getString(R.string.settings_remember_position_desc),
                    checked = settings.rememberPosition,
                    onCheckedChange = viewModel::setRememberPosition,
                )
            }
        }

        // ---------------------------------------------------------- 字幕 ----
        item { SectionHeader(resources.getString(R.string.settings_subtitle)) }
        item {
            GlassCard {
                SwitchRow(
                    icon = Icons.Outlined.Subtitles,
                    title = resources.getString(R.string.settings_auto_sub),
                    subtitle = resources.getString(R.string.settings_auto_sub_desc),
                    checked = settings.autoLoadSubtitles,
                    onCheckedChange = viewModel::setAutoLoadSubtitles,
                )
                InfoRow(
                    icon = Icons.Outlined.MusicNote,
                    title = resources.getString(R.string.settings_alang),
                    subtitle = settings.defaultAudioLanguage.ifBlank {
                        resources.getString(R.string.settings_language_hint)
                    },
                    onClick = { textDialog = TextDialogKind.AudioLanguage },
                )
                InfoRow(
                    icon = Icons.Outlined.Language,
                    title = resources.getString(R.string.settings_slang),
                    subtitle = settings.defaultSubtitleLanguage.ifBlank {
                        resources.getString(R.string.settings_language_hint)
                    },
                    onClick = { textDialog = TextDialogKind.SubtitleLanguage },
                )
            }
        }

        // ---------------------------------------------------------- 手势 ----
        item { SectionHeader(resources.getString(R.string.settings_gesture)) }
        item {
            GlassCard {
                InfoRow(
                    icon = Icons.Outlined.TouchApp,
                    title = resources.getString(R.string.settings_gesture_controls),
                    subtitle = resources.getString(R.string.settings_gesture_desc),
                    trailing = {
                        Icon(
                            imageVector = Icons.Outlined.ChevronRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    onClick = onOpenGestures,
                )
            }
        }

        // ------------------------------------------------------ 系统与硬件 ----
        item { SectionHeader(resources.getString(R.string.settings_system_hardware_section)) }
        item {
            GlassCard {
                InfoRow(
                    icon = Icons.Outlined.Memory,
                    title = resources.getString(R.string.settings_system_hardware),
                    subtitle = resources.getString(R.string.settings_system_hardware_desc),
                    trailing = {
                        Icon(
                            imageVector = Icons.Outlined.ChevronRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    onClick = onOpenSystemInfo,
                )
            }
        }

        // ---------------------------------------------------------- 关于 ----
        item { SectionHeader(resources.getString(R.string.settings_about)) }
        item {
            GlassCard {
                InfoRow(
                    icon = Icons.Outlined.Info,
                    title = resources.getString(R.string.settings_version),
                    subtitle = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                )
                InfoRow(
                    icon = Icons.Outlined.Memory,
                    title = resources.getString(R.string.settings_core),
                    subtitle = resources.getString(R.string.settings_core_value, BuildConfig.LIBMPV_VERSION),
                )
                InfoRow(
                    icon = Icons.Outlined.Palette,
                    title = resources.getString(R.string.settings_glass),
                    subtitle = "Kyant0/backdrop ${BuildConfig.LIQUID_GLASS_VERSION}",
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { confirmReset = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Outlined.RestartAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(
                        text = resources.getString(R.string.settings_reset),
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
internal fun SwitchRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    subtitle: String? = null,
) {
    InfoRow(
        icon = icon,
        title = title,
        subtitle = subtitle,
        trailing = {
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        },
        onClick = { onCheckedChange(!checked) },
    )
}

@Composable
internal fun TextInputDialog(
    title: String,
    initial: String,
    hint: String?,
    numeric: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember(initial) { mutableStateOf(initial) }
    val context = LocalContext.current
    val resources = rememberResources()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Text,
                    ),
                )
                // 提示文字放在输入框下面而不是 placeholder 里：
                // placeholder 在已有内容时会消失，而这句提示是「该填什么」的说明，
                // 需要一直看得见。
                if (hint != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = hint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value) }) {
                Text(resources.getString(R.string.action_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(resources.getString(R.string.action_cancel)) }
        },
    )
}

private fun themeLabel(mode: ThemeMode): Int = when (mode) {
    ThemeMode.SYSTEM -> R.string.settings_theme_system
    ThemeMode.LIGHT -> R.string.settings_theme_light
    ThemeMode.DARK -> R.string.settings_theme_dark
}

/** 设置页里所有「点开弹输入框」的项 */
private enum class TextDialogKind(
    val titleRes: Int,
    val hintRes: Int?,
    val numeric: Boolean,
) {
    AudioLanguage(R.string.settings_alang, R.string.settings_language_hint, false),
    SubtitleLanguage(R.string.settings_slang, R.string.settings_language_hint, false),
    ;

    fun initial(settings: AppSettings): String = when (this) {
        AudioLanguage -> settings.defaultAudioLanguage
        SubtitleLanguage -> settings.defaultSubtitleLanguage
    }

    fun apply(viewModel: SettingsViewModel, raw: String) {
        val value = raw.trim()
        when (this) {
            AudioLanguage -> viewModel.setAudioLanguage(value)
            SubtitleLanguage -> viewModel.setSubtitleLanguage(value)
        }
    }
}
