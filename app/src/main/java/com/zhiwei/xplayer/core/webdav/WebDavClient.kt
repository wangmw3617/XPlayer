package com.zhiwei.xplayer.core.webdav

import android.net.Uri
import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.xmlpull.v1.XmlPullParser
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 一个极瘦的 WebDAV 客户端。
 *
 * ## 为什么自己写
 *
 * 播放器对 WebDAV 的需求其实只有三件事：
 * 1. 列目录（`PROPFIND` + `Depth: 1`）；
 * 2. 拿到一个能交给 mpv 的 URL（普通 `GET`，由 FFmpeg 的 http 协议自己处理，
 *    根本不经过 OkHttp）；
 * 3. Basic 鉴权。
 *
 * 为这三件事引 sardine（JAXB + Apache HttpClient）或 dav4jvm（JitPack + xpp3
 * 的 R8 冲突）都不划算，见 `libs.versions.toml` 里 okhttp 那段注释。
 * XML 用 Android 自带的 [XmlPullParser]，零新依赖。
 *
 * ## 线程
 *
 * 所有方法都是 `suspend` 且切到 [Dispatchers.IO]。OkHttp 自己的
 * `execute()` 是阻塞调用，在协程里必须显式换线程，否则会卡住主线程。
 *
 * ## 关于 URL 拼接
 *
 * 服务端返回的 `href` 可能是全 URL、可能是绝对路径，也可能是 URL 编码过的。
 * 这里统一先解码成明文路径，再相对账号的 base 算出一个干净的 `/xxx` 路径，
 * 上层就永远只面对这一种形态。
 */
