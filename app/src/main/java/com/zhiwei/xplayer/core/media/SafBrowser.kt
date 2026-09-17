package com.zhiwei.xplayer.core.media

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.zhiwei.xplayer.core.util.Formatters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/** SAF 目录里的一项 */
data class SafNode(
    val uri: String,
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val lastModified: Long,
)

/**
 * 通过 SAF（Storage Access Framework）浏览用户授权的目录。
 *
 * 用「持有一个 tree Uri + 按路径逐级 findFile」的方式导航，而不是每进一级就
 * 重新 `fromTreeUri`：`DocumentFile.fromTreeUri` 只对树的根文档有效，
 * 对子文档需要 `DocumentsContract.buildDocumentUriUsingTree` 之类的手工构造，
 * 容易在个别厂商的 DocumentsProvider 上踩坑。逐级 findFile 慢一点，但稳。
 */
object SafBrowser {

    /** 列出 [treeUri] 下 [path] 指向目录里的内容（目录优先，然后按名称排序） */
    suspend fun list(
        context: Context,
        treeUri: String,
        path: List<String> = emptyList(),
    ): List<SafNode> = withContext(Dispatchers.IO) {
        runCatching<List<SafNode>> {
            val root = DocumentFile.fromTreeUri(context, treeUri.toUri())
                ?: return@runCatching emptyList()
            var current: DocumentFile = root
            for (segment in path) {
                current = current.findFile(segment) ?: return@runCatching emptyList()
            }
            current.listFiles()
                .asSequence()
                .filter { it.name != null }
                .mapNotNull { doc ->
                    val name = doc.name ?: return@mapNotNull null
                    // 隐藏文件与系统目录（.thumbnails / Android）不展示
                    if (name.startsWith(".")) return@mapNotNull null
                    val directory = doc.isDirectory
                    if (!directory && !Formatters.isMediaFile(name)) return@mapNotNull null
                    SafNode(
                        uri = doc.uri.toString(),
                        name = name,
                        isDirectory = directory,
                        sizeBytes = if (directory) 0L else doc.length(),
                        lastModified = doc.lastModified(),
                    )
                }
                .sortedWith(
                    compareBy<SafNode>({ !it.isDirectory }, { it.name.lowercase(Locale.US) }),
                )
                .toList()
        }.getOrDefault(emptyList())
    }

    /** 尝试从 tree Uri 推断一个人类可读的名字，用作授权列表里的标题 */
    fun displayNameOf(treeUri: String): String {
        val decoded = runCatching { Uri.decode(treeUri) }.getOrDefault(treeUri)
        val marker = "/tree/"
        val index = decoded.indexOf(marker)
        if (index < 0) return decoded
        val tail = decoded.substring(index + marker.length)
        val colon = tail.indexOf(':')
        val path = if (colon >= 0) tail.substring(colon + 1) else tail
        if (path.isBlank()) {
            // 形如 primary: 表示「内部存储」根目录
            val volume = if (colon >= 0) tail.substring(0, colon) else tail
            return if (volume == "primary") "内部存储" else volume
        }
        return path.trimEnd('/').substringAfterLast('/').ifBlank { path }
    }

    /** 判断两个 tree Uri 是否指向同一棵树（授权去重用） */
    fun sameTree(a: String, b: String): Boolean = a.trimEnd('/') == b.trimEnd('/')
}
