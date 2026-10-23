package com.zhiwei.xplayer.core.device

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.view.Display
import android.view.WindowManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

data class DeviceHardwareInfo(
    val manufacturer: String?,
    val model: String?,
    val androidRelease: String,
    val apiLevel: Int,
    val securityPatch: String?,
    val soc: String?,
    val board: String?,
    val supportedAbis: List<String>,
    val totalRamGiB: Double?,
    val lowRamDevice: Boolean,
    val glEsVersion: String?,
)

data class DisplayHardwareInfo(
    val widthPx: Int?,
    val heightPx: Int?,
    val refreshRateHz: Float?,
    val supportedHdrTypes: List<String>,
    val supportsDolbyVision: Boolean,
    val supportsHdr10: Boolean,
    val supportsHdr10Plus: Boolean,
    val supportsHlg: Boolean,
    val maxLuminance: Float?,
    val maxAverageLuminance: Float?,
    val minLuminance: Float?,
)

data class HardwareCodecInfo(
    val name: String,
    val mimeType: String,
    val profiles: List<String>,
    val tunneledPlayback: Boolean,
    val securePlayback: Boolean,
    val supportsHdr10Plus: Boolean,
    val dolbyVisionProfiles: List<String>,
)

data class CodecFamilyCapability(
    val displayName: String,
    val mimeType: String,
    val hardwareCodecs: List<HardwareCodecInfo>,
    val softwareCodecCount: Int,
) {
    val hasHardwareDecoder: Boolean get() = hardwareCodecs.isNotEmpty()
    val hasTunneledPlayback: Boolean get() = hardwareCodecs.any { it.tunneledPlayback }
}

enum class DirectPlaybackVerdict {
    NATIVE_PASS_THROUGH_CANDIDATE,
    DISPLAY_AND_DECODER_WITHOUT_TUNNEL,
    LIBMPV_TONE_MAPPING_ONLY,
}

data class DynamicFormatCapability(
    val displaySupported: Boolean,
    val decoderNames: List<String>,
    val decoderProfiles: List<String>,
    val tunneledDecoderNames: List<String>,
    val verdict: DirectPlaybackVerdict,
) {
    val decoderSupported: Boolean get() = decoderNames.isNotEmpty()
    val tunneledPlaybackSupported: Boolean get() = tunneledDecoderNames.isNotEmpty()
}

data class SystemHardwareSnapshot(
    val device: DeviceHardwareInfo,
    val display: DisplayHardwareInfo,
    val codecFamilies: List<CodecFamilyCapability>,
    val hardwareDecoderCount: Int,
    val softwareDecoderCount: Int,
    val codecScanError: String?,
    val dolbyVision: DynamicFormatCapability,
    val hdr10Plus: DynamicFormatCapability,
)

/**
 * 只读取系统公开能力，不创建解码器，也不读取任何稳定的设备标识。
 *
 * MediaCodec 的 Profile 列表只能证明解码器声明了某种能力，不能单独证明某个
 * Dolby Vision/HDR10+ 样片一定能原生直通。界面层需要把这个限制明确展示出来。
 */
@Singleton
@SuppressLint("InlinedApi")
class SystemHardwareProbe @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    fun probe(): SystemHardwareSnapshot {
        val device = probeDevice()
        val display = probeDisplay()
        val codecScan = probeMediaCodecs()
        val allHardwareCodecs = codecScan.families
            .flatMap { it.hardwareCodecs }
            .distinctBy { it.name to it.mimeType }

