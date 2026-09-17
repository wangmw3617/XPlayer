package com.zhiwei.xplayer.core.media

/** 媒体库里的一条媒体（来自 MediaStore 扫描） */
data class MediaEntry(
    val uri: String,
    val title: String,
    val fileName: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val addedAt: Long,
    val isVideo: Boolean,
    val bucket: String?,
    val width: Int,
    val height: Int,
    /** MediaStore 里的真实路径；Android 11+ 上可能为 null，仅用于展示与字幕匹配 */
    val path: String?,
) {
    val resolution: String?
        get() = if (width > 0 && height > 0) "${width}×${height}" else null
}

/** 媒体库排序方式 */
enum class MediaSort {
    ADDED,
    NAME,
    DURATION,
    SIZE,
    ;

    companion object {
        fun fromName(name: String?): MediaSort =
            entries.firstOrNull { it.name == name } ?: ADDED
    }
}