class WebDavClient(
    private val account: DavAccount,
    private val client: OkHttpClient = defaultClient(),
) {

    companion object {
        /** PROPFIND 的请求体。只问需要的几个属性，省流量也省服务端解析时间。 */
        private val PROPFIND_BODY = """
            <?xml version="1.0" encoding="utf-8" ?>
            <d:propfind xmlns:d="DAV:">
              <d:prop>
                <d:displayname/>
                <d:resourcetype/>
                <d:getcontentlength/>
                <d:getlastmodified/>
                <d:getcontenttype/>
              </d:prop>
            </d:propfind>
        """.trimIndent().toByteArray(Charsets.UTF_8)

        private const val DEPTH_HEADER = "Depth"
        private val XML_MEDIA_TYPE = "application/xml; charset=utf-8".toMediaType()
        private val HTTP_200_PATTERN = Regex("""(?:^|\s)200(?:\s|$)""")

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            // 列目录慢一点可以忍，但不能无限等 —— 否则界面会一直转圈
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            // WebDAV 常见的自签名证书场景：这里不主动信任坏证书，
            // 交给系统信任链判断，用户要连自建服务就自己把证书装上。
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    /** 列表缓存，避免同一个目录被反复 PROPFIND（返回键来回切时很常见）。 */
    private val cache = LinkedHashMap<String, List<DavEntry>>()
    private val cacheLimit = 24

    /** 探测账号是否可用；顺带把 base 路径标准化。返回根目录下的条目。 */
    suspend fun list(path: String = "/"): List<DavEntry> = withContext(Dispatchers.IO) {
        synchronized(cache) { cache[path] }?.let { return@withContext it }

        val request = Request.Builder()
            .url(buildUrl(path))
            .method("PROPFIND", PROPFIND_BODY.toRequestBody(XML_MEDIA_TYPE))
            .header(DEPTH_HEADER, "1")
            .header("Authorization", Credentials.basic(account.username, account.password))
            .header("Accept", "application/xml, text/xml, */*")
            .build()

        val entries = try {
            execute(request).use { response ->
                when {
                    response.code == 401 -> throw DavException("认证失败，请检查用户名与密码")
                    response.code == 404 -> throw DavException("路径不存在：$path")
                    response.code == 405 -> throw DavException("服务端不支持 WebDAV（PROPFIND 被拒绝）")
                    !response.isSuccessful ->
                        throw DavException("服务端返回 ${response.code}")
                    else -> withContext(Dispatchers.Default) {
                        parseMultiStatus(response.body.byteStream(), path)
                    }
                }
            }
        } catch (e: DavException) {
            throw e
        } catch (e: IOException) {
            throw DavException("网络错误：${e.message ?: "连接失败"}", e)
        }

        synchronized(cache) {
            if (cache.size >= cacheLimit) {
                cache.keys.firstOrNull()?.let { cache.remove(it) }
            }
            cache[path] = entries
        }
        entries
    }

    /** 清掉缓存。用户手动刷新，或者改完账号配置之后调用。 */
    fun invalidate() {
        synchronized(cache) { cache.clear() }
    }

    /**
     * OkHttp 的阻塞 execute 不响应协程取消。这里用 enqueue，取消时直接 cancel Call，
     * 快速切换目录时旧请求会真正断开，而不是只在 UI 层丢弃结果。
     */
    private suspend fun execute(request: Request): Response =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (!continuation.isCancelled) continuation.resumeWithException(e)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        if (continuation.isActive) {
                            continuation.resume(response)
                        } else {
                            response.close()
                        }
                    }
                },
            )
        }

    /**
     * 拼出可以直接播放的 URL。
     *
     * **不含任何凭据** —— 这是刻意的。这个 URL 会被写进播放历史（Room 表）
     * 并显示在界面上，把 `user:pass@` 塞进来等于把密码落盘 + 露在屏幕上。
     * 认证走 [playbackHeaders]，由 mpv 的 `http-header-fields` 下发。
     */
    fun playableUrl(path: String): String = account.url.trimEnd('/') + encodePath(path)

    /**
     * 播放该路径时要带的请求头。
     *
     * mpv 的 `http-header-fields` 是逗号分隔的 `Name: Value` 列表；
     * 值里若含逗号会被 mpv 误切，所以这里对 Basic 凭据先做 base64
     * （base64 字母表不含逗号），天然安全。
     */
    fun playbackHeaders(): List<String> {
        if (account.username.isBlank()) return emptyList()
        return listOf("Authorization: ${Credentials.basic(account.username, account.password)}")
    }

    // -------------------------------------------------------------- 内部 ----

    private fun buildUrl(path: String): String {
        val base = account.url.trimEnd('/')
        val full = base + encodePath(path)
        // 提前校验，坏 URL 在这里就报错，别等到 OkHttp 抛 IllegalArgumentException
        full.toHttpUrlOrNull() ?: throw DavException("地址不合法：$full")
        return full
    }

    /** 逐段 URL 编码，但保留 `/` 作为分隔符。 */
    private fun encodePath(path: String): String {
        val normalized = if (path.startsWith("/")) path else "/$path"
        return normalized.split('/').joinToString("/") { segment ->
            if (segment.isEmpty()) {
                segment
            } else {
                java.net.URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
            }
        }
    }

    /**
     * 解析 `207 Multi-Status`。
     *
     * 结构大致是：
     * ```
     * <d:multistatus>
     *   <d:response>
     *     <d:href>/dav/files/me/movie.mp4</d:href>
     *     <d:propstat>
     *       <d:prop>
     *         <d:displayname>movie.mp4</d:displayname>
     *         <d:resourcetype><d:collection/></d:resourcetype>
     *         <d:getcontentlength>735000000</d:getcontentlength>
     *       </d:prop>
     *     </d:propstat>
     *   </d:response>
     * </d:multistatus>
     * ```
     *
     * 注意三点：
     * 1. 命名空间前缀各家不同（`d:` / `D:` / `lp1:`）。开启
     *    FEATURE_PROCESS_NAMESPACES 后，`XmlPullParser.getName()`（Kotlin 里是
     *    属性 `parser.name`）返回的是**本地名**，不带前缀；而
     *    `parser.getPrefix()` 才是前缀。所以可以直接 `parser.name == "href"`，
     *    绝不能写成 `"d:href"` —— 换个服务端前缀就匹配不上了；
     * 2. 每个 response 里可能有多个 `propstat`（200 一个、404 一个），
     *    只有 `status` 含 200 的那个才可信；
     * 3. 第一个 response 通常是**目录自身**（href 就是请求路径），要跳过，
     *    否则界面会多出一项「自己」。
     */
    private fun parseMultiStatus(stream: java.io.InputStream, requestedPath: String): List<DavEntry> {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        parser.setInput(stream, null)

        val result = mutableListOf<DavEntry>()
        var inResponse = false
        var inPropStat = false
        var inProp = false
        var propStatOk = false
        var href: String? = null
        var name: String? = null
        var isCollection = false
        var size = 0L
        var modified: String? = null
        var contentType: String? = null
        var pendingName: String? = null
        var pendingCollection = false
        var pendingSize = 0L
        var pendingModified: String? = null
        var pendingContentType: String? = null

        val requested = requestedPath.trimEnd('/').ifEmpty { "/" }

        fun flush() {
            val rawHref = href ?: return
            val path = normalizeHref(rawHref, requestedPath)
            // 跳过请求目录自身。用「归一化后是否等于请求路径」判断，
            // 而不是比深度：深度算法在服务端 href 前缀与账号 base 不一致时会错，
            // 可能把真正的子项也一起滤掉。
            if (path.trimEnd('/').ifEmpty { "/" } == requested) return
            val leaf = name?.takeIf { it.isNotBlank() }
                ?: path.trimEnd('/').substringAfterLast('/')
            result += DavEntry(
                path = path,
                name = leaf,
                isDirectory = isCollection,
                sizeBytes = size,
                lastModified = modified,
                contentType = contentType,
            )
        }

        fun resetPendingProperties() {
            pendingName = null
            pendingCollection = false
            pendingSize = 0L
            pendingModified = null
            pendingContentType = null
        }

        fun applyPendingProperties() {
            if (!propStatOk) return
            name = pendingName ?: name
            isCollection = isCollection || pendingCollection
            if (pendingSize > 0L) size = pendingSize
            modified = pendingModified ?: modified
            contentType = pendingContentType ?: contentType
        }

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                // 注意：org.xmlpull.v1.XmlPullParser 的方法叫 getName()，
                // 没有 localName()。Kotlin 把 getName() 映射成属性 `name`。
                XmlPullParser.START_TAG -> when (parser.name) {
                    "response" -> {
                        inResponse = true
                        inPropStat = false
                        inProp = false
                        propStatOk = false
                        href = null; name = null; isCollection = false
                        size = 0L; modified = null; contentType = null
                        resetPendingProperties()
                    }
                    "href" -> if (inResponse && !inPropStat) {
                        href = parser.nextText().trim()
                    }
                    "propstat" -> if (inResponse) {
                        inPropStat = true
                        inProp = false
                        propStatOk = false
                        resetPendingProperties()
                    }
                    "prop" -> if (inPropStat) inProp = true
                    "status" -> if (inPropStat) {
                        propStatOk = HTTP_200_PATTERN.containsMatchIn(parser.nextText())
                    }
                    "collection" -> if (inProp) pendingCollection = true
                    "displayname" -> if (inProp) {
                        pendingName = parser.nextText().trim()
                    }
                    "getcontentlength" -> if (inProp) {
                        pendingSize = parser.nextText().trim().toLongOrNull() ?: 0L
                    }
                    "getlastmodified" -> if (inProp) {
                        pendingModified = parser.nextText().trim()
                    }
                    "getcontenttype" -> if (inProp) {
                        pendingContentType = parser.nextText().trim()
                    }
                }
                XmlPullParser.END_TAG -> when (parser.name) {
                    "prop" -> inProp = false
                    "propstat" -> if (inPropStat) {
                        applyPendingProperties()
                        inPropStat = false
                        inProp = false
                        propStatOk = false
                    }
                    "response" -> if (inResponse) {
                        flush()
                        inResponse = false
                    }
                }
            }
            event = parser.next()
        }
        return result.sortedWith(ENTRY_ORDER)
    }

    /** 目录在前、同类按名称自然序，和常见的文件管理器一致。 */
    private val ENTRY_ORDER = compareBy<DavEntry>({ !it.isDirectory }, { it.name.lowercase() })

    /**
     * 把服务端给的 href 归一成「相对账号 base 的明文路径」。
     *
     * 三种形态都要处理：`https://host/a/b`、`/a/b`、`a/b`。
     * 还要 URL 解码 —— 服务端返回的 href 是按 RFC 编码过的，
     * 中文文件名会是 `%E7%94%B5%E5%BD%B1`，不解码界面上就是一串百分号。
     */
    private fun normalizeHref(href: String, requestedPath: String): String {
        // 少数服务端会返回相对 href。此时必须相对「当前请求目录」解析；
        // 若错误地相对账号根目录解析，子目录中的文件会被拼到错误路径。
        val requestBase = buildUrl(requestedPath).trimEnd('/') + "/"
        val resolved = requestBase.toHttpUrlOrNull()?.resolve(href)
        var path = resolved?.encodedPath
            ?: href.substringBefore('?').substringBefore('#')
        path = decode(path)
        // 再去掉账号 base 里的路径前缀。
        // 必须校验「段边界」：base 是 `/dav`、href 是 `/davos/x` 时，
        // 光用 startsWith 会把 `os/x` 当路径，直接把条目弄丢。
        val basePath = basePathOf(account.url)
        if (basePath.isNotEmpty() && path.startsWith(basePath)) {
            val rest = path.removePrefix(basePath)
            if (rest.isEmpty() || rest.startsWith("/")) {
                path = rest
            }
        }
        if (!path.startsWith("/")) path = "/$path"
        return path
    }

    private fun decode(value: String): String = Uri.decode(value)

    private fun basePathOf(url: String): String {
        val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return ""
        return uri.path?.trimEnd('/') ?: ""
    }
}