        return SystemHardwareSnapshot(
            device = device,
            display = display,
            codecFamilies = codecScan.families,
            hardwareDecoderCount = codecScan.hardwareDecoderCount,
            softwareDecoderCount = codecScan.softwareDecoderCount,
            codecScanError = codecScan.error,
            dolbyVision = dynamicFormatCapability(
                displaySupported = display.supportsDolbyVision,
                codecs = allHardwareCodecs.filter {
                    it.mimeType == MediaFormat.MIMETYPE_VIDEO_DOLBY_VISION ||
                        it.dolbyVisionProfiles.isNotEmpty()
                },
                profileSelector = { it.dolbyVisionProfiles },
            ),
            hdr10Plus = dynamicFormatCapability(
                displaySupported = display.supportsHdr10Plus,
                codecs = allHardwareCodecs.filter { it.supportsHdr10Plus },
                profileSelector = { codec ->
                    codec.profiles.filter { "HDR10+" in it }
                },
            ),
        )
    }

    private fun probeDevice(): DeviceHardwareInfo {
        val activityManager = context.getSystemService(ActivityManager::class.java)
        val memoryInfo = ActivityManager.MemoryInfo()
        runCatching { activityManager?.getMemoryInfo(memoryInfo) }
        val totalRamGiB = memoryInfo.totalMem
            .takeIf { it > 0L }
            ?.div(1024.0 * 1024.0 * 1024.0)
        val glEsVersion = runCatching {
            activityManager?.deviceConfigurationInfo?.glEsVersion
        }.getOrNull()?.clean()
        val soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Build.SOC_MANUFACTURER, Build.SOC_MODEL)
                .mapNotNull { it.clean() }
                .distinct()
                .joinToString(" ")
                .takeIf { it.isNotBlank() }
        } else {
            null
        }

        return DeviceHardwareInfo(
            manufacturer = Build.MANUFACTURER.clean(),
            model = Build.MODEL.clean(),
            androidRelease = Build.VERSION.RELEASE.orEmpty().ifBlank { "?" },
            apiLevel = Build.VERSION.SDK_INT,
            securityPatch = Build.VERSION.SECURITY_PATCH.clean(),
            soc = soc,
            board = Build.BOARD.clean(),
            supportedAbis = Build.SUPPORTED_ABIS.orEmpty().filter { it.isNotBlank() },
            totalRamGiB = totalRamGiB,
            lowRamDevice = activityManager?.isLowRamDevice == true,
            glEsVersion = glEsVersion,
        )
    }

    private fun probeDisplay(): DisplayHardwareInfo {
        val display = runCatching {
            context.getSystemService(WindowManager::class.java)?.defaultDisplay
        }.getOrNull()
        val hdrCapabilities = runCatching { display?.hdrCapabilities }.getOrNull()
        val supportedHdrTypes = hdrCapabilities?.supportedHdrTypes.orEmpty()
        val mode = runCatching { display?.mode }.getOrNull()
        val metrics = context.resources.displayMetrics

        return DisplayHardwareInfo(
            widthPx = mode?.physicalWidth?.takeIf { it > 0 } ?: metrics.widthPixels,
            heightPx = mode?.physicalHeight?.takeIf { it > 0 } ?: metrics.heightPixels,
            refreshRateHz = mode?.refreshRate?.takeIf { it > 0f }
                ?: display?.refreshRate?.takeIf { it > 0f },
            supportedHdrTypes = supportedHdrTypes.map(::hdrTypeLabel).distinct(),
            supportsDolbyVision =
                Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION in supportedHdrTypes,
            supportsHdr10 = Display.HdrCapabilities.HDR_TYPE_HDR10 in supportedHdrTypes,
            supportsHdr10Plus = Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS in supportedHdrTypes,
            supportsHlg = Display.HdrCapabilities.HDR_TYPE_HLG in supportedHdrTypes,
            maxLuminance = hdrCapabilities?.desiredMaxLuminance?.validLuminance(),
            maxAverageLuminance =
                hdrCapabilities?.desiredMaxAverageLuminance?.validLuminance(),
            minLuminance = hdrCapabilities?.desiredMinLuminance?.validLuminance(),
        )
    }

    private fun probeMediaCodecs(): MediaCodecScan {
        val codecInfos = runCatching {
            MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.toList()
        }.getOrElse { error ->
            return MediaCodecScan(
                families = emptyList(),
                hardwareDecoderCount = 0,
                softwareDecoderCount = 0,
                error = error.message ?: error::class.java.simpleName,
            )
        }
        val videoDecoders = codecInfos.filter(::isVideoDecoder)
        val families = VIDEO_MIME_SPECS.map { spec ->
            val decoders = videoDecoders.filter { spec.mimeType in it.supportedTypes }
            val hardware = decoders
                .filter(::isHardwareCodec)
                .map { probeHardwareCodec(it, spec.mimeType) }
                .sortedBy { it.name.lowercase(Locale.ROOT) }
            CodecFamilyCapability(
                displayName = spec.displayName,
                mimeType = spec.mimeType,
                hardwareCodecs = hardware,
                softwareCodecCount = decoders.count { !isHardwareCodec(it) },
            )
        }

        return MediaCodecScan(
            families = families,
            hardwareDecoderCount = videoDecoders.count(::isHardwareCodec),
            softwareDecoderCount = videoDecoders.count { !isHardwareCodec(it) },
            error = null,
        )
    }

    private fun probeHardwareCodec(
        codecInfo: MediaCodecInfo,
        mimeType: String,
    ): HardwareCodecInfo {
        val capabilities = runCatching {
            codecInfo.getCapabilitiesForType(mimeType)
        }.getOrNull()
        val rawProfiles = capabilities
            ?.profileLevels
            ?.map { it.profile }
            .orEmpty()
            .distinct()
        val dolbyVisionProfiles = rawProfiles
            .filter { isDolbyVisionProfile(mimeType, it) }
            .map { profileName(mimeType, it) }
            .distinct()

        return HardwareCodecInfo(
            name = codecInfo.name,
            mimeType = mimeType,
            profiles = rawProfiles.map { profileName(mimeType, it) }.distinct().sorted(),
            tunneledPlayback = capabilities.supportsFeature(
                MediaCodecInfo.CodecCapabilities.FEATURE_TunneledPlayback,
            ),
            securePlayback = capabilities.supportsFeature(
                MediaCodecInfo.CodecCapabilities.FEATURE_SecurePlayback,
            ),
            supportsHdr10Plus = supportsHdr10Plus(mimeType, rawProfiles),
            dolbyVisionProfiles = dolbyVisionProfiles,
        )
    }

    private fun dynamicFormatCapability(
        displaySupported: Boolean,
        codecs: List<HardwareCodecInfo>,
        profileSelector: (HardwareCodecInfo) -> List<String>,
    ): DynamicFormatCapability {
        val distinctCodecs = codecs.distinctBy { it.name }
        val decoderNames = distinctCodecs.map { it.name }
        val tunneledNames = distinctCodecs
            .filter { it.tunneledPlayback }
            .map { it.name }
        val verdict = when {
            displaySupported && decoderNames.isNotEmpty() && tunneledNames.isNotEmpty() ->
                DirectPlaybackVerdict.NATIVE_PASS_THROUGH_CANDIDATE

            displaySupported && decoderNames.isNotEmpty() ->
                DirectPlaybackVerdict.DISPLAY_AND_DECODER_WITHOUT_TUNNEL

            else -> DirectPlaybackVerdict.LIBMPV_TONE_MAPPING_ONLY
        }
        return DynamicFormatCapability(
            displaySupported = displaySupported,
            decoderNames = decoderNames,
            decoderProfiles = distinctCodecs
                .flatMap(profileSelector)
                .distinct(),
            tunneledDecoderNames = tunneledNames,
            verdict = verdict,
        )
    }

    private fun isVideoDecoder(info: MediaCodecInfo): Boolean {
        if (info.isEncoder) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && info.isAlias) return false
        return info.supportedTypes.any { mime ->
            mime.startsWith("video/") &&
                mime != MediaFormat.MIMETYPE_VIDEO_RAW &&
                mime != MediaFormat.MIMETYPE_VIDEO_SCRAMBLED
        }
    }

    private fun isHardwareCodec(info: MediaCodecInfo): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return info.isHardwareAccelerated && !info.isSoftwareOnly
        }
        val name = info.name.lowercase(Locale.ROOT)
        return !name.startsWith("omx.google.") &&
            !name.startsWith("c2.android.") &&
            !name.contains(".software.") &&
            !name.contains(".sw.")
    }

    private fun profileName(mimeType: String, profile: Int): String {
        if (isDolbyVisionProfile(mimeType, profile)) {
            return dolbyVisionProfileName(profile)
        }
        return when (mimeType) {
            MediaFormat.MIMETYPE_VIDEO_AVC -> avcProfileName(profile)
            MediaFormat.MIMETYPE_VIDEO_HEVC -> hevcProfileName(profile)
            MediaFormat.MIMETYPE_VIDEO_AV1 -> av1ProfileName(profile)
            MediaFormat.MIMETYPE_VIDEO_VP9 -> vp9ProfileName(profile)
            else -> "Profile $profile"
        }
    }

    private fun avcProfileName(profile: Int): String = when (profile) {
        MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline -> "Baseline"
        MediaCodecInfo.CodecProfileLevel.AVCProfileMain -> "Main"
        MediaCodecInfo.CodecProfileLevel.AVCProfileHigh -> "High"
        else -> "Profile $profile"
    }

    private fun hevcProfileName(profile: Int): String = when (profile) {
        MediaCodecInfo.CodecProfileLevel.HEVCProfileMain -> "Main"
        MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 -> "Main 10"
        MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10 -> "Main 10 HDR10"
        MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus -> "Main 10 HDR10+"
        MediaCodecInfo.CodecProfileLevel.HEVCProfileMainStill -> "Main Still"
        else -> "Profile $profile"
    }

    private fun av1ProfileName(profile: Int): String = when (profile) {
        MediaCodecInfo.CodecProfileLevel.AV1ProfileMain8 -> "Main 8"
        MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10 -> "Main 10"
        MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10HDR10 -> "Main 10 HDR10"
        MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10HDR10Plus -> "Main 10 HDR10+"
        else -> "Profile $profile"
    }

    private fun vp9ProfileName(profile: Int): String = when (profile) {
        MediaCodecInfo.CodecProfileLevel.VP9Profile0 -> "Profile 0"
        MediaCodecInfo.CodecProfileLevel.VP9Profile1 -> "Profile 1"
        MediaCodecInfo.CodecProfileLevel.VP9Profile2 -> "Profile 2"
        MediaCodecInfo.CodecProfileLevel.VP9Profile2HDR -> "Profile 2 HDR"
        MediaCodecInfo.CodecProfileLevel.VP9Profile2HDR10Plus -> "Profile 2 HDR10+"
        MediaCodecInfo.CodecProfileLevel.VP9Profile3 -> "Profile 3"
        MediaCodecInfo.CodecProfileLevel.VP9Profile3HDR -> "Profile 3 HDR"
        MediaCodecInfo.CodecProfileLevel.VP9Profile3HDR10Plus -> "Profile 3 HDR10+"
        else -> "Profile $profile"
    }

    private fun dolbyVisionProfileName(profile: Int): String = when (profile) {
        MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvavPer -> "dvav.per"
        MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvavPen -> "dvav.pen"
        MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvheDer -> "dvhe.der"
        MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvheDen -> "dvhe.den"
        MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvheDtr -> "dvhe.dtr"
        MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvheStn -> "dvhe.stn"
        MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvheSt -> "dvhe.st"
        MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvheDth -> "dvhe.dth"
        MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvheDtb -> "dvhe.dtb"
        MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvavSe -> "dvav.se"
        MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvav110 -> "dvav.110"
        else -> "Dolby Vision profile $profile"
    }

    private fun supportsHdr10Plus(mimeType: String, profiles: List<Int>): Boolean =
        when (mimeType) {
            MediaFormat.MIMETYPE_VIDEO_HEVC ->
                MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus in profiles

            MediaFormat.MIMETYPE_VIDEO_AV1 ->
                MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10HDR10Plus in profiles

            MediaFormat.MIMETYPE_VIDEO_VP9 ->
                MediaCodecInfo.CodecProfileLevel.VP9Profile2HDR10Plus in profiles ||
                    MediaCodecInfo.CodecProfileLevel.VP9Profile3HDR10Plus in profiles

            else -> false
        }

    private fun isDolbyVisionProfile(mimeType: String, profile: Int): Boolean {
        if (mimeType == MediaFormat.MIMETYPE_VIDEO_DOLBY_VISION) return true
        if (mimeType != MediaFormat.MIMETYPE_VIDEO_HEVC) return false
        return profile == MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvavPer ||
            profile == MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvavPen ||
            profile == MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvheDer ||
            profile == MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvheDen ||
            profile == MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvheDtr ||
            profile == MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvheStn ||
            profile == MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvheSt ||
            profile == MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvheDth ||
            profile == MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvheDtb ||
            profile == MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvavSe ||
            profile == MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvav110
    }

    private fun hdrTypeLabel(type: Int): String = when (type) {
        Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION -> "Dolby Vision"
        Display.HdrCapabilities.HDR_TYPE_HDR10 -> "HDR10"
        Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS -> "HDR10+"
        Display.HdrCapabilities.HDR_TYPE_HLG -> "HLG"
        else -> "HDR type $type"
    }

    private fun MediaCodecInfo.CodecCapabilities?.supportsFeature(feature: String): Boolean =
        this?.let { capabilities ->
            runCatching { capabilities.isFeatureSupported(feature) }.getOrDefault(false)
        } ?: false

    private fun Float.validLuminance(): Float? =
        takeIf { it.isFinite() && it > 0f }

    private fun String?.clean(): String? =
        this?.trim()?.takeIf {
            it.isNotBlank() && !it.equals(Build.UNKNOWN, ignoreCase = true)
        }

    private data class MimeSpec(
        val displayName: String,
        val mimeType: String,
    )

    private data class MediaCodecScan(
        val families: List<CodecFamilyCapability>,
        val hardwareDecoderCount: Int,
        val softwareDecoderCount: Int,
        val error: String?,
    )

    private companion object {
        val VIDEO_MIME_SPECS = listOf(
            MimeSpec("H.264 / AVC", MediaFormat.MIMETYPE_VIDEO_AVC),
            MimeSpec("HEVC / H.265", MediaFormat.MIMETYPE_VIDEO_HEVC),
            MimeSpec("AV1", MediaFormat.MIMETYPE_VIDEO_AV1),
            MimeSpec("VP9", MediaFormat.MIMETYPE_VIDEO_VP9),
            MimeSpec("Dolby Vision", MediaFormat.MIMETYPE_VIDEO_DOLBY_VISION),
        )
    }
}
