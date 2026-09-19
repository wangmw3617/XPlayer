package com.zhiwei.xplayer.core.webdav

/**
 * 一个 WebDAV 服务端配置。
 *
 * [url] 存的是用户填的原始地址（例如 `https://dav.example.com/remote.php/dav/files/me/`），
 * [basePath] 是启动时探测出来的、用来拼接相对路径的基准 —— 两者可能不同：
 * 用户可能少写尾斜杠、也可能填到某个子目录，PROPFIND 实际生效的 href 前缀要以服务端返回为准。
 */
data class DavAccount(
    val url: String,
    val username: String,
    val password: String,
    val displayName: String = "",
) {
    val isEmpty: Boolean get() = url.isBlank()

    /** 列表里显示的名字：优先用备注名，否则退回主机名 */
    val label: String
        get() = displayName.ifBlank {
            runCatching { java.net.URI(url).host }.getOrNull()?.takeIf { it.isNotBlank() } ?: url
        }

    /**
     * 这个账号是否「拥有」某个播放 URL。
     *
     * 用途：从播放历史里重新播一个 WebDAV 源时，uri 本身不带凭据
     * （见 [WebDavClient.playableUrl]），需要靠这个判断去把对应的账号
     * 找回来，然后用它的凭据补上 Authorization 头。
     * 否则「继续观看」里的 WebDAV 条目会一律 401。
     */
    fun owns(mediaUrl: String): Boolean {
        val base = url.trimEnd('/')
        return base.isNotEmpty() && mediaUrl.startsWith(base)
    }
}

/**
 * 一个 WebDAV 资源（文件或目录）。
 *
 * [path] 是**相对 [DavAccount.url] 的路径**，以 `/` 开头，不带尾斜杠 ——
 * 这样上层做「进入子目录 / 拼父目录」时不用关心服务端返回的 href 前缀差异。
 * 真正请求时由 [WebDavClient] 负责把 base 补回去。
 */
data class DavEntry(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long = 0L,
    val lastModified: String? = null,
    val contentType: String? = null,
) {
    /** 能不能交给 mpv 播。WebDAV 上的媒体直接走 URL，不需要先下到本地。 */
    val isPlayable: Boolean
        get() = !isDirectory && MediaTypes.isPlayable(name)
}

/** 常见媒体扩展名判断。集中放这里，SAF 浏览与 WebDAV 浏览共用一套口径。 */
object MediaTypes {
    private val VIDEO = setOf(
        "mp4", "mkv", "webm", "avi", "mov", "flv", "ts", "m2ts", "mts", "mpg", "mpeg",
        "wmv", "3gp", "m4v", "ogv", "rmvb", "rm", "vob", "divx", "asf", "f4v",
    )
    private val AUDIO = setOf(
        "mp3", "flac", "aac", "m4a", "ogg", "oga", "opus", "wav", "wma", "ape",
        "alac", "dsf", "dff", "mka", "ac3", "dts", "amr", "aiff", "mid",
    )
    private val SUBTITLE = setOf("srt", "ass", "ssa", "vtt", "sub", "idx", "smi", "ttml", "lrc")

    fun extensionOf(name: String): String =
        name.substringAfterLast('.', "").lowercase()

    fun isVideo(name: String): Boolean = extensionOf(name) in VIDEO
    fun isAudio(name: String): Boolean = extensionOf(name) in AUDIO
    fun isSubtitle(name: String): Boolean = extensionOf(name) in SUBTITLE
    fun isPlayable(name: String): Boolean = isVideo(name) || isAudio(name)
}

/** WebDAV 操作失败的统一异常，带一句能直接显示给用户的中文说明。 */
class DavException(message: String, cause: Throwable? = null) : Exception(message, cause)
