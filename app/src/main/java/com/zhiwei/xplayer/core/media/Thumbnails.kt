package com.zhiwei.xplayer.core.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.LruCache
import android.util.Size
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 缩略图加载。
 *
 * 刻意不引 Coil：本应用只需要「给一个 content:// 取一张位图」这一种能力，
 * 而 Coil 的 ImageLoader / Decoder 工厂 API 在 3.x 上改动频繁，为一个缩略图
 * 承担那套版本风险不划算。这里用系统 API + 内存缓存直接实现：
 *
 *  - Android 10+ 走 [android.content.ContentResolver.loadThumbnail]，由系统
 *    的媒体提供者直接给出已解码的缩略图，比抽帧快一个数量级；
 *  - 更低版本或系统缩略图缺失时退回 [MediaMetadataRetriever] 抽关键帧；
 *  - 音频取内嵌封面（ID3 APIC / FLAC picture）。
 *
 * 缓存用 [LruCache] 以 KB 计量，上限取进程可用内存的 1/8。
 */
object Thumbnails {

    private val cache: LruCache<String, Bitmap> = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 8L / 1024L).toInt().coerceAtLeast(8 * 1024),
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    /** 同一 key 并发请求时只真正解码一次 */
    private val locks = ConcurrentHashMap<String, Mutex>()

    fun cached(uri: String, sizePx: Int): Bitmap? = cache.get(keyOf(uri, sizePx))

    suspend fun load(
        context: Context,
        uri: String,
        isVideo: Boolean,
        sizePx: Int = DEFAULT_SIZE,
    ): Bitmap? {
        val key = keyOf(uri, sizePx)
        cache.get(key)?.let { return it }
        val mutex = locks.getOrPut(key) { Mutex() }
        return mutex.withLock {
            cache.get(key)?.let { return@withLock it }
            val bitmap = withContext(Dispatchers.IO) {
                runCatching {
                    val parsed = uri.toUri()
                    if (isVideo) videoThumbnail(context, parsed, sizePx) else audioArtwork(context, parsed)
                }.getOrNull()
            }
            if (bitmap != null) cache.put(key, bitmap)
            bitmap
        }
    }

    private fun keyOf(uri: String, sizePx: Int) = "$uri@$sizePx"

    private fun videoThumbnail(context: Context, uri: Uri, sizePx: Int): Bitmap? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                context.contentResolver.loadThumbnail(uri, Size(sizePx, sizePx), null)
            }.getOrNull()?.let { return it }
        }
        return runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                retriever.getFrameAtTime(
                    FIRST_FRAME_US,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                )
            } finally {
                runCatching { retriever.release() }
            }
        }.getOrNull()
    }

    private fun audioArtwork(context: Context, uri: Uri): Bitmap? = runCatching {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val bytes = retriever.embeddedPicture
            if (bytes == null) null else BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } finally {
            runCatching { retriever.release() }
        }
    }.getOrNull()

    /** 取第 1 秒的帧：第 0 帧经常是黑场或纯色，视觉上等于没缩略图 */
    private const val FIRST_FRAME_US = 1_000_000L

    const val DEFAULT_SIZE = 384
}
