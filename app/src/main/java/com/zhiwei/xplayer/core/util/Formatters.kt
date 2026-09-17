package com.zhiwei.xplayer.core.util

import java.util.Calendar
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 纯函数格式化工具。
 *
 * 刻意做成 object + 纯函数（不依赖 Context、不依赖系统时钟 —— 需要「现在」的地方
 * 由调用方传入），这样单元测试可以直接跑，不需要 Robolectric。
 */
object Formatters {

    /** 毫秒 -> `H:MM:SS` / `M:SS`；未知时长返回 `--:--` */
    fun duration(ms: Long): String {
        if (ms <= 0L) return "--:--"
        val totalSeconds = ms / 1000L
        return clock(totalSeconds)
    }

    /** 播放位置 -> `H:MM:SS` / `M:SS`；0 显示为 `0:00` 而不是 `--:--` */
    fun position(ms: Long): String = clock((if (ms < 0L) 0L else ms) / 1000L)

    private fun clock(totalSeconds: Long): String {
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0L) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%d:%02d", minutes, seconds)
        }
    }

    /** 字节 -> `1.2 GB` */
    fun size(bytes: Long): String {
        if (bytes <= 0L) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var index = 0
        while (value >= 1024.0 && index < units.lastIndex) {
            value /= 1024.0
            index++
        }
        return if (index == 0) {
            String.format(Locale.US, "%.0f %s", value, units[index])
        } else {
            String.format(Locale.US, "%.1f %s", value, units[index])
        }
    }

    /** 相对时间：刚刚 / N 分钟前 / N 小时前 / N 天前 / yyyy-MM-dd */
    fun relativeTime(timestampMs: Long, nowMs: Long): String {
        if (timestampMs <= 0L) return ""
        val diff = nowMs - timestampMs
        if (diff < 0L) return "刚刚"
        val minute = 60_000L
        val hour = 60L * minute
        val day = 24L * hour
        return when {
            diff < minute -> "刚刚"
            diff < hour -> "${diff / minute} 分钟前"
            diff < day -> "${diff / hour} 小时前"
            diff < 30L * day -> "${diff / day} 天前"
            else -> {
                val calendar = Calendar.getInstance()
                calendar.timeInMillis = timestampMs
                String.format(
                    Locale.US,
                    "%04d-%02d-%02d",
                    calendar.get(Calendar.YEAR),
                    calendar.get(Calendar.MONTH) + 1,
                    calendar.get(Calendar.DAY_OF_MONTH),
                )
            }
        }
    }

    /** 播放进度百分比（0..1），时长未知时返回 0 */
    fun progress(positionMs: Long, durationMs: Long): Float {
        if (durationMs <= 0L) return 0f
        return (positionMs.toDouble() / durationMs.toDouble()).coerceIn(0.0, 1.0).toFloat()
    }

    /** 音量/亮度手势的百分比文案：0.42 -> `42%` */
    fun percent(value: Float): String =
        String.format(Locale.US, "%d%%", (value * 100f).roundToInt())

    /** 倍速文案：1.0 -> `1x`，1.25 -> `1.25x` */
    fun speed(value: Float): String {
        val text = if (value % 1f == 0f) {
            String.format(Locale.US, "%.1f", value)
        } else {
            String.format(Locale.US, "%.2f", value)
        }
        return text.trimEnd('0').trimEnd('.') + "x"
    }

    /** 音频/字幕延迟文案：正数带 + 号，1.5 -> `+1.50s` */
    fun delay(seconds: Double): String = String.format(Locale.US, "%+.2fs", seconds)

    /** 去掉扩展名 */
    fun baseName(fileName: String): String {
        val index = fileName.lastIndexOf('.')
        return if (index > 0) fileName.substring(0, index) else fileName
    }

    /** 取扩展名（小写，不含点） */
    fun extensionOf(name: String): String {
        val index = name.lastIndexOf('.')
        if (index < 0 || index == name.length - 1) return ""
        return name.substring(index + 1).lowercase(Locale.US)
    }

    fun isMediaFile(name: String): Boolean = extensionOf(name) in MEDIA_EXTENSIONS

    fun isSubtitleFile(name: String): Boolean = extensionOf(name) in SUBTITLE_EXTENSIONS

    fun isVideoFile(name: String): Boolean = extensionOf(name) in VIDEO_EXTENSIONS

    /** 绝对值（给测试用，顺带说明这个文件确实有可测的纯逻辑） */
    fun absOf(value: Int): Int = abs(value)

    val MEDIA_EXTENSIONS: Set<String> = VIDEO_EXTENSIONS + AUDIO_EXTENSIONS

    val VIDEO_EXTENSIONS: Set<String> = setOf(
        "mp4", "mkv", "webm", "avi", "mov", "wmv", "flv", "f4v", "m4v", "mpg", "mpeg",
        "ts", "m2ts", "mts", "vob", "3gp", "3g2", "ogv", "rm", "rmvb", "asf", "divx",
        "mxf", "y4m", "m2v", "m1v", "dav", "rec", "trp", "tp", "nut", "h264", "h265",
        "hevc", "av1", "vp9", "ivf",
    )

    val AUDIO_EXTENSIONS: Set<String> = setOf(
        "mp3", "aac", "m4a", "flac", "wav", "ogg", "oga", "opus", "wma", "alac", "ape",
        "mka", "amr", "ac3", "dts", "eac3", "truehd", "aiff", "aif", "mid", "m4b", "mpc",
        "tak", "tta", "wv", "dsf", "dff", "caf", "spx",
    )

    val SUBTITLE_EXTENSIONS: Set<String> = setOf(
        "srt", "ass", "ssa", "sub", "vtt", "idx", "sup", "smi", "rt", "ttml", "dfxp", "mpl",
    )
}
