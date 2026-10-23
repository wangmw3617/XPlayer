package com.zhiwei.xplayer.ui.screen

import android.content.res.Resources
import android.media.MediaFormat
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zhiwei.xplayer.R
import com.zhiwei.xplayer.core.device.CodecFamilyCapability
import com.zhiwei.xplayer.core.device.DirectPlaybackVerdict
import com.zhiwei.xplayer.core.device.DynamicFormatCapability
import com.zhiwei.xplayer.core.device.HardwareCodecInfo
import com.zhiwei.xplayer.core.device.SystemHardwareSnapshot
import com.zhiwei.xplayer.ui.components.GlassCard
import com.zhiwei.xplayer.ui.components.InfoRow
import com.zhiwei.xplayer.ui.components.SectionHeader
import com.zhiwei.xplayer.ui.components.rememberResources
import com.zhiwei.xplayer.ui.vm.SystemInfoViewModel
import java.util.Locale

@Composable
fun SystemInfoScreen(viewModel: SystemInfoViewModel = hiltViewModel()) {
    val resources = rememberResources()
    val hardware by viewModel.hardware.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val playerState by viewModel.playerState.collectAsStateWithLifecycle()
    val snapshot = hardware.snapshot

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item {
            GlassCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Outlined.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = resources.getString(R.string.system_info_intro),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = viewModel::refresh,
                    enabled = !hardware.loading,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(resources.getString(R.string.system_info_refresh))
                }
                if (hardware.loading) {
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        }

        if (snapshot == null && hardware.loading) {
            item {
                GlassCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(14.dp))
                        Text(
                            text = resources.getString(R.string.system_info_loading),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }

        val error = hardware.error
        if (error != null) {
            item {
                GlassCard {
                    InfoRow(
                        icon = Icons.Outlined.Info,
                        title = resources.getString(R.string.system_info_failed),
                        subtitle = error,
                    )
                }
            }
        }

        if (snapshot != null) {
            systemInformation(resources, snapshot)
            playbackPipeline(
                resources = resources,
                gpuNext = settings.gpuNext,
                hardwareDecoding = settings.hardwareDecoding,
                hwdecActive = playerState.hwdecActive,
                hasVideo = playerState.hasVideo,
                videoWidth = playerState.videoWidth,
                videoHeight = playerState.videoHeight,
            )
            compatibility(resources, snapshot)
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.systemInformation(
    resources: Resources,
    info: SystemHardwareSnapshot,
) {
    item { SectionHeader(resources.getString(R.string.system_info_section_device)) }
    item {
        GlassCard {
            val model = listOfNotNull(info.device.manufacturer, info.device.model)
                .distinct()
                .joinToString(" ")
                .ifBlank { unknown(resources) }
            val patch = info.device.securityPatch?.let { " · $it" }.orEmpty()
            val soc = info.device.soc ?: info.device.board
            val ram = info.device.totalRamGiB?.let {
                String.format(Locale.ROOT, "%.1f GiB", it)
            }
            val memory = buildString {
                append(ram ?: unknown(resources))
                if (info.device.lowRamDevice) {
                    append(" · ")
                    append(resources.getString(R.string.system_info_device_low_ram))
                }
            }

            InfoRow(
                icon = Icons.Outlined.PhoneAndroid,
                title = resources.getString(R.string.system_info_device_model),
                subtitle = model,
            )
            InfoRow(
                icon = Icons.Outlined.Info,
                title = resources.getString(R.string.system_info_device_android),
                subtitle = "${info.device.androidRelease} · API ${info.device.apiLevel}$patch",
            )
            InfoRow(
                icon = Icons.Outlined.Memory,
                title = resources.getString(R.string.system_info_device_soc),
                subtitle = soc ?: unknown(resources),
            )
            InfoRow(
                icon = Icons.Outlined.Memory,
                title = resources.getString(R.string.system_info_device_abi),
                subtitle = info.device.supportedAbis.joinToString(", ").ifBlank {
                    unknown(resources)
                },
            )
            InfoRow(
                icon = Icons.Outlined.Memory,
                title = resources.getString(R.string.system_info_device_ram),
                subtitle = memory,
            )
            InfoRow(
                icon = Icons.Outlined.Speed,
                title = resources.getString(R.string.system_info_device_gles),
                subtitle = info.device.glEsVersion ?: unknown(resources),
            )
        }
    }

    item { SectionHeader(resources.getString(R.string.system_info_section_display)) }
    item {
        GlassCard {
            val resolution = if (info.display.widthPx != null && info.display.heightPx != null) {
                "${info.display.widthPx} × ${info.display.heightPx}"
            } else {
                unknown(resources)
            }
            val refresh = info.display.refreshRateHz?.let {
                String.format(Locale.ROOT, "%.2f Hz", it)
            } ?: unknown(resources)
            val hdr = info.display.supportedHdrTypes
                .joinToString(" · ")
                .ifBlank { resources.getString(R.string.system_info_not_detected) }
            val luminance = luminanceRange(
                resources = resources,
                min = info.display.minLuminance,
                max = info.display.maxLuminance,
                maxAverage = info.display.maxAverageLuminance,
            )

            InfoRow(
                icon = Icons.Outlined.Tv,
                title = resources.getString(R.string.system_info_display_resolution),
                subtitle = resolution,
            )
            InfoRow(
                icon = Icons.Outlined.Tv,
                title = resources.getString(R.string.system_info_display_refresh),
                subtitle = refresh,
            )
            InfoRow(
                icon = Icons.Outlined.Tv,
                title = resources.getString(R.string.system_info_display_hdr),
                subtitle = hdr,
            )
            InfoRow(
                icon = Icons.Outlined.Tv,
                title = resources.getString(R.string.system_info_display_luminance),
                subtitle = luminance,
            )
        }
    }

    item { SectionHeader(resources.getString(R.string.system_info_section_codec)) }
    item {
        GlassCard {
            InfoRow(
                icon = Icons.Outlined.Videocam,
                title = resources.getString(R.string.system_info_codec_summary),
                subtitle = resources.getString(
                    R.string.system_info_codec_summary_value,
                    info.hardwareDecoderCount,
                    info.softwareDecoderCount,
                ),
            )
            if (info.codecScanError != null) {
                InfoRow(
                    icon = Icons.Outlined.Info,
                    title = resources.getString(R.string.system_info_failed),
                    subtitle = resources.getString(
                        R.string.system_info_codec_scan_failed,
                        info.codecScanError,
                    ),
                )
            }
            info.codecFamilies.forEach { family ->
                val dolbyVision =
                    family.mimeType == MediaFormat.MIMETYPE_VIDEO_DOLBY_VISION
                val capabilityFlags = buildList {
                    if (family.hasTunneledPlayback) {
                        add(resources.getString(R.string.system_info_codec_tunneled))
                    }
                    if (family.hardwareCodecs.any { it.securePlayback }) {
                        add(resources.getString(R.string.system_info_codec_secure))
                    }
                }
                val subtitle = if (dolbyVision) {
                    dynamicCodecSubtitle(resources, info.dolbyVision)
                } else {
                    codecFamilySubtitle(resources, family)
                }
                InfoRow(
                    icon = Icons.Outlined.Videocam,
                    title = family.displayName,
                    subtitle = subtitle,
                    trailing = if (capabilityFlags.isNotEmpty()) {
                        {
                            StatusBadge(
                                text = capabilityFlags.joinToString(" · "),
                                highlighted = true,
                            )
                        }
                    } else {
                        null
                    },
                )
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.playbackPipeline(
    resources: Resources,
    gpuNext: Boolean,
    hardwareDecoding: Boolean,
    hwdecActive: String,
    hasVideo: Boolean,
    videoWidth: Int,
    videoHeight: Int,
) {
    item { SectionHeader(resources.getString(R.string.system_info_section_playback)) }
    item {
        GlassCard {
            val output = if (gpuNext) "gpu-next · android · OpenGL ES" else "gpu · android · OpenGL ES"
            val configuredHwdec = if (hardwareDecoding) {
                "mediacodec,mediacodec-copy"
            } else {
                "no"
            }
            val active = hwdecActive.ifBlank { "no" }
            val video = if (hasVideo) {
                "$videoWidth × $videoHeight"
            } else {
                resources.getString(R.string.system_info_none_playing)
            }

            InfoRow(
                icon = Icons.Outlined.Speed,
                title = resources.getString(R.string.system_info_playback_output),
                subtitle = output,
            )
            InfoRow(
                icon = Icons.Outlined.Memory,
                title = resources.getString(R.string.system_info_playback_hwdec_setting),
                subtitle = configuredHwdec,
            )
            InfoRow(
                icon = Icons.Outlined.Videocam,
                title = resources.getString(R.string.system_info_playback_hwdec_active),
                subtitle = active,
            )
            InfoRow(
                icon = Icons.Outlined.Tv,
                title = resources.getString(R.string.system_info_playback_video),
                subtitle = video,
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            Text(
                text = resources.getString(R.string.system_info_playback_pipeline),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
            Text(
                text = resources.getString(R.string.system_info_playback_pipeline_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            )
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.compatibility(
    resources: Resources,
    info: SystemHardwareSnapshot,
) {
    item { SectionHeader(resources.getString(R.string.system_info_section_compatibility)) }
    item {
        GlassCard {
            CompatibilityBlock(
                title = "Dolby Vision",
                capability = info.dolbyVision,
                resources = resources,
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 14.dp))
            CompatibilityBlock(
                title = "HDR10+",
                capability = info.hdr10Plus,
                resources = resources,
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 14.dp))
            Text(
                text = resources.getString(R.string.system_info_hdr_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CompatibilityBlock(
    title: String,
    capability: DynamicFormatCapability,
    resources: Resources,
) {
    val verdictLabel: String
    val verdictDescription: String
    when (capability.verdict) {
        DirectPlaybackVerdict.NATIVE_PASS_THROUGH_CANDIDATE -> {
            verdictLabel = resources.getString(R.string.system_info_verdict_native)
            verdictDescription = resources.getString(R.string.system_info_verdict_native_desc)
        }

        DirectPlaybackVerdict.DISPLAY_AND_DECODER_WITHOUT_TUNNEL -> {
            verdictLabel = resources.getString(R.string.system_info_verdict_partial)
            verdictDescription = resources.getString(R.string.system_info_verdict_partial_desc)
        }

        DirectPlaybackVerdict.LIBMPV_TONE_MAPPING_ONLY -> {
            verdictLabel = resources.getString(R.string.system_info_verdict_tone_map)
            verdictDescription = resources.getString(R.string.system_info_verdict_tone_map_desc)
        }
    }
    val supported = resources.getString(R.string.system_info_supported)
    val notDetected = resources.getString(R.string.system_info_not_detected)
    val decoderNames = capability.decoderNames.joinToString(" · ").ifBlank { notDetected }
    val tunneledNames = capability.tunneledDecoderNames.joinToString(" · ").ifBlank { notDetected }
    val profiles = capability.decoderProfiles.take(5).joinToString(" · ") + if (
        capability.decoderProfiles.size > 5
    ) {
        " …"
    } else {
        ""
    }

    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            StatusBadge(
                text = verdictLabel,
                highlighted =
                    capability.verdict == DirectPlaybackVerdict.NATIVE_PASS_THROUGH_CANDIDATE,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = verdictDescription,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        CompatibilityLine(
            resources.getString(
                R.string.system_info_compatibility_display,
                if (capability.displaySupported) supported else notDetected,
            ),
        )
        CompatibilityLine(
            resources.getString(R.string.system_info_compatibility_decoder, decoderNames),
        )
        CompatibilityLine(
            resources.getString(R.string.system_info_compatibility_tunnel, tunneledNames),
        )
        if (profiles.isNotBlank()) {
            CompatibilityLine(
                resources.getString(R.string.system_info_compatibility_profiles, profiles),
            )
        }
    }
}

@Composable
private fun CompatibilityLine(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(vertical = 1.dp),
    )
}

@Composable
private fun StatusBadge(
    text: String,
    highlighted: Boolean,
) {
    val container = if (highlighted) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val content = if (highlighted) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        color = container,
        contentColor = content,
        shape = RoundedCornerShape(6.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

private fun codecFamilySubtitle(
    resources: Resources,
    family: CodecFamilyCapability,
): String {
    if (family.hardwareCodecs.isEmpty()) {
        return resources.getString(R.string.system_info_codec_no_hardware)
    }
    return codecSubtitle(
        resources = resources,
        codecs = family.hardwareCodecs,
        profiles = family.hardwareCodecs.flatMap { it.profiles }.distinct(),
    )
}

private fun dynamicCodecSubtitle(
    resources: Resources,
    capability: DynamicFormatCapability,
): String {
    if (capability.decoderNames.isEmpty()) {
        return resources.getString(R.string.system_info_codec_no_hardware)
    }
    return buildString {
        append(
            resources.getString(
                R.string.system_info_codec_entry,
                capability.decoderNames.size,
            ),
        )
        val profiles = compactProfiles(capability.decoderProfiles)
        if (profiles.isNotBlank()) {
            append(" · ")
            append(resources.getString(R.string.system_info_codec_profiles, profiles))
        }
        append('\n')
        append(
            resources.getString(
                R.string.system_info_codec_names,
                capability.decoderNames.joinToString(" · "),
            ),
        )
    }
}

private fun codecSubtitle(
    resources: Resources,
    codecs: List<HardwareCodecInfo>,
    profiles: List<String>,
): String = buildString {
    append(resources.getString(R.string.system_info_codec_entry, codecs.size))
    val profileSummary = compactProfiles(profiles)
    if (profileSummary.isNotBlank()) {
        append(" · ")
        append(resources.getString(R.string.system_info_codec_profiles, profileSummary))
    }
    append('\n')
    append(
        resources.getString(
            R.string.system_info_codec_names,
            codecs.map { it.name }.distinct().joinToString(" · "),
        ),
    )
}

private fun compactProfiles(profiles: List<String>): String {
    if (profiles.isEmpty()) return ""
    return profiles.take(6).joinToString(" / ") + if (profiles.size > 6) " …" else ""
}

private fun luminanceRange(
    resources: Resources,
    min: Float?,
    max: Float?,
    maxAverage: Float?,
): String {
    if (min == null && max == null && maxAverage == null) {
        return resources.getString(R.string.system_info_unknown)
    }
    val minValue = min?.let { String.format(Locale.ROOT, "%.4f", it) } ?: "?"
    val maxValue = max?.let { String.format(Locale.ROOT, "%.1f", it) } ?: "?"
    val base = "$minValue - $maxValue cd/m²"
    return if (maxAverage != null) {
        "$base · " + resources.getString(
            R.string.system_info_display_luminance_average,
            String.format(Locale.ROOT, "%.1f", maxAverage),
        )
    } else {
        base
    }
}

private fun unknown(resources: Resources): String =
    resources.getString(R.string.system_info_unknown)
