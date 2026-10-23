package com.zhiwei.xplayer.core.media

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.zhiwei.xplayer.core.util.Formatters
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

enum class MediaAccess {
    DENIED,
    PARTIAL,
    FULL,
}

data class MediaCatalogState(
    val videos: List<MediaEntry> = emptyList(),
    val audio: List<MediaEntry> = emptyList(),
    val videoAccess: MediaAccess = MediaAccess.DENIED,
    val audioAccess: MediaAccess = MediaAccess.DENIED,
    val loading: Boolean = false,
    val scanned: Boolean = false,
    val error: String? = null,
) {
    val hasAnyAccess: Boolean
        get() = videoAccess != MediaAccess.DENIED || audioAccess != MediaAccess.DENIED
}

/**
 * MediaStore 媒体扫描。
 *
 * 只读 `EXTERNAL_CONTENT_URI`（外置共享存储的汇总视图），不做多卷遍历 ——
 * 多卷枚举在 Android 10+ 上需要 `MediaStore.getExternalVolumeNames`，
 * 而汇总 URI 已经把各卷合并了，对本应用来说足够。
 */
@Singleton
class MediaRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private data class QueryResult(
        val entries: List<MediaEntry>,
        val error: String? = null,
    )

    private val scanMutex = Mutex()
    private val _catalog = MutableStateFlow(
        MediaCatalogState(
            videoAccess = currentVideoAccess(),
            audioAccess = currentAudioAccess(),
        ),
    )
    val catalog: StateFlow<MediaCatalogState> = _catalog.asStateFlow()

    fun hasMediaPermission(): Boolean =
        currentVideoAccess() != MediaAccess.DENIED || currentAudioAccess() != MediaAccess.DENIED

    fun requiredReadPermissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_AUDIO,
        )
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    fun requiredVideoPermissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
            arrayOf(Manifest.permission.READ_MEDIA_VIDEO)
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    /**
     * 应用级媒体扫描。多个页面共享同一份结果，并且同一时间只允许一个扫描任务。
     * [force] 用于权限变化后的主动刷新，普通首次进入只调用一次。
     */
    suspend fun refresh(force: Boolean = false) = scanMutex.withLock {
        val videoAccess = currentVideoAccess()
        val audioAccess = currentAudioAccess()
        val previous = _catalog.value
        if (
            !force &&
            previous.scanned &&
            previous.videoAccess == videoAccess &&
            previous.audioAccess == audioAccess
        ) {
            return@withLock
        }

        _catalog.value = previous.copy(
            videoAccess = videoAccess,
            audioAccess = audioAccess,
            loading = true,
            error = null,
        )

        if (videoAccess == MediaAccess.DENIED && audioAccess == MediaAccess.DENIED) {
            _catalog.value = MediaCatalogState(
                videoAccess = videoAccess,
                audioAccess = audioAccess,
                loading = false,
                scanned = true,
            )
            return@withLock
        }

        // 视频与音频是两次独立的 ContentResolver 查询，并发读取可显著缩短媒体库首次加载。
        val (videoResult, audioResult) = coroutineScope {
            val video = if (videoAccess != MediaAccess.DENIED) {
                async(Dispatchers.IO) {
                    runCatching { queryVideos() }.fold(
                        onSuccess = { QueryResult(it) },
                        onFailure = { QueryResult(emptyList(), "视频：${it.message ?: "读取失败"}") },
                    )
                }
            } else {
                null
            }
            val audio = if (audioAccess != MediaAccess.DENIED) {
                async(Dispatchers.IO) {
                    runCatching { queryAudio() }.fold(
                        onSuccess = { QueryResult(it) },
                        onFailure = { QueryResult(emptyList(), "音频：${it.message ?: "读取失败"}") },
                    )
                }
            } else {
                null
            }
            (video?.await() ?: QueryResult(emptyList())) to
                (audio?.await() ?: QueryResult(emptyList()))
        }
        val errors = listOfNotNull(videoResult.error, audioResult.error)

        _catalog.value = MediaCatalogState(
            videos = videoResult.entries,
            audio = audioResult.entries,
            videoAccess = videoAccess,
            audioAccess = audioAccess,
            loading = false,
            scanned = true,
            error = errors.takeIf { it.isNotEmpty() }?.joinToString("；"),
        )
    }

    private fun currentVideoAccess(): MediaAccess {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return legacyAccess()
        if (granted(Manifest.permission.READ_MEDIA_VIDEO)) return MediaAccess.FULL
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        ) {
            return MediaAccess.PARTIAL
        }
        return MediaAccess.DENIED
    }

    private fun currentAudioAccess(): MediaAccess {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return legacyAccess()
        return if (granted(Manifest.permission.READ_MEDIA_AUDIO)) {
            MediaAccess.FULL
        } else {
            MediaAccess.DENIED
        }
    }

    private fun legacyAccess(): MediaAccess =
        if (granted(Manifest.permission.READ_EXTERNAL_STORAGE)) {
            MediaAccess.FULL
        } else {
            MediaAccess.DENIED
        }

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    // ---------------------------------------------------------------- video ----

    private fun queryVideos(): List<MediaEntry> {
        val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.TITLE,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.HEIGHT,
            MediaStore.Video.Media.DATA,
        )
        val sort = "${MediaStore.Video.Media.DATE_ADDED} DESC"
        val out = ArrayList<MediaEntry>()
        context.contentResolver.query(collection, projection, null, null, sort)?.use { cursor ->
            val idIdx = cursor.getColumnIndex(MediaStore.Video.Media._ID)
            val nameIdx = cursor.getColumnIndex(MediaStore.Video.Media.DISPLAY_NAME)
            val titleIdx = cursor.getColumnIndex(MediaStore.Video.Media.TITLE)
            val durationIdx = cursor.getColumnIndex(MediaStore.Video.Media.DURATION)
            val sizeIdx = cursor.getColumnIndex(MediaStore.Video.Media.SIZE)
            val addedIdx = cursor.getColumnIndex(MediaStore.Video.Media.DATE_ADDED)
            val bucketIdx = cursor.getColumnIndex(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
            val widthIdx = cursor.getColumnIndex(MediaStore.Video.Media.WIDTH)
            val heightIdx = cursor.getColumnIndex(MediaStore.Video.Media.HEIGHT)
            val dataIdx = cursor.getColumnIndex(MediaStore.Video.Media.DATA)

            while (cursor.moveToNext()) {
                val id = if (idIdx >= 0) cursor.getLong(idIdx) else continue
                val path = if (dataIdx >= 0) cursor.getString(dataIdx) else null
                val fileName = if (nameIdx >= 0) cursor.getString(nameIdx) else null
                val title = if (titleIdx >= 0) cursor.getString(titleIdx) else null
                out.add(
                    MediaEntry(
                        uri = ContentUris.withAppendedId(collection, id).toString(),
                        title = pickTitle(title, fileName, path),
                        fileName = pickFileName(fileName, path),
                        durationMs = if (durationIdx >= 0) cursor.getLong(durationIdx) else 0L,
                        sizeBytes = if (sizeIdx >= 0) cursor.getLong(sizeIdx) else 0L,
                        addedAt = if (addedIdx >= 0) cursor.getLong(addedIdx) * 1000L else 0L,
                        isVideo = true,
                        bucket = if (bucketIdx >= 0) cursor.getString(bucketIdx) else null,
                        width = if (widthIdx >= 0) cursor.getInt(widthIdx) else 0,
                        height = if (heightIdx >= 0) cursor.getInt(heightIdx) else 0,
                        path = path,
                    ),
                )
            }
        }
        return out
    }

    // ---------------------------------------------------------------- audio ----

    private fun queryAudio(): List<MediaEntry> {
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DATA,
        )
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        val sort = "${MediaStore.Audio.Media.DATE_ADDED} DESC"
        val out = ArrayList<MediaEntry>()
        context.contentResolver.query(collection, projection, selection, null, sort)?.use { cursor ->
            val idIdx = cursor.getColumnIndex(MediaStore.Audio.Media._ID)
            val nameIdx = cursor.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME)
            val titleIdx = cursor.getColumnIndex(MediaStore.Audio.Media.TITLE)
            val durationIdx = cursor.getColumnIndex(MediaStore.Audio.Media.DURATION)
            val sizeIdx = cursor.getColumnIndex(MediaStore.Audio.Media.SIZE)
            val addedIdx = cursor.getColumnIndex(MediaStore.Audio.Media.DATE_ADDED)
            val albumIdx = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM)
            val dataIdx = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)

            while (cursor.moveToNext()) {
                val id = if (idIdx >= 0) cursor.getLong(idIdx) else continue
                val path = if (dataIdx >= 0) cursor.getString(dataIdx) else null
                val fileName = if (nameIdx >= 0) cursor.getString(nameIdx) else null
                val title = if (titleIdx >= 0) cursor.getString(titleIdx) else null
                out.add(
                    MediaEntry(
                        uri = ContentUris.withAppendedId(collection, id).toString(),
                        title = pickTitle(title, fileName, path),
                        fileName = pickFileName(fileName, path),
                        durationMs = if (durationIdx >= 0) cursor.getLong(durationIdx) else 0L,
                        sizeBytes = if (sizeIdx >= 0) cursor.getLong(sizeIdx) else 0L,
                        addedAt = if (addedIdx >= 0) cursor.getLong(addedIdx) * 1000L else 0L,
                        isVideo = false,
                        bucket = if (albumIdx >= 0) cursor.getString(albumIdx) else null,
                        width = 0,
                        height = 0,
                        path = path,
                    ),
                )
            }
        }
        return out
    }

    // --------------------------------------------------------------- helpers ----

    private fun pickTitle(title: String?, fileName: String?, path: String?): String {
        title?.takeIf { it.isNotBlank() }?.let { return it }
        return pickFileName(fileName, path)
    }

    private fun pickFileName(fileName: String?, path: String?): String {
        fileName?.takeIf { it.isNotBlank() }?.let { return it }
        path?.let { File(it).name.takeIf { name -> name.isNotBlank() } }?.let { return it }
        return "未知文件"
    }

    companion object {
        /** 从 uri 里取出 MediaStore 的 id（用于缩略图 API），失败返回 -1 */
        fun idOf(uri: Uri): Long = runCatching { ContentUris.parseId(uri) }.getOrDefault(-1L)

        /** 从 uri 里取出 MediaStore 的 id（用于缩略图 API），失败返回 -1 */
        fun idOf(uri: String): Long = idOf(uri.toUri())

        /** 供 UI 直接用的扩展名判断 */
        fun looksLikeVideo(fileName: String): Boolean = Formatters.isVideoFile(fileName)
    }
}
